# MediaPipe 底层 Graph 迁移与统一 AAR 完整实录

> ⚠️ **已回退（2026-08-19）**：本文记录的「底层 Graph + 统一 AAR」方案已**放弃**，
> camera 换回 `tasks-vision FaceLandmarker`，testmodel 改用 **LiteRT C++ SDK（JNI）**。
> 本文仅作为**历史踩坑记录**保留（15 个坑、服务器图验证器、Graph API 陷阱仍有参考价值），
> 不再代表当前架构。当前架构见 [`交接文档.md`](./交接文档.md) 与 [`ARCHITECTURE.md`](./ARCHITECTURE.md)。

> 本文是 EyeProject（眼健康助手）从「ML Kit / MediaPipe Tasks API」迁移到「MediaPipe 底层 Graph」的完整记录（已废弃路线），
> 覆盖：技术决策、两条计算图的编译、统一 AAR 打包、App 集成、全部踩坑、以及服务器端图验证方法。
> 目标是记录底层 Graph 的完整踩坑过程，供需要自编 MediaPipe 图的场景参考。

---

## 1. 背景与目标

EyeProject 有两条 AI pipeline 需要落地：

| Pipeline | 功能 | 用到的模型 | 落地模块 |
|---|---|---|---|
| **iris_tracking** | 人脸 468 点 + 虹膜 10 点检测（替代 Face Landmarker） | face_detection / face_landmark / iris_landmark | `feature:camera` |
| **face_gaze** | 视线估计（L2CS-Net 出 yaw/pitch 角度） | face_detection_short_range + l2cs_net | `feature:testmodel` |

最初用 Maven 的 `tasks-vision 0.10.14`（Tasks API 黑盒）。目标是下沉到**底层 Graph（.pbtxt）**，
掌握完整控制权（图结构、归一化、自定义计算器），并最终把两条 pipeline 编进**同一个 AAR**。

### 关键洞察：framework 层只能存在一份

Tasks API 本质也是 graph。底层 `mediapipe_aar` 打包时会把 framework 层 Java 类
（`Graph`/`Packet`/proto）打进 AAR，而 Maven 的 `tasks-core` 也带同样的类 → D8 报 `Duplicate class`。

正确姿势不是「两个 AAR 各自带 framework」，而是「**一个 AAR 同时含两条 pipeline 的 calculator，Java 层只有一份 framework**」，
并彻底移除 Maven 的 `tasks-vision`。

---

## 2. 技术栈

| 项 | 值 |
|---|---|
| JDK | 17 |
| AGP | 9.3.0（内置 Kotlin） |
| Kotlin | 2.2.10 |
| Gradle | 9.5 |
| Hilt | 2.59.2 + KSP |
| compileSdk | 37 |
| CameraX | 1.4.1 |
| LiteRT | 2.16.1 |
| 架构 | Now-in-Android（app / core / feature + build-logic） |

---

## 3. 服务器编译环境

| 项 | 值 |
|---|---|
| 服务器 | AutoDL（Ubuntu 22.04，CPU 实例） |
| 内存 | ≥8GB（实测 2GB 会 OOM） |
| Bazel | 7.7.0 |
| gcc | **gcc-13**（host 编译必需，gcc-11/12 有 NTTP bug + 缺新指令集） |
| Python | 3.10（`HERMETIC_PYTHON_VERSION=3.10`） |
| Android SDK / NDK | `/root/autodl-tmp/Android/Sdk` + NDK r28b（clang-19） |
| 网络 | **mihomo 标准代理**（AutoDL 学术加速是 MITM → PKIX 问题） |

### 代理启动（容器重开或首次需要）

```bash
export CLASHCTL_HOME=/root/clashctl
. $CLASHCTL_HOME/scripts/cmd/clashctl.sh
clashctl on
export http_proxy=http://127.0.0.1:7890
export https_proxy=http://127.0.0.1:7890
```

---

## 4. 计算图设计

### 4.1 iris_tracking 图（官方图，未改）

直接用官方 desktop 图 `mediapipe/graphs/iris_tracking/iris_tracking_cpu.pbtxt`，编译成 **binary graph**：

```python
# mediapipe/graphs/iris_tracking/BUILD（追加）
mediapipe_binary_graph(
    name = "iris_tracking_cpu_binary_graph",
    graph = "iris_tracking_cpu.pbtxt",
    output_name = "iris_tracking_cpu.binarypb",
    deps = [":iris_tracking_cpu_deps"],   # 官方已定义的 cc_library
)
```

