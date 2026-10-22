"""把 OCEC-P（ocec_p_float32.tflite）的权重导出成 iOS 手写推理用的二进制，并生成 Swift 单测夹具。

为什么不在 iOS 直接跑 tflite：应用已静态链接 MediaPipe（内含一份 TFLite），再引入 TensorFlowLiteC
有符号重复/冲突风险；OCEC-P 只有约 2.8 万个参数、7 层，Accelerate 手写前向足够快（单眼 < 1 ms）。

本脚本做三件事（需要 tensorflow，本机 conda base 环境即可）：
1. 校验模型哈希 → 按 OcecEyeClassifier.swift 约定的顺序与布局导出权重（小端 float32，8 字节头 "OCECP001"）
2. 用 numpy 按与 Swift 完全相同的算法复算前向，与 TFLite 解释器逐张对拍（最大误差须 < 1e-5）
3. 从真机眼片（results/data/crops）按 Swift 同款最近邻采样到 40×24，写出夹具：输入像素 + TFLite 输出

用法：python scripts/ios/export_ocec_weights.py
"""
from pathlib import Path
import base64
import glob
import hashlib
import json
import sys

import numpy as np
import tensorflow as tf
from PIL import Image

root = Path(__file__).resolve().parents[2]
MODEL = root / 'results/models/ocec_p_float32.tflite'
MODEL_SHA256 = 'ac5c52e30e97e135c5819ffb1402c5e28ef53c72d740da97e0727e4c17455f3d'
WEIGHTS_OUT = root / 'iosApp/Models/ocec_p_weights.bin'
FIXTURE_OUT = root / 'iosApp/Tests/ocec_fixture.json'
W, H = 40, 24


def out(text):
    sys.stdout.buffer.write((text + '\n').encode('utf-8'))


if hashlib.sha256(MODEL.read_bytes()).hexdigest() != MODEL_SHA256:
    sys.exit('模型文件变了：张量下标与层结构需要重新核对后再改 MODEL_SHA256')

interpreter = tf.lite.Interpreter(model_path=str(MODEL))
interpreter.allocate_tensors()
t = interpreter.get_tensor

# 张量下标来自本模型的算子表（op 0–31），哈希锁定后下标稳定
c0w = t(24).transpose(1, 2, 3, 0).reshape(27, 32)       # OHWI [32,3,3,3] → [(ky,kx,ci), co]
c1w = t(15).transpose(1, 2, 3, 0).reshape(288, 32)      # [32,3,3,32]
c2w = t(14).reshape(128, 32).T                          # 1×1 [128,1,1,32] → [ci, co]
dww = t(13).reshape(3, 3, 128)                          # 深度卷积 [1,3,3,128] → [ky,kx,c]
se1w = t(12).reshape(16, 128).T                         # SE 压缩 [16,1,1,128]
se2w = t(11).reshape(128, 16).T                         # SE 激励 [128,1,1,16]
c5w = t(10).reshape(32, 128).T                          # 投影 [32,1,1,128]
fc1w = t(17).T                                          # 全连接 [32,64] → [in, out]
fc2w = t(16).T                                          # [1,32] → [32,1]
tensors = [
    ('conv0.weight', c0w), ('conv0.bias', t(25)),
    ('conv1.weight', c1w), ('conv1.bias', t(9)),
    ('expand.weight', c2w), ('expand.bias', t(8)),
    ('depthwise.weight', dww), ('depthwise.bias', t(7)),
    ('se.reduce.weight', se1w), ('se.reduce.bias', t(2)),
    ('se.expand.weight', se2w), ('se.expand.bias', t(6)),
    ('project.weight', c5w), ('project.bias', t(5)),
    ('head.scale', t(22).reshape(64)), ('head.shift', t(21).reshape(64)),
    ('fc1.weight', fc1w), ('fc1.bias', t(1)),
    ('fc2.weight', fc2w), ('fc2.bias', t(4)),
]
flat = np.concatenate([np.ascontiguousarray(a, dtype='<f4').ravel() for _, a in tensors])
WEIGHTS_OUT.parent.mkdir(parents=True, exist_ok=True)
WEIGHTS_OUT.write_bytes(b'OCECP001' + flat.tobytes())
out(f'权重 {flat.size} 个 float32 → {WEIGHTS_OUT.relative_to(root)}')
for name, a in tensors: out(f'  {name}: {list(np.shape(a))}')
P = dict((name, np.asarray(a, dtype=np.float32)) for name, a in tensors)


