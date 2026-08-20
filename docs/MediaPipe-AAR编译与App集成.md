# MediaPipe AAR 编译与 App 集成实录 —— 从编 AAR 到 Duplicate class 冲突

> ⚠️ **已回退（2026-08-19）**：本文记录的 AAR 方案已放弃（camera 回 tasks、testmodel 改 LiteRT C++ SDK），
> 仅作历史参考。当前架构见 [`交接文档.md`](./交接文档.md)。

> 接续 [`MediaPipe自定义图编译实录.md`](./MediaPipe自定义图编译实录.md)（图编译成功）。
> 本文记录「把验证过的图打包成 Android AAR → 集成进 EyeProject App」的完整过程，
> 以及最后遇到的 **framework 层 Java 类重复冲突**及其本质。
> 结论：AAR 编译链路全通，App 集成撞上「两条 MediaPipe 路线共用 framework 层」的架构坑。

## 1. 目标

把 `face_gaze_cpu` 图（人脸检测 → 裁剪 → L2CS-Net → logits）打包成 Android AAR，
集成进 EyeProject 的 `feature:testmodel`，用 Kotlin 端 Graph API 调用，验证「图在 App 里能跑、能收 logits」。

## 2. 服务器端：装 Android SDK + NDK

在 AutoDL 服务器（已装好 Bazel 7.7 + gcc-13 + mihomo 代理）上：

```bash
cd /root/autodl-tmp/mediapipe
bash setup_android_sdk_and_ndk.sh \
  /root/autodl-tmp/Android/Sdk \
  /root/autodl-tmp/Android/Ndk r28b --accept-licenses
```

装好：SDK（platform android-36 + build-tools 35）+ NDK r28b（**clang-19**）。
脚本会在 WORKSPACE 追加 `android_sdk_repository` / `android_ndk_repository` 配置。

> 关键点：AAR 编译是 **Android 交叉编译（arm64）**，用 NDK 的 clang，不是 gcc。
> 所以上一份文档里 gcc-13 那套坑（NTTP bug / binutils 指令集）在 AAR 编译里**不会重现**。

## 3. 服务器端：写 AAR 打包 BUILD

`mediapipe_aar` 规则仍存在（`mediapipe/java/com/google/mediapipe/mediapipe_aar.bzl`），参数与旧文档一致：

```python
# mediapipe/examples/android/src/java/com/google/mediapipe/apps/facegaze/BUILD
load("//mediapipe/java/com/google/mediapipe:mediapipe_aar.bzl", "mediapipe_aar")

mediapipe_aar(
    name = "face_gaze_aar",
    calculators = ["//mediapipe/graphs/face_gaze:face_gaze_cpu"],
    assets = [
        "//mediapipe/graphs/face_gaze:l2cs_net.tflite",
        "//mediapipe/modules/face_detection:face_detection_short_range.tflite",
    ],
)
```

- `calculators`：要编进 native 库的 calculator（传 subgraph 目标，传递依赖自动包含全部 calculator）；
- `assets`：模型文件（打进 AAR 的 assets，图运行时按 `model_path` 加载）。

## 4. 服务器端：修 mediapipe_aar.bzl 的过时依赖

首次编 AAR 报错：

```
ERROR: no such package 'java/com/google/common/base'
```

这是 `mediapipe_aar.bzl` 里硬编码的**旧版 Guava 路径**（`//java/com/google/common/base`），
新版 MediaPipe 已改成 Maven 依赖。修复：

```python
# mediapipe_aar.bzl 的 deps 里
"//java/com/google/common/base",   # 旧
"@maven//:com_google_guava_guava", # 新
```

> 这暴露一个事实：**新版 MediaPipe 已不再维护 framework 层的 `mediapipe_aar` 打包方式**，
> 官方主推 tasks 的 AAR（`mediapipe_tasks_aar.bzl`）。底层 `mediapipe_aar` 是「能用但半废弃」状态。

## 5. 服务器端：编 AAR 成功

```bash
export ANDROID_HOME=/root/autodl-tmp/Android/Sdk
export ANDROID_NDK_HOME=/root/autodl-tmp/Android/Ndk/android-ndk-r28b
bazel build --config=android_arm64 \
  //mediapipe/examples/android/src/java/com/google/mediapipe/apps/facegaze:face_gaze_aar
```

产物：`face_gaze_aar.aar`（72MB），内容：

