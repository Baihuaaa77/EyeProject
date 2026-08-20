# MediaPipe 在 Android 端的完整技术栈：自定义 Graph（计算图）Pipeline 的构建与落地

> 调研结论：MediaPipe 自 2023 年全面转向 **google-ai-edge/mediapipe** 仓库，官方主推 **Tasks API**（高层、Maven 分发、跨平台一致），而底层 **Framework/Graph（C++ 计算图）** 仍是自定义 pipeline 与深度定制的唯一途径。本文覆盖两层技术栈，重点落在自定义 `.pbtxt` graph → `mediapipe_aar()` 编译 → Android 落地全链路。

## 1. MediaPipe 分层：Tasks API vs Framework/Graph

### 1.1 Tasks API（高层）

面向「直接用现成能力」的开发者。它把「模型 + 前处理 + 后处理 + 推理 + 结果封装」打包成一个**不可拆解的 Task 黑盒**，输入一帧，输出结构化结果（关键点、检测框、分类等）。

- **特点**：跨平台一致（Android/iOS/Python/JS/C++ 同名 API）、Maven/CocoaPods/pip/npm 分发、自带模型下载、自动 delegate 管理、内置 GPU/CPU 切换。
- **代价**：**无法改 pipeline 内部结构**（不能插自定义节点、不能换中间模型、不能新增输出流）。想加「注视估计」这类非官方能力，只能把 Tasks 的输出再喂给第二个独立推理引擎（额外一次拷贝），或用底层 Framework。

```kotlin
dependencies {
    implementation("com.google.mediapipe:tasks-vision:0.10.21")  // 视觉 Task
    implementation("com.google.mediapipe:tasks-text:0.10.21")    // 文本 Task
    implementation("com.google.mediapipe:tasks-audio:0.10.21")   // 音频 Task
}
```

> `0.10.x` 是稳定线（0.10.14 后 API 已稳定）。Android 端通过 `BaseOptions.setModelAssetPath()` 加载 `.task`（本质是内含 `*.tflite` + 元数据的打包格式）。

### 1.2 Framework/Graph（底层 C++）

MediaPipe 的**执行引擎本体**：基于 C++ 的「有向无环计算图（DAG）」运行时（`CalculatorGraph`），用 protobuf 文本 `.pbtxt` 描述拓扑。所有 Tasks 底层其实也是这套 graph 在跑。

- **特点**：可自定义任意节点/边/模型/输出流；原生支持 GPU/CPU delegate、多流并行、同步、循环子图（subgraph）、时间戳同步。
- **代价**：需用 **Bazel** 从源码构建（Android 上生成 AAR），工程门槛高。

### 1.3 对比表

| 维度 | Tasks API | Framework/Graph |
|---|---|---|
| 抽象层级 | 高（Task 黑盒） | 低（Calculator + Stream + Packet） |
| 分发 | Maven Central（`com.google.mediapipe:tasks-*`） | Bazel 构建 AAR 自集成 |
| 自定义 pipeline | ❌ 不支持 | ✅ 完全可控 |
| 换/插模型 | ❌ 只能用官方 `.task` | ✅ 任意 `.tflite` |
| 跨平台一致性 | ✅ 强 | ⚠️ 需自己封装各端 |
| 上手成本 | 低 | 高（需 Bazel + C++ 基础） |
| 适合场景 | 快速集成官方能力 | 定制检测→裁剪→关键点→注视等串联 pipeline |

**结论**：要「人脸检测 → 裁剪 → 关键点/虹膜 → 注视估计」这种自定义链路并跑在**单次推理会话内**、共享内存零拷贝，必须走 Framework/Graph。

## 2. Graph / 计算图概念

### 2.1 核心原语

