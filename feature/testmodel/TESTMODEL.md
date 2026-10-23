# feature:testmodel

> 模型测试页（实验性质）：MediaPipe FaceDetector + LiteRT 双路线（C++ SDK / Android API）跑 L2CS-Net 注视估计；另有 FaceLandmarker 眨眼检测独立测试页。
> 原「全替换眨眼检测」（BlazeFace + OCEC）页面已随 EAR / OCEC 对比结束删除（2026-10-23），眨眼统一用 EAR；研究记录见 `results/`。

## 职责

- 验证「人脸检测 → 裁剪人脸 → 注视估计（yaw/pitch）」全链路
- 验证「FaceLandmarker 478 点关键点 → EAR 眨眼检测」链路（独立眨眼测试页）
- 对比两条 LiteRT 推理路线的可用性与性能：
  - **C++ 路线**：`GazeNative.run()` → `libgaze_native.so`（预处理 + 推理 + 解码全在 C++）
  - **Android API 路线**：`CompiledModel`（`com.google.ai.edge.litert`，GPU 加速）在 Kotlin 侧推理

## 结构

主页面 `TestModelScreen` 为简单导航页（`EyeSelectCard` 选择测试入口），各测试按子包 `gaze/`、`blink/` 归类。

### gaze/ —— 视线估计测试

#### 模型层

| 类型 | 说明 |
|---|---|
| `TestModel` | 模型抽象接口：`info` / `infer_use_cpp_lib(bitmap)` / `infer_use_android_api(bitmap)` / `close()` |
| `GazeNativeModel` | L2CS-Net 实现。输入 1×3×448×448，输出 [yaw, pitch]（度）。C++ 路线：Bitmap→RGB 字节→JNI；Android 路线：模型只 create 一次、复用 input/output buffers，避免每帧分配触发 GC |
| `GazeNative` | JNI 桥（`libgaze_native.so`）：`run(pixels, w, h, modelPath, shadersDir): FloatArray[2]` |

#### 检测与 UI

| 类型 | 说明 |
|---|---|
| `FaceDetectorAnalyzer` | MediaPipe FaceDetector（仅人脸框，比 FaceLandmarker 轻量），每 2 帧取 1 帧，输出 `FrameResult` |
| `GazeViewModel` | 绑定 CameraX 预览 + 分析；检测到人脸后裁剪送模型；`changeInfer(useAndroidApi)` 运行时切换推理路线 |
| `GazeUiState` | 模型信息/错误、yaw/pitch、人脸框、FPS、推理耗时、当前路线 |
| `GazeOverlay` | 人脸框 + 注视方向自绘覆盖层 |
| `GazeScreen` | 视线估计测试页 UI（含 TopBackBar 返回） |
| `navigation/GazeNavigation` | `GazeRoute` + `navigateToGaze()` + `NavGraphBuilder.gazeScreen(onBack)` |

### blink/ —— 眨眼检测测试

| 类型 | 说明 |
|---|---|
| `BlinkLandmarkerAnalyzer` | 眨眼页专用 FaceLandmarker（478 点，GPU，LIVE_STREAM）分析器，输出人脸框 + 双眼 EAR 6 点/虹膜 5 点（`BlinkFrameResult`/`BlinkEyeLandmarks`） |
| `BlinkDetector` | EAR 眨眼检测（阈值 0.23、连续 3 帧闭眼、回升记一次），与 core:domain 同名算法一致的独立实现 |
| `BlinkTestViewModel` / `BlinkTestUiState` | 绑定预览 + 分析，每帧现算 L/R/avg EAR 并累计眨眼次数；`resetBlinkCount()` 清零 |
| `BlinkOverlay` | 人脸框 + 双眼 EAR 轮廓 + 虹膜圆自绘覆盖层（闭眼变 warning 色） |
| `BlinkTestScreen` | 眨眼测试页 UI（数据面板：L/R/avg EAR、睁/闭眼、眨眼数、fps、耗时、录制进度/保存文件名；重置计数 + 60秒检测按钮） |
| `navigation/BlinkTestNavigation` | `BlinkTestRoute` + `navigateToBlinkTest()` + `NavGraphBuilder.blinkTestScreen(onBack)` |

### 60s 检测（blink/）

`BlinkTestViewModel.startRecording()` / `stopRecording()`：录制期间逐帧记录
`t_s,ear_left,ear_right,ear_avg,latency_ms,fps`（无脸帧 ear 留空），60s 到点自动结束
（可提前点「停止并保存」），CSV 写入应用外部私有目录（免存储权限）：
`/sdcard/Android/data/com.zkyjss.eyeproject/files/blink_test_<时间戳>.csv`。

拉取与分析：

```bash
adb pull /sdcard/Android/data/com.zkyjss.eyeproject/files/blink_test_xxx.csv .
python feature/testmodel/tools/analyze_blink_test.py blink_test_xxx.csv --plot blink.png
```

分析输出：rows/时长/face_frames、ear_avg 与双眼 mean/p95/max/min、闭眼帧占比（EAR<0.23）、
latency 与 fps 统计；`--plot` 生成 EAR（含阈值线）/耗时/帧率三面板图。

### 根包（主页导航）

| 类型 | 说明 |
|---|---|
| `TestModelScreen` | 主导航页：`EyeSelectCard` 选择「视线估计 / 眨眼检测」入口 |
| `navigation/TestModelNavigation` | `TestModelRoute`（主页）+ `navigateToTestModel()` + `NavGraphBuilder.testModelScreen(onBack, onGazeClick, onBlinkTestClick)` |

### C++（cpp/）

`gaze_engine.{h,cpp}`（L2CS-Net 推理与 bins→角度解码）、`image_processor` / `image_utils`（预处理：resize 448 + ImageNet mean/std + CHW）、`jni_bridge.cpp`；`litert_cc_sdk/`、`third_party/` 为内嵌 SDK（CMake FetchContent 拉 flatbuffers 等）。

`jni_bridge.cpp` 导出符号与 Kotlin 包名绑定（`Java_com_zkyjss_eyeproject_feature_testmodel_gaze_GazeNative_run`）——移动 `GazeNative` 包路径时必须同步修改，否则运行时 `UnsatisfiedLinkError`。

## 依赖关系

- **依赖**：`core:model`、`core:designsystem`、`core:ui`；CameraX、MediaPipe tasks-vision、`com.google.ai.edge.litert:litert:2.1.1`
- **被依赖**：`app`（NavHost 挂 testModelScreen）
- 资产：`l2cs_net.tflite`、`blaze_face_short_range.tflite`、`face_landmarker.task`、`shaders/`；`jniLibs/arm64-v8a` 内嵌 LiteRT `.so`

## 注意事项

- 与 `core:camera` 各有一套相机分析器（本模块用 FaceDetector 而非 FaceLandmarker），属实验页独立实现，合并前先明确收敛策略
- **模块约定（2026-09-09 用户明确）**：不依赖 core 算法/相机模块（`core:domain` / `core:camera`），即使重复实现也保持独立——眨眼测试页自带 `face_landmarker.task` 与独立 FaceLandmarker 实例，`BlinkDetector` 为本地副本
- `face_landmarker.task` 与 `core:camera` 的同名资产当前字节相同（assembleDebug 资产合并通过）；若日后只更新其中一份会冲突，届时重命名本模块资产或同步更新
- Android API 路线的 GPU 版曾有数据处理问题（git 历史），当前 `Accelerator.GPU`；`GazeNativeModelAndroidApiTest`（app androidTest）覆盖该路线
- 本页为实验/演示用途，不属于生产监测链路；README 的技术文档（L2CS-Net、LiteRT）在 `docs/`