- 输入：`input_video`（ImageFrame）
- 输出：`face_landmarks_with_iris`（NormalizedLandmarkList = 468 脸点 + 10 虹膜点）
- App 端用 `Graph.loadBinaryGraph(byte[])` 加载 `iris_tracking_cpu.binarypb`

### 4.2 face_gaze 图（自定义，含自定义计算器）

文件：`mediapipe/graphs/face_gaze/face_gaze_cpu.pbtxt`

```
FaceDetectionShortRangeCpu → DetectionsToRectsCalculator → RectTransformationCalculator
    → ImageCroppingCalculator（按 ROI 裁剪，border_mode）
    → ImageTransformationCalculator（缩放到 448×448，STRETCH）
    → TfLiteConverterCalculator（[0,1] 归一化，zero_center:false）
    → TfLiteInferenceCalculator（l2cs_net.tflite，xnnpack）
    → GazeLogitsCalculator（自定义：拆出 yaw/pitch 两个 logits 流）
```

顶层声明（注意 tag 带 index）：

```proto
type: "FaceGazeCpu"
input_stream: "IMAGE:image"
output_stream: "FLOATS:0:gaze_yaw_logits"
output_stream: "FLOATS:1:gaze_pitch_logits"
```

### 4.3 自定义计算器 GazeLogitsCalculator

文件：`mediapipe/graphs/face_gaze/gaze_logits_calculator.cc`

**为什么需要它**：`TfLiteInferenceCalculator` 只能输出**单个** `std::vector<TfLiteTensor>` 流，
而 `TfLiteTensorsToFloatsCalculator` / `TensorsToFloatsCalculator` 都**只取第一个张量**（无索引选项）。
L2CS-Net 有两个输出（yaw/pitch logits），必须自定义计算器拆分。

```cpp
class GazeLogitsCalculator : public CalculatorBase {
 public:
  static absl::Status GetContract(CalculatorContract* cc) {
    cc->Inputs().Tag("TENSORS").Set<std::vector<TfLiteTensor>>();
    cc->Outputs().Get("FLOATS", 0).Set<std::vector<float>>();
    cc->Outputs().Get("FLOATS", 1).Set<std::vector<float>>();
    return absl::OkStatus();
  }
  absl::Status Process(CalculatorContext* cc) final {
    const auto& input_tensors =
        cc->Inputs().Tag("TENSORS").Get<std::vector<TfLiteTensor>>();
    RET_CHECK_GE(input_tensors.size(), 2);
    for (int t = 0; t < 2; ++t) {
      const TfLiteTensor* raw_tensor = &input_tensors[t];
      int num_values = 1;
      for (int i = 0; i < raw_tensor->dims->size; ++i)
        num_values *= raw_tensor->dims->data[i];
      const float* raw_floats = raw_tensor->data.f;
      auto output = std::make_unique<std::vector<float>>(
          raw_floats, raw_floats + num_values);
      cc->Outputs().Get("FLOATS", t).Add(output.release(), cc->InputTimestamp());
    }
    return absl::OkStatus();
  }
};
REGISTER_CALCULATOR(GazeLogitsCalculator);
```

BUILD 依赖（关键：`@litert//tflite:framework` 提供 `TfLiteTensor`，`alwayslink=1` 防被丢弃）：

```python
cc_library(
    name = "gaze_logits_calculator",
    srcs = ["gaze_logits_calculator.cc"],
    deps = [
        "//mediapipe/framework:calculator_framework",
        "//mediapipe/framework/port:ret_check",
        "@litert//tflite:framework",
    ],
    alwayslink = 1,
)
```

---

## 5. 统一 AAR 打包

### 5.1 eye_combined BUILD

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
        "//mediapipe/graphs/face_gaze:l2cs_net.tflite",
        "//mediapipe/graphs/face_gaze:face_gaze_cpu_graph",   # face_gaze 的 binarypb
        "//mediapipe/modules/face_detection:face_detection_short_range.tflite",
        "//mediapipe/modules/face_landmark:face_landmark.tflite",
        "//mediapipe/modules/iris_landmark:iris_landmark.tflite",
        "//mediapipe/graphs/iris_tracking:iris_tracking_cpu_binary_graph",
    ],
)
```

要点：
- `calculators` 传**两个图目标**，`mediapipe_aar` 会把两者的传递依赖（所有 calculator + subgraph 注册）一起编进 `libmediapipe_jni.so`
- `assets` 放 4 个模型 + 2 个 `.binarypb` 图

### 5.2 两处 mediapipe_aar.bzl 修改

1. **Guava 过时路径**（第 127 行）：`//java/com/google/common/base` → `@maven//:com_google_guava_guava`
2. **补 libc++_shared.so**（opencv 分支，第 139 行）：

