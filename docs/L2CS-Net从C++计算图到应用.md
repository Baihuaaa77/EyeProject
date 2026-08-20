# 手把手：从 C++ 计算图到可运行 App —— 以 L2CS-Net（注视估计）为例

> ⚠️ **已回退（2026-08-19）**：本文记录的「MediaPipe 计算图 + AAR」路线已放弃，
> testmodel 现改用 **LiteRT C++ SDK（JNI）**（见 [`交接文档.md`](./交接文档.md)）。本文仅作历史参考。

> 目标：带你走完「在 C++ 层构建 MediaPipe 计算图 → 用 Bazel 编译成 AAR → 集成进 Android App →
> 在页面上实时测试 L2CS-Net 注视估计」的完整链路。每一步都有命令、代码和解释。

## 0. 先理解：为什么是「C++ 计算图」

MediaPipe 分两层（详见 `docs/MediaPipe技术文档.md`）：

- **Tasks API**：高层黑盒，只能跑官方 `.task`，改不了 pipeline 内部。
- **Framework/Graph**：底层 C++ 计算图，用 `.pbtxt` 描述「节点 + 边」，可任意串联模型/自定义前后处理。

L2CS-Net 是**第三方注视估计模型**（非 MediaPipe 官方），要把它和「人脸检测 → 裁剪」串成**一条端到端 pipeline**，
只能走 Framework/Graph，最终编译成 AAR 给 App 用。

**关键认知**：90% 的自定义 graph **不需要写 C++ 代码**——用 MediaPipe 内置的 Calculator 拼 `.pbtxt` 即可。
只有当你需要**自定义前后处理**（内置节点不满足）时才写 C++ Calculator。本文两者都讲，但主线用内置节点。

## 1. 前置准备

```bash
# 1) 安装 Bazel（MediaPipe 官方要求 bazelisk 或特定 Bazel 版本，见 mediapipe 仓库 WORKSPACE）
#    推荐用 bazelisk：https://github.com/bazelbuild/bazelisk

# 2) 克隆 MediaPipe 源码（google-ai-edge 分支）
git clone https://github.com/google-ai-edge/mediapipe.git
cd mediapipe

# 3) Android SDK/NDK：设置 ANDROID_HOME / ANDROID_NDK_HOME（mediapipe 的 WORKSPACE 会读）
```

> 注意：Bazel 构建 MediaPipe 对版本很敏感，务必用仓库里 `.bazelversion` 指定的版本（bazelisk 会自动切换）。

## 2. 准备 L2CS-Net 的 `.tflite` 模型

