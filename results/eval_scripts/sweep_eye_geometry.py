# -*- coding: utf-8 -*-
"""sweep_eye_geometry.py — 眼框几何参数扫描（在已保存的眼片上找最优构图）。

用途：对一组同状态（如全部睁眼）的眼片，测试中心缩放 × 上下偏移的组合，
     找使分类置信度最高（睁眼片 mean prob 最高 / <0.5 占比最低）的几何。
注意：结果会随会话（距离/姿态）漂移，多会话取公共规律；最终仍建议微调带几何增强。

用法: python sweep_eye_geometry.py <crops_dir> [models_dir]
"""
import sys, os
import numpy as np, cv2, onnxruntime as ort

def main(crops_dir, models_dir):
    so = ort.SessionOptions(); so.log_severity_level = 3
    sess = ort.InferenceSession(os.path.join(models_dir, "ocec_p.onnx"), so,
                                providers=["CPUExecutionProvider"])
    def classify(crop_bgr):
        rgb = cv2.cvtColor(crop_bgr, cv2.COLOR_BGR2RGB)
        inp = cv2.resize(rgb, (40, 24), interpolation=cv2.INTER_LINEAR).astype(np.float32)/255.0
        inp = np.ascontiguousarray(inp.transpose(2,0,1))[None]
        return float(np.squeeze(sess.run(None, {"images": inp})[0]))

    files = sorted(f for f in os.listdir(crops_dir) if f.endswith(".png"))
    imgs = [cv2.imread(os.path.join(crops_dir, f)) for f in files]
    print(f"n={len(imgs)}  (假定为同状态眼片)")
    print(f"{'变体':<26}{'mean':>7}{'<0.5占比':>9}")
    best = None
    for zoom in (1.0, 0.85, 0.7, 0.55, 0.45):
        for yo in (-0.2, -0.1, 0.0, 0.1):
            ps = []
            for img in imgs:
                h, w = img.shape[:2]
                cw, ch = int(w*zoom), int(h*zoom)
                cx, cy = w//2, int(h/2 + yo*h)
                x1, y1 = max(0, cx-cw//2), max(0, cy-ch//2)
                crop = img[y1:y1+ch, x1:x1+cw]
                ps.append(classify(crop) if crop.size else 0.5)
            ps = np.array(ps); bad = (ps<0.5).mean()*100
            print(f"zoom={zoom:<4} y_off={yo:+.2f}{'':<12}{ps.mean():>7.3f}{bad:>8.1f}%")
            if best is None or bad < best[2]: best = (zoom, yo, bad, ps.mean())
    print(f"BEST: zoom={best[0]} y_off={best[1]:+.2f}  <0.5={best[2]:.1f}%  mean={best[3]:.3f}")

if __name__ == "__main__":
    crops = sys.argv[1] if len(sys.argv) > 1 else "results/data/crops/session1"
    models = sys.argv[2] if len(sys.argv) > 2 else "results/models"
    main(crops, models)
