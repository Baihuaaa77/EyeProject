# -*- coding: utf-8 -*-
"""eval_dataset_gt_boxes.py — 用数据集 GT 眼框评估 OCEC 分类器（管线一致性验证）。

数据：MichalMlodawski/closed-open-eyes（HF，ODC-By）parquet 分片，
     每行含 Image_data(JPEG) + Left/Right_eye_react([x,y,w,h]) + Label(closed_eyes/open_eyes)。
结论参考：GT 眼框下 accuracy 99.38% / F1 0.9938（2026-09-11，400 open + 400 closed）。

用法: python eval_dataset_gt_boxes.py [models_dir] [shards_dir]
      默认 models_dir=shards_dir=results/models
"""
import sys, os
import numpy as np, cv2, pyarrow.parquet as pq, onnxruntime as ort

def main(models_dir, shards_dir):
    so = ort.SessionOptions(); so.log_severity_level = 3
    sess = ort.InferenceSession(os.path.join(models_dir, "ocec_p.onnx"), so,
                                providers=["CPUExecutionProvider"])
    def classify(crop_bgr):
        rgb = cv2.cvtColor(crop_bgr, cv2.COLOR_BGR2RGB)
        inp = cv2.resize(rgb, (40, 24), interpolation=cv2.INTER_LINEAR).astype(np.float32)/255.0
        inp = np.ascontiguousarray(inp.transpose(2,0,1))[None]
        return float(np.squeeze(sess.run(None, {"images": inp})[0]))

    probs_open, probs_closed = [], []
    for shard, want_open in (("900", True), ("1200", True), ("001", False), ("002", False)):
        t = pq.read_table(os.path.join(shards_dir, f"cew_shard_{shard}.parquet")).to_pandas()
        for _, row in t.iterrows():
            is_open = row["Label"] != "closed_eyes"
            if is_open != want_open: continue
            img = cv2.imdecode(np.frombuffer(row["Image_data"]["file"], np.uint8), cv2.IMREAD_COLOR)
            if img is None: continue
            for rc in ("Left_eye_react", "Right_eye_react"):
                r = np.array(row[rc]); x, y, w, h = int(r[0]), int(r[1]), int(r[2]), int(r[3])
                if w <= 0 or h <= 0: continue
                crop = img[max(0,y):y+h, max(0,x):x+w]
                if crop.size == 0: continue
                (probs_open if is_open else probs_closed).append(classify(crop))

    po, pc = np.array(probs_open), np.array(probs_closed)
    tp = int((po>=0.5).sum()); fn = len(po)-tp
    tn = int((pc<0.5).sum()); fp = len(pc)-tn
    prec = tp/max(1,tp+fp); rec = tp/max(1,tp+fn)
    f1 = 2*prec*rec/max(1e-9, prec+rec)
    print(f"open   n={len(po)}: mean={po.mean():.3f} p5={np.percentile(po,5):.3f} 漏判={fn}({fn/len(po)*100:.1f}%)")
    print(f"closed n={len(pc)}: mean={pc.mean():.3f} p95={np.percentile(pc,95):.3f} 误判={fp}({fp/len(pc)*100:.1f}%)")
    print(f"accuracy={(tp+tn)/(tp+tn+fp+fn)*100:.2f}%  precision={prec:.4f}  recall={rec:.4f}  F1={f1:.4f}")

if __name__ == "__main__":
    models = sys.argv[1] if len(sys.argv) > 1 else "results/models"
    shards = sys.argv[2] if len(sys.argv) > 2 else models
    main(models, shards)
