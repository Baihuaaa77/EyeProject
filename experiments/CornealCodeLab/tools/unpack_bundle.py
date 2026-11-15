"""将单个实验数据JSON无损还原为离线复算目录；输出目录必须不存在。"""
import argparse
import base64
import json
from pathlib import Path
import shutil


def unpack(source, destination):
    bundle = json.loads(Path(source).read_text(encoding="utf-8-sig"))
    if bundle.get("format") != "corneal-lab-bundle-1":
        raise ValueError("不是支持的光码实验数据格式")
    documents = bundle.get("documents", {})
    binaries = bundle.get("binaryFiles", {})
    for name in [*documents, *binaries]:
        if not isinstance(name, str) or Path(name).name != name or "/" in name or "\\" in name or name in (".", ".."):
            raise ValueError("文件名包含路径，拒绝解包")
        if not name.endswith((".json", ".jsonl", ".f32", ".u8")):
            raise ValueError("包含不支持的实验数据文件")
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=False)
    try:
        for name, value in documents.items():
            target = destination / name
            if name.endswith(".jsonl"):
                if not isinstance(value, list):
                    raise ValueError("逐帧记录必须为数组")
                target.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in value), encoding="utf8")
            else:
                target.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf8")
        for name, value in binaries.items():
            (destination / name).write_bytes(base64.b64decode(value, validate=True))
    except Exception:
        # 仅清理由本次调用新建且已验证的输出目录，不触及现有实验资料。
        shutil.rmtree(destination)
        raise
    return destination


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    unpack(args.source, args.destination)
