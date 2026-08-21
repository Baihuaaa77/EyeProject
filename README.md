# EyeProject — 眼健康助手（Compose + MediaPipe 重构版）

将 `EyeProtect_handoff_2026-07-24`（Kotlin View + CameraX + ML Kit）重构为
**Jetpack Compose + MediaPipe Face Landmarker** 版本，采用官方多模块分层架构，
UI 完全自定义（不使用 Material 默认组件）。

## 一、架构与模块

完全参照 [Now in Android (NIA)](https://github.com/android/nowinandroid)：`app` / `core` / `feature`
三层 + `build-logic` 约定插件。详见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

```
build-logic/convention/   约定插件（android.library/application/compose/hilt）
app/                      入口 + Hilt DI + 系统组件(色温服务/提醒接收器)
core/
  common/                 通用工具（时间/光照格式化）
  designsystem/           设计令牌（颜色/排版/形状/主题）
  ui/                     可复用自绘组件（EyeButton/EyeMetricCard/…）
  model/                  纯 Kotlin 领域模型
  domain/                 距离估算纯逻辑 + 色温/提醒控制接口
  data/                   使用时长/亮度/光线/偏好 仓储
  notifications/          通知抽象（Notifier + SystemNotifier）
  testing/                测试工具（MainDispatcherRule）
feature/
  camera/                 CameraX + MediaPipe FaceLandmarker 分析管线
  home/                   Compose 仪表盘 UI + HomeViewModel（MVVM + UDF）
  testmodel/              模型测试页（FaceDetector 检测人脸 + LiteRT 跑 L2CS-Net 注视估计）
```

## 二、技术栈

- JDK 17、AGP 9.3.0、Kotlin 2.2.10、Gradle 9.5、compileSdk 37 / minSdk 26 / targetSdk 37
- Compose BOM 2026.02.01（自绘组件，已移除 material3）
- Hilt 2.59.2 + KSP 2.2.10-2.0.2（依赖注入）
- CameraX 1.4.1、MediaPipe tasks-vision 0.10.14（camera FaceLandmarker + testmodel FaceDetector）
- LiteRT 双路线（testmodel 注视估计）：LiteRT C++ SDK（JNI，`libgaze_native.so`）+ Maven `com.google.ai.edge.litert:litert:2.1.1`（Android API）
- NDK 27.3.13750724（r27d）

## 三、构建前准备

1. **模型文件**（均已随项目，无需下载）：
   - `feature/camera/src/main/assets/face_landmarker.task`（3.7MB，MediaPipe 人脸关键点）
   - `feature/testmodel/src/main/assets/l2cs_net.tflite`（45.7MB，L2CS-Net 注视估计）
   - `feature/testmodel/src/main/jniLibs/arm64-v8a/libLiteRt.so`（LiteRT C++ 运行时）

2. **JDK 17 + NDK 27**：确认 `JAVA_HOME` 指向 JDK 17；装 NDK **27.3.13750724**（r27d）与 CMake 3.22.1。

3. 构建：

   ```powershell
   .\gradlew.bat assembleDebug
   ```

   > 首次构建会 CMake `FetchContent` 联网拉 abseil/flatbuffers/OpenCL。

## 四、与原项目的功能对齐

| 能力 | 旧实现 | 新实现 |
|---|---|---|
| 前置摄像头 | CameraX | CameraX（feature:camera） |
| 眼部检测 | ML Kit Face Detection | **MediaPipe Face Landmarker**（478 点 + 虹膜） |
| 距离估算 | 40cm 标定比例法 | 保留算法，改用虹膜中心更精准 |
| 过近提醒 | <30cm 警告 | 保留（Compose 状态驱动） |
| 屏幕时长 / 亮度 / 光照 | 主 Activity 内联 | core:data 仓储 + Flow |
| 全局色温 | Overlay 前台服务 | 保留（app 层） |
| 定时提醒 | AlarmManager | 保留（app 层） |

## 五、已知风险（首次构建时可能需要微调）

1. **Hilt × AGP 9（内置 Kotlin）**：已通过 `android.disallowKotlinSourceSets=false` 解决
   （放行 KSP/Hilt 的 kotlin.sourceSets 集成，参考
   [dagger#5099](https://github.com/google/dagger/issues/5099)）。构建已验证通过。
2. **版本号**：`coreKtx/lifecycle/activityCompose` 等已按 compileSdk 37 上调，构建已验证通过。
3. **旋转/镜像**：MediaPipe 关键点坐标基于图像原生方向，预览 overlay 的旋转/镜像映射
   可能需要按目标设备方向微调（`EyeOverlay` 已做水平镜像）。
4. **配置缓存**：`gradle.properties` 开启了 `configuration-cache`，构建已验证兼容。

## 六、构建验证结果（2026-08-19）

- `.\gradlew.bat assembleDebug` → **BUILD SUCCESSFUL**（387 个任务）。
- 产物：`app/build/outputs/apk/debug/app-debug.apk`（约 97 MB）。
- 原生库：`libgaze_native.so`（LiteRT C++ 注视管线）+ `libLiteRt.so` + `libmediapipe_tasks_vision_jni.so`（tasks）。
- 资产：`face_landmarker.task`（3.7MB）+ `l2cs_net.tflite`（45.7MB）。

### 本机（沙箱）构建专用的额外配置

以下配置是为适配受限执行环境（无 `~/.android` 写权限、出网需代理、Kotlin 守护进程受限）加入的，
在你本机正常开发环境下可以保留（不影响功能）或移除：

- `gradle.properties` 中的 `systemProp.*.proxyHost/proxyPort`（本地 Clash 代理 127.0.0.1:7897）
- `gradle.properties` 中的 `-Djava.io.tmpdir=E:/workspace/.gradle-tmp`（沙箱临时目录）
- `gradle.properties` 中的 `kotlin.compiler.execution.strategy=in-process`
- `app/build.gradle.kts` 中的 `signingConfigs.sandboxDebug`（工作区内 debug keystore）
- `gradle.properties` 中的 `android.disallowKotlinSourceSets=false` —— **此条为 Hilt×AGP9 必需，建议保留**

## 七、说明

- 已删除旧模板 `app/src/main/java/com/zkyjss/eyeproject/ui/theme/{Theme,Color,Type}.kt`
  中的 Material 主题实现（保留占位文件），改为 `core:designsystem` 的自定义 `EyeProjectTheme`。
- 构建缓存重定向：本执行环境将 `GRADLE_USER_HOME` 指向 `E:\workspace\.gradle-home`，
  你的本机环境仍使用默认 `D:\gradle\caches`。
