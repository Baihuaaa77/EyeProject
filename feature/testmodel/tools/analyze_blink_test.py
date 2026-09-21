#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""analyze_blink_test.py — 眨眼页 60s 检测数据（blink_test_*.csv）分析（纯标准库；画图需 matplotlib）。

输入列（首行为表头）：t_s,ear_left,ear_right,ear_avg,latency_ms,fps
无脸帧 ear 列为空，统计时跳过；fps 列始终有值。

报告：样本数 / 时长 / EAR 均值与 p95 / 闭眼帧占比（EAR < 0.23）/ 检测耗时 / 帧率
图（--plot）：EAR（含 0.23 阈值线）· 检测耗时 · 帧率 三面板随时间曲线

用法:
  python analyze_blink_test.py <csv> [--plot out.png]
"""

import argparse
import csv
import statistics
import sys

EAR_THRESHOLD = 0.23


def to_float(value):
    value = (value or "").strip()
    if not value:
        return None
    try:
        return float(value)
    except ValueError:
        return None


def load_rows(path):
    """读 CSV，逐列转 float；空字段为 None。"""
    rows = []
    with open(path, "r", encoding="utf-8", errors="replace", newline="") as handle:
        for raw in csv.DictReader(handle):
            rows.append({
                "t_s": to_float(raw.get("t_s")),
                "ear_left": to_float(raw.get("ear_left")),
                "ear_right": to_float(raw.get("ear_right")),
                "ear_avg": to_float(raw.get("ear_avg")),
                "latency_ms": to_float(raw.get("latency_ms")),
                "fps": to_float(raw.get("fps")),
            })
    return rows


def col(rows, key):
    return [r[key] for r in rows if r[key] is not None]


def p95(values):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round(0.95 * (len(ordered) - 1))))]


def statline(values, precision=4):
    if not values:
        return "n/a"
    return "mean %.*f  p95 %.*f  max %.*f  min %.*f" % (
        precision, statistics.fmean(values),
        precision, p95(values),
        precision, max(values),
        precision, min(values),
    )


def render_report(path, rows):
    ear_avg = col(rows, "ear_avg")
    ear_left = col(rows, "ear_left")
    ear_right = col(rows, "ear_right")
    latency = col(rows, "latency_ms")
    fps = col(rows, "fps")
    closed = [v for v in ear_avg if v < EAR_THRESHOLD]

    out = []
    out.append("blink test report: %s" % path)
    out.append("rows %d  duration_s %.1f  face_frames %d" % (
        len(rows), (col(rows, "t_s") or [0.0])[-1] if rows else 0.0, len(ear_avg)))
    out.append("")
    out.append("ear_avg               %s" % statline(ear_avg))
    out.append("ear_left              %s" % statline(ear_left))
    out.append("ear_right             %s" % statline(ear_right))
    if ear_avg:
        out.append("ear_below_%.2f_pct     %.1f  (closed frames)" % (
            EAR_THRESHOLD, 100.0 * len(closed) / len(ear_avg)))
    out.append("latency_ms            %s" % statline(latency, 1))
    out.append("fps                   %s" % statline(fps, 1))
    return "\n".join(out)


def render_plot(path, rows, out_png):
    """EAR / 检测耗时 / 帧率 三面板随时间曲线（共用时间轴）。"""
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        return "matplotlib not installed, skip --plot (pip install matplotlib to enable)"

    fig, (ax_ear, ax_lat, ax_fps) = plt.subplots(
        3, 1, figsize=(10, 9), sharex=True)

    t = col(rows, "t_s")
    # EAR：双眼 + 平均，阈值线
    for key, label, color in (
        ("ear_left", "ear left", "tab:gray"),
        ("ear_right", "ear right", "tab:purple"),
        ("ear_avg", "ear avg", "tab:blue"),
    ):
        xs = [r["t_s"] for r in rows if r[key] is not None]
        ys = [r[key] for r in rows if r[key] is not None]
        if ys:
            ax_ear.plot(xs, ys, color=color, lw=1.0 if key != "ear_avg" else 1.5,
                        label=label)
    ax_ear.axhline(EAR_THRESHOLD, color="tab:red", ls="--", lw=1.0,
                   label="threshold %.2f" % EAR_THRESHOLD)
    ax_ear.set_ylabel("ear")
    ax_ear.grid(True, alpha=0.3)
    ax_ear.legend(loc="upper right", fontsize="small", ncols=2)

    xs = [r["t_s"] for r in rows if r["latency_ms"] is not None]
    ys = [r["latency_ms"] for r in rows if r["latency_ms"] is not None]
    if ys:
        ax_lat.plot(xs, ys, color="tab:orange", lw=1.0)
        ax_lat.axhline(statistics.fmean(ys), color="tab:orange", ls="--", lw=0.8,
                       alpha=0.6, label="mean %.1f ms" % statistics.fmean(ys))
        ax_lat.legend(loc="upper right", fontsize="small")
    ax_lat.set_ylabel("latency (ms)")
    ax_lat.grid(True, alpha=0.3)

    xs = [r["t_s"] for r in rows if r["fps"] is not None]
    ys = [r["fps"] for r in rows if r["fps"] is not None]
    if ys:
        ax_fps.plot(xs, ys, color="tab:green", lw=1.0)
        ax_fps.axhline(statistics.fmean(ys), color="tab:green", ls="--", lw=0.8,
                       alpha=0.6, label="mean %.1f fps" % statistics.fmean(ys))
        ax_fps.legend(loc="upper right", fontsize="small")
    ax_fps.set_ylabel("fps")
    ax_fps.set_xlabel("elapsed (s)")
    ax_fps.grid(True, alpha=0.3)

    fig.suptitle("blink test: %s" % path)
    fig.tight_layout()
    fig.savefig(out_png, dpi=130)
    return "plot saved: %s" % out_png


def main(argv):
    parser = argparse.ArgumentParser(description="Analyze blink test CSV.")
    parser.add_argument("csv", help="CSV file from blink test page")
    parser.add_argument("--plot", metavar="PNG", help="optional matplotlib chart output")
    args = parser.parse_args(argv)

    rows = load_rows(args.csv)
    if not rows:
        sys.stdout.buffer.write(b"no data rows\n")
        return 1

    report = render_report(args.csv, rows)
    if args.plot:
        report += "\n" + render_plot(args.csv, rows, args.plot)
    # GBK 控制台兜底：统一用 UTF-8 字节写出
    sys.stdout.buffer.write(report.encode("utf-8") + b"\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