| 内容 | 大小 | 说明 |
|---|---|---|
| `jni/arm64-v8a/libmediapipe_jni.so` | 88.7MB | native 库（图 + 所有 calculator + framework） |
| `jni/arm64-v8a/libopencv_java4.so` | 23.5MB | OpenCV |
| `classes.jar` | 612KB | **framework 层 Java 类**（Graph/Packet/proto）← 冲突根源 |
| `assets/l2cs_net.tflite` | 47.8MB | L2CS-Net 模型 |
| `assets/face_detection_short_range.tflite` | 229KB | 人脸检测模型 |

## 6. 本机：调研新版 Java Graph API

从 AAR 的 `classes.jar`（`javap`）确认的底层 Graph API：

| 方法 | 作用 |
|---|---|
| `Graph.setGraphType(String)` | **用注册的 subgraph 作为顶层图**（省掉写顶层图 pbtxt） |
| `Graph.addPacketCallback(String, PacketCallback)` | 收结果 |
| `Graph.startRunningGraph()` | 启动图 |
| `Graph.addPacketToInputStream(String, Packet, long)` | 喂数据 |
| `AndroidPacketCreator(Graph).createRgbImageFrame(Bitmap)` | Bitmap → Packet |
| `PacketGetter.getFloat32Vector(Packet)` | **读 float 数组**（logits） |
| `Graph.closeAllPacketSources()` + `waitUntilGraphDone()` | 关闭 |

关键发现：新版把数据读取从 `Packet` 类移到了 `PacketGetter` 工具类，
`Packet` 类被瘦身（只有 create/getTimestamp/copy/release 等）。

## 7. 本机：写 Kotlin 集成代码

### 7.1 `FaceGazeGraphModel.kt`（实现 TestModel 接口）

```kotlin
class FaceGazeGraphModel : TestModel {
    private val graph = Graph()
    private val packetCreator = AndroidPacketCreator(graph)

    init {
        graph.setGraphType("FaceGazeCpu")  // 用 subgraph 当顶层图
        graph.addPacketCallback("gaze_yaw_logits") { p ->
            yawLogits = PacketGetter.getFloat32Vector(p); latch?.countDown()
        }
        graph.addPacketCallback("gaze_pitch_logits") { p ->
            pitchLogits = PacketGetter.getFloat32Vector(p); latch?.countDown()
        }
        graph.startRunningGraph()
    }

    override fun infer(bitmap: Bitmap): InferenceResult {
        val packet = packetCreator.createRgbImageFrame(bitmap)
        graph.addPacketToInputStream("image", packet, SystemClock.elapsedRealtimeNanos() / 1000)
        latch?.await(5, TimeUnit.SECONDS)  // 等两个 logits 回调
        return InferenceResult(listOf(yawLogits ?: FloatArray(0), pitchLogits ?: FloatArray(0)), ...)
    }
}
```

### 7.2 ViewModel 加开关

`TestModelViewModel` 加 `USE_GRAPH` 常量，切换 LiteRT / Graph 实现：

```kotlin
private fun loadModel() {
    model = if (USE_GRAPH) FaceGazeGraphModel()
            else LiteRtTestModel.load(context, MODEL_ASSET)
}
```

### 7.3 依赖配置

- `feature:testmodel/build.gradle.kts` 加 `implementation(files("libs/face_gaze_aar.aar"))`（因为 Graph 实现在这里）；
- `app/build.gradle.kts` 加 `ndk { abiFilters += "arm64-v8a" }`（AAR 是 arm64 编的）。

## 8. 构建失败：Duplicate class 冲突

```text
Duplicate class com.google.mediapipe.framework.Graph found in modules
  face_gaze_aar.aar 和 tasks-core-0.10.14.aar (com.google.mediapipe:tasks-core:0.10.14)
```

### 8.1 冲突的本质

EyeProject 里**两条 MediaPipe 路线**，它们的 **framework 层 Java 类重复**：

| 路线 | 位置 | 带来什么 |
|---|---|---|
| tasks API（Face Landmarker） | `feature:camera` → `tasks-vision 0.10.14` → `tasks-core` | framework 类 + tasks 类 + native 库 |
| C++ 图（face_gaze） | `feature:testmodel` → `face_gaze_aar.aar` | framework 类 + native 库（**无 tasks 类**） |

`mediapipe_aar` 打包时把 framework 层 Java 类（Graph/Packet/proto）打进了 AAR，
而 Maven 的 `tasks-core` 也带同样的类 → D8 报重复。