```python
deps += select({
    "//conditions:default": [
        "//third_party:android_jni_opencv_cc_lib",
        "//third_party:libc++_shared_lib",   # ← 新增，否则 App 报 libc++_shared.so not found
    ],
    ...
})
```

> 根因：`libopencv_java4.so` 动态依赖 `libc++_shared.so`，但 `mediapipe_aar` 只打了 opencv、
> 漏了它的 STL 依赖。`libmediapipe_jni.so` 因 `linkstatic=1` 静态链接 libc++，所以不报。

### 5.3 编译命令

```bash
export ANDROID_HOME=/root/autodl-tmp/Android/Sdk
export ANDROID_NDK_HOME=/root/autodl-tmp/Android/Ndk/android-ndk-r28b
bazel build --config=android_arm64 \
  //mediapipe/examples/android/src/java/com/google/mediapipe/apps/eye_combined:eye_combined_aar
```

产物：`eye_combined_aar.aar`（约 78.8MB），jni 含：
- `libc++_shared.so`（0.87MB）
- `libmediapipe_jni.so`（102MB）
- `libopencv_java4.so`（22MB）

---

## 6. 服务器端图验证（关键：不用装真机）

`ValidatedGraphConfig` 的验证发生在 `CalculatorGraph::Initialize()`，所以在服务器上写一个最小验证器，
即可**秒级**检查图配置（流类型、tag/index、subgraph 注册），避免每次装真机逐项踩坑。

文件：`mediapipe/graphs/face_gaze/verify_graph.cc`

```cpp
int main(int argc, char** argv) {
  std::string contents;
  mediapipe::file::GetContents(argv[1], &contents);
  auto config = mediapipe::ParseTextProtoOrDie<mediapipe::CalculatorGraphConfig>(contents);
  mediapipe::CalculatorGraph graph;
  auto init_status = graph.Initialize(config, {});
  if (!init_status.ok()) {
    std::cerr << "INIT FAILED: " << init_status.message() << std::endl;
    return 1;
  }
  std::cout << "INIT OK" << std::endl;
  return 0;
}
```

BUILD：

```python
cc_binary(
    name = "face_gaze_cpu_verify",
    srcs = ["verify_graph.cc"],
    deps = [
        "//mediapipe/framework:calculator_framework",
        "//mediapipe/framework/port:file_helpers",
        "//mediapipe/framework/port:parse_text_proto",
        "//mediapipe/framework/port:status",
        ":face_gaze_cpu",
    ],
)
```

用法：

```bash
# 去掉 type 声明作为顶层图
sed '/^type:/d' mediapipe/graphs/face_gaze/face_gaze_cpu.pbtxt > /tmp/face_gaze_top.pbtxt
# host 编译（gcc-13 + 禁用新指令集）
export CC=gcc-13 CXX=g++-13
bazel build --cxxopt=-std=c++20 --host_cxxopt=-std=c++20 \
  --define=xnn_enable_avxvnniint8=false \
  --define=xnn_enable_avx512fp16=false \
  --define=xnn_enable_avx256vnnigfni=false \
  --define=xnn_enable_avx512vnnigfni=false \
  --define=xnn_enable_avx512amx=false \
  //mediapipe/graphs/face_gaze:face_gaze_cpu_verify
# 运行验证
bazel-bin/mediapipe/graphs/face_gaze/face_gaze_cpu_verify /tmp/face_gaze_top.pbtxt
# 输出 INIT OK = 图配置正确
```

> 注意：官方 `simple_run_graph_main` 依赖 `third_party/gloop`（Linux 上未配置），
> 所以自写验证器不依赖 gloop，更干净。

---

## 7. App 集成

### 7.1 本地 Maven 仓库（关键：library 模块不能用 files() 依赖本地 AAR）

`feature:camera` / `feature:testmodel` 是 **Android library 模块**，AGP 不允许用 `files("libs/xxx.aar")`
直接依赖本地 AAR（`bundleDebugAar` 报 `hasLocalAarDeps`）。规范做法是本地 Maven 仓库：

```
mediapipe-m2repo/
└── com/google/mediapipe/eye_combined/1.0.0/
    ├── eye_combined-1.0.0.aar
    └── eye_combined-1.0.0.pom   # 声明传递依赖
```

`settings.gradle.kts`：

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

### 7.2 运行时依赖清单（对齐官方 tasks-core 0.10.14 POM）

