"""在本机转换并验证研究权重，再注入未签名的 iPhone 实验包。"""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
from zipfile import ZIP_DEFLATED, ZipFile

import flatbuffers
import numpy as np


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def convert(source, output, format_name):
    import _tools

    output.unlink(missing_ok=True)
    _tools.mnnconvert([
        "mnnconvert", "-f", format_name, "--modelFile", str(source),
        "--MNNModel", str(output), "--bizCode", "GazeLab",
    ])
    if not output.is_file() or output.stat().st_size < 100_000:
        raise RuntimeError(f"MNN 转换失败：{source}")


def run_mnn(path, inputs):
    import MNN

    interpreter = MNN.Interpreter(str(path))
    session = interpreter.createSession({"numThread": 2})
    expected = interpreter.getSessionInputAll(session)
    if set(expected) != set(inputs):
        raise ValueError(f"{path.name} 输入不匹配：{len(expected)} 个实际输入，期望 {list(inputs)}")
    for name, values in inputs.items():
        tensor = expected[name]
        if tuple(tensor.getShape()) != values.shape:
            raise ValueError(f"{name} 输入形状不匹配：{tensor.getShape()} / {values.shape}")
        host = MNN.Tensor(tensor.getShape(), MNN.Halide_Type_Float, np.ascontiguousarray(values), tensor.getDimensionType())
        tensor.copyFromHostTensor(host)
    interpreter.runSession(session)
    return {name: np.array(value.getNumpyData()).reshape(-1).copy()
            for name, value in interpreter.getSessionOutputAll(session).items()}


def load_class(path, name):
    # 上游模型文件中的 torchvision 未参与前向计算。
    source = path.read_text(encoding="utf-8")
    source = "\n".join(line for line in source.splitlines() if not line.startswith("import torchvision"))
    spec = importlib.util.spec_from_file_location(path.stem, path)
    module = importlib.util.module_from_spec(spec)
    exec(compile(source, str(path), "exec"), module.__dict__)
    return getattr(module, name)


def torch_model(code, weights, target, kind):
    import torch

    class Network(torch.nn.Module):
        def __init__(self, model):
            super().__init__()
            self.model = model

        def forward(self, left, right, face, geometry):
            return self.model((left, right, face, geometry))

    aff = kind == "affnet"
    model = load_class(code / "model" / f"{kind}.py", "AFFNetModel" if aff else "ITrackerModel")()
    state = torch.load(weights, map_location="cpu", weights_only=True)
    model.load_state_dict({name.removeprefix("module."): value for name, value in state.items()})
    if not aff:
        # 用标准 LRN 算子导出，保留 CrossMapLRN2d 的窗口和参数。
        for feature_group in (model.eyeModel.features, model.faceModel.conv.features):
            for index, layer in enumerate(feature_group):
                if isinstance(layer, torch.nn.CrossMapLRN2d):
                    feature_group[index] = torch.nn.LocalResponseNorm(5, alpha=0.0001, beta=0.75, k=1.0)

    net = Network(model.eval())
    size = 112 if aff else 224
    rng = np.random.default_rng(2441)
    geometry = np.asarray([[140, 350, 600, 860, 195, 485, 280, 540, 339, 485, 424, 540]],
                          dtype=np.float32) if aff else np.zeros((1, 1, 25, 25), dtype=np.float32)
    if not aff:
        geometry[:, :, 7:19, 5:20] = 1
    dims = [(1, 3, size, size), (1, 3, size, size), (1, 3, 224, 224)]
    examples = [rng.random(size=d, dtype=np.float32) for d in dims] + [geometry]
    inputs = ["left", "right", "face", "geometry"]
    arguments = tuple(torch.from_numpy(sample) for sample in examples)
    onnx_path = target.with_suffix(".onnx")
    with torch.no_grad():
        reference = net(*arguments).numpy().reshape(-1)
        torch.onnx.export(net, arguments, str(onnx_path), input_names=inputs, output_names=["coordinates"],
                          opset_version=16, dynamo=False)
    convert(onnx_path, target, "ONNX")
    candidate = run_mnn(target, dict(zip(inputs, examples)))["coordinates"]
    np.testing.assert_allclose(candidate, reference, atol=0.003, rtol=0.001)
    print(f"{kind}：Python/MNN 输出等价，最大绝对差 {np.max(np.abs(candidate - reference)):.6f}")
    return inputs, ["coordinates"]


