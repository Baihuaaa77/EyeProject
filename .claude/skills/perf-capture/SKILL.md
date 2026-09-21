---
name: perf-capture
description: 采集并分析 EyeProject 应用在真机上的 CPU/内存性能数据时使用（模型推理耗时、页面卡顿、后台监测负载等场景）。通过 adb 完成设备检测→应用检测→脚本推送→采样→拉回→分析全流程；无设备或应用未运行时明确告知用户并停止。
argument-hint: [采样秒数，默认 60]
---

# 性能采集与分析 runbook

设备端采样脚本（`scripts/perf_sampler.sh`）跑在 **adb shell 域**，权限比应用进程（untrusted_app）宽，
`/proc/stat`、每核计数大概率可读。脚本**只采原始计数**，所有差分与百分比由
`scripts/analyze_perf.py` 在 PC 端完成。只采 CPU 与内存（不采 GPU）；每行仅 ~6 次 fork，
默认间隔 0.5s 的实际采样率 ≈ 2Hz。

所有命令在仓库根目录执行。

## 流程（逐步执行；任一步的停止条件命中就停下告知用户，不要跳步）

### 1. 设备检测

```bash
adb devices
```

- 输出无 `device` 状态行（空列表 / `unauthorized` / `offline`）→ **停止**，告知用户：
  连接手机并授权 USB 调试（`unauthorized` 需要在手机上点允许）。

### 2. 应用运行检测

```bash
adb shell pidof com.zkyjss.eyeproject
```

- 输出为空 → **停止**，告知用户先启动应用并进入要观测的页面，附建议命令：

```bash
adb shell am start -n com.zkyjss.eyeproject/com.zkyjss.eyeproject.MainActivity
```

- 有输出（PID）→ 记下它，继续。

### 3. 推送采样脚本

```bash
adb push .claude/skills/perf-capture/scripts/perf_sampler.sh /data/local/tmp/perf_sampler.sh
```

### 4. 采集

```bash
adb shell sh /data/local/tmp/perf_sampler.sh com.zkyjss.eyeproject 0.5 <ROWS> /data/local/tmp/perf_last.csv
```

- `<ROWS>`：采样条数 = 目标秒数 × 2（默认间隔 0.5s）；用户参数为秒数时换算后传入，缺省 120（≈1 分钟）。
- **阻塞式运行**，期间提示用户正常操作手机上的目标页面。
- 联测模型推理：**先在手机上开推理，再起采样**，才能采到推理期间的负载。
- `<ROWS>` 传 0 表示一直采到 Ctrl-C（长观测用，通知用户按 Ctrl-C 结束）。
- stderr 会打印一行探测摘要（核数 / clk_tck），值得看一眼。

### 5. 拉回并清理

```bash
adb pull /data/local/tmp/perf_last.csv "<本地路径>"
adb shell rm /data/local/tmp/perf_last.csv
```

- 本地路径：`$CLAUDE_JOB_DIR/tmp/perf/perf_<yyyyMMdd-HHmmss>.csv`；该变量不可用时用系统临时目录。

### 6. 分析

```bash
python .claude/skills/perf-capture/scripts/analyze_perf.py "<csv路径>" [--plot out.png]
```

- 报告为「字段 值」纯文本（ASCII，GBK 控制台安全）；`--plot` 装有 matplotlib 才出
  PNG（CPU / 内存双面板），否则提示跳过。

### 7. 解读要点（写进给用户的总结）

- `app_cpu_one_core_pct`：本应用 CPU 折算单核占比（>100% = 多核并行）
- `app_cpu_of_total_pct`：本应用占全机算力比
- `total_cpu_util_pct` / `cpuN_busy_pct`：整机与每核利用率
- `rss_mb` / `hwm_mb` / `mem_avail_mb`：应用常驻内存 / 生命周期峰值 / 系统可用内存
- `restarts > 0`：采样期间应用重启过，重启前后的差分已自动断开
- `cutime/cstime` 口径可能带来单点尖刺（子进程回收一次性计入），看趋势别看单点
- 实际间隔 ≈ 0.55~0.7s（0.5s sleep + 每行 ~6 次 fork 的读取开销）；分析端按 ts 实差
  计算百分比不受影响，换算「采样时长」看报告里的 `duration_s`
- 每核列部分 ROM 读不到或核离线，空列属正常

## 字段语义（CSV 列）

| 列 | 含义 | 为空的原因 |
|---|---|---|
| ts / pid / cores / clk_tck | 时间戳（毫秒）/ 目标 PID / 核数 / jiffies 频率 | pid 空 = 应用不在运行 |
| threads / rss_kb / hwm_kb | 应用线程数 / 常驻内存 / 峰值内存（kB） | 同上 |
| proc / total / idle | 进程 jiffies、全 CPU jiffies 和、idle(+iowait) | proc 空 = 应用不在运行 |
| cpuN_total / cpuN_idle | 每核 jiffies 与 idle | 核离线 |
| mem_total_kb / mem_avail_kb | 系统内存总量 / 可用量 | ROM 限制（罕见） |

## 手动单行命令（脱离 skill 使用）

```bash
adb push .claude/skills/perf-capture/scripts/perf_sampler.sh /data/local/tmp/ && \
adb shell sh /data/local/tmp/perf_sampler.sh com.zkyjss.eyeproject 0.5 120 /data/local/tmp/perf_last.csv && \
adb pull /data/local/tmp/perf_last.csv perf.csv && adb shell rm /data/local/tmp/perf_last.csv && \
python .claude/skills/perf-capture/scripts/analyze_perf.py perf.csv
```