`mediapipe_aar`（半废弃打包方式）不打包 Java 传递依赖，也不生成 POM。必须在 POM + 模块里显式声明：

| 依赖 | 版本 | 缺它会报什么 |
|---|---|---|
| `com.google.flogger:flogger` | 0.6 | `NoClassDefFoundError: FluentLogger` |
| `com.google.flogger:flogger-system-backend` | 0.6 | 同上（日志后端） |
| `com.google.guava:guava` | 27.0.1-android | `NoClassDefFoundError: ListenableFuture` 等 |
| `com.google.protobuf:protobuf-javalite` | 4.35.1 | `Cannot access GeneratedMessageLite` |

> protobuf-javalite 的 Maven 版本线是 4.x（最新 4.35.1），与 Bazel 的 `protobuf 6.31.1` 是**两套版本号**，别混淆。

### 7.3 底层 Graph API 的正确用法（5 个致命坑）

```kotlin
class XxxGraphModel(context: Context) {
    // ⚠️ 坑1：loadLibrary 必须在 Graph() 构造【之前】执行！
    // 因为 Graph() 内部立刻调 nativeCreateGraph，而底层 Graph 不像 Tasks 的 SolutionBase 会自动 loadLibrary。
    init {
        System.loadLibrary("mediapipe_jni")
        System.loadLibrary("opencv_java4")
    }

    private val graph = Graph()
    private val packetCreator = AndroidPacketCreator(graph)

    init {
        // ⚠️ 坑2：asset 管理器要初始化（图内模型读取走 GetResources）
        AndroidAssetUtil.initializeNativeAssetManager(context)

        // ⚠️ 坑3：binarypb 在 assets 里，必须用字节版加载！
        // loadBinaryGraph(String) 走 POSIX open() 读文件系统，读不到 asset。
        val bytes = AndroidAssetUtil.getAssetBytes(context.assets, "xxx.binarypb")
        graph.loadBinaryGraph(bytes)   // 字节版，走 nativeLoadBinaryGraphBytes

        // ⚠️ 坑4：不要用 setGraphType 加载图！
        // setGraphType 只设置 type 过滤器，不加载任何图（native 端只存个字符串）。
        // 要加载 subgraph，正确做法是 loadBinaryGraph 加载它的 binarypb。

        graph.addPacketCallback("output_stream") { packet -> ... }
        graph.startRunningGraph()
    }

    fun close() {
        // ⚠️ 坑5：tearDown() 才真正释放 native graph（含 model/interpreter）！
        // 只调 closeAllPacketSources + waitUntilGraphDone 会泄漏 native 资源，
        // 反复重建 graph 后累积内存，最终 InterpreterBuilder 分配失败（偶发 interpreter_ 为 null）。
        try {
            graph.closeAllPacketSources()
            graph.waitUntilGraphDone()
        } finally {
            graph.tearDown()
        }
    }
}
```

---

## 8. 踩坑全记录（按遇到顺序，共 15 个）

| # | 报错 / 现象 | 根因 | 解法 |
|---|---|---|---|
| 1 | `Duplicate class Graph` | 两个 AAR 各带 framework 层 | 统一 AAR，去掉 Maven tasks-vision |
| 2 | `bundleDebugAar hasLocalAarDeps` | library 模块不能 `files()` 依赖本地 AAR | 本地 Maven 仓库 + 坐标依赖 |
| 3 | `Cannot access GeneratedMessageLite` | AAR 不打包 protobuf | 加 protobuf-javalite |
| 4 | `NoClassDefFoundError: FluentLogger` | AAR 不打包 flogger/guava | 对齐 tasks-core 依赖清单 |
| 5 | `No implementation found: nativeCreateGraph` | 缺 loadLibrary；且时机在 Graph() 之后 | loadLibrary 提到属性声明之前 |
| 6 | `dlopen failed: libc++_shared.so not found` | AAR 漏打 opencv 的 STL 依赖 | mediapipe_aar.bzl 加 libc++_shared_lib |
| 7 | `Failed to open file: iris_tracking_cpu.binarypb` | loadBinaryGraph(String) 读文件系统，不读 asset | 改用 loadBinaryGraph(byte[]) |
| 8 | `Graph is not loaded!` | setGraphType 只设 type 过滤器，不加载图 | 改 loadBinaryGraph(byte[]) 加载 binarypb |
| 9 | `tag "TENSORS" index 0 ... reassigned` | TfLiteInference 写了两个输出流 | 单输出 vector + 自定义拆 logits 计算器 |
| 10 | `ImageCroppingCalculator flags == 1` | NORM_RECT 输入和 width/height 选项冲突 | 拆成 ImageCropping(ROI) + ImageTransformation(缩放) |
| 11 | `tag "FLOATS" index 0 ... reassigned` | 顶层输出流声明没带 index | 改成 FLOATS:0 / FLOATS:1 |
| 12 | `expects vector<TfLiteTensor> but connected vector<Tensor>` | ImageToTensor(api2) 与 TfLiteInference(legacy) 类型断裂 | 换 TfLiteConverterCalculator(legacy) |
| 13 | `no such package 'third_party/gloop/base'` | simple_run_graph_main 依赖 gloop | 自写 verify_graph.cc 不依赖 gloop |
| 14 | `gcc: unrecognized -mavxvnniint8` | host 编译 gcc 缺新指令集 | CC=gcc-13 + 禁用 5 个 xnn 指令集 |
| 15 | `RET_CHECK failure ... interpreter_`（分辨率切换偶发） | close() 漏 tearDown()，native graph（model/interpreter）累积泄漏，内存紧张后 InterpreterBuilder 分配失败 | close() 的 finally 里调 graph.tearDown() |

