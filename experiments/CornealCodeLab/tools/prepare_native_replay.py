"""将已导出的原视频眼区按原生像素保存，供手机同源Swift跟踪器离线复算。原始资料不修改。"""
import argparse
import json
import math
from pathlib import Path

import cv2
import numpy as np


def prepare(trial, output):
    trial, output = Path(trial), Path(output)
    output.mkdir(parents=True, exist_ok=False)
    rows = [json.loads(line) for line in (trial / "frames.jsonl").read_text(encoding="utf8").splitlines()]
    camera = json.loads((trial / "camera.json").read_text(encoding="utf8"))
    grouped = {}
    for row in rows:
        grouped.setdefault(row["index"], []).append(row)
    indices = sorted(grouped)
    cap = cv2.VideoCapture(str(trial / "camera.mov"))
    if not cap.isOpened():
        raise ValueError("无法打开原始视频")
    cv2.setNumThreads(2)
    count = 0
    with (output / "native_eyes.u8").open("wb") as pixels, (output / "native_frames.jsonl").open("w", encoding="utf8") as frames:
        while True:
            ok, image = cap.read()
            if not ok:
                break
            time = cap.get(cv2.CAP_PROP_POS_MSEC) / 1000
            # 写入丢帧时依照PTS找对应记录，不能按数组下标错配视频。
            if not camera.get("videoWriteDroppedFrames", 0) and count < len(indices):
                current = grouped[indices[count]]
            else:
                index = min(indices, key=lambda i: abs(grouped[i][0]["pts"] - camera["videoStartPTS"] - time))
                current = grouped[index]
            for row in current:
                row = dict(row)
                roi = row.get("roi")
                row.pop("nativeOffset", None)
                if roi:
                    x, y = math.floor(roi["x"]), math.floor(roi["y"])
                    w, h = math.ceil(roi["x"] + roi["width"]) - x, math.ceil(roi["y"] + roi["height"]) - y
                    if x >= 0 and y >= 0 and x + w <= image.shape[1] and y + h <= image.shape[0]:
                        gray = cv2.transform(image[y:y+h, x:x+w], np.array([[0.0722, 0.7152, 0.2126]], dtype=np.float32))
                        row["nativeOffset"] = pixels.tell()
                        row["nativeROI"] = dict(x=x, y=y, width=w, height=h)
                        pixels.write(gray.tobytes())
                frames.write(json.dumps(row, ensure_ascii=False) + "\n")
            count += 1
    cap.release()
    if not count:
        raise ValueError("原视频没有可解码帧")
    (output / "native_source.json").write_text(json.dumps({"frames": count, "pixelFormat": "gray8", "source": "camera.mov解码眼区；带H.264压缩，与手机相机原生BGRA有差别"}, ensure_ascii=False, indent=2), encoding="utf8")
    return count


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trial", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    print(json.dumps({"frames": prepare(args.trial, args.output)}))