| 概念 | 说明 |
|---|---|
| **Calculator**（节点） | 处理单元。C++ 类，实现 `GetContract()`（声明输入/输出流与 tag）+ `Process()`（消费 Packet 产出 Packet） |
| **Stream**（边） | 有向数据流，携带时间戳，连接 calculator，可加 tag（如 `IMAGE:image`） |
| **Packet** | 流上传输的数据单元：`ImageFrame`、`GpuBuffer`、`Tensor`、`NormalizedRect`、`NormalizedLandmarkList`、`Detection`、`MatrixData`、`float[]` 等 |
| **Side Packet** | 图初始化时注入的常量（模型路径、参数），运行期不变 |
| **`.pbtxt`** | 图的文本描述（protobuf text format），定义 `input_stream`/`output_stream`/`node{}` |
| **Subgraph**（子图） | 用 `.pbtxt` 封装成可复用「大节点」（如 `FaceLandmarkFrontCpu`），通过 BUILD 目标名引用 |

```cpp
class MyCalculator : public CalculatorBase {
 public:
  static absl::Status GetContract(CalculatorContract* cc) {
    cc->Inputs().Tag("IMAGE").Set<ImageFrame>();
    cc->Outputs().Tag("LANDMARKS").Set<NormalizedLandmarkList>();
    return absl::OkStatus();
  }
  absl::Status Process(CalculatorContext* cc) override {
    const auto& in = cc->Inputs().Tag("IMAGE").Get<ImageFrame>();
    cc->Outputs().Tag("LANDMARKS")
        .AddPacket(MakePacket<NormalizedLandmarkList>(...).At(cc->InputTimestamp()));
    return absl::OkStatus();
  }
};
REGISTER_CALCULATOR(MyCalculator);
```

### 2.2 关键内置节点

| Calculator | 作用 | 关键输入 → 输出 |
|---|---|---|
| `ImageToTensorCalculator` | 图像→推理张量（resize/letterbox/归一化），产出坐标映射矩阵 | `IMAGE`(+`NORM_RECT`) → `TENSORS`,`MATRIX` |
| `TfLiteInferenceCalculator` | 跑 TFLite 模型，透传 `MATRIX` | `TENSORS` → `TENSORS`(+`MATRIX`) |
| `TensorsToDetectionsCalculator` | 解码 SSD 式输出为检测框 | `TENSORS` → `DETECTIONS` |
| `TensorsToLandmarksCalculator` | 张量 → 绝对坐标关键点 | `TENSORS` → `LANDMARKS` |
| `TensorsToFaceLandmarksCalculator` | 张量 → 归一化关键点（结合 `MATRIX` 反 letterbox） | `TENSORS`+`MATRIX` → `NORM_LANDMARKS` |
| `DetectionToRectCalculator` | 检测框 → 裁剪矩形（含旋转校正） | `DETECTION`(+`IMAGE_SIZE`) → `NORM_RECT` |
| `ImageCroppingCalculator` | 按 `NORM_RECT` 裁剪/缩放图像 | `IMAGE`+`NORM_RECT` → `IMAGE` |
| `RecolorCalculator` | 颜色格式/空间转换 | `IMAGE` → `IMAGE` |
| `TensorsToFloatsCalculator` | 张量 → `std::vector<float>`（便于 Java 读数值） | `TENSORS` → `FLOATS` |
| `BeginLoopCalculator` / `EndLoopCalculator` | 检测列表逐张循环处理再聚合 | `ITERABLE`→`ITEM` / `ITEM`→`ITERABLE` |
| `GateCalculator` / `PassThroughCalculator` | 允许/禁止数据流过 / 透传 | — |
| `FaceDetectionShortRangeCpu` / `FaceLandmarkFrontCpu` | 官方封装好的子图 | 见下文 |

### 2.3 输入/输出流名（tag）约定

| tag / 流名 | 类型 | 含义 |
|---|---|---|
| `IMAGE` / `IMAGE:image` | `ImageFrame` 或 `GpuBuffer` | 输入图像帧 |
| `IMAGE_CPU` / `IMAGE_GPU` | `ImageFrame` / `GpuBuffer` | 显式指定图像存储位置 |
| `NORM_RECT` | `NormalizedRect` | 归一化矩形（ROI/裁剪框） |
| `DETECTIONS` | `std::vector<Detection>` | 检测结果 |
| `LANDMARKS` | `LandmarkList` / `NormalizedLandmarkList` | 关键点 |
| `NORM_LANDMARKS` | `NormalizedLandmarkList` | 归一化关键点（相对图宽高） |
| `TENSORS` | `std::vector<Tensor>` | 张量流 |
| `MATRIX` | `MatrixData` | 坐标变换矩阵 |
| `TRANSFORMATIONS` | `std::vector<MatrixData>` | 4×4 变换矩阵列表 |
| `BLENDSHAPES` | `ClassificationList` | 52 个 blendshape 系数 |
| `MESH_3D` / `MESH_WITH_GEOMETRY` | `FaceGeometry` | 3D 网格 + 变换 |
| `IMAGE_SIZE` | `std::pair<int,int>` | 图像尺寸（DetectionToRect 需要） |

