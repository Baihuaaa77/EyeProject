#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""analyze_perf.py — perf_sampler.sh 采样结果的 PC 端分析（纯标准库；画图需 matplotlib）。

输入 perf_sampler.sh 产出的原始计数 CSV，在 PC 端完成差分与百分比计算：

  - App CPU 折算单核 %  = procΔ / (elapsed × clk_tck)
  - App CPU 占全机 %    = procΔ / totalΔ
  - 整机 / 每核 CPU 利用率 % = 100 × (1 − idleΔ / totalΔ)
  - 内存 = RSS / HWM / MemAvailable 趋势

报告输出为「字段 值」纯文本（ASCII，GBK 控制台安全），无装饰符号与 sparkline。
"""

import argparse
import csv
import statistics
import sys


def to_int(value):
    """空串/非数字 -> None，其余 -> int。"""
    if value is None:
        return None
    value = value.strip()
    if not value:
        return None
    try:
        return int(value)
    except ValueError:
        try:
            return int(float(value))
        except ValueError:
            return None


def load_rows(path):
    with open(path, "r", encoding="utf-8", errors="replace", newline="") as handle:
        reader = csv.DictReader(handle)
        return [dict(row) for row in reader]


def core_columns(fieldnames):
    """从表头提取 cpuN_total / cpuN_idle 的 N 列表。"""
    cores = []
    for name in fieldnames or []:
        if name.startswith("cpu") and name.endswith("_total"):
            idx = name[len("cpu"):-len("_total")]
            if idx.isdigit():
                cores.append(int(idx))
    return sorted(set(cores))


def compute_series(rows):
    """对相邻行做差分，得到各指标时序（首行作为基线不产生指标）。

    返回 (series, per_core, notes)：
      series  : 指标名 -> {"x": [累计秒], "y": [值]}
      per_core: "cpuN_busy_pct" -> {"x": [...], "y": [...]}
      notes   : rows/pairs/skipped/restarts/app_gaps/duration_s
    """
    series = {name: {"x": [], "y": []} for name in (
        "app_cpu_one_core_pct",
        "app_cpu_of_total_pct",
        "total_cpu_util_pct",
        "threads",
        "rss_kb",
        "hwm_kb",
        "mem_avail_kb",
    )}
    per_core = {}
    notes = {"rows": len(rows), "pairs": 0, "skipped": 0,
             "restarts": 0, "app_gaps": 0, "duration_s": 0.0}

    elapsed_sum = 0.0
    for i in range(1, len(rows)):
        prev, cur = rows[i - 1], rows[i]
        pid = (cur.get("pid") or "").strip()
        prev_pid = (prev.get("pid") or "").strip()

        # PID 变化 = 应用中途重启过（差分只在 pid_ok 时计算，自动断开）
        if pid and prev_pid and pid != prev_pid:
            notes["restarts"] += 1
        pid_ok = bool(pid) and bool(prev_pid) and pid == prev_pid

        ts0, ts1 = to_int(prev.get("ts")), to_int(cur.get("ts"))
        if ts0 is None or ts1 is None:
            notes["skipped"] += 1
            continue
        elapsed = (ts1 - ts0) / 1000.0  # ts 列为毫秒
        if elapsed <= 0:
            notes["skipped"] += 1
            continue
        elapsed_sum += elapsed
        notes["pairs"] += 1

        def add(name, value):
            if value is not None:
                series[name]["x"].append(elapsed_sum)
                series[name]["y"].append(value)

        clk = to_int(cur.get("clk_tck")) or 100
        total0, total1 = to_int(prev.get("total")), to_int(cur.get("total"))
        idle0, idle1 = to_int(prev.get("idle")), to_int(cur.get("idle"))
        total_delta = (total1 - total0) if None not in (total0, total1) else 0

        # ---- 系统级指标（不依赖目标进程） ----
        if total_delta > 0:
            idle_delta = min(total_delta, max(0, idle1 - idle0))
            add("total_cpu_util_pct", 100.0 * (1.0 - idle_delta / total_delta))

        # 每核利用率（按 cpuN key 对齐，缺失/离线的核跳过）
        for idx in core_columns(list(cur.keys())):
            t0 = to_int(prev.get("cpu%d_total" % idx))
            t1 = to_int(cur.get("cpu%d_total" % idx))
            d0 = to_int(prev.get("cpu%d_idle" % idx))
            d1 = to_int(cur.get("cpu%d_idle" % idx))
            if None in (t0, t1, d0, d1):
                continue
            t_delta = t1 - t0
            if t_delta <= 0:
                continue
            i_delta = min(t_delta, max(0, d1 - d0))
            slot = per_core.setdefault("cpu%d_busy_pct" % idx, {"x": [], "y": []})
            slot["x"].append(elapsed_sum)
            slot["y"].append(100.0 * (1.0 - i_delta / t_delta))

        # ---- 应用指标（当前值 + 差分） ----
        add("threads", to_int(cur.get("threads")))
        add("rss_kb", to_int(cur.get("rss_kb")))
        add("hwm_kb", to_int(cur.get("hwm_kb")))
        add("mem_avail_kb", to_int(cur.get("mem_avail_kb")))

        proc0, proc1 = to_int(prev.get("proc")), to_int(cur.get("proc"))
        if pid_ok and None not in (proc0, proc1):
            # 进程计数含已回收子进程的一次性尖刺（cutime/cstime），属口径内行为
            proc_delta = max(0, proc1 - proc0)
            add("app_cpu_one_core_pct", proc_delta * 100.0 / (elapsed * clk))
            if total_delta > 0:
                add("app_cpu_of_total_pct",
                    min(100.0, proc_delta * 100.0 / total_delta))
        else:
            notes["app_gaps"] += 1

    notes["duration_s"] = elapsed_sum
    return series, per_core, notes


def statline(values):
    """一行统计：mean/p95/max/min；空序列返回 n/a。"""
    values = [v for v in values if v is not None]
    if not values:
        return "n/a"
    ordered = sorted(values)
    p95 = ordered[min(len(ordered) - 1, int(round(0.95 * (len(ordered) - 1))))]
    return "mean %.1f  p95 %.1f  max %.1f  min %.1f" % (
        statistics.fmean(values), p95, ordered[-1], ordered[0])


def render_report(path, series, per_core, notes):
    """「字段 值」纯文本报告，无装饰符号。"""
    out = []
    out.append("perf report: %s" % path)
    out.append("rows %d  pairs %d  skipped %d  restarts %d  app_gaps %d  duration_s %.1f" % (
        notes["rows"], notes["pairs"], notes["skipped"],
        notes["restarts"], notes["app_gaps"], notes["duration_s"]))
    out.append("")

    out.append("app_cpu_one_core_pct   %s" % statline(series["app_cpu_one_core_pct"]["y"]))
    out.append("app_cpu_of_total_pct   %s" % statline(series["app_cpu_of_total_pct"]["y"]))
    out.append("total_cpu_util_pct     %s" % statline(series["total_cpu_util_pct"]["y"]))
    for key in sorted(per_core):
        values = per_core[key]["y"]
        if values:
            out.append("%-22s mean %.1f  max %.1f" % (
                key, statistics.fmean(values), max(values)))
    out.append("threads                %s" % statline(series["threads"]["y"]))
    out.append("rss_mb                 %s" % statline(
        [v / 1024.0 for v in series["rss_kb"]["y"]]))
    hwm = series["hwm_kb"]["y"]
    if hwm:
        out.append("hwm_mb                 max %.1f" % (max(hwm) / 1024.0))
    out.append("mem_avail_mb           %s" % statline(
        [v / 1024.0 for v in series["mem_avail_kb"]["y"]]))
    return "\n".join(out)


def render_plot(path, series, per_core, out_png):
    """CPU / 内存双面板折线图：每核利用率做淡色背景线，可用内存走副轴。"""
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        return "matplotlib not installed, skip --plot (pip install matplotlib to enable)"

    fig, (ax_cpu, ax_mem) = plt.subplots(2, 1, figsize=(10, 7), sharex=True)

    # 上：CPU：每核淡色背景线 + 应用与整机主线
    for key in sorted(per_core):
        slot = per_core[key]
        if slot["y"]:
            ax_cpu.plot(slot["x"], slot["y"], color="gray",
                        alpha=0.25, lw=0.8, label="_nolegend_")
    slot = series["app_cpu_one_core_pct"]
    if slot["y"]:
        ax_cpu.plot(slot["x"], slot["y"], color="tab:blue", lw=1.4,
                    label="app cpu (one-core %)")
    slot = series["total_cpu_util_pct"]
    if slot["y"]:
        ax_cpu.plot(slot["x"], slot["y"], color="tab:red", lw=1.2,
                    alpha=0.85, label="total util %")
    ax_cpu.set_ylabel("cpu %")
    ax_cpu.grid(True, alpha=0.3)
    ax_cpu.legend(loc="upper left", fontsize="small")

    # 下：内存：RSS（左轴 MB）+ MemAvailable（右轴 GB）
    slot = series["rss_kb"]
    if slot["y"]:
        mb = [v / 1024.0 for v in slot["y"]]
        ax_mem.plot(slot["x"], mb, color="tab:blue", lw=1.4, label="app rss (MB)")
        ax_mem.axhline(statistics.fmean(mb), color="tab:blue",
                       ls="--", lw=0.8, alpha=0.6)
    slot = series["mem_avail_kb"]
    ax_avail = None
    if slot["y"]:
        ax_avail = ax_mem.twinx()
        ax_avail.plot(slot["x"], [v / 1048576.0 for v in slot["y"]],
                      color="tab:orange", lw=1.0, alpha=0.8, label="mem avail (GB)")
        ax_avail.set_ylabel("avail (GB)", color="tab:orange")
        ax_avail.tick_params(axis="y", labelcolor="tab:orange")
    ax_mem.set_ylabel("rss (MB)")
    ax_mem.set_xlabel("elapsed (s)")
    ax_mem.grid(True, alpha=0.3)
    lines = [ax_mem.get_legend_handles_labels()]
    if ax_avail is not None:
        lines.append(ax_avail.get_legend_handles_labels())
    handles = [h for ls, _ in lines for h in ls]
    labels = [lb for _, lbs in lines for lb in lbs]
    if handles:
        ax_mem.legend(handles, labels, loc="upper left", fontsize="small")

    fig.suptitle("perf capture: %s" % path)
    fig.tight_layout()
    fig.savefig(out_png, dpi=130)
    return "plot saved: %s" % out_png


def main(argv):
    parser = argparse.ArgumentParser(description="Analyze perf_sampler.sh CSV output.")
    parser.add_argument("csv", help="CSV file from perf_sampler.sh")
    parser.add_argument("--plot", metavar="PNG", help="optional matplotlib chart output")
    args = parser.parse_args(argv)

    rows = load_rows(args.csv)
    if len(rows) < 2:
        sys.stdout.buffer.write(b"need at least 2 data rows (first row is baseline)\n")
        return 1

    series, per_core, notes = compute_series(rows)
    report = render_report(args.csv, series, per_core, notes)
    if args.plot:
        report += "\n" + render_plot(args.csv, series, per_core, args.plot)
    # GBK 控制台兜底：统一用 UTF-8 字节写出
    sys.stdout.buffer.write(report.encode("utf-8") + b"\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
