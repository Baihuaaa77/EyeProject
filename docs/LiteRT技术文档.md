# LiteRT 在 Android 端使用技术文档

> LiteRT = 原 TensorFlow Lite（2024 年更名，归属 Google AI Edge 生态）。本文覆盖 LiteRT 在 Android
> 端的完整用法：依赖、Interpreter API、Delegate、量化、Task Library、Support Library、CameraX 集成、
> 性能优化与常见坑，并对比 MediaPipe / ML Kit 定位。

## 1. LiteRT 是什么

LiteRT 是 Google 的**端侧（on-device）机器学习推理引擎**，专为移动端/嵌入式低延迟、低功耗、小体积设计。
它跑 `.tflite` 格式模型（`.lite` 为新扩展名，向下兼容 `.tflite`）。

- **定位**：LiteRT 是「**底层推理引擎**」，只负责「给模型喂张量 → 跑推理 → 取回张量」。
  它**不做**前处理/后处理/检测解码，这些要么你自己写，要么用 Task Library（高层）或 Support Library（图像处理）。
- **归属**：Google AI Edge（ai.google.dev/edge），与 MediaPipe、ML Kit 同属端侧 AI 栈。
- **历史**：TensorFlow Lite → 2024 年更名为 LiteRT；同时出现新的 v2 分支（Java 包名从
  `org.tensorflow.lite.*` 迁到 `com.google.ai.edge.litert.*`）。

### 1.1 LiteRT / MediaPipe / ML Kit 三者关系

| | LiteRT | MediaPipe Tasks | ML Kit |
|---|---|---|---|
| 层级 | 底层推理引擎 | 中层（模型+前后处理打包成 Task） | 高层（Google 托管模型，Play Services 分发） |
| 你控制什么 | 张量输入输出 | 结构化结果（关键点/框/分类） | 只调 API，模型云端下发 |
| 自定义模型 | ✅ 任意 `.tflite` | ⚠️ Tasks 只能用官方 `.task`；底层 Graph 可自定义（C++/Bazel） | ❌ 固定 |
| 前/后处理 | 自己写 | 内置 | 内置 |
| 适合 | 跑自己的模型（如 L2CS-Net 注视）、需要完全控制推理 | 官方人脸/姿态/手势/分割等能力 | 简单 OCR/条码/人脸检测等 |

> **与本项目的关系**：EyeProject 用 MediaPipe Face Landmarker 做人脸/眼部关键点；若要接**注视估计**
> （L2CS-Net 等非官方模型），可选两条路：① 用 MediaPipe 底层 Graph 把 L2CS 拼进 pipeline（见
> `docs/MediaPipe技术文档.md`）；② 用 **LiteRT 直接跑 L2CS 的 `.tflite`**，把 MediaPipe 裁出的人脸图喂给它。
> 本文即讲第 ② 条路的 LiteRT 用法。

## 2. 模型格式与转换

LiteRT 模型是 `.tflite`（FlatBuffers 格式）或新扩展名 `.lite`。转换来源：

| 来源 | 工具 | 说明 |
|---|---|---|
| TensorFlow / Keras | `tf.lite.TFLiteConverter` | 官方主线 |
| PyTorch | `ai-edge-torch`（PyTorch → TFLite） | 2024 起官方推荐，替代 ONNX 绕路 |
| JAX | `ai-edge-torch` / JAX 导出 | 较新 |
| ONNX | `onnx2tf` / 第三方 | 需验证算子支持 |

```python
import tensorflow as tf
converter = tf.lite.TFLiteConverter.from_saved_model("saved_model_dir")
# 可选：动态范围量化 / FP16 / INT8（见第 6 节）
converter.optimizations = [tf.lite.Optimize.DEFAULT]
tflite_model = converter.convert()
open("model.tflite", "wb").write(tflite_model)
```

把 `.tflite` 放到 `app/src/main/assets/`，运行时用 `loadModelFile` 读取。

## 3. 依赖（Maven）

```kotlin
dependencies {
    // 核心解释器（Interpreter API）
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    // GPU 委托（可选，体积大）
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    // Support Library（TensorImage/TensorBuffer/ImageProcessor）
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    // Task Library（高层：分类/检测/分割等）
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")
}
```