> 命名约定：`<TAG>:<local_name>`。Java/Kotlin 端 `addPacketCallback("stream_name")` / `addPacketToInputStream("stream_name", ...)` 使用的是**图顶层流名**。

## 3. 自定义 Pipeline 完整示例（.pbtxt）

### 3.1 官方内部结构：`FaceLandmarkFrontCpu`

```pbtxt
input_stream: "IMAGE:image"
input_stream: "ROI:roi"
output_stream: "LANDMARKS:face_landmarks"

node {
  calculator: "ImageToTensorCalculator"
  input_stream: "IMAGE_CPU:image"
  input_stream: "NORM_RECT:roi"
  output_stream: "TENSORS:image_tensor"
  output_stream: "MATRIX:transform_matrix"
  options {
    [mediapipe.ImageToTensorCalculatorOptions.ext] {
      output_tensor_width: 192
      output_tensor_height: 192
      keep_aspect_ratio: true
      output_tensor_float_range { min: 0.0 max: 1.0 }
      border_mode: BORDER_ZERO
    }
  }
}

node {
  calculator: "TfLiteInferenceCalculator"
  input_stream: "TENSORS:image_tensor"
  output_stream: "TENSORS:landmark_tensor"
  output_stream: "TENSORS:face_flag_tensor"
  output_stream: "MATRIX:transform_matrix"
  options {
    [mediapipe.TfLiteInferenceCalculatorOptions.ext] {
      model_path: "face_landmark.tflite"
      delegate { gpu { use_advanced_gpu_api: true } }
    }
  }
}

node {
  calculator: "TensorsToFaceLandmarksCalculator"
  input_stream: "TENSORS:landmark_tensor"
  input_stream: "TENSORS:face_flag_tensor"
  input_stream: "MATRIX:transform_matrix"
  output_stream: "NORM_LANDMARKS:face_landmarks"
}
```

要点：`ImageToTensorCalculator` 把 ROI letterbox 到 192×192，`MATRIX` 记录「张量坐标↔原图坐标」映射，`TensorsToFaceLandmarksCalculator` 用它把关键点**反算回原图归一化坐标**。

### 3.2 自定义「人脸检测 → 裁剪 → 关键点/虹膜 → 注视估计」graph