---

## 9. 最终成果与文件清单

### 服务器端（/root/autodl-tmp/mediapipe）

- `mediapipe/graphs/face_gaze/face_gaze_cpu.pbtxt` — face_gaze 图
- `mediapipe/graphs/face_gaze/gaze_logits_calculator.cc` — 自定义拆 logits 计算器
- `mediapipe/graphs/face_gaze/verify_graph.cc` — 服务器图验证器
- `mediapipe/graphs/face_gaze/BUILD`
- `mediapipe/graphs/iris_tracking/BUILD` — 追加 iris_tracking_cpu_binary_graph
- `mediapipe/examples/android/.../eye_combined/BUILD` — 统一 AAR
- `mediapipe/java/com/google/mediapipe/mediapipe_aar.bzl` — Guava + libc++ 两处修改

### App 端（本机）

- `feature/camera/.../IrisTrackingAnalyzer.kt` — iris 图分析器
- `feature/testmodel/.../FaceGazeGraphModel.kt` — face_gaze 图模型
- `feature/testmodel/.../TestModelViewModel.kt` — USE_GRAPH=true + 异常暴露
- `gradle/libs.versions.toml` — 依赖清单
- `settings.gradle.kts` — 本地 Maven 仓库
- `mediapipe-m2repo/` — 本地 Maven 仓库（AAR + POM）

---

## 10. 当前状态与剩余 TODO

### ✅ 已完成

- iris_tracking 图（camera）跑通：人脸检测 + 虹膜点
- face_gaze 图（testmodel）跑通：L2CS-Net 推理出 yaw/pitch
- 统一 AAR（framework 层只一份）
- 服务器端图验证器

### ⏳ 剩余 TODO

1. **归一化缺口（影响精度）**：`TfLiteConverterCalculator` 只做 `[0,1]` 归一化，
   L2CS-Net 需要 **ImageNet 三通道归一化** `(px - mean)/std`（R/G/B 的 mean/std 不同：
   mean=[123.675, 116.28, 103.53]，std=[58.395, 57.12, 57.375]）。
   需自定义归一化计算器（输入侧），或确认 l2cs_net.tflite 是否内置归一化。
   **当前 face_gaze 输出的角度数值不准，但流程完整跑通。**

2. **虹膜中心索引（影响 camera 精度）**：代码里假设 RIGHT_IRIS=468、LEFT_IRIS=473，
   眼部轮廓/眼角索引（33/133/362/263 等）沿用 Face Landmarker 约定，需真机打印
   `face_landmarks_with_iris` 的点数验证 iris_tracking 图的虹膜点拼接顺序。

---

## 参考

- 官方计算器教程：`docs/framework_concepts/calculators.md`（CalculatorBase 生命周期）
- 官方参考图：`mediapipe/modules/iris_landmark/iris_landmark_cpu.pbtxt`、
  `mediapipe/graphs/object_detection/object_detection_mobile_cpu.pbtxt`（TfLiteInference 单输出 + Split 模式）
- 官方 desktop 运行器：`mediapipe/examples/desktop/simple_run_graph_main.cc` / `demo_run_graph_main.cc`
- 前置文档：`docs/MediaPipe自定义图编译实录.md`（图编译 + host 编译踩坑）、
  `docs/MediaPipe-AAR编译与App集成.md`（AAR 打包 + Duplicate class 分析）
