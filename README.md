# EyeProject — 眼健康助手

基于 **Jetpack Compose + MediaPipe Face Landmarker** 的实时眼疲劳监测应用：
实时采集用眼环境/姿势/眨眼等数据，经 C++ 疲劳评分算法（EyeFatigueAlgorithm）综合评分，
以仪表盘 + 曲线图展示，后台前台服务持续运行，数据本地持久化（滚动保留 2 天）。

## 一、架构与模块

参照 [Now in Android (NIA)](https://github.com/android/nowinandroid) 的分层多模块结构，
UI 全部自绘（不依赖 Material 默认组件、不依赖第三方图表库）。

```
app/                      入口 + Hilt DI + 系统组件（色温服务 / 提醒接收器 / 监测前台服务 MonitoringService）
core/
  common/                 通用工具（时间/光照格式化）
  designsystem/           设计令牌（颜色/排版/形状/主题）
  ui/                     可复用自绘组件（EyeButton/EyeMetricCard/EyeText/EyeSwitch/…）
  model/                  纯 Kotlin 领域模型（RiskFactors/FatigueRisk/MonitoringSample/HeadPose/…）
  domain/                 BlinkDetector(EAR 眨眼) + DepthFromIris(测距) + 色温/提醒控制接口
  data/                   环境光照 / 屏幕亮度 / 监测数据(JSON) 仓储
  notifications/          通知抽象（Notifier + SystemNotifier + NotificationChannels）
  testing/                测试工具（MainDispatcherRule）
feature/
  camera/                 CameraX + MediaPipe FaceLandmarker（头姿 / 虹膜 / EAR 关键点）+ 相机内参
  fatigue/                眼疲劳监测：C++ 评分算法(JNI) + FatigueMonitor 引擎 + 仪表盘 UI（自绘图表）
  testmodel/              模型测试页（FaceDetector 检测人脸 + LiteRT 跑 L2CS-Net 注视估计）
```

## 二、数据采集与存储

### 1. 输入：采样时间序列 + 用户画像

算法输入改为「时间序列」：把保存的 `MonitoringSample` 序列（每 30 秒一条）整体传入，外加一份静态用户画像 `UserProfile`（7 字段，当前用默认值）：

- **MonitoringSample[]**（时间序列，各字段等长数组）：
  - `timestampMillis`（毫秒）、`ambientLux`（lux）、`screenBrightness`（0~100）、
    `distanceCm`（cm，0 = 未检测到人脸）、`durationMin`（分钟，算法可从时间戳重新推导）、
    `blinkCount`（本 30 秒区间眨眼次数）
- **UserProfile**（profile double[7]）：
  - `age`、`hasMyopia`、`hasDryEye`、`parentalMyopia`、`readingPosture`、
    `discomfortScore`、`contentContrast`

### 2. 算法输出（FatigueRisk）

`computeRiskSeries` 返回 double[27]、`computeRiskSeriesText` 返回 String[8]，映射为 `feature/fatigue/FatigueRisk.kt`：

- `totalRisk`（综合风险 0~1）、`level`（LOW/MEDIUM/HIGH，阈值 0.3/0.7）、`advice`（总体建议）
- `factors`：6 因子（light/distance/time/blink/personal/feedback）各含 value/label/advice
- `metrics`：派生指标（连续/累计用眼时长、眨眼频率、平均距离/光照/亮度、样本数、片段数）
- `features`：10 维特征向量（离线训练用）
- 组合层可切换：规则版（默认）/ 训练版逻辑回归（`setCombinerLogistic`）

### 3. 保存的记录结构（MonitoringSample）

每次采样（间隔 **30 秒**）保存一条 `core/model/MonitoringSample.kt`：

| 字段 | 类型 | 含义 |
|---|---|---|
| timestampMillis | Long | 采样时间戳 |
| ambientLux | Float | 环境光照（lux） |
| screenBrightness | Int | 屏幕亮度（0~100） |
| distanceCm | Float | 用眼距离（cm） |
| durationMin | Float | 连续用眼时长（分钟） |
| blinkCount | Int | 眨眼次数（本 30 秒区间） |

> 综合风险（totalRisk / riskLevel / advice）改为实时计算、仅界面展示，不落盘。

### 4. 持久化格式（JSON）

`core/data/MonitoringRepository.kt`（`JsonMonitoringRepository`）保存到
`filesDir/monitoring_samples.json`：

```json
{
  "version": 1,
  "samples": [
    {
      "ts": 1760000000000,
      "lux": 320.5,
      "brightness": 50,
      "distance": 38.2,
      "duration": 12.5,
      "blink": 7
    }
  ]
}
```

- **采样间隔**：30 秒一条，**每采一条立即写入 JSON**。
- **保留策略**：滚动保留最近 **2 天**，更早的自动清理。
- **演示数据**：首次运行（无历史数据）会自动捏造最近 24 小时数据（每 5 分钟一条，共 288 条），便于直接查看图表效果。

## 三、技术栈

- JDK 17、AGP 9.3.0、Kotlin 2.2.10、Gradle 9.5、compileSdk 37 / minSdk 26 / targetSdk 37
- Compose BOM 2026.02.01（自绘组件，无 material3、无第三方图表库）
- Hilt 2.59.2 + KSP 2.2.10-2.0.2
- CameraX 1.4.1、MediaPipe tasks-vision 0.10.14
- C++ 疲劳评分算法：`feature/fatigue` 内 `model.cpp` + `eye_health_jni.cpp` → `libeye_health_jni.so`
- LiteRT（`feature/testmodel` 注视估计）：LiteRT C++ SDK（JNI）+ Maven `com.google.ai.edge.litert:litert:2.1.1`（`libLiteRt.so` 来自 AAR）
- NDK 27.3.13750724（r27d）

## 四、构建

```powershell
.\gradlew.bat assembleDebug
```

- 模型文件随项目：`feature/camera/src/main/assets/face_landmarker.task`、`feature/testmodel/src/main/assets/l2cs_net.tflite`、`blaze_face_short_range.tflite`、`shaders/`。
- 首次构建 CMake `FetchContent` 会联网拉 abseil/flatbuffers/OpenCL。
- 本机需 JDK 17 + NDK 27.3.13750724（r27d）+ CMake 3.22.1。

## 五、说明

- 距离测距已从「40cm 标定比例法」改为 **Depth-from-Iris**（无需标定）；已移除分辨率切换与使用情况访问（`PACKAGE_USAGE_STATS`）。
- 首页摄像头预览已移除，相机在后台由 `MonitoringService`（`foregroundServiceType="camera"`）持有，保证应用退到后台仍实时监测。
- 权限流程：首次进入按标准流程请求相机权限，之后仅在缺失权限时再次请求。