L2CS-Net（[arXiv:2203.03339](https://arxiv.org/abs/2203.03339)）输出 **yaw / pitch 两个 90-bin 分类头**。
先把 PyTorch 权重转成 TFLite：

```python
# 用官方 ai-edge-torch 把 PyTorch 转 TFLite（2024 起官方推荐，免 ONNX 绕路）
import ai_edge_torch
import torch

model = load_l2cs_net()                    # 你的 L2CS-Net（ResNet-50 主干 + 双分类头）
model.eval()
edge_model = ai_edge_torch.convert(model, sample_args=(torch.randn(1, 3, 224, 224),))
edge_model.export("l2cs_net.tflite")
```

转出来后确认输入输出：

```python
import tensorflow as tf
i = tf.lite.Interpreter("l2cs_net.tflite")
i.allocate_tensors()
print(i.get_input_details())   # 期望 [1,224,224,3] float32
print(i.get_output_details())  # 期望两个 [1,90]（yaw / pitch 各一个 90-bin 分类头）
```

> L2CS-Net 有多个开源实现，输出头数量可能不同（有的用单头 concat 成 [1,180]）。**以你的模型实际输出为准**，
> 下面 `.pbtxt` 里 `TensorsToFloatsCalculator` 要接的流名要跟着改。

## 3. 写 `.pbtxt` 计算图

目标 pipeline：`人脸检测(BlazeFace) → 人脸矩形 → 裁剪 224×224 → L2CS-Net → yaw/pitch`。

```pbtxt
# mediapipe/graphs/face_gaze/face_gaze_cpu.pbtxt
input_stream: "IMAGE:image"

# ===== 1) 人脸检测（BlazeFace short-range，128×128）=====
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

# ===== 2) 检测框 → 归一化矩形（人脸 ROI，含旋转校正）=====
node {
  calculator: "DetectionToRectCalculator"
  input_stream: "DETECTION:detection"
  input_stream: "IMAGE_SIZE:image_size"
  output_stream: "NORM_RECT:face_rect"
}

# ===== 3) 裁剪人脸到 224×224 =====
node {
  calculator: "ImageCroppingCalculator"
  input_stream: "IMAGE:image"
  input_stream: "NORM_RECT:face_rect"
  output_stream: "IMAGE:face_crop"
  options {
    [mediapipe.ImageCroppingCalculatorOptions.ext] { width: 224 height: 224 }
  }
}

# ===== 4) L2CS-Net 注视估计 =====
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
# 把两个 90-bin 分类头张量转成 float 数组，方便 Kotlin 端读 [yaw_logits..., pitch_logits...]
node {
  calculator: "TensorsToFloatsCalculator"
  input_stream: "TENSORS:gaze_yaw"
  input_stream: "TENSORS:gaze_pitch"
  output_stream: "FLOATS:gaze_logits"
}

output_stream: "FLOATS:gaze_logits"
```

**多脸处理**：上面的 `DetectionToRectCalculator` 只吃单张脸。若要支持多脸，需在它外面包一层
`BeginLoopCalculator`/`EndLoopCalculator`（`ITERABLE`→`ITEM`→逐张处理→`ITERABLE`），这是官方
`face_landmark_cpu.pbtxt` 的做法。教学示例先按单脸写。

## 4. 什么时候才需要写 C++ Calculator

内置节点能覆盖「图像→张量、跑模型、张量→框/关键点/float、裁剪」等绝大多数需求。只有当你需要：

- 自定义**后处理**（如把 90-bin softmax 期望算成角度，而不只是取 argmax）；
- 自定义**前处理**（特殊归一化、颜色空间、多输入拼接）；
- 读**非标准输出**（自定义张量解码）；

才写 C++ Calculator。下面是「把 yaw/pitch 两个 90-bin logits 用 softmax 期望解码成连续角度」的 C++ 节点示例：

```cpp
// gaze_angles_calculator.cc
#include <cmath>
#include <vector>
#include "mediapipe/framework/calculator_framework.h"
#include "mediapipe/framework/port/status.h"

namespace mediapipe {

// 输入：两个 float 流（yaw_logits[90]、pitch_logits[90]）
// 输出：一个 float 流 [yaw_deg, pitch_deg]
class GazeAnglesCalculator : public CalculatorBase {
 public:
  static absl::Status GetContract(CalculatorContract* cc) {
    cc->Inputs().Tag("YAW").Set<std::vector<float>>();
    cc->Inputs().Tag("PITCH").Set<std::vector<float>>();
    cc->Outputs().Tag("ANGLES").Set<std::vector<float>>();
    return absl::OkStatus();
  }

  absl::Status Process(CalculatorContext* cc) override {
    const auto& yaw = cc->Inputs().Tag("YAW").Get<std::vector<float>>();
    const auto& pitch = cc->Inputs().Tag("PITCH").Get<std::vector<float>>();

    // 90-bin：yaw ∈ [-180,180] 每 bin 4°；pitch ∈ [-90,90] 每 bin 2°
    float yaw_deg = SoftMaxExpectation(yaw, 90, -180.0f, 4.0f);
    float pitch_deg = SoftMaxExpectation(pitch, 90, -90.0f, 2.0f);

    auto out = absl::make_unique<std::vector<float>>(std::vector<float>{yaw_deg, pitch_deg});
    cc->Outputs().Tag("ANGLES").Add(out.release(), cc->InputTimestamp());
    return absl::OkStatus();
  }

 private:
  static float SoftMaxExpectation(const std::vector<float>& logits, int n,
                                  float start, float step) {
    float max = *std::max_element(logits.begin(), logits.end());
    float sum = 0.0f, acc = 0.0f;
    for (int i = 0; i < n; ++i) {
      float e = std::exp(logits[i] - max);
      sum += e;
      acc += e * (start + i * step);
    }
    return acc / sum;
  }
};
REGISTER_CALCULATOR(GazeAnglesCalculator);

}  // namespace mediapipe
```

对应 `.pbtxt` 把最后的 `TensorsToFloatsCalculator` 换成：

```pbtxt
node {
  calculator: "GazeAnglesCalculator"
  input_stream: "YAW:gaze_yaw_logits"
  input_stream: "PITCH:gaze_pitch_logits"
  output_stream: "ANGLES:gaze_angles"
}
```

> 注意：`TfLiteInferenceCalculator` 输出的 `TENSORS:gaze_yaw` 是张量流，要喂给自定义 calculator 需先
> `TensorsToFloatsCalculator` 转成 float 流，再接 `GazeAnglesCalculator`。上面的 C++ 输入就是 float 流。

## 5. 写 BUILD 文件 + 编译 AAR

### 5.1 图的 BUILD（.pbtxt → .binarypb + 声明 calculator 依赖）

```python
# mediapipe/graphs/face_gaze/BUILD
load("//mediapipe/framework:mediapipe_graph.bzl", "mediapipe_binary_graph")

cc_library(
    name = "mobile_calculators",
    deps = [
        "//mediapipe/calculators/image:image_to_tensor_calculator",
        "//mediapipe/calculators/image:image_cropping_calculator",
        "//mediapipe/calculators/image:recolor_calculator",
        "//mediapipe/calculators/tensor:tensors_to_detections_calculator",
        "//mediapipe/calculators/tensor:tensors_to_floats_calculator",
        "//mediapipe/calculators/tflite:tflite_inference_calculator",
        "//mediapipe/calculators/util:detection_to_rect_calculator",
        # 若写了自定义 calculator：
        "//mediapipe/graphs/face_gaze:gaze_angles_calculator",
    ],
)

cc_library(
    name = "gaze_angles_calculator",
    srcs = ["gaze_angles_calculator.cc"],
    deps = ["//mediapipe/framework:calculator_framework"],
)

mediapipe_binary_graph(
    name = "face_gaze_mobile_cpu",
    graph = "face_gaze_cpu.pbtxt",
    output_name = "face_gaze_mobile_cpu.binarypb",
    deps = [":mobile_calculators"],
)
```

### 5.2 AAR 的 BUILD（打包 Java SDK + native 库 + 图 + 模型）

```python
# mediapipe/examples/android/src/java/com/google/mediapipe/apps/facegaze/BUILD
load("//mediapipe/java/com/google/mediapipe:mediapipe_aar.bzl", "mediapipe_aar")

mediapipe_aar(
    name = "face_gaze_aar",
    calculators = ["//mediapipe/graphs/face_gaze:mobile_calculators"],
    assets = [
        "//mediapipe/graphs/face_gaze:face_gaze_mobile_cpu.binarypb",
        "//mediapipe/modules/face_detection:face_detection_short_range.tflite",
        "//mediapipe/graphs/face_gaze:l2cs_net.tflite",   # 自备模型
    ],
    assets_dir = "",
)
```

### 5.3 Bazel 编译

```bash
# 单 ABI（arm64，体积小）
bazel build -c opt --config=android_arm64 \
  //mediapipe/examples/android/src/java/com/google/mediapipe/apps/facegaze:face_gaze_aar

# 多 ABI
bazel build -c opt --fat_apk_cpu=arm64-v8a,armeabi-v7a \
  //mediapipe/examples/android/src/java/com/google/mediapipe/apps/facegaze:face_gaze_aar
```

产物：`bazel-bin/.../facegaze/face_gaze_aar.aar`。

## 6. 集成进 Android App

```gradle
// app/build.gradle.kts
android {
    defaultConfig { ndk { abiFilters += listOf("arm64-v8a") } }  // 与 AAR ABI 对齐
}
dependencies {
    implementation(files("libs/face_gaze_aar.aar"))
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
}
```

把 `.aar` 放到 `app/libs/`。

## 7. Kotlin 端调用（Graph + Packet）

```kotlin
import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.Graph
import com.google.mediapipe.framework.Packet

class FaceGazeEngine(context: Context) {
    private val graph: Graph = Graph.create(
        context,
        "face_gaze_mobile_cpu.binarypb",  // assets 中的图
        "image",                          // 顶层 input_stream
        "gaze_logits"                     // 顶层输出流之一
    ) { packet -> onGaze(packet) }

    init {
        graph.addPacketCallback("gaze_logits") { onGaze(it) }
        graph.startRunningGraph()
    }

    fun process(bitmap: Bitmap, timestampUs: Long) {
        val packet = Packet.createRgbaImageFrame(bitmap, timestampUs)  // 必须 ARGB_8888
        graph.addPacketToInputStream("image", packet, timestampUs)
    }

    private fun onGaze(packet: Packet) {
        val floats = packet.getFloatArray()   // [yaw_logits(90), pitch_logits(90)] 或 [yaw,pitch]（若接了自定义解码）
        // 若是 90-bin logits，这里做 softmax 期望得到角度
    }

    fun close() {
        graph.closeAllPacketSources()
        graph.waitUntilGraphDone()
    }
}
```

## 8. 在 testmodel 页面测试

本项目已新建 `feature:testmodel` 模块 + `TestModelScreen`（入口在 HomeScreen 顶部加了一个按钮）。
它封装了一个 `GazeModel` 抽象，默认实现用 **LiteRT 直接跑 L2CS-Net 的 `.tflite`**（不依赖 Bazel 编译 AAR，
适合快速验证模型本身）；若你已经按本文编译出了 AAR，可再实现一个 `GraphGazeModel` 换成底层图。

页面功能：

- 显示模型输入/输出形状（从 `.tflite` 读出来）；
- 摄像头预览实时跑推理，输出 yaw/pitch 角度 + 每帧延迟 + FPS；
- 模型文件缺失时给出「把 l2cs_net.tflite 放到 assets」的提示。

> 两条落地路线对比：**LiteRT 路线**（本文 testmodel 默认实现）快、免 Bazel，适合验证模型精度；
> **MediaPipe Graph 路线**（本文第 3~7 步）能零拷贝串联检测+关键点+注视，适合最终产品化。
