# -*- coding: utf-8 -*-
"""eval_device_crops.py — 设备端眼片采样的 PC 端复核（验证预处理是否一致）。

输入：results/crops*/ 目录中 StandaloneBlinkAnalyzer 采样的眼片 PNG，
     文件名格式 `{left|right}_p<设备端概率>_<时间戳>.png`。
输出：PC 端 ONNX 重分类 vs 设备端概率的一致性统计 + 蒙太奇图。
判读：|PC-设备| 小且相关高 → 预处理一致，误差来自模型域差；
     大分歧 → 设备端预处理有 bug（通道/归一化/布局）。

用法: python eval_device_crops.py <crops_dir> [models_dir]
"""
import sys, os, re
import numpy as np, cv2, onnxruntime as ort

def main(crops_dir, models_dir):
    so = ort.SessionOptions(); so.log_severity_level = 3
    sess = ort.InferenceSession(os.path.join(models_dir, "ocec_p.onnx"), so,
                                providers=["CPUExecutionProvider"])
    files = [f for f in sorted(os.listdir(crops_dir)) if re.match(r"(left|right)_p[\d.]+_\d+\.png", f)]
    dev, pc = [], []
    cell_w, cell_h, label_h, cols = 80, 48, 14, 8
    cells = []
    for fn in files:
        m = re.match(r"(left|right)_p([\d.]+)_", fn)
        devp = float(m.group(2))
        img = cv2.cvtColor(cv2.imread(os.path.join(crops_dir, fn)), cv2.COLOR_BGR2RGB)
        inp = cv2.resize(img, (40, 24), interpolation=cv2.INTER_LINEAR).astype(np.float32)/255.0
        inp = np.ascontiguousarray(inp.transpose(2,0,1))[None]
        pcp = float(np.squeeze(sess.run(None, {"images": inp})[0]))
        dev.append(devp); pc.append(pcp)
        cells.append((cv2.resize(cv2.imread(os.path.join(crops_dir, fn)), (cell_w, cell_h)), devp, pcp))
    dev, pc = np.array(dev), np.array(pc)
    print(f"n={len(dev)}")
    print(f"PC   prob: mean={pc.mean():.3f}  <0.5={np.mean(pc<0.5)*100:.1f}%")
    print(f"设备 prob: mean={dev.mean():.3f}  <0.5={np.mean(dev<0.5)*100:.1f}%")
    print(f"平均|PC-设备|={np.mean(np.abs(dev-pc)):.3f}  大分歧(>0.2)={np.mean(np.abs(dev-pc)>0.2)*100:.1f}%")
    print(f"相关系数={np.corrcoef(dev,pc)[0,1]:.3f}")
    rows_n = (len(cells)+cols-1)//cols
    sheet = np.full((rows_n*(cell_h+label_h), cols*cell_w, 3), 255, np.uint8)
    for i, (img, devp, pcp) in enumerate(cells):
        r_, c_ = i//cols, i%cols
        y0 = r_*(cell_h+label_h)
        sheet[y0:y0+cell_h, c_*cell_w:(c_+1)*cell_w] = img
        cv2.putText(sheet, f"d{devp:.2f} p{pcp:.2f}", (c_*cell_w+2, y0+cell_h+11),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.32, (0,0,255) if devp<0.5 else (0,128,0), 1)
    out = os.path.join(crops_dir, "montage.png")
    cv2.imwrite(out, sheet)
    print(f"蒙太奇 -> {out}")

if __name__ == "__main__":
    crops = sys.argv[1] if len(sys.argv) > 1 else "results/data/crops/session1"
    models = sys.argv[2] if len(sys.argv) > 2 else "results/models"
    main(crops, models)