def silu(x): return x / (1 + np.exp(-x))
def sigmoid(x): return 1 / (1 + np.exp(-x))


def im2col(x, stride):
    """x: [h,w,c]，3×3、四周补 1 圈零，返回 [oh*ow, 9*c]（ky,kx,ci 顺序）。"""
    h, w, c = x.shape
    p = np.pad(x, ((1, 1), (1, 1), (0, 0)))
    oh, ow = (h - 1) // stride + 1, (w - 1) // stride + 1
    cols = np.zeros((oh * ow, 9 * c), np.float32)
    for oy in range(oh):
        for ox in range(ow):
            cols[oy * ow + ox] = p[oy * stride:oy * stride + 3, ox * stride:ox * stride + 3, :].ravel()
    return cols, oh, ow


def forward(rgb):
    """与 OcecEyeClassifier.swift 同一算法：rgb [24,40,3] float32 (0–1) → 睁眼概率。"""
    cols, oh, ow = im2col(rgb, 1)
    x = silu(cols @ P['conv0.weight'] + P['conv0.bias']).reshape(oh, ow, 32)
    cols, oh, ow = im2col(x, 2)
    skip = silu(cols @ P['conv1.weight'] + P['conv1.bias'])                 # [240,32]
    e = silu(skip @ P['expand.weight'] + P['expand.bias']).reshape(oh, ow, 128)
    pe = np.pad(e, ((1, 1), (1, 1), (0, 0)))
    d = np.zeros_like(e)
    for ky in range(3):
        for kx in range(3):
            d += pe[ky:ky + oh, kx:kx + ow, :] * P['depthwise.weight'][ky, kx]
    d = silu(d + P['depthwise.bias']).reshape(oh * ow, 128)
    s = np.maximum(d.mean(0) @ P['se.reduce.weight'] + P['se.reduce.bias'], 0)
    s = sigmoid(s @ P['se.expand.weight'] + P['se.expand.bias'])
    y = silu((d * s) @ P['project.weight'] + P['project.bias'] + skip)     # [240,32]
    f = np.concatenate([y.mean(0), y.max(0)]) * P['head.scale'] + P['head.shift']
    f = np.maximum(f @ P['fc1.weight'] + P['fc1.bias'], 0)
    return float(sigmoid(f @ P['fc2.weight'] + P['fc2.bias'])[0])


def tflite(rgb):
    interpreter.set_tensor(interpreter.get_input_details()[0]['index'], rgb[None].astype(np.float32))
    interpreter.invoke()
    return float(interpreter.get_tensor(interpreter.get_output_details()[0]['index'])[0])


def sample(img):
    """Swift 同款最近邻：整张源图当裁剪框（right/bottom 排他），取目标像素中心映射回源图后向下取整。"""
    sh, sw, _ = img.shape
    ys = [min(sh - 1, int((y + 0.5) * sh / H)) for y in range(H)]
    xs = [min(sw - 1, int((x + 0.5) * sw / W)) for x in range(W)]
    return img[np.ix_(ys, xs)]


files = sorted(glob.glob(str(root / 'results/data/crops/*/*.png')))
if not files: sys.exit('缺少真机眼片 results/data/crops')
worst = 0.0
cases = []
for i, f in enumerate(files):
    pixels = sample(np.asarray(Image.open(f).convert('RGB')))
    rgb = pixels.astype(np.float32) / 255
    ref, mine = tflite(rgb), forward(rgb)
    worst = max(worst, abs(ref - mine))
    if i % 6 == 0:
        cases.append({'name': Path(f).name, 'rgb': base64.b64encode(pixels.astype(np.uint8).tobytes()).decode(), 'prob': ref})
out(f'numpy 复算 vs TFLite：{len(files)} 张真机眼片，最大误差 {worst:.2e}')
if worst > 1e-5: sys.exit('复算与 TFLite 不一致：权重布局或层结构有误')
probs = [c['prob'] for c in cases]
if min(probs) > 0.3 or max(probs) < 0.7: sys.exit('夹具需同时覆盖睁眼与闭眼')
FIXTURE_OUT.write_text(json.dumps({'width': W, 'height': H, 'cases': cases}, ensure_ascii=False, indent=1), encoding='utf-8')
out(f'夹具 {len(cases)} 例（概率 {min(probs):.3f}–{max(probs):.3f}）→ {FIXTURE_OUT.relative_to(root)}')
