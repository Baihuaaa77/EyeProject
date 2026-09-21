# -*- coding: utf-8 -*-
"""segface_eye_seg_test.py — 决定性实验：SegFace(MobileNetV3) 在极端姿态下能否正确分割眼区。

流程：BlazeFace 脸框(带 1.5x margin 方形裁剪) → SegFace 512 推理 → l_eye/r_eye 类 mask
     → mask bbox 映射回原图 → 与 DEIMv2 GT 眼框(红)叠加对比 + 耗时。
用法: python segface_eye_seg_test.py <image> <repo_dir> <weights> <out_png>
"""
import sys, os
import numpy as np, cv2, torch

IMG = sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\djj\xd-workspace\projects\EyeProject\app\src\androidTest\assets\sample1.png"
REPO = sys.argv[2] if len(sys.argv) > 2 else r"C:\Users\djj\AppData\Local\Temp\segface"
WEIGHTS = sys.argv[3] if len(sys.argv) > 3 else r"C:\Users\djj\AppData\Local\Temp\segface_weights\mobilenet_celeba_512_model_299.pt"
OUT = sys.argv[4] if len(sys.argv) > 4 else r"C:\Users\djj\xd-workspace\projects\EyeProject\results\figures\segface_eye_seg.png"

sys.path.insert(0, REPO)
from network import get_model  # noqa: E402
from ai_edge_litert.interpreter import Interpreter  # noqa: E402

img = cv2.imread(IMG); h0, w0 = img.shape[:2]

# ---- BlazeFace 脸框 ----
itp = Interpreter(model_path=r"C:\Users\djj\AppData\Local\Temp\blazeface_sr.tflite"); itp.allocate_tensors()
scale = 128.0/max(h0, w0); nw, nh = round(w0*scale), round(h0*scale)
canvas = np.zeros((128,128,3), np.float32); dx, dy = (128-nw)//2, (128-nh)//2
canvas[dy:dy+nh, dx:dx+nw] = cv2.resize(img, (nw,nh))
itp.set_tensor(itp.get_input_details()[0]['index'], ((canvas-127.5)/127.5).astype(np.float32)[None]); itp.invoke()
regs = itp.get_tensor(itp.get_output_details()[0]['index'])[0]; clss = itp.get_tensor(itp.get_output_details()[1]['index'])[0]
scores = 1/(1+np.exp(-np.clip(clss[:,0],-100,100)))
min_s, max_s, layers, strides = 0.1484375, 0.75, 4, [8,16,16,16]
cs = [min_s + (max_s-min_s)*k/(layers-1) for k in range(layers)]
A = []
li = 0
while li < layers:
    j = li
    while j < layers and strides[j]==strides[li]: j += 1
    grid = 128//strides[li]
    for yy in range(grid):
        for xx in range(grid):
            for k in range(li, j):
                A.append(((yy+0.5)/grid,(xx+0.5)/grid))
                A.append(((yy+0.5)/grid,(xx+0.5)/grid))
    li = j
i = int(np.argmax(scores))
cx = (A[i][1] + regs[i,0]/128)*128; cy = (A[i][0] + regs[i,1]/128)*128
fw = regs[i,2]/128*128; fh = regs[i,3]/128*128
sx = (cx - dx)/scale; sy = (cy - dy)/scale; sfw = fw/scale; sfh = fh/scale

# ---- 脸框 1.5x 方形裁剪 → 512 ----
side = max(sfw, sfh) * 1.5
ccx, ccy = sx, sy + sfh*0.1  # 脸框中心略下（脸框常偏上含额头）
x1 = int(max(0, ccx - side/2)); y1 = int(max(0, ccy - side/2))
x2 = int(min(w0, x1 + side)); y2 = int(min(h0, y1 + side))
crop = img[y1:y2, x1:x2]
face512 = cv2.resize(crop, (512, 512), interpolation=cv2.INTER_CUBIC)

# ---- SegFace 推理 ----
print("loading SegFace mobilenet ...", flush=True)
model = get_model("segface_celeb", 512, "mobilenet")
ckpt = torch.load(WEIGHTS, map_location="cpu", weights_only=False)
model.load_state_dict(ckpt["state_dict_backbone"])
model.eval()
rgb = cv2.cvtColor(face512, cv2.COLOR_BGR2RGB).astype(np.float32)/255.0
mean = np.array([0.485, 0.456, 0.406], np.float32); std = np.array([0.229, 0.224, 0.225], np.float32)
tin = torch.from_numpy(((rgb - mean)/std).transpose(2,0,1))[None]
t0 = __import__("time").perf_counter()
with torch.no_grad():
    logits = model(tin, None, None)  # labels/dataset 仅训练分支使用，推理传 None
dt = (__import__("time").perf_counter()-t0)*1000
pred = logits.argmax(1)[0].numpy()  # [512,512] 类别 0..18
print(f"infer {dt:.0f}ms  classes present: {sorted(np.unique(pred).tolist())}")

# ---- 眼类 mask（l_eye=8, r_eye=9）→ bbox → 映射回原图 ----
eye_mask = np.isin(pred, [8, 9])
ys, xs = np.where(eye_mask)
anno = img.copy()
if len(xs) > 30:
    # 512crop → 原图
    sc = (y2-y1)/512.0
    bx1 = int(x1 + xs.min()*sc); by1 = int(y1 + ys.min()*sc)
    bx2 = int(x1 + xs.max()*sc); by2 = int(y1 + ys.max()*sc)
    cv2.rectangle(anno, (bx1,by1), (bx2,by2), (0,255,255), 2)
    cv2.putText(anno, "seg eye bbox", (bx1, by1-4), cv2.FONT_HERSHEY_SIMPLEX, 0.4, (0,255,255), 1)
    # mask 半透明叠加
    mask_full = np.zeros((h0, w0), np.uint8)
    mask_full[y1:y2, x1:x2] = eye_mask.astype(np.uint8)*255
    layer = anno.copy(); layer[mask_full>0] = (255,200,0)
    anno = cv2.addWeighted(layer, 0.35, anno, 0.65, 0)
    print(f"seg eye bbox: ({bx1},{by1})-({bx2},{by2})")
else:
    print("!! 眼类像素过少:", len(xs))
# DEIMv2 GT
cv2.rectangle(anno, (165,77), (181,91), (0,0,255), 2)
cv2.putText(anno, "DEIMv2 GT", (150, 72), cv2.FONT_HERSHEY_SIMPLEX, 0.4, (0,0,255), 1)
out = cv2.resize(anno, (w0*2, h0*2), interpolation=cv2.INTER_NEAREST)
cv2.imwrite(OUT, out)
print("saved:", OUT)
