# core:model

源码已迁到 `shared/model/src/commonMain/kotlin`，本安卓模块通过 `api` 转发同一份 KMP JVM 类型，包名保持不变。新增公共模型应修改共享目录。

> 纯 Kotlin 领域模型层：全项目共享的数据结构定义，不依赖任何 Android API。

## 职责

- 定义视觉检测、监测采样、配置等跨模块流转的数据结构
- 保持零 Android 依赖（如 `Point2f` 替代 `android.graphics.PointF`），保证可测试性与层间解耦

## 模型清单

### 几何基础

| 类型 | 说明 |
|---|---|
| `Point2f` | 二维点（像素坐标） |
| `BoundingBox` | 像素包围盒，带 `width/height/centerX/centerY` 派生属性 |

### 视觉检测（两条管线）

| 类型 | 管线 | 说明 |
|---|---|---|
| `FaceLandmarkFrame` | core:camera 关键点管线 | FaceLandmarker 逐帧输出：人脸列表 + 源尺寸 + 时间戳 + 检测延迟 + FPS |
| `FaceMetrics` | 同上 | 单张人脸：人脸框 + 双眼 `EyeLandmarks` |
| `EyeLandmarks` | 同上 | 单眼：EAR 6 点、虹膜 5 点、虹膜像素直径（测距用） |
| `FrameResult` | feature:testmodel 检测管线 | FaceDetector 多人脸逐帧输出 |
| `FaceEyeDetection` | 同上 | 单人脸 + 双眼 `EyeDetection`（含旋转角度） |
| `EyeDetection` | 同上 | 单眼：眼周点、眼中心、虹膜中心（注视估计用） |

### 监测业务

| 类型 | 说明 |
|---|---|
| `MonitoringSample` | 30 秒一条的采样记录（时间戳/lux/亮度/距离/时长/眨眼数），持久化单元 |
| `RealTimeMetrics` | 实时指标（比采样更即时，仅界面展示不落盘） |
| `RiskLevel` | 疲劳风险等级 LOW/MEDIUM/HIGH（阈值 0.30/0.70，与算法一致） |

### 配置

| 类型 | 说明 |
|---|---|
| `ColorTemperatureMode` | 色温模式 OFF/COMFORT/WARM（key、label、overlay 颜色与不透明度） |

## 依赖关系

- **依赖**：无（纯 Kotlin）
- **被依赖**：`core:camera`、`core:data`、`core:domain`、`core:notifications`、全部 feature 模块、`app`

## 注意事项

- 两条视觉管线的帧模型**不通用**：camera 管线产出 `FaceLandmarkFrame`，testmodel 管线产出 `FrameResult`，合并前不要互相混用
- 新增模型字段时同步检查 JSON 持久化（`core:data` 的 `JsonMonitoringRepository`）的序列化键名