```pbtxt
# face_gaze_cpu.pbtxt —— 自定义端到端 pipeline（教学示例）
input_stream: "IMAGE:image"

# 1) 人脸检测（BlazeFace short-range，128×128）
node {
  calculator: "ImageToTensorCalculator"
  input_stream: "IMAGE:image"
  output_stream: "TENSORS:image_tensor"
  options {
    [mediapipe.ImageToTensorCalculatorOptions.ext] {
      output_tensor_width: 128
      output_tensor_height: 128
      keep_aspect_ratio: true
      output_tensor_float_range { min: 0.0 max: 1.0 }
      border_mode: BORDER_ZERO
    }
  }
}
node {
  calculator: "TfLiteInferenceCalculator"
  input_stream: "TENSORS:image_tensor"
  output_stream: "TENSORS:regressors"
  output_stream: "TENSORS:classificators"
  options {
    [mediapipe.TfLiteInferenceCalculatorOptions.ext] {
      model_path: "face_detection_short_range.tflite"
      delegate { gpu { use_advanced_gpu_api: true } }
    }
  }
}
node {
  calculator: "TensorsToDetectionsCalculator"
  input_stream: "TENSORS:regressors"
  input_stream: "TENSORS:classificators"
  output_stream: "DETECTIONS:detections"
  options {
    [mediapipe.TensorsToDetectionsCalculatorOptions.ext] {
      num_classes: 1
      num_boxes: 896
      num_coords: 16
      box_coord_offset: 0
      keypoint_coord_offset: 4
      num_keypoints: 6
      num_values_per_keypoint: 2
      sigmoid_score: true
      score_clipping_thresh: 100.0
      reverse_output_order: true
      x_scale: 128.0
      y_scale: 128.0
      h_scale: 128.0
      w_scale: 128.0
      min_score_thresh: 0.5
    }
  }
}

# 2) 检测 → 归一化矩形（人脸 ROI）
node {
  calculator: "DetectionToRectCalculator"
  input_stream: "DETECTION:detection"   # 多脸需 BeginLoop/EndLoop 逐张
  input_stream: "IMAGE_SIZE:image_size"
  output_stream: "NORM_RECT:face_rect"
}

# 3) 关键点 + 虹膜（官方子图）
node {
  calculator: "FaceLandmarkFrontCpu"
  input_stream: "IMAGE:image"
  input_stream: "ROI:face_rect"
  output_stream: "LANDMARKS:face_landmarks"   # 478 点（含虹膜），归一化坐标
}

# 4) 裁剪人脸供注视估计（224×224，L2CS-Net 输入）
node {
  calculator: "ImageCroppingCalculator"
  input_stream: "IMAGE:image"
  input_stream: "NORM_RECT:face_rect"
  output_stream: "IMAGE:face_crop"
  options {
    [mediapipe.ImageCroppingCalculatorOptions.ext] { width: 224 height: 224 }
  }
}

# 5) 注视估计（自备 L2CS-Net，非官方）
node {
  calculator: "ImageToTensorCalculator"
  input_stream: "IMAGE:face_crop"
  output_stream: "TENSORS:gaze_input"
  options {
    [mediapipe.ImageToTensorCalculatorOptions.ext] {
      output_tensor_width: 224
      output_tensor_height: 224
      output_tensor_float_range { min: 0.0 max: 1.0 }
    }
  }
}
node {
  calculator: "TfLiteInferenceCalculator"
  input_stream: "TENSORS:gaze_input"
  output_stream: "TENSORS:gaze_yaw"
  output_stream: "TENSORS:gaze_pitch"
  options {
    [mediapipe.TfLiteInferenceCalculatorOptions.ext] {
      model_path: "l2cs_net.tflite"
      delegate { gpu { use_advanced_gpu_api: true } }
    }
  }
}
node {
  calculator: "TensorsToFloatsCalculator"
  input_stream: "TENSORS:gaze_yaw"
  input_stream: "TENSORS:gaze_pitch"
  output_stream: "FLOATS:gaze_angles"   # [yaw, pitch]
}

output_stream: "LANDMARKS:face_landmarks"
output_stream: "FLOATS:gaze_angles"
```

节点作用：`ImageToTensorCalculator`(128)→letterbox 归一化；`TfLiteInferenceCalculator`→BlazeFace；`TensorsToDetectionsCalculator`→解码 896 anchor（`num_coords:16` = 4 框 + 6 关键点×2）；`DetectionToRectCalculator`→NORM_RECT；`FaceLandmarkFrontCpu`→478 点；`ImageCroppingCalculator`→裁 224×224 脸图；`TfLiteInferenceCalculator`→L2CS 输出 yaw/pitch；`TensorsToFloatsCalculator`→float 数组供上层读。

## 4. 把自定义 graph 编译成 Android AAR

### 4.1 BUILD 文件