### 8.2 关键认知：不是「pipeline 数量限制」

- MediaPipe **支持任意多条 pipeline 共存**（多个图/模型同时跑）；
- 但 **framework 层 Java 类只能存在一份**；
- 正确姿势是**编一个「统一 AAR」**：native 库同时含 Face Landmarker 的 calculator + face_gaze 的 calculator，Java 层只有一份 framework。

当前是「两个独立 AAR 各自带 framework」，所以撞车。

### 8.3 为什么不能简单 exclude

- `tasks-core` 不能排除（Face Landmarker 需要它的 tasks 层，face_gaze_aar 不提供）；
- `face_gaze_aar` 是 `files()` 文件依赖，Gradle 的 `exclude` 只对 Maven 模块生效，排不掉它内部的单个类。

## 9. 结论：终局走「统一 AAR」

**已完成（AAR 链路全通）**：图编译 → AAR 编译 → 下载 → 依赖配置 → Kotlin 集成代码，全部就绪。

**卡点（App 集成）**：两条 MediaPipe 路线的 framework 层 Java 类重复。

三个候选方案：

| 方案 | 做法 | 成本 |
|---|---|---|
| ① 编统一 AAR | 把 iris_tracking + face_gaze 两个图编进**同一个** AAR，framework 层只有一份 | 重，但一劳永逸 |
| ② hack 解压删类 | 解压 AAR 删重复的 framework 类，让 tasks-core 提供 | 快，但 0.10.14 旧 framework 可能缺新方法（如 `setGraphType`） |
| ③ 回 LiteRT | C++ 图路线到「AAR 编译成功」已是完整验证，功能用 LiteRT | 零成本 |

**最终选择：① 统一 AAR**。核心洞察是——**framework 层 Java 类（Graph/Packet/proto）只能存在一份**，
所以正确姿势不是「两个 AAR 各自带 framework」，而是「一个 AAR 同时含两条 pipeline 的 calculator，Java 层只有一份 framework」。
同时彻底移除 Maven 的 `tasks-vision`，`feature:camera` 也从 Tasks API 换成底层 Graph 加载 iris_tracking 图。
完整实操见下节。

## 参考

- `mediapipe_aar.bzl`：`mediapipe/java/com/google/mediapipe/mediapipe_aar.bzl`
- 官方底层 Graph 示例：`mediapipe/examples/android/.../handtrackinggpu/MainActivity.java`
- 关键 API 出处：`mediapipe/java/com/google/mediapipe/framework/{Graph,AndroidPacketCreator,PacketGetter}.java`
- 上一篇：`docs/MediaPipe自定义图编译实录.md`

---

# 终章：统一 AAR（iris_tracking + face_gaze 合一）

> 本节接续上面第 9 节的方案 ①，记录最终落地的「一个 AAR 装两条 pipeline」。
> 所有 BUILD/pbtxt 内容均为服务器 `/root/autodl-tmp/mediapipe` 上的真实最终版本（2026-08 核对）。

## 10. 目标

把两条 pipeline 编进同一个 AAR，替换掉 Maven 的 `tasks-vision`：

| pipeline | 图类型 | 用途 | 模块 |
|---|---|---|---|
| iris_tracking | 顶层 binary graph | 468 人脸点 + 10 虹膜点（Face Landmarker 替代） | `feature:camera` |
| face_gaze | subgraph（`FaceGazeCpu`） | L2CS-Net 视线估计 | `feature:testmodel` |

## 11. iris_tracking：从官方 desktop 图改成 binary graph

官方 `mediapipe/graphs/iris_tracking/iris_tracking_cpu.pbtxt` 是给 desktop 用的顶层图，
把它编译成 **binary graph**（`.binarypb`）打进 AAR，App 端用 `Graph.loadBinaryGraph()` 加载。

### 11.1 顶层图 `iris_tracking_cpu.pbtxt`（未改，直接用官方版）

```
# 输入
input_stream: "input_video"              # ImageFrame
# 输出
output_stream: "output_video"            # 渲染后的图（App 用不到但图结构需要）
output_stream: "face_landmarks_with_iris"  # NormalizedLandmarkList ← App 真正要的
```

数据流：`input_video → FaceLandmarkFrontCpu → face_landmarks(468) → 按 33/133 与 362/263 拆左右眼边界
→ IrisLandmarkLeftAndRightCpu → 左/右虹膜 + 眼轮廓 → UpdateFaceLandmarksCalculator 回填
→ Concatenate(updated_face_landmarks + iris_landmarks) → face_landmarks_with_iris`。