def fold_tflite_weights(source, destination):
    from tensorflow.lite.python import schema_py_generated as schema

    content = source.read_bytes()
    model = schema.ModelT.InitFromObj(schema.Model.GetRootAsModel(content, 0))
    count = 0
    for graph in model.subgraphs:
        operators = []
        for operator in graph.operators:
            opcode = model.operatorCodes[operator.opcodeIndex]
            if (max(opcode.builtinCode, opcode.deprecatedBuiltinCode) == schema.BuiltinOperator.DEQUANTIZE
                    and len(operator.inputs) == len(operator.outputs) == 1):
                src = graph.tensors[operator.inputs[0]]
                dst = graph.tensors[operator.outputs[0]]
                original = model.buffers[src.buffer]
                data = original.data
                if data is None and original.size:
                    data = np.frombuffer(content, dtype=np.uint8, count=original.size, offset=original.offset)
                if src.type == schema.TensorType.FLOAT16 and dst.type == schema.TensorType.FLOAT32 and data is not None and len(data):
                    weights = np.frombuffer(data.tobytes(), dtype="<f2").astype("<f4")
                    buffer = schema.BufferT()
                    buffer.data = weights.view(np.uint8)
                    dst.buffer = len(model.buffers)
                    model.buffers.append(buffer)
                    count += 1
                    continue
            operators.append(operator)
        graph.operators = operators
    if count == 0:
        raise ValueError("没有找到 L2CS-Net 中的 FP16 常量转 FP32 节点")
    used = {tensor.buffer for graph in model.subgraphs for tensor in graph.tensors if tensor.type != schema.TensorType.FLOAT16}
    for index, buffer in enumerate(model.buffers):
        if buffer.size and index in used:
            buffer.data = np.frombuffer(content, dtype=np.uint8, count=buffer.size, offset=buffer.offset)
        buffer.offset = 0
        buffer.size = 0
    builder = flatbuffers.Builder(1 << 20)
    builder.Finish(model.Pack(builder), file_identifier=b"TFL3")
    destination.write_bytes(builder.Output())
    print(f"L2CS-Net：折叠 {count} 个 FP16 常量解码节点")


def l2cs(source, target):
    import tensorflow as tf

    folded = target.with_suffix(".folded.tflite")
    fold_tflite_weights(source, folded)
    convert(folded, target, "TFLITE")
    resolver = tf.lite.experimental.OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES
    ref = tf.lite.Interpreter(model_path=str(source), experimental_op_resolver_type=resolver)
    ref.allocate_tensors()
    inp = ref.get_input_details()[0]
    outputs = [o["name"] for o in ref.get_output_details()]
    rng = np.random.default_rng(709)
    weights = np.arange(90) * 4 - 180
    for _ in range(3):
        values = rng.uniform(-2, 2, inp["shape"]).astype(np.float32)
        ref.set_tensor(inp["index"], values)
        ref.invoke()
        candidate = run_mnn(target, {inp["name"]: values})
        error = max(abs(np.dot(candidate[item["name"]] - ref.get_tensor(item["index"]).reshape(-1), weights))
                    for item in ref.get_output_details())
        if not np.isfinite(error) or error > 0.25:
            raise ValueError(f"L2CS-Net 角度转换误差 {error}°")
        print(f"L2CS-Net：转换前后最大角度差 {error:.5f}°")
    return [inp["name"]], outputs


def mgaze(source, target):
    import MNN

    shutil.copyfile(source, target)
    inputs = ["face", "left", "right", "rect"]
    dims = [(1, 3, 224, 224), (1, 3, 112, 112), (1, 3, 112, 112), (1, 12)]
    rng = np.random.default_rng(709)
    samples = [rng.random(size=d, dtype=np.float32) for d in dims]
    output = run_mnn(target, dict(zip(inputs, samples)))["output_0"]
    module = MNN.nn.load_module_from_file(str(source), inputs, ["output_0"])
    expression = []
    for values in samples:
        if values.ndim == 4:
            values = np.ascontiguousarray(values.transpose(0, 2, 3, 1))
            item = MNN.expr.placeholder(values.shape, MNN.expr.NHWC)
        else:
            item = MNN.expr.placeholder(values.shape)
        item.write(values)
        expression.append(item)
    reference = module.onForward(expression)[0].read().reshape(-1)
    np.testing.assert_allclose(output[:2], reference[:2], atol=0.003, rtol=0.001)
    print(f"MGazeNet：作者 NHWC 接口与设备 NCHW 接口等价，最大坐标差 {np.max(np.abs(output[:2]-reference[:2])):.6f}")
    return inputs, ["output_0"]