```python
# mediapipe/graphs/face_gaze/BUILD
load("//mediapipe/framework:mediapipe_graph.bzl", "mediapipe_binary_graph")

cc_library(
    name = "mobile_calculators",
    deps = [
        "//mediapipe/calculators/core:begin_loop_calculator",
        "//mediapipe/calculators/core:end_loop_calculator",
        "//mediapipe/calculators/image:image_cropping_calculator",
        "//mediapipe/calculators/image:image_to_tensor_calculator",
        "//mediapipe/calculators/image:recolor_calculator",
        "//mediapipe/calculators/tensor:tensors_to_detections_calculator",
        "//mediapipe/calculators/tensor:tensors_to_floats_calculator",
        "//mediapipe/calculators/tensor:tensors_to_landmarks_calculator",
        "//mediapipe/calculators/tflite:tflite_inference_calculator",
        "//mediapipe/calculators/util:detection_to_rect_calculator",
        "//mediapipe/graphs/face_landmark:mobile_calculators",
    ],
)

mediapipe_binary_graph(
    name = "face_gaze_mobile_cpu",
    graph = "face_gaze_cpu.pbtxt",
    output_name = "face_gaze_mobile_cpu.binarypb",
    deps = [":mobile_calculators"],
)
```

```python
# mediapipe/examples/android/src/java/com/google/mediapipe/apps/facegaze/BUILD
load("//mediapipe/java/com/google/mediapipe:mediapipe_aar.bzl", "mediapipe_aar")

mediapipe_aar(
    name = "face_gaze_aar",
    calculators = ["//mediapipe/graphs/face_gaze:mobile_calculators"],
    assets = [
        "//mediapipe/graphs/face_gaze:face_gaze_mobile_cpu.binarypb",
        "//mediapipe/modules/face_detection:face_detection_short_range.tflite",
        "//mediapipe/modules/face_landmark:face_landmark_with_attention.tflite",
        "//mediapipe/modules/gaze:l2cs_net.tflite",   # 自备模型
    ],
    assets_dir = "",
)
```

### 4.2 Bazel 构建

```bash
# 单 ABI（arm64，体积小）
bazel build -c opt --config=android_arm64 \
  //mediapipe/examples/android/src/java/com/google/mediapipe/apps/facegaze:face_gaze_aar

# 多 ABI
bazel build -c opt --fat_apk_cpu=arm64-v8a,armeabi-v7a \
  //mediapipe/examples/android/src/java/com/google/mediapipe/apps/facegaze:face_gaze_aar
```

产物：`bazel-bin/.../facegaze/face_gaze_aar.aar`。

### 4.3 Android 引入

```gradle
android {
    defaultConfig { ndk { abiFilters += listOf("arm64-v8a") } }  // 与 AAR ABI 对齐
}
dependencies {
    implementation(files("libs/face_gaze_aar.aar"))
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
}
```

### 4.4 Kotlin 调用（底层 SDK）

```kotlin
import com.google.mediapipe.framework.Graph
import com.google.mediapipe.framework.Packet
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList

class FaceGazeGraph(context: Context) {
    private val graph: Graph = Graph.create(
        context,
        "face_gaze_mobile_cpu.binarypb", // assets 中的图
        "image",                          // 图顶层 input_stream
        "gaze_angles"                     // 顶层输出流之一
    ) { packet -> onGaze(packet) }

    init {
        graph.addPacketCallback("face_landmarks") { onLandmarks(it) }
        graph.startRunningGraph()
    }

    fun process(bitmap: Bitmap, timestampUs: Long) {
        val packet = Packet.createRgbaImageFrame(bitmap, timestampUs)  // 需 ARGB_8888
        graph.addPacketToInputStream("image", packet, timestampUs)
    }

    private fun onLandmarks(packet: Packet) {
        val faces: List<NormalizedLandmarkList> = packet.getNormalizedLandmarkListVector()
        faces.firstOrNull()?.landmarkList?.forEach { lm -> /* lm.x, lm.y (0..1), lm.z */ }
    }
    private fun onGaze(packet: Packet) { val gaze = packet.getFloatArray() /* [yaw,pitch] */ }

    fun close() { graph.closeAllPacketSources(); graph.waitUntilGraphDone() }
}
```

**API 要点**：`Graph.create(...)` 为新版便捷工厂；旧版（≤0.9）用 `Graph()` + `loadBinaryGraph()`。`setGraph(pbtxtString)` 可加载文本图（调试用）。通用读取：`packet.getProtoVector(NormalizedLandmarkList.parser())`。

## 5. 模型清单

