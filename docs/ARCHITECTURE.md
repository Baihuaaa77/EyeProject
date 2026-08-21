# EyeProject 架构说明（参照 Now in Android）

本文档说明 EyeProject 如何参照 Google 官方 [Now in Android (NIA)](https://github.com/android/nowinandroid)
进行模块化重构，以及两者结构的对应关系。

## 一、NIA 的架构理念

NIA 是 Google 官方的"模块化最佳实践"参考实现，核心思想：

1. **分层清晰**：`app` / `core` / `feature` 三层（外加 `build-logic`）。
2. **约定插件（Convention Plugins）**：把每个模块重复的构建配置抽到 `build-logic`，
   模块的 `build.gradle.kts` 只声明"我是什么类型" + 依赖，几十行变几行。
3. **单向依赖**：`feature → core`，`core` 之间也按依赖方向排列，禁止反向与循环依赖。
4. **职责单一**：`core` 不依赖任何 `feature`；`feature` 一个功能一个模块；`app` 只做入口与装配。

## 二、模块结构对照

| NIA 模块 | EyeProject 模块 | 说明 |
|---|---|---|
| `app/` | `app/` | 入口、DI 装配、系统组件（色温服务/提醒接收器） |
| `build-logic/convention/` | `build-logic/convention/` | 约定插件（本项目 5 个） |
| `core/common/` | `core/common/` | 通用工具（时间/光照格式化） |
| `core/designsystem/` | `core/designsystem/` | 设计令牌：颜色/排版/形状/主题 |
| `core/ui/` | `core/ui/` | 可复用自绘组件（EyeButton/EyeMetricCard/…） |
| `core/model/` | `core/model/` | 纯 Kotlin 领域模型（无 Android 依赖） |
| `core/domain/` | `core/domain/` | 领域逻辑 + 用例 + 控制接口 |
| `core/data/` | `core/data/` | 数据仓储（使用时长/亮度/光照/偏好） |
| `core/database/` | —（无） | 眼健康 App 无本地数据库需求 |
| `core/datastore/` | —（暂用 SharedPreferences） | 未来可迁移到 DataStore |
| `core/network/` | —（无） | 无网络请求 |
| `core/notifications/` | `core/notifications/` | Notifier + SystemNotifier（提醒/色温通知） |
| `core/testing/` | `core/testing/` | 测试工具（MainDispatcherRule） |
| `feature/foryou` 等 | `feature/camera/`、`feature/home/`、`feature/testmodel/` | 摄像头视觉管线 + 主仪表盘 + 模型测试（LiteRT C++） |
| `sync/work/` | —（无） | 无后台同步任务 |

> 结论：EyeProject 是"眼健康监测"单功能应用，因此 `core` 层省略了 database/network/datastore/sync，
> `feature` 层只有 camera 与 home 两个。这是 NIA 结构按需裁剪的结果。

## 三、约定插件（Convention Plugins）

`build-logic/convention/` 定义了 5 个约定插件，模块按需组合：

| 插件 id | 作用 |
|---|---|
| `eyeproject.android.library` | 应用 `com.android.library` + compileSdk/minSdk/Java17 |
| `eyeproject.android.application` | 应用 `com.android.application` + compileSdk/minSdk/targetSdk/Java17 |
| `eyeproject.android.library.compose` | 应用 Compose 编译器插件 + 开启 `buildFeatures.compose` |
| `eyeproject.android.application.compose` | 同上，面向 application 模块 |
| `eyeproject.android.hilt` | 应用 Hilt + KSP，注入 hilt-android / hilt-compiler |

示例（`feature/home/build.gradle.kts` 全文）：

```kotlin
plugins {
    id("eyeproject.android.library")
    id("eyeproject.android.library.compose")
    id("eyeproject.android.hilt")
}

android { namespace = "com.zkyjss.eyeproject.feature.home" }

dependencies { /* 只声明模块专属依赖 */ }
```

对比改动前，每个模块约 30~60 行（重复 SDK/Java/Compose/Hilt 配置）现在只剩几行。

## 四、依赖方向（单向、无环）

```
app
 ├─ feature:home  ──► core:ui / core:common / core:model / core:domain / core:data / feature:camera
 ├─ feature:camera ──► core:model
 ├─ core:data      ──► core:model
 ├─ core:domain    ──► core:model
 └─ core:ui        ──► core:designsystem
```

规则：`feature` 可依赖任意 `core`；`core` 之间只向更底层依赖（model 是最底层）；`core` 绝不依赖 `feature`；
`app` 依赖所有 `feature` 与必要的 `core`。

## 五、与原结构（XinYu 简化版）的关键差异

1. **新增 `build-logic` 约定插件** —— NIA 的招牌，XinYu 没有。
2. **拆分 `core:designsystem`（令牌）与 `core:ui`（组件）** —— NIA 的标准切分，XinYu 也做了。
3. **新增 `core:common`** —— 通用工具沉淀，避免散落在 ViewModel/Screen。
4. 复用 EyeProject 既有的 AGP 9.3 内置 Kotlin + Hilt + KSP 技术栈（比 XinYu 的 AGP 8.3 更新）。

## 六、各模块职责明细

- **core:model**：`BoundingBox`、`Point2f`、`EyeDetection`、`FaceEyeDetection`、`FrameResult`、
  `DistanceEstimate/DistanceState`、`ColorTemperatureMode`、`AppUsageInfo`、`AnalysisResolution`。
- **core:domain**：`DistanceEstimator`（40cm 标定距离估算）、`ColorTemperatureController`/`ReminderController` 接口。
- **core:data**：`ScreenUsageRepository`、`AmbientLightMonitor`、`ScreenBrightnessReader`、
  `ColorTemperaturePreferences`、`AppSettings`、`UsageAccessHelper`。
- **core:designsystem**：`Color/Type/Shape/Theme`（CompositionLocal 自定义主题，无 Material 默认配色）。
- **core:ui**：`EyeText`（BasicText 原语）、`EyeButton`、`EyeSectionCard`、`EyeMetricCard`、
  `EyeStatusChip`、`EyeSegmentedRow`、`EyeTopBar`。
- **feature:camera**：`CameraController`（CameraX）、`FaceLandmarkerAnalyzer`（MediaPipe Face Landmarker）。
- **feature:home**：`HomeViewModel`（MVVM+UDF）、`HomeScreen`、`EyeOverlay`、`HomeUiState`。
- **feature:testmodel**：`FaceDetectorAnalyzer`（MediaPipe FaceDetector 检人脸框）、`GazeNative`（JNI 桥，对应 `src/main/cpp/jni_bridge.cpp`）、
  `GazeNativeModel`（双推理路线：C++ JNI `infer_use_cpp_lib` + Maven litert Android API `infer_use_android_api`）、
  `TestModelViewModel`、`TestModelScreen`；C++ 源码 + `litert_cc_sdk/` + CMake 在 `src/main/cpp/`。
- **app**：`MainActivity`、`EyeProtectApplication`、`ColorTemperatureOverlayService`、
  `EyeCareReminderScheduler/Receiver`、`BootCompletedReceiver`、`AppModule`（Hilt @Binds）。

## 七、构建验证

`.\gradlew.bat assembleDebug` → **BUILD SUCCESSFUL**（产物 `app/build/outputs/apk/debug/app-debug.apk`）。
