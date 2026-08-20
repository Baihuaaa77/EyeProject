#!/system/bin/sh

PKG="com.zkyjss.eyeproject"
INTERVAL=1
SAMPLES=60

PID=$(pidof "$PKG" | awk '{print $1}')
if [ -z "$PID" ]; then
  echo "ERROR: process $PKG not found"
  exit 1
fi

CORES=$(grep -c '^processor' /proc/cpuinfo)

read_total_cpu() {
  awk '/^cpu / {s=0; for(i=2;i<=NF;i++) s+=$i; print s}' /proc/stat
}

read_proc_cpu() {
  awk '{
    sub(/^[^)]*\) /, "")
    split($0, a, " ")
    print a[12] + a[13] + a[14] + a[15]
  }' /proc/$PID/stat 2>/dev/null || echo "0"
}

prev_total=$(read_total_cpu)
prev_proc=$(read_proc_cpu)

echo "time,pid,cores,cpu_one_core_pct,cpu_total_pct,proc_delta,total_delta"

i=0
while [ $i -lt $SAMPLES ]; do
  sleep $INTERVAL

  now_total=$(read_total_cpu)
  now_proc=$(read_proc_cpu)

  total_delta=$((now_total - prev_total))
  proc_delta=$((now_proc - prev_proc))

  if [ "$total_delta" -gt 0 ]; then
    cpu_total=$(awk "BEGIN { printf \"%.2f\", $proc_delta * 100 / $total_delta }")
  else
    cpu_total="0.00"
  fi
  cpu_one_core=$(awk "BEGIN { printf \"%.2f\", $cpu_total * $CORES }")

  echo "$(date +%s),$PID,$CORES,$cpu_one_core,$cpu_total,$proc_delta,$total_delta"

  prev_total=$now_total
  prev_proc=$now_proc
  i=$((i + 1))
done