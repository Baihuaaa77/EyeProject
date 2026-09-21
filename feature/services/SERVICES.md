# feature:services

> 眼病症自检服务集合：干眼检测（完整流程）+ 老花 / 斜视检测（占位）。

## 职责

- 服务页列出病症检测入口（`EyeSelectCard`）
- 干眼检测：Tumbling E 视标反应测试 + 相机同步监测（眨眼、睁眼时长、测距门控），产出正确率/平均反应时等指标
- 提供通用的「摄像头预览 + 眼部关键点标注」检测界面组件

## 结构

### 页面与导航

| 类型 | 说明 |
|---|---|
| `ServicesScreen` | 服务首页：三个 `EyeSelectCard` 入口 |
| `DryEyeScreen` | 干眼检测页（三阶段 Setup/Testing/Result，vico 结果曲线，material3 Slider 调对比度） |
| `PresbyopiaScreen` / `StrabismusScreen` | 老花 / 斜视检测页（**占位**，待实现） |
| `navigation/ServicesNavigation` | 4 个路由：`ServicesRoute` / `DryEyeRoute` / `PresbyopiaRoute` / `StrabismusRoute` 及对应 navigate / NavGraphBuilder 扩展 |

### 干眼测试（DryEyeTestViewModel）

- 流程：Setup（设置时长/对比度）→ Testing（逐次呈现 `TumblingE` 视标，用户按方向键作答）→ Result（正确率、平均反应时、持续睁眼时长）
- 相机侧同步采集：眨眼次数、持续睁眼时长（`BlinkDetector`）、测距门控（`DepthFromIris`，**< 30cm 判无效**，`MIN_TEST_DISTANCE_CM`）
- 结果模型：`DryEyeTestPhase` / `DryEyeTrialResult`（方向、正确性、反应时、睁眼时长、视标档位）

### 通用检测组件（detection/）

| 类型 | 说明 |
|---|---|
| `DetectionViewModel` | 绑定 CameraX **Preview + ImageAnalysis**（有预览，区别于监测引擎的无预览），复用 `core:camera` 的 `FaceLandmarkerAnalyzer`，输出 `DetectionUiState`（人脸/关键点/距离/眨眼） |
| `DetectionContent` | 通用检测界面：预览 + 关键点标记 + 距离/眨眼指标行 |
| `EyeOverlay` | 预览上自绘眼部关键点（红点 + 虹膜中心） |

### 视标

| 类型 | 说明 |
|---|---|
| `VisionOptotype.kt` | `TumblingE(direction, size, color)` 自绘 E 字视标（0=右 1=下 2=左 3=上）；`OPTOTYPE_SIZES_DP` 三档（150/105/65dp） |

## 依赖关系

- **依赖**：`core:camera`（`FaceLandmarkerAnalyzer`、`CameraIntrinsicsProvider`）、`core:domain`（`BlinkDetector`、`DepthFromIris`）、`core:model`、`core:designsystem`、`core:ui`；vico、material3（Slider）
- **被依赖**：`app`（NavHost 挂 servicesScreen / dryEyeScreen / presbyopiaScreen / strabismusScreen）

## 注意事项

- 本模块与 `core:camera` 的集成方式是「自带预览绑定」：`FaceLandmarkerAnalyzer` 共享单例引擎（回调列表机制），但相机生命周期由本模块 ViewModel 管理
- 干眼测试的计时/相位状态在 ViewModel 内协程驱动，`onCleared` 时务必停相机
- 老花/斜视页实现后应复用 `DetectionContent` + 视标体系，不要另起炉灶
