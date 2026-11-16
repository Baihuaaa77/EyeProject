"""导出逐帧跟踪复核表与原生眼区对照图；图像观测和预测分开统计。"""
import argparse
import csv
import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont


def review(trial, tracks, output):
    trial, output = Path(trial), Path(output)
    output.mkdir(parents=True, exist_ok=False)
    results = json.loads(Path(tracks).read_text(encoding="utf8"))
    rows = [json.loads(x) for x in (trial / "native-replay/native_frames.jsonl").read_text(encoding="utf8").splitlines()]
    native = np.memmap(trial / "native-replay/native_eyes.u8", dtype=np.uint8, mode="r")
    by_key = {(r["index"], r.get("eye")): r for r in rows}
    font_path = Path("C:/Windows/Fonts/msyh.ttc")
    font = ImageFont.truetype(str(font_path), 17) if font_path.exists() else ImageFont.load_default()
    metrics = []
    with (output / "逐帧坐标.csv").open("w", encoding="utf-8-sig", newline="") as file:
        writer = csv.writer(file)
        writer.writerow(["eye", "frame", "seconds", "x", "y", "source", "confidence", "diameter", "expected", "snr"])
        for result in results:
            frames = result["frames"]
            start = frames[0]["pts"]
            for f in frames:
                p = f.get("imagePoint", {})
                writer.writerow([result["eye"], f["frameIndex"], f["pts"]-start, p.get("x"), p.get("y"), f["source"], f["confidence"], f.get("observedDiameterPixels"), f.get("expectedDiameterPixels"), f.get("snr")])
            metric = {k: v for k, v in result.items() if k not in ("frames", "note")}
            for key in ("observedDiameterPixels", "expectedDiameterPixels"):
                values = [f[key] for f in frames if key in f and f["source"] == "observed"]
                metric[key] = np.percentile(values, [10, 50, 90]).tolist() if values else []
            metrics.append(metric)
            # 每个时间点并排保留原图与叠加图，便于独立检查亮点身份。
            sheet = Image.new("RGB", (1040, 300*8), "#151515")
            draw = ImageDraw.Draw(sheet)
            for j, second in enumerate([1, 3, 6, 10, 15, 20, 25, 30]):
                f = min(frames, key=lambda r: abs(r["pts"]-start-second))
                row = by_key.get((f["frameIndex"], result["eye"]))
                if not row or "nativeOffset" not in row:
                    continue
                roi = row["nativeROI"]
                w, h, offset = int(roi["width"]), int(roi["height"]), row["nativeOffset"]
                image = Image.fromarray(np.array(native[offset:offset+w*h]).reshape(h, w)).convert("RGB")
                # 原生裁剪等比显示；坐标转换直接用原生 ROI。
                scale = min(510/w, 245/h)
                image = image.resize((round(w*scale), round(h*scale)))
                sheet.paste(image, (0, j*300+45))
                sheet.paste(image, (520, j*300+45))
                p = f.get("imagePoint")
                if p:
                    x, y = 520+(p["x"]-roi["x"])*scale, j*300+45+(p["y"]-roi["y"])*scale
                    color = "#46ed81" if f["source"] == "observed" else "#ffc857"
                    draw.ellipse((x-7, y-7, x+7, y+7), outline=color, width=2)
                    draw.line((x-13, y, x-8, y), fill=color, width=2)
                draw.text((4, j*300+6), f'{second}s  frame={f["frameIndex"]}  {result["eye"]}', font=font, fill="white")
                draw.text((525, j*300+6), f'{f["source"]}  Q={f["confidence"]:.2f}', font=font, fill="white")
            sheet.save(output / (result["eye"]+"_原图与跟踪.png"))
    (output / "统计.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf8")
    print(json.dumps(metrics, ensure_ascii=True))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trial", type=Path)
    parser.add_argument("tracks", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    review(args.trial, args.tracks, args.output)
