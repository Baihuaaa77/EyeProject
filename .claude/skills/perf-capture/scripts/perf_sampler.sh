#!/system/bin/sh
# perf_sampler.sh — 设备端性能采样器（/perf-capture skill）
#
# 采集目标应用的 CPU（整机 + 每核原始计数）与内存（RSS/HWM/系统可用）、线程数。
# 设计原则：
#   1. 只采原始计数，差分/百分比由 analyze_perf.py 在 PC 端完成（脚本无状态、抗核热插拔）
#   2. 每行仅 ~6 次 fork（date/pidof/awk×3/sleep），默认 0.5s 间隔的实际采样率 ≈ 2Hz
#
# 用法:
#   sh perf_sampler.sh [PKG] [INTERVAL] [SAMPLES] [OUT]
#     PKG      目标应用包名（默认 com.zkyjss.eyeproject）
#     INTERVAL 采样间隔秒（默认 0.5，toybox sleep 支持小数）
#     SAMPLES  采样条数（默认 120 ≈ 1 分钟；0 = 一直采到 Ctrl-C）
#     OUT      可选：结果写到设备上该路径；缺省输出到 stdout（纯 CSV）
#
# stdout = 纯 CSV（一行一条样本），所有提示信息走 stderr。
# 跑在 adb shell 域，权限比应用进程（untrusted_app）宽：/proc/stat 等大多可读。

PKG=${1:-com.zkyjss.eyeproject}
INTERVAL=${2:-0.5}
SAMPLES=${3:-120}
OUT=${4:-}

CLK=$(getconf CLK_TCK 2>/dev/null)
case "$CLK" in ''|*[!0-9]*) CLK=100 ;; esac

CORES=$(grep -c '^processor' /proc/cpuinfo 2>/dev/null)
case "$CORES" in ''|*[!0-9]*) CORES=1 ;; esac

# 时间戳统一毫秒：date 支持 %3N 直接取 ms；不支持的 ROM 用 秒×1000 兜底（仍为 ms 口径）
DATE_MS='native'
case "$(date +%s%3N 2>/dev/null)" in
  ''|*[!0-9]*) DATE_MS='fallback' ;;
esac

# ---------- 读取函数（全部容错，失败留空；每个文件只 fork 一次 awk） ----------

# 一次 awk 读 /proc/<pid>/stat + /proc/<pid>/status：
# 输出 "threads,rss_kb,hwm_kb,proc_jiffies"（proc = utime+stime+cutime+cstime）
read_app() {
  awk '
    FNR == 1 { fno++ }
    fno == 1 {
      s = $0
      sub(/^.*\)/, "", s)
      split(s, a, " ")
      proc = a[12] + a[13] + a[14] + a[15]
    }
    fno == 2 {
      if ($1 == "Threads:") th = $2
      else if ($1 == "VmRSS:") rss = $2
      else if ($1 == "VmHWM:") hwm = $2
    }
    END { printf "%s,%s,%s,%s\n", th, rss, hwm, proc }
  ' "/proc/$1/stat" "/proc/$1/status" 2>/dev/null
}

# 一次 awk 读 /proc/stat：输出 "total,idle,cpu0_total,cpu0_idle,..."（idle 含 iowait）
read_cpu() {
  awk -v cores="$CORES" '
    /^cpu / {
      t = 0; for (j = 2; j <= NF; j++) t += $j
      T = t; I = $5 + $6
    }
    /^cpu[0-9]+/ {
      t = 0; for (j = 2; j <= NF; j++) t += $j
      TT[$1] = t; II[$1] = $5 + $6
    }
    END {
      printf "%s,%s", T, I
      for (i = 0; i < cores; i++) {
        k = "cpu" i
        printf ",%s,%s", (k in TT ? TT[k] : ""), (k in II ? II[k] : "")
      }
      printf "\n"
    }
  ' /proc/stat 2>/dev/null
}

# 一次 awk 读 /proc/meminfo：输出 "mem_total_kb,mem_avail_kb"
read_mem() {
  awk '
    $1 == "MemTotal:" { t = $2 }
    $1 == "MemAvailable:" { a = $2 }
    END { printf "%s,%s\n", t, a }
  ' /proc/meminfo 2>/dev/null
}

# ---------- 输出 ----------

emit() {
  if [ -n "$OUT" ]; then
    printf '%s\n' "$1" >> "$OUT" || echo "perf_sampler: write to $OUT failed" >&2
  else
    printf '%s\n' "$1"
  fi
}

HDR="ts,pid,cores,clk_tck,threads,rss_kb,hwm_kb,proc,total,idle"
i=0
while [ $i -lt $CORES ]; do
  HDR="$HDR,cpu${i}_total,cpu${i}_idle"
  i=$(( i + 1 ))
done
HDR="$HDR,mem_total_kb,mem_avail_kb"

if [ -n "$OUT" ]; then
  : > "$OUT" || { echo "perf_sampler: cannot create $OUT" >&2; exit 1; }
fi
emit "$HDR"

echo "perf_sampler: pkg=$PKG interval=${INTERVAL}s samples=$SAMPLES cores=$CORES clk_tck=$CLK out=${OUT:-stdout}" >&2

# ---------- 主循环 ----------

i=0
while :; do
  if [ "$DATE_MS" = native ]; then
    TS=$(date +%s%3N)
  else
    TS=$(( $(date +%s) * 1000 ))
  fi

  # 每轮重新取 PID：应用中途重启后自动附着新进程，断点体现在 pid 列
  set -- $(pidof "$PKG" 2>/dev/null)
  PID=
  [ $# -gt 0 ] && PID=$1

  if [ -n "$PID" ]; then
    APP=$(read_app "$PID")
  else
    APP=",,,"
  fi

  CPU=$(read_cpu)
  MEM=$(read_mem)

  emit "$TS,$PID,$CORES,$CLK,$APP,$CPU,$MEM"

  i=$(( i + 1 ))
  if [ "$SAMPLES" -gt 0 ] && [ "$i" -ge "$SAMPLES" ]; then
    break
  fi
  sleep "$INTERVAL" 2>/dev/null || sleep 1
done

echo "perf_sampler: done, $i rows${OUT:+ -> $OUT}" >&2
