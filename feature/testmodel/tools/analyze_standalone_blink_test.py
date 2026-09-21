#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""analyze_standalone_blink_test.py — 全替换眨眼页 60s 检测数据（standalone_blink_*.csv）分析。

输入列（首行为表头）：t_s,prob_left,prob_right,prob_avg,blink_count,det_ms,cls_ms,fps
无脸帧概率列留空，统计时跳过。

报告：样本数 / 时长 / 双眼与平均概率统计 / 闭眼帧占比（prob_avg<0.5）/ 检测与分类耗时 / fps
图（--plot）：概率（含 0.5 阈值线）· 耗时 · 帧率 三面板随时间曲线。

颜色：Okabe-Ito CVD 安全色（#0072B2 蓝 / #E69F00 橙 / #009E73 绿），阈值线灰色虚线。
"""

import argparse
import csv
import statistics
import sys


def load(path):
    rows = []
    with open(path, "r", encoding="utf-8", errors="replace", newline="") as handle:
        for row in csv.DictReader(handle):
            def f(key):
                v = (row.get(key) or "").strip()
                try:
                    return float(v)
                except ValueError:
                    return None
            rows.append({
                "t_s": f("t_s"),
                "prob_left": f("prob_left"),
                "prob_right": f("prob_right"),
                "prob_avg": f("prob_avg"),
                "lat_det_ms": f("det_ms"),
                "lat_cls_ms": f("cls_ms"),
                "fps": f("fps"),
            })
    return rows


def col(rows, key):
    return [r[key] for r in rows if r[key] is not None]


def pct95(values):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round(0.95 * (len(ordered) - 1))))]


def statline(values, nd=3):
    if not values:
        return "n/a"
    return "mean %.*f  p95 %.*f  max %.*f  min %.*f" % (
        nd, statistics.fmean(values), nd, pct95(values),
        nd, max(values), nd, min(values))


def render_report(path, rows):
    out = []
    t = [r["t_s"] for r in rows if r["t_s"] is not None]
    duration = (max(t) - min(t)) if t else 0.0
    pa = col(rows, "prob_avg")
    closed_pct = (sum(1 for v in pa if v < 0.5) / len(pa) * 100) if pa else 0.0
    out.append("blink standalone report: %s" % path)
    out.append("rows %d  duration_s %.1f  face_frames %d" % (len(rows), duration, len(pa)))
    out.append("")
    out.append("prob_avg              %s" % statline(pa))
    out.append("prob_left             %s" % statline(col(rows, "prob_left")))
    out.append("prob_right            %s" % statline(col(rows, "prob_right")))
    out.append("closed_frames_pct     %.1f  (prob_avg < 0.5)" % closed_pct)
    out.append("det_ms                %s" % statline(col(rows, "lat_det_ms"), 1))
    out.append("cls_ms                %s" % statline(col(rows, "lat_cls_ms"), 1))
    out.append("fps                   %s" % statline(col(rows, "fps"), 1))
    return "\n".join(out)


def render_plot(path, rows, out_png):
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        return "matplotlib not installed, skip --plot (pip install matplotlib to enable)"

    C_BLUE, C_ORANGE, C_GREEN, C_GRAY = "#0072B2", "#E69F00", "#009E73", "#7F7F7F"
    fig, (ax_p, ax_l, ax_f) = plt.subplots(3, 1, figsize=(10, 9), sharex=True)

    def series(key):
        xs = [r["t_s"] for r in rows if r[key] is not None]
        ys = [r[key] for r in rows if r[key] is not None]
        return xs, ys

    # 面板 1：概率
    for key, label, color in (("prob_left", "left", C_ORANGE),
                              ("prob_right", "right", C_GREEN),
                              ("prob_avg", "avg", C_BLUE)):
        xs, ys = series(key)
        if ys:
            ax_p.plot(xs, ys, lw=1.3, color=color, label=label)
    ax_p.axhline(0.5, color=C_GRAY, ls="--", lw=1.0)
    ax_p.text(0.995, 0.52, "threshold 0.5", transform=ax_p.get_yaxis_transform(),
              ha="right", fontsize=8, color=C_GRAY)
    ax_p.set_ylabel("prob open")
    ax_p.set_ylim(-0.02, 1.05)
    ax_p.grid(True, alpha=0.3)
    ax_p.legend(loc="lower left", fontsize="small", ncol=3)

    # 面板 2：耗时
    for key, label, color in (("lat_det_ms", "detect", C_BLUE), ("lat_cls_ms", "classify", C_ORANGE)):
        xs, ys = series(key)
        if ys:
            mean = statistics.fmean(ys)
            ax_l.plot(xs, ys, lw=1.3, color=color, label=label)
            ax_l.axhline(mean, color=color, ls="--", lw=0.8, alpha=0.5)
    ax_l.set_ylabel("latency (ms)")
    ax_l.grid(True, alpha=0.3)
    ax_l.legend(loc="upper right", fontsize="small")

    # 面板 3：帧率
    xs, ys = series("fps")
    if ys:
        ax_f.plot(xs, ys, lw=1.3, color=C_GREEN, label="fps")
        ax_f.axhline(statistics.fmean(ys), color=C_GREEN, ls="--", lw=0.8, alpha=0.6)
    ax_f.set_ylabel("fps")
    ax_f.set_xlabel("elapsed (s)")
    ax_f.grid(True, alpha=0.3)
    ax_f.legend(loc="upper right", fontsize="small")

    fig.suptitle("blink standalone: %s" % path)
    fig.tight_layout()
    fig.savefig(out_png, dpi=130)
    return "plot saved: %s" % out_png


def main(argv):
    parser = argparse.ArgumentParser(description="Analyze standalone blink CSV.")
    parser.add_argument("csv", help="CSV file from StandaloneBlinkViewModel")
    parser.add_argument("--plot", metavar="PNG", help="optional matplotlib chart output")
    args = parser.parse_args(argv)

    rows = load(args.csv)
    if not rows:
        sys.stdout.buffer.write(b"no data rows\n")
        return 1
    report = render_report(args.csv, rows)
    if args.plot:
        report += "\n" + render_plot(args.csv, rows, args.plot)
    sys.stdout.buffer.write(report.encode("utf-8") + b"\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