关键节点：`ConstantSidePacketCalculator(num_faces=1)`、`SplitNormalizedLandmarkList(Vector)Calculator`、
`IrisLandmarkLeftAndRightCpu`、`UpdateFaceLandmarksCalculator`（自定义，位于 `graphs/iris_tracking/calculators/`）、
`IrisRendererCpu`（渲染，App 侧冗余但图自带）。

### 11.2 `iris_tracking/BUILD`：新增 binary graph 规则

在官方 BUILD 基础上（官方已有 `iris_tracking_cpu_deps` 这个 `cc_library`），**只需追加**：

```python
mediapipe_binary_graph(
    name = "iris_tracking_cpu_binary_graph",
    graph = "iris_tracking_cpu.pbtxt",
    output_name = "iris_tracking_cpu.binarypb",
    deps = [":iris_tracking_cpu_deps"],
)
```

`iris_tracking_cpu_deps` 官方已定义，无需改动（含 face_landmark_front_cpu、iris_landmark_left_and_right_cpu 等）。

## 12. face_gaze：最终是纯内置节点版（无自定义 calculator）

> ⚠️ 更正早期记录：最终版 **没有** `gaze_angles_calculator.cc`。
> 图只输出 L2CS-Net 的两个 90-bin logits，softmax → 角度 的解码全部留在 Kotlin 端，
> 所以 `face_gaze` 目录最终只有 `BUILD + face_gaze_cpu.pbtxt + l2cs_net.tflite`。

### 12.1 `face_gaze_cpu.pbtxt` 全文（顶层 subgraph）

```
type: "FaceGazeCpu"

input_stream: "IMAGE:image"
output_stream: "FLOATS:gaze_yaw_logits"
output_stream: "FLOATS:gaze_pitch_logits"

# FaceDetectionShortRangeCpu → DetectionsToRectsCalculator(USE_KEYPOINTS)
#   → RectTransformationCalculator(scale 2.0, shift_y -0.1, square_long)
#   → ImageCroppingCalculator(448×448)
#   → ImageToTensorCalculator(448×448, [0,1])
#   → TfLiteInferenceCalculator(l2cs_net.tflite, delegate xnnpack)
#   → TensorsToFloatsCalculator × 2 → gaze_yaw_logits / gaze_pitch_logits
```

完整 pbtxt 见上一节已核对内容，输入流名是 **`image`**（与 Kotlin 端 `addPacketToInputStream("image", ...)` 一致）。

### 12.2 `face_gaze/BUILD`

```python
load("//mediapipe/framework/tool:mediapipe_graph.bzl", "mediapipe_simple_subgraph")
load("//mediapipe/framework/tool:mediapipe_files.bzl", "mediapipe_files")

mediapipe_simple_subgraph(
    name = "face_gaze_cpu",
    graph = "face_gaze_cpu.pbtxt",
    register_as = "FaceGazeCpu",
    deps = [
        "//mediapipe/calculators/image:image_cropping_calculator",
        "//mediapipe/calculators/image:image_properties_calculator",
        "//mediapipe/calculators/tensor:image_to_tensor_calculator",
        "//mediapipe/calculators/tensor:tensors_to_floats_calculator",
        "//mediapipe/calculators/tflite:tflite_inference_calculator",
        "//mediapipe/calculators/util:detections_to_rects_calculator",
        "//mediapipe/calculators/util:rect_transformation_calculator",
        "//mediapipe/modules/face_detection:face_detection_short_range_cpu",
    ],
)

mediapipe_files(
    srcs = ["l2cs_net.tflite"],
)
```

## 13. 统一 AAR 的 BUILD（eye_combined）

文件：`mediapipe/examples/android/src/java/com/google/mediapipe/apps/eye_combined/BUILD`

```python
load("//mediapipe/java/com/google/mediapipe:mediapipe_aar.bzl", "mediapipe_aar")

mediapipe_aar(
    name = "eye_combined_aar",
    calculators = [
        "//mediapipe/graphs/face_gaze:face_gaze_cpu",
        "//mediapipe/graphs/iris_tracking:iris_tracking_cpu_deps",
    ],
    assets = [
        # face_gaze 模型
        "//mediapipe/graphs/face_gaze:l2cs_net.tflite",
        # iris_tracking 模型
        "//mediapipe/modules/face_detection:face_detection_short_range.tflite",
        "//mediapipe/modules/face_landmark:face_landmark.tflite",
        "//mediapipe/modules/iris_landmark:iris_landmark.tflite",
        # iris 图（二进制）
        "//mediapipe/graphs/iris_tracking:iris_tracking_cpu_binary_graph",
    ],
)
```