**Google Play Services 版（减小 APK 体积）**：用系统预装的 TFLite 运行时，约省几 MB：

```kotlin
dependencies {
    implementation("com.google.android.gms:play-services-tflite-java:16.2.0")
    implementation("com.google.android.gms:play-services-tflite-support:16.2.0")
    // 用 org.tensorflow.lite 的 API，但运行时走 Play Services
}
```

> 版本以 [Maven Central](https://central.sonatype.com/artifact/org.tensorflow/tensorflow-lite) 最新为准。
> `org.tensorflow:tensorflow-lite` 版本号对齐 TensorFlow 2.x（如 2.16.1、2.18.0）；support/task 库单独版本号（0.4.x）。

**NDK ABI**：默认包含 armeabi-v7a + arm64-v8a + x86 + x86_64，可裁剪：

```kotlin
android { defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") } } }
```

## 4. Interpreter API（核心）

### 4.1 加载模型

```kotlin
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

fun loadModelFile(context: Context, path: String): MappedByteBuffer {
    val fd = context.assets.openFd(path)
    return FileInputStream(fd.fileDescriptor).channel.map(
        FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
    )
}

val interpreter = Interpreter(loadModelFile(context, "model.tflite"))
```

### 4.2 基本推理（run）

输入输出用多维数组（形状须与模型一致）：

```kotlin
// 例：输入 224x224x3 float，输出 1000 分类 logits
val input = Array(1) { Array(224) { Array(224) { FloatArray(3) } } }  // 填像素
val output = Array(1) { FloatArray(1000) }
interpreter.run(input, output)
```

### 4.3 用 Options 配置线程 + Delegate

```kotlin
val options = Interpreter.Options().apply {
    setNumThreads(4)                 // CPU 线程数
    // setUseNNAPI(true)             // NNAPI（简单开关）
    addDelegate(GpuDelegate())       // GPU 委托（见第 5 节）
}
val interpreter = Interpreter(modelBuffer, options)
```

### 4.4 用 `InterpreterApi`（支持 Play Services / 委托选择的统一接口）

```kotlin
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.InterpreterFactory

val options = InterpreterApi.Options().apply {
    setNumThreads(4)
    addDelegateFactory { GpuDelegate() }
}
val interpreter = InterpreterApi.create(
    options, InterpreterFactory().create(options, modelBuffer)
)
```

### 4.5 用 `TensorBuffer` + `Tensor`（Support Library，类型安全）

```kotlin
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

// 输入
val input = TensorBuffer.createFixedSize(intArrayOf(1, 224, 224, 3), DataType.FLOAT32)
val tensor = Tensor.fromTensorBuffer(input)
// 输出
val output = TensorBuffer.createFixedSize(intArrayOf(1, 1000), DataType.FLOAT32)
interpreter.runForMultipleInputsOutputs(
    arrayOf<Any>(tensor.input),
    mapOf(0 to output)
)
```

### 4.6 查询模型输入输出形状

```kotlin
interpreter.getInputTensor(0).shape()      // intArrayOf(1, 224, 224, 3)
interpreter.getOutputTensor(0).shape()     // intArrayOf(1, 1000)
interpreter.getInputTensor(0).dataType()   // DataType.FLOAT32 / UINT8 / INT8
```

### 4.7 释放

```kotlin
interpreter.close()  // 或 try-with-resources / use {}
```

## 5. 委托（Delegate）

| Delegate | 依赖 | 硬件 | 说明 |
|---|---|---|---|
| **XNNPack**（默认 CPU） | 内置 | CPU | SIMD 多线程，默认启用 |
| **GPU** | `tensorflow-lite-gpu` | OpenGL ES / OpenCL | 卷积类模型提速明显 |
| **NNAPI** | 内置 | NPU/DSP（厂商） | 走 Android Neural Networks API，兼容性因机型而异 |
| Hexagon / 其他 | 厂商 | DSP | 需厂商 SDK |

```kotlin
// GPU
implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
val delegate = GpuDelegate()
val options = Interpreter.Options().addDelegate(delegate)

// NNAPI（两种方式）
options.setUseNNAPI(true)                       // 简单开关
options.addDelegate(NnApiDelegate())            // 精细控制

// XNNPack 线程数
options.setNumThreads(4)
```

> GPU delegate 首次初始化较慢（需建 OpenCL/GL 上下文），对**小模型**可能反而更慢；大模型（ResNet 级）提速明显。

## 6. 量化（减小体积 + 提速）

| 类型 | 权重 | 激活 | 体积 | 精度损失 | 适用 |
|---|---|---|---|---|---|
| FP32（基线） | float32 | float32 | 1× | 无 | 基准 |
| FP16 | float16 | float16/float32 | ~0.5× | 极小 | GPU delegate |
| 动态范围量化 | int8 | float32 | ~0.25× | 小 | CPU 通用 |
| 全整型 INT8 | int8 | int8 | ~0.25× | 小 | CPU + NNAPI/NPU，需标定数据 |

```python
# 动态范围量化
converter.optimizations = [tf.lite.Optimize.DEFAULT]

# FP16
converter.optimizations = [tf.lite.Optimize.DEFAULT]
converter.target_spec.supported_types = [tf.float16]

# 全整型 INT8（需 representative_dataset 标定）
converter.optimizations = [tf.lite.Optimize.DEFAULT]
converter.representative_dataset = representative_data_gen
converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
```

> 量化后 `getOutputTensor(0).dataType()` 可能是 `UINT8/INT8`，输出需反量化（乘 scale + 加 zero_point），
> Task Library / Support Library 已自动处理。

## 7. Task Library（高层，免写前后处理）

Task Library 把「模型 + 前后处理」打包成现成 Task，与 MediaPipe Tasks 定位类似。

```kotlin
// 图像分类
val options = ImageClassifier.ImageClassifierOptions.builder()
    .setMaxResults(3)
    .setScoreThreshold(0.5f)
    .build()
val classifier = ImageClassifier.createFromFileAndOptions(context, "model.tflite", options)
val results = classifier.classify(tensorImage)   // List<Classifications>
```

| Task | 类 |
|---|---|
| 图像分类 | `org.tensorflow.lite.task.vision.classifier.ImageClassifier` |
| 目标检测 | `...detector.ObjectDetector` |
| 图像分割 | `...segmenter.ImageSegmenter` |
| 文本 | `org.tensorflow.lite.task.text.*`（NLClassifier、BertQuestionAnswerer 等） |

## 8. Support Library（图像/张量处理）

```kotlin
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.NormalizeOp
import org.tensorflow.lite.support.common.ops.QuantizeOp

val imageProcessor = ImageProcessor.Builder()
    .add(ResizeOp(224, 224, ResizeOp.ResizeMethod.BILINEAR))
    .add(NormalizeOp(0f, 255f))          // 归一化到 [0,1]
    .build()

val tensorImage = TensorImage.fromBitmap(bitmap)
val processed = imageProcessor.process(tensorImage)
```

## 9. CameraX 集成示例（完整闭环）

```kotlin
// 1) 配置 ImageAnalysis 输出 YUV_420_888（或 RGBA_8888）
val analysis = ImageAnalysis.Builder()
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .build()

analysis.setAnalyzer(executor) { imageProxy ->
    // 2) ImageProxy → Bitmap（用 Support Library 或自写 YUV→RGB）
    val bitmap = imageProxy.toBitmap()          // 或 yuv2rgb(imageProxy)
    val tensorImage = TensorImage.fromBitmap(bitmap)
    val processed = imageProcessor.process(tensorImage)

    // 3) 推理
    val output = TensorBuffer.createFixedSize(intArrayOf(1, 1000), DataType.FLOAT32)
    interpreter.run(processed.tensorBuffer.buffer, output.buffer)

    // 4) 关闭，避免丢帧
    imageProxy.close()
}
```

> `imageProxy.toBitmap()` 不是 CameraX 内置 API，需自己写 YUV_420_888 → ARGB_8888 转换（或用 Support Library 的
> `TensorImage.fromImage` / 第三方 `YuvToRgbConverter`）。注意：LiteRT 本身**不要求** RGBA_8888（那是
> MediaPipe `MediaImageBuilder` 的限制），LiteRT 喂 `TensorBuffer` 即可，格式由你自己决定。

## 10. 性能优化

1. **选对 delegate**：大卷积模型上 GPU，小模型 CPU（XNNPack 多线程）可能更快。
2. **量化**：INT8 全整型对 CPU/NPU 提速明显。
3. **复用 `Interpreter`**：别每帧新建，初始化开销大。
4. **减少拷贝**：`MappedByteBuffer` 映射模型、复用输入输出 buffer。
5. **控制输入分辨率**：CameraX 分析分辨率降到模型需要的尺寸（如 224×224），别用全分辨率。
6. **`Interpreter` 线程安全**：单个 Interpreter 非线程安全，多线程请用多实例或 `InterpreterApi` + `runSignature`。
7. **批处理**：若模型支持，一次跑多帧。

## 11. 常见坑

1. **输入形状不匹配**：`run` 前确认数组形状与 `getInputTensor(0).shape()` 一致，否则 `IllegalArgumentException`。
2. **量化模型输出类型**：INT8 模型输出是 `UINT8/INT8`，别直接用 `FloatArray` 接。
3. **GPU delegate 首次慢 / 崩溃**：部分机型 OpenCL 不可用，需 `try-catch` 回退 CPU。
4. **`org.tensorflow.lite.Interpreter` 在 LiteRT v2 缺失**：v2 分支把包改名到 `com.google.ai.edge.litert.*`（见第 12 节）。
5. **16 KB page size**：2025 年起新设备要求 16 KB 页对齐，老版本原生库可能崩溃，需升级到支持 16 KB 的版本。
6. **模型算子不支持**：转换时报 `TOCO failed` / 运行时报「op not found」，用 `Select TF ops` 或换实现。
7. **线程数设太高**：`setNumThreads` 超过核数无益，反而争抢。
8. **ABI 不匹配**：`UnsatisfiedLinkError`，检查 `abiFilters` 与 `.so`。

## 12. LiteRT v2 迁移（包名变更）

2024 年起 LiteRT 出现 v2 主线，Java/Kotlin 包从 `org.tensorflow.lite.*` 迁到 `com.google.ai.edge.litert.*`：

| 旧（v1.x，stable） | 新（v2） |
|---|---|
| `org.tensorflow.lite.Interpreter` | `com.google.ai.edge.litert.Interpreter` |
| `org.tensorflow.lite.Interpreter.Options` | `com.google.ai.edge.litert.Interpreter.Options` |
| `org.tensorflow.lite.gpu.GpuDelegate` | `com.google.ai.edge.litert.gpu.GpuDelegate` |
| Maven `org.tensorflow:tensorflow-lite:2.x` | 新版坐标（以官方迁移指南为准） |

```kotlin
// 新 v2 用法（示意，以官方为准）
import com.google.ai.edge.litert.Interpreter
val interpreter = Interpreter(modelBuffer, Interpreter.Options().setNumThreads(4))
```

> **建议**：生产项目现阶段仍用 stable 的 `org.tensorflow:tensorflow-lite:2.x` + `org.tensorflow.lite.*`；
> 迁移到 v2 前务必读官方 [Migrate to LiteRT](https://developers.google.com/edge/litert/migration) 迁移指南。

## 13. 官方文档 / 出处

- LiteRT 总览：https://ai.google.dev/edge/litert
- LiteRT on Android：https://developer.android.com/ai/generative-ai/litert（旧 tf/lite）
- 构建 LiteRT for Android：https://ai.google.dev/edge/litert/build/android
- Interpreter API：https://developers.google.com/edge/litert/android/inference
- GPU 委托：https://developers.google.com/edge/litert/android/gpu
- NNAPI：https://developers.google.com/edge/litert/android/nnapi
- 模型转换：https://ai.google.dev/edge/litert/models
- 量化：https://ai.google.dev/edge/litert/models/post_training_quantization
- Task Library：https://ai.google.dev/edge/litert/libraries/tasks
- Support Library：https://ai.google.dev/edge/litert/libraries/support
- 迁移指南（TF Lite → LiteRT）：https://developers.google.com/edge/litert/migration
- LiteRT 仓库：https://github.com/google-ai-edge/LiteRT
- Maven（tensorflow-lite）：https://central.sonatype.com/artifact/org.tensorflow/tensorflow-lite
