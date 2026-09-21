# -*- coding: utf-8 -*-
"""eval_segface_dataset.py — SegFace(眼区分割) + OCEC 在 closed-open-eyes 数据集上的评估。

管线：512×512 原图 → SegFace-MobileNetV3 → l_eye(8)/r_eye(9) mask → mask bbox → OCEC → open/closed
指标：眼类出现率、mask-vs-GT框 IoU、分类 accuracy/F1、SegFace CPU 延迟。
对照：GT框+OCEC F1 0.9938（上限）；BlazeFace+几何+OCEC F1 0.9873（真机路线）。

用法: python eval_segface_dataset.py [repo_dir] [weights] [shards_dir] [models_dir]
"""
import sys, os, time
import numpy as np, cv2, pyarrow.parquet as pq, onnxruntime as ort, torch

REPO = sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\djj\AppData\Local\Temp\segface"
WEIGHTS = sys.argv[2] if len(sys.argv) > 2 else r"C:\Users\djj\AppData\Local\Temp\segface_weights\mobilenet_celeba_512_model_299.pt"
SHARDS = sys.argv[3] if len(sys.argv) > 3 else r"C:\Users\djj\xd-workspace\projects\EyeProject\results\models"
MODELS = sys.argv[4] if len(sys.argv) > 4 else SHARDS

sys.path.insert(0, REPO)
from network import get_model  # noqa: E402

print("loading SegFace mobilenet ...", flush=True)
model = get_model("segface_celeb", 512, "mobilenet")
ckpt = torch.load(WEIGHTS, map_location="cpu", weights_only=False)
model.load_state_dict(ckpt["state_dict_backbone"])
model.eval()

so = ort.SessionOptions(); so.log_severity_level = 3
ocec = ort.InferenceSession(os.path.join(MODELS, "ocec_p.onnx"), so, providers=["CPUExecutionProvider"])
def ocec_prob(crop_bgr):
    rgb = cv2.cvtColor(crop_bgr, cv2.COLOR_BGR2RGB)
    inp = cv2.resize(rgb, (40, 24), interpolation=cv2.INTER_LINEAR).astype(np.float32)/255.0
    inp = np.ascontiguousarray(inp.transpose(2,0,1))[None]
    return float(np.squeeze(ocec.run(None, {"images": inp})[0]))

MEAN = np.array([0.485, 0.456, 0.406], np.float32)
STD = np.array([0.229, 0.224, 0.225], np.float32)
EYE_CLASSES = (8, 9)  # l_eye, r_eye

def iou_rect_vs_mask(rect, mask):
    x, y, w, h = rect
    gt = np.zeros_like(mask, np.uint8)
    gt[max(0,y):y+h, max(0,x):x+w] = 1
    inter = np.logical_and(gt, mask).sum()
    union = np.logical_or(gt, mask).sum()
    return inter/max(1, union)

tp = fp = tn = fn = miss = n = 0
ious, lat = [], []

for shard in ("001", "002", "900", "1200"):
    t = pq.read_table(os.path.join(SHARDS, f"cew_shard_{shard}.parquet")).to_pandas()
    for _, row in t.iterrows():
        img = cv2.imdecode(np.frombuffer(row["Image_data"]["file"], np.uint8), cv2.IMREAD_COLOR)
        if img is None: continue
        if img.shape[:2] != (512, 512):
            img = cv2.resize(img, (512, 512), interpolation=cv2.INTER_CUBIC)
        rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB).astype(np.float32)/255.0
        tin = torch.from_numpy(((rgb - MEAN)/STD).transpose(2,0,1))[None]
        t0 = time.perf_counter()
        with torch.no_grad():
            pred = model(tin, None, None).argmax(1)[0].numpy()
        lat.append(time.perf_counter()-t0)
        n += 1
        is_open = row["Label"] != "closed_eyes"
        eye_masks = [np.isin(pred, c) for c in EYE_CLASSES]
        crops = []
        for rc in ("Left_eye_react", "Right_eye_react"):
            r = np.array(row[rc]); rect = (int(r[0]), int(r[1]), int(r[2]), int(r[3]))
            if rect[2] <= 0 or rect[3] <= 0: continue
            best_iou, best_mask = 0.0, None
            for m in eye_masks:
                v = iou_rect_vs_mask(rect, m)
                if v > best_iou: best_iou, best_mask = v, m
            ious.append(best_iou)
            if best_mask is not None and best_mask.sum() > 20:
                ys, xs = np.where(best_mask)
                crops.append(img[max(0,ys.min()):ys.max()+1, max(0,xs.min()):xs.max()+1])
        if not crops:
            miss += 1
            continue
        probs = [ocec_prob(c) for c in crops]
        pred_open = float(np.mean(probs)) >= 0.5
        if is_open:
            if pred_open: tp += 1
            else: fn += 1
        else:
            if pred_open: fp += 1
            else: tn += 1

det = tp + fn + fp + tn
prec = tp/max(1, tp+fp); rec = tp/max(1, tp+fn)
f1 = 2*prec*rec/max(1e-9, prec+rec)
lat_arr = np.array(lat)
print(f"images: {n}  seg-latency: mean={lat_arr.mean()*1000:.0f}ms  p95={np.percentile(lat_arr,95)*1000:.0f}ms (PC CPU)")
print(f"eye-class 出现率: {(n-miss)/n*100:.1f}%  (miss={miss})")
print(f"mask-vs-GT框 IoU: mean={np.mean(ious):.3f}  p25={np.percentile(ious,25):.3f}")
print(f"SegFace+OCEC: accuracy={(tp+tn)/max(1,det)*100:.2f}%  precision={prec:.4f}  recall={rec:.4f}  F1={f1:.4f}")
print(f"对照: GT框+OCEC F1=0.9938(99.38%) | BlazeFace+几何+OCEC F1=0.9873(98.75%) | DEIMv2+OCEC F1=0.9975(99.75%)")