要点：

- `calculators` 传**两个图目标**，`mediapipe_aar` 会把两者的传递依赖（所有 calculator）一起编进 `libmediapipe_jni.so`；
- `assets` 里放 4 个模型 + 1 个 `.binarypb` 图（`iris_tracking_cpu_binary_graph` 的产物 `iris_tracking_cpu.binarypb`）；
- `face_gaze` 是 subgraph（`register_as=FaceGazeCpu`），App 用 `Graph.setGraphType("FaceGazeCpu")`；
- `iris_tracking` 是 binary graph，App 用 `Graph.loadBinaryGraph("iris_tracking_cpu.binarypb")`。

## 14. 编译命令（服务器）

```bash
export ANDROID_HOME=/root/autodl-tmp/Android/Sdk
export ANDROID_NDK_HOME=/root/autodl-tmp/Android/Ndk/android-ndk-r28b
bazel build --config=android_arm64 \
  //mediapipe/examples/android/src/java/com/google/mediapipe/apps/eye_combined:eye_combined_aar
```

前提（承接前两份文档）：`mediapipe_aar.bzl` 的 Guava 路径已修（第 127 行 `@maven//:com_google_guava_guava`），
SDK/NDK 已装好。产物 `eye_combined_aar.aar`（78.5MB）。

## 15. App 集成：本地 Maven 仓库（关键坑）

### 15.1 为什么不能用 `files("libs/xxx.aar")`

`feature:camera` / `feature:testmodel` 是 **Android library 模块**，AGP 不允许 library 模块
用 `files()` 直接依赖本地 AAR（`bundleDebugAar` 会报 `hasLocalAarDeps`，因为本地 AAR 的资源/类
不会被打进它产出的 AAR）。**规范做法是把 AAR 放进本地 Maven 仓库，按坐标依赖。**

### 15.2 目录结构

```
mediapipe-m2repo/
└── com/google/mediapipe/eye_combined/1.0.0/
    ├── eye_combined-1.0.0.aar
    └── eye_combined-1.0.0.pom   # 声明 protobuf-javalite 传递依赖
```

### 15.3 `settings.gradle.kts` 注册仓库

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("$rootDir/mediapipe-m2repo")
            content { includeModule("com.google.mediapipe", "eye_combined") }
        }
    }
}
```

### 15.4 版本目录与依赖

```toml
# libs.versions.toml
mediapipeEyeCombined = "1.0.0"
mediapipe-eye-combined = { group = "com.google.mediapipe", name = "eye_combined", version.ref = "mediapipeEyeCombined" }
protobuf-javalite = { group = "com.google.protobuf", name = "protobuf-javalite", version = "3.25.5" }
```

```kotlin
// feature:camera / feature:testmodel 的 build.gradle.kts
implementation(libs.mediapipe.eye.combined)
implementation(libs.protobuf.javalite)   // AAR 的 classes.jar 没打包 com/google/protobuf/*，必须补
```

> `protobuf-javalite` 必须显式加：统一 AAR 的 `classes.jar` 引用了 `com.google.protobuf` 的
> `GeneratedMessageLite` / `Parser`，但 AAR 本身没把这些类打进去，会报
> `Cannot access 'GeneratedMessageLite'`。

## 16. 已知 TODO（运行时验证，编译不阻塞）

1. **虹膜中心索引**：`face_landmarks_with_iris` 结构 = 468 脸点 + 10 虹膜点，
   代码里假设 RIGHT_IRIS=468、LEFT_IRIS=473，需真机打印验证；
2. **眼睛轮廓/眼角索引**（33/133/362/263 等）同样待验证；
3. **L2CS-Net 归一化缺口**：模型需要 ImageNet 三通道 `(px−mean)/std`，而 `ImageToTensorCalculator`
   只做了 `[0,1]`，需确认 `l2cs_net.tflite` 转换时是否已内置归一化，否则要自定义节点或 Kotlin 预处理；
4. **protobuf 版本不一致**：AAR 用 Bazel protobuf 6.x 编译，Maven 侧 `protobuf-javalite` 只有 3.25.5，
   编译已通过，运行时 protobuf 序列化若报版本错需对齐。
