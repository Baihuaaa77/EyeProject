"""把已导出的 CornealCodeLab 原视频抽帧，供共享屏幕映光算法离线复核；不上传素材。"""
import argparse
import json
import math
import struct
from pathlib import Path

import av
import numpy as np


def prepare(folder: Path, output: Path, stride: int):
    records = []
    for trial in sorted(folder.iterdir()):
        if not (trial / "camera.mov").exists():
            continue
        camera = json.loads((trial / "camera.json").read_text(encoding="utf-8"))
        start = camera["videoStartPTS"]
        tracks = json.loads((trial / "glint_tracks.json").read_text(encoding="utf-8"))
        lookup = {(t["eye"], f["frameIndex"]): f for t in tracks for f in t["frames"]}
        frames = [json.loads(line) for line in (trial / "frames.jsonl").read_text(encoding="utf-8").splitlines()]
        requested = {}
        for f in frames:
            if f["index"] % stride == 1 and "iris" in f and "nativeROI" in f:
                requested.setdefault(round((f["pts"] - start) * 600), []).append(f)
        with av.open(str(trial / "camera.mov")) as video:
            for frame in video.decode(video=0):
                pts = round(float(frame.time) * 600)
                selected = requested.get(pts)
                if not selected:
                    continue
                rgb = frame.to_ndarray(format="rgb24")
                for f in selected:
                    roi, iris = f["nativeROI"], f["iris"]
                    # 旧文件的虹膜坐标以记录 ROI 为基准，nativeROI 是相同原像素裁剪。
                    x, y, w, h = (roi[k] for k in ("x", "y", "width", "height"))
                    base = f["roi"]
                    cx = base["x"] + iris["center"]["x"] * base["width"] - x
                    cy = base["y"] + iris["center"]["y"] * base["height"] - y
                    diameter = iris["boundaryRadiusX"] * base["width"] * 2
                    lid = [(base["x"] + p["x"] * base["width"] - x,
                            base["y"] + p["y"] * base["height"] - y) for p in iris["eyelid"]]
                    result = lookup.get((f["eye"], f["index"]), {})
                    phase = {"on": 1, "off": 0}.get(result.get("emissionPhase"), -1)
                    ref = result.get("independentMeasurementPoint")
                    if result.get("independentSource") != "observed" or result.get("independentConfidence", 0) < .8:
                        ref = None
                    gray = np.rint(rgb[y:y+h, x:x+w].astype(np.float32) @ np.array([.2126, .7152, .0722], np.float32)).clip(0, 255).astype(np.uint8)
                    records.append((trial.name, "LEFT" if f["eye"] == "image_left" else "RIGHT", f["index"], phase,
                                    w, h, cx, cy, diameter, lid, (ref["x"]-x, ref["y"]-y) if ref else (math.nan, math.nan), gray.tobytes()))
    with output.open("wb") as stream:
        stream.write(struct.pack(">i", len(records)))
        for trial, eye, index, phase, w, h, cx, cy, diameter, lid, ref, gray in records:
            for text in (trial, eye):
                data = text.encode("utf-8")
                stream.write(struct.pack(">i", len(data)) + data)
            stream.write(struct.pack(">4i3f", index, phase, w, h, cx, cy, diameter))
            stream.write(struct.pack(">14f", *(v for point in lid for v in point), *ref))
            stream.write(gray)
    print(f"prepared {len(records)} eye frames")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--stride", type=int, default=4)
    args = parser.parse_args()
    prepare(args.folder, args.output, args.stride)