### 5.1 BlazeFace（人脸检测）

- 出处：[BlazeFace: Sub-millisecond Neural Face Detection on Mobile GPUs](https://arxiv.org/abs/1907.05047)。
- 结构：深度可分离卷积 SSD，896 anchor，6 关键点（左眼、右眼、鼻尖、嘴中心、双耳垂）。
- 输入：RGB，128×128（short-range）/ 192×192（full-range），float [0,1]。
- 输出：`regressors`（16 值/框）、`classificators`（1 得分/框）。

| 模型 | 输入 | 体积(约) |
|---|---|---|
| `face_detection_short_range.tflite` | 128×128 | ~205 KB |
| `face_detection_full_range.tflite` | 192×192 | ~1.8 MB |
| `face_detection_full_range_sparse.tflite` | 192×192 | 更小 |

### 5.2 Face Mesh / Face Landmarker

- 出处：[Real-time Facial Surface Geometry from Monocular Video on Mobile GPUs](https://arxiv.org/abs/2006.10962)，[Face Landmarker 指南](https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker)。
- 输入：192×192，RGB，float [0,1]。
- 输出：468 点基础网格（3D）；含虹膜 **478 点**；Tasks 额外输出 **52 blendshape** + **4×4 变换矩阵**。

| 模型 | 体积(约) | 说明 |
|---|---|---|
| `face_landmark.tflite` | ~2.9 MB | 468 点 |
| `face_landmark_with_attention.tflite` | ~2.6 MB | 478 点（含虹膜） |
| `face_landmarker.task` | ~3.7 MB | Tasks 打包版 |

**关键索引（0-based）：** 左眼外角 **33** / 内角 **133**；右眼内角 **362** / 外角 **263**；右眼虹膜中心 **468**（468–472）；左眼虹膜中心 **473**（473–477）。

**常用 blendshape：** `eyeBlinkLeft/Right`、`eyeLookDownLeft/Right`、`eyeLookInLeft/Right`、`eyeLookOutLeft/Right`、`eyeLookUpLeft/Right`、`eyeSquintLeft/Right`、`eyeWideLeft/Right`、`mouthSmileLeft/Right`、`jawOpen`、`browInnerUp` 等。

> 约定：MediaPipe 的「左/右」是**被摄者自身**的左/右（画面里左右相反）。

### 5.3 Gaze（L2CS-Net 等）

- 出处：[L2CS-Net](https://github.com/Ahmednull/L2CS-Net)，论文 [arXiv:2203.03339](https://arxiv.org/abs/2203.03339)。**非 MediaPipe 官方组件**。
- 结构：ResNet-50 主干 + 双分类头（yaw、pitch）。
- 输入：裁剪人脸 224×224（RGB，归一化）。
- 输出：两个 90-bin 分类头（yaw ∈ [-180°,180°]、pitch ∈ [-90°,90°]，每 bin 4°），softmax 期望得连续角度。
- 体积：PyTorch ~90 MB；TFLite float ~25–35 MB，int8 量化 ~10 MB 内。

## 6. CameraX 集成

```kotlin
// 1) LIVE_STREAM 配置
val options = FaceLandmarker.FaceLandmarkerOptions.builder()
    .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
    .setRunningMode(RunningMode.LIVE_STREAM)
    .setNumFaces(1)
    .setOutputFaceBlendshapes(true)
    .setOutputFacialTransformationMatrixes(true)
    .setResultListener { result, inputImage -> handle(result) }
    .build()
val landmarker = FaceLandmarker.createFromOptions(context, options)

// 2) ImageAnalysis 必须输出 RGBA_8888
val analysis = ImageAnalysis.Builder()
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)  // 关键！
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .build()

analysis.setAnalyzer(executor) { imageProxy ->
    val mpImage = MediaImageBuilder(imageProxy).build()   // 零拷贝
    landmarker.detectAsync(mpImage, imageProxy.imageInfo.timestamp)  // LIVE_STREAM 必须传时间戳
    imageProxy.close()   // 用完立刻 close
}
```

- **旋转**：用 `imageProxy.imageInfo.rotationDegrees` 旋转位图或结果；Tasks 返回**归一化坐标**（相对喂入图方向），不会自动旋转。
- **Builder 选择**：`BitmapImageBuilder`（通用、需 ARGB_8888、有拷贝）vs `MediaImageBuilder`（零拷贝、**仅 RGBA_8888**）。

## 7. 部署与性能

### 7.1 Delegate

```pbtxt
delegate { gpu { use_advanced_gpu_api: true } }     # GPU / OpenGL ES
delegate { xnnpack { num_threads: 4 } }             # CPU 多线程
```

Tasks：`BaseOptions.setDelegate(Delegate.GPU / Delegate.CPU)`（Android 默认 GPU）。

### 7.2 体积与 fps 参考（旗舰机量级）

| 任务 | 模型 | delegate | 参考 fps |
|---|---|---|---|
| 人脸检测 short-range | 205 KB | GPU | 100+ |
| Face Landmark 478 | ~2.6 MB | GPU | 60–100+ |
| Face Landmark 478 | ~2.6 MB | CPU | ~30 |
| L2CS-Net（ResNet-50） | ~30 MB | GPU | 15–30 |

AAR：单 ABI（arm64）自定义 graph 约 **10–25 MB**（含 native 库 + 模型）。

### 7.3 常见坑

1. **`MediaImageBuilder` 必须 RGBA_8888**：`ImageAnalysis` 默认 YUV_420_888 会抛异常。
2. **`LIVE_STREAM` 必须传时间戳**，且不能重复。
3. **`ImageProxy.close()` 忘关** → 内存暴涨、fps 骤降。
4. **letterbox 坐标**：`keep_aspect_ratio + BORDER_ZERO` 会补边，靠 `MATRIX` 反算（Tasks 已处理）。
5. **AAR ABI 与 `abiFilters` 对齐**，否则 `UnsatisfiedLinkError`。
6. **`model_path` 与 assets 路径一致**（一字不差）。
7. **CPU 图 vs GPU 图**：GPU 图需 GL 上下文；离屏/后台用 CPU 图。
8. **多脸需 BeginLoop/EndLoop**，不能直接喂列表给 `DetectionToRectCalculator`。
9. **Tasks 版本号一致**，避免符号/ABI 冲突。
10. **CAMERA 权限**运行时申请。

## 官方文档 / 出处 URL

**框架与概念**
- Solutions 总览：https://developers.google.com/edge/mediapipe/solutions/guide
- Tasks：https://developers.google.com/edge/mediapipe/solutions/tasks
- Graphs：https://developers.google.com/edge/mediapipe/framework/framework_concepts/graphs
- Calculators：https://developers.google.com/edge/mediapipe/framework/framework_concepts/calculators
- Android Hello World：https://developers.google.com/edge/mediapipe/framework/getting_started/hello_world_android
- Android Archive Library：https://developers.google.com/edge/mediapipe/framework/getting_started/android_archive_library

**视觉 Task 指南**
- Face Landmarker：https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker
- Face Landmarker（Android）：https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker/android
- Face Detector：https://developers.google.com/edge/mediapipe/solutions/vision/face_detector

**代码仓库**
- 主仓库：https://github.com/google-ai-edge/mediapipe
- Face Mesh graphs：https://github.com/google-ai-edge/mediapipe/tree/master/mediapipe/graphs/face_mesh
- Face Landmark 模块：https://github.com/google-ai-edge/mediapipe/tree/master/mediapipe/modules/face_landmark
- Face Detection 模块：https://github.com/google-ai-edge/mediapipe/tree/master/mediapipe/modules/face_detection

**模型与论文**
- BlazeFace：https://arxiv.org/abs/1907.05047
- Face Mesh：https://arxiv.org/abs/2006.10962
- L2CS-Net 论文：https://arxiv.org/abs/2203.03339
- L2CS-Net 仓库：https://github.com/Ahmednull/L2CS-Net

**依赖与工具**
- tasks-vision Maven：https://mvnrepository.com/artifact/com.google.mediapipe/tasks-vision
- CameraX：https://developer.android.com/training/camerax
