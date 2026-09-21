# results/ — 眨眼检测实验产物索引

两条眨眼检测路线的实验数据、报告与工具（2026-09-09 ~ 09-11）。
**入口报告：[standalone-blink-experiment-report.md](standalone-blink-experiment-report.md)**（最终结论看它的 §1）。

## 目录结构

| 路径 | 内容 |
|---|---|
| `standalone-blink-experiment-report.md` | **主报告**：BlazeFace+OCEC 全替换路线实验（数据流图/精度/性能/模型介绍/否决分析） |
| `ocec-standalone-pipeline-research.md` | 选型与验证过程记录（模型下载/解码验证/几何标定/SegFace 假设检验） |
| `on-device-landmark-models-3w-scan.md` | 调研 ①：端侧 landmark 模型 3W 扫描（经典方案） |
| `on-device-face-eye-blink-models-lit-review-2026.md` | 调研 ②：2023-2026 前沿 lit-review（增量） |
| `eval_scripts/` | 全部评估脚本（数据集三管线对比 / GT 框评估 / 设备眼片复核 / 几何扫描 / SegFace 实验） |
| `models/` + `models/README.md` | PC 端模型副本（blazeface/ocec/deimv2 地址、yunet）+ 4 个数据集分片 |
| `dataset_eval/` | 数据集对比结果（JSON + SegFace 评估输出） |
| `data/` | 原始采集数据：`blink60s/`（MediaPipe 路线 60s）、`standalone60s/`（本路线 60s ×4，含几何迭代过程）、`perf/`（两次 perf-capture）、`crops/session1/`（眼片采样 50 张） |
| `charts/` | 分析产物图：三面板图 ×2、perf 双面板 ×2、眼片蒙太奇 ×3 |
| `figures/` | 验证对照图：人体测量学眼框、DEIMv2 vs BlazeFace 检测、DEIMv2 眼片特写、SegFace 分割 |

## 快速复跑

```bash
# standalone 60s 数据分析（三面板图）
python feature/testmodel/tools/analyze_standalone_blink_test.py results/data/standalone60s/standalone_blink_20260911_100321.csv --plot out.png
# 数据集三管线对比（DEIMv2 慢，约 4 分钟）
python results/eval_scripts/eval_pipelines.py
# GT 眼框评估 OCEC 上限
python results/eval_scripts/eval_dataset_gt_boxes.py results/models results/models
```

## 备注

- `data/crops/session2`（紧框几何眨眼段采样 80 张）已随目录整理丢失；设备端 `files/crops/` 如仍有原件可随时 `adb pull` 重取——不过微调数据将按新协议重新采集，此项不影响后续工作。
- 两条路线的结论与后续计划（微调数据采集协议）见主报告 §1 与 §6。