def prepare(args):
    args.output.mkdir(parents=True, exist_ok=True)
    entries = []
    methods = {
        "MGazeNet": ("mgaze", args.mgaze, mgaze, "GazeFollower 1e722b8，公开 7M MNN 权重"),
        "AFF-Net": ("affnet", args.affnet, lambda path, model: torch_model(args.mobilepog, path, model, "affnet"),
                    "MobilePoG 66854a8，Dynamic-MobilePoG AFF-Net"),
        "iTracker": ("itracker", args.itracker, lambda path, model: torch_model(args.mobilepog, path, model, "itracker"),
                     "MobilePoG 66854a8，GazeCapture iTracker"),
        "L2CS-Net": ("l2cs", args.l2cs, l2cs, "EyeProject l2cs_net.tflite，FP16 常量转 FP32，输出顺序沿用 Android 模块"),
    }
    for method, (name, original, prepare_model, source) in methods.items():
        if method in args.skip:
            continue
        if not original or not original.is_file():
            raise ValueError(f"{method} 缺少原始权重")
        filename = f"{name}.mnn"
        path = args.output / filename
        input_names, output_names = prepare_model(original, path)
        entries.append({
            "identity": {"method": method, "source": f"{source}；原文件 SHA-256 {sha256(original)}",
                         "sha256": sha256(path), "filename": filename},
            "inputNames": input_names, "outputNames": output_names,
        })
    manifest = args.output / "manifest.json"
    manifest.write_text(json.dumps({"models": entries}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"已验证 {len(entries)} 个模型：{manifest}")


def package(args):
    import plistlib

    manifest = args.models / "manifest.json"
    entries = json.loads(manifest.read_text(encoding="utf-8"))["models"]
    if len(entries) < 2 or len({m["identity"]["method"] for m in entries}) != len(entries):
        raise ValueError("对照包至少需要两个不同模型")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    root = "Payload/GazeLab.app"
    temp = args.output.with_suffix(".tmp")
    with ZipFile(args.shell) as shell, ZipFile(temp, "w", ZIP_DEFLATED) as ipa:
        plist = plistlib.loads(shell.read(root + "/Info.plist"))
        if (plist["CFBundleIdentifier"] != "com.zkyjss.gazelab"
                or plist["CFBundleShortVersionString"] != "0.2.0"
                or plist["DTPlatformName"] != "iphoneos"
                or root + "/embedded.mobileprovision" in shell.namelist()
                or any(name.startswith(root + "/_CodeSignature/") for name in shell.namelist())):
            raise ValueError("输入不是预期的未签名 iPhone 工程包")
        for entry in shell.infolist():
            if not entry.filename.startswith(root + "/Models/"):
                ipa.writestr(entry, shell.read(entry))
        ipa.write(manifest, f"{root}/Models/manifest.json")
        for item in entries:
            identity = item["identity"]
            model = args.models / identity["filename"]
            if sha256(model) != identity["sha256"]:
                raise ValueError(f"{model.name} SHA-256 校验失败")
            ipa.write(model, f"{root}/Models/{model.name}")
    with ZipFile(temp) as ipa:
        if ipa.testzip():
            raise ValueError("IPA 压缩校验失败")
    temp.replace(args.output)
    print(f"{args.output}  SHA-256 {sha256(args.output)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    build = subparsers.add_parser("prepare")
    for name in ("mobilepog", "mgaze", "affnet", "itracker", "l2cs", "output"):
        build.add_argument(f"--{name}", type=Path, required=name == "output")
    build.add_argument("--skip", action="append", default=[], choices=["MGazeNet", "AFF-Net", "iTracker", "L2CS-Net"])
    app = subparsers.add_parser("package")
    for name in ("shell", "models", "output"):
        app.add_argument(f"--{name}", type=Path, required=True)
    args = parser.parse_args()
    (prepare if args.command == "prepare" else package)(args)


if __name__ == "__main__":
    main()
