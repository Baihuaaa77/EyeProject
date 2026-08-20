# MediaPipe 自定义图编译实录 —— 从写 .pbtxt 到 BUILD SUCCESSFUL

> ⚠️ **已回退（2026-08-19）**：本文记录的图编译方案已放弃，仅作历史参考（新版 MediaPipe API 差异、编译踩坑仍有价值）。
> 当前架构见 [`交接文档.md`](./交接文档.md)。

> 本文记录 EyeProject 从「学习 MediaPipe 计算图」到「在 Linux 服务器上把自定义图编译通过」的完整过程，
> 重点是**新版 MediaPipe（google-ai-edge）与旧文档的 API 差异**，以及**编译环境踩坑全记录**。
> 目标是让后来者照着这份文档，能在一天内复现「写图 → 编译通过」。

## 1. 背景与目标

EyeProject 的「注视估计」有两条落地路线：

| 路线 | 实现 | 状态 |
|---|---|---|
| **LiteRT 路线** | `feature:testmodel` 直接加载 `l2cs_net.tflite`，Kotlin 端喂图/解码 | ✅ 已跑通，能出 yaw/pitch 角度 |
| **C++ 图路线** | MediaPipe 底层计算图，C++ 层串「检测→裁剪→推理→输出」 | ✅ 图编译通过（本文记录） |

C++ 图路线的价值：**零拷贝、一条 pipeline 跑完、不用在 Kotlin 端手工做人脸检测和预处理**，是「产品化」的正路。

本次目标是**只编译 .pbtxt 图本身**（纯内置节点，不写自定义 C++ 节点），验证图的正确性。

## 2. MediaPipe 图的核心概念

| 概念 | 类比 | 说明 |
|---|---|---|
| **Graph（图）** | 流水线图纸 | 有向无环图（DAG），描述「哪些节点、怎么连线」 |
| **Calculator（节点）** | 工位 | C++ 类，`GetContract` 声明接口、`Process` 干活 |
| **Stream（流）** | 传送带 | 有向数据流，靠「流名」接线 |
| **Packet（数据包）** | 传送带上的零件 | 数据单元，带时间戳 |

图用 `.pbtxt`（protobuf 文本）描述，核心规则：**两个节点流名一致就连起来**，类型不匹配要加转换节点。

## 3. 新版 MediaPipe 的 API 差异（⚠️ 最重要的实战发现）

文档 `L2CS-Net从C++计算图到应用.md` 基于**旧版 MediaPipe**，而当前仓库是**新版 google-ai-edge**，API 有根本性变化：

| 旧版文档 | 新版 google-ai-edge |
|---|---|
| `DetectionToRectCalculator`（单数） | **已移除**，用 `DetectionsToRectsCalculator`（复数，可直接吃 `DETECTIONS` vector，取第一个） |
| `TfLiteInferenceCalculator` + `model_path` | 仍在用（参考 `iris_landmark_cpu.pbtxt`），但官方主推 `InferenceCalculator` + `MODEL` side packet |
| `delegate { gpu { use_advanced_gpu_api: true } }` | `delegate { xnnpack {} }`（CPU）/ `delegate { gpu {} }`（GPU） |
| `mediapipe_binary_graph` | 以 `mediapipe_simple_subgraph` 为主 |
| `TensorsToFloatsCalculator` 多输入多输出 | 新版是「单输入单输出」（每个张量转成一个 float 流） |
| 人脸检测手写 pipeline | 直接用官方子图 `FaceDetectionShortRangeCpu` |

**关键结论**：旧文档的图在新版上基本无法直接编译，必须按新版 API 重写。

## 4. 图的设计（纯内置节点版）

文件：`mediapipe/graphs/face_gaze/face_gaze_cpu.pbtxt`

设计：**不写自定义 C++ 节点**，只用内置节点拼图，输出 L2CS-Net 的两个 90-bin logits，softmax 解码留给 Kotlin 端。

```
人脸检测(FaceDetectionShortRangeCpu) → 图像尺寸(ImagePropertiesCalculator)
        ↓ detections + image_size
检测→ROI(DetectionsToRectsCalculator) → 扩展ROI(RectTransformationCalculator)
        ↓ face_roi + image
裁剪448×448(ImageCroppingCalculator) → 转张量(ImageToTensorCalculator)
        ↓ gaze_input
L2CS-Net推理(TfLiteInferenceCalculator) → 两个90-bin张量
        ↓
张量转float(TensorsToFloatsCalculator ×2) → gaze_yaw_logits / gaze_pitch_logits
```

关键尺寸：**L2CS-Net 输入是 448×448**（不是文档写的 224×224）。

完整图见仓库 `mediapipe/graphs/face_gaze/face_gaze_cpu.pbtxt`。

## 5. BUILD 文件

文件：`mediapipe/graphs/face_gaze/BUILD`

```python
load("//mediapipe/framework/tool:mediapipe_graph.bzl", "mediapipe_simple_subgraph")

package(default_visibility = ["//visibility:public"])

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
```

> ⚠️ 注意：`MODULE.bazel` 里删除了 3 行 iOS 依赖（`apple_support`/`rules_apple`/`rules_swift`），
> 因为 `rules_swift` 在 Linux/Windows 上会触发 swift 工具链配置失败。编 iOS 时需加回。

## 6. 编译环境（Linux 服务器）

| 项 | 值 |
|---|---|
| 服务器 | AutoDL（Ubuntu 22.04，CPU 实例） |
| 内存 | **≥8GB**（实测 2GB 会 OOM，62GB 顺畅） |
| JDK | 17（Bazel 用内嵌 JDK 21） |
| Python | 3.10（匹配 `requirements_lock_3_10.txt`） |
| Bazel | 7.7.0（`.bazelversion` 指定） |
| **gcc** | **gcc-13**（关键！gcc 11/12 都有 NTTP bug） |
| 网络 | mihomo 代理（标准代理，非 MITM） |

## 7. 完整踩坑记录

按遇到顺序：

| # | 坑 | 现象 | 解法 |
|---|---|---|---|
| 1 | Windows 上 Bazel 找不到 python | `System Python was not found`（执行的是 `python3` 命令，Windows 没有） | `--repo_env=HERMETIC_PYTHON_VERSION=3.10` |
| 2 | rules_swift 找 swiftc.exe | `No 'swiftc.exe' executable found in Path` | 删 MODULE.bazel 的 iOS 依赖 |
| 3 | LLVM symlink 权限 | `WinError 1314` | （换 Linux 后消失） |
| 4 | **AutoDL 学术加速是 MITM** | Bazel 下载 bcr.bazel.build 报 `PKIX path building failed` | **换 mihomo 标准代理**（不 MITM，Bazel 看到真实证书） |
| 5 | 容器 cgroup 内存 2GB | Bazel server 反复 `Socket closed`（OOM 被杀） | 重开容器到 62GB |
| 6 | gcc 11 缺新指令集 | `unrecognized command-line option '-mavxvnniint8'` | 装 gcc-13 |
| 7 | gcc 11/12 的类类型 NTTP bug | `CompileTimeString` 拷贝构造被 delete 但被调用 | **gcc-13**（修复 P0732 类类型 NTTP 的 delete 拷贝） |
| 8 | gcc-13 生成指令但 binutils 老 | `no such instruction: 'vpdpbssd'` | 禁用新指令集（见下） |
| 9 | OpenCV 头文件路径 | `opencv2/core/version.hpp: No such file` | `ln -s /usr/include/opencv4/opencv2 /usr/include/opencv2` |
| 10 | 缺 EGL 头文件 | `EGL/egl.h: No such file` | `apt-get install libegl1-mesa-dev libgles2-mesa-dev` |
| 11 | 服务器拉不到订阅站 | 直连超时、学术加速 503 | 本地下载订阅 / 订阅站拼接链接 |

### 关键坑详解

**坑 4（PKIX）**：AutoDL 学术加速是 **MITM 代理**，用自签 CA 伪造所有 HTTPS 证书。Bazel 7.x 是自包含二进制、内嵌 JDK，不认系统证书（[Bazel Issue #16223](https://github.com/bazelbuild/bazel/issues/16223)，8.4 才修复）。解法不是改 Bazel 的 cacerts（会触发 corrupt 校验），而是**换用不 MITM 的标准代理**（如 mihomo/clash）。

**坑 7（NTTP bug）**：`mediapipe/framework/api3/node.h` 用了 `template <CompileTimeString kRegistrationName>`（C++20 类类型非类型模板参数），`CompileTimeString` 的拷贝构造被 delete。gcc 11/12 在实例化时错误地尝试拷贝 → 编译失败。**必须 gcc-13**。

**坑 8（binutils）**：gcc-13 能生成 `vpdpbssd`（AVX-VNNI-INT8）指令，但系统 binutils 2.38 不认识。解法是禁用这些新指令集（用 `--define`）。

## 8. 最终能编译成功的命令

```bash
# 环境
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export CC=gcc-13 CXX=g++-13
export http_proxy=http://127.0.0.1:7890 https_proxy=http://127.0.0.1:7890

# 编译（关键：gcc-13 + C++20 + 禁用 5 个新指令集）
bazel build --cxxopt=-std=c++20 --host_cxxopt=-std=c++20 \
  --repo_env=HERMETIC_PYTHON_VERSION=3.10 \
  --define=xnn_enable_avxvnniint8=false \
  --define=xnn_enable_avx512fp16=false \
  --define=xnn_enable_avx256vnnigfni=false \
  --define=xnn_enable_avx512vnnigfni=false \
  --define=xnn_enable_avx512amx=false \
  --jobs=32 //mediapipe/graphs/face_gaze:face_gaze_cpu
```

三个关键点缺一不可：

1. **`--cxxopt=-std=c++20`**：MediaPipe 的 `CompileTimeString` 类类型 NTTP 是 C++20 特性（`.bazelrc` 里 `build:linux` 本来有，但容器平台检测失效，需显式加）；
2. **`CC=gcc-13`**：gcc 11/12 有 NTTP 的 delete 拷贝 bug，gcc 13 修复；
3. **禁用 5 个指令集**：gcc-13 支持但系统 binutils 太老、汇编不过。

## 9. 编译产物

```
bazel-bin/mediapipe/graphs/face_gaze/libface_gaze_cpu.so
bazel-bin/mediapipe/graphs/face_gaze/libface_gaze_cpu.lo
```

`libface_gaze_cpu.so` 就是「人脸检测 → 裁剪 → L2CS-Net → 90-bin logits」这条 pipeline 的 subgraph 注册产物，**证明了图和 BUILD 完全正确**。

## 10. 后续（编 AAR，未做）

验证图能编译后，做成 Android 可用的 AAR 还需要：

1. 装 Android SDK + NDK（`setup_android_sdk_and_ndk.sh`）；
2. 传 `l2cs_net.tflite` 到 `face_gaze` 目录，BUILD 加 `mediapipe_files(srcs = ["l2cs_net.tflite"])`；
3. 写 `apps/facegaze` 的 `mediapipe_aar` BUILD，编 AAR；
4. AAR 进 App，Kotlin 端用 Graph API 喂图、收 logits。

> 已知缺口：L2CS-Net 需要 **ImageNet 三通道归一化**（`(px − mean)/std`，R/G/B 不同 mean/std），
> MediaPipe 内置的 `ImageToTensorCalculator` 只支持 `[0,1]`/`[-1,1]` 单范围归一化，做不了三通道归一化。
> 需确认 `l2cs_net.tflite` 是否已在转换时内置归一化，否则要自定义归一化节点或 Kotlin 端预处理。

## 参考

- 新版 MediaPipe 参考图：`mediapipe/modules/face_landmark/face_landmark_front_cpu.pbtxt`、`mediapipe/modules/iris_landmark/iris_landmark_cpu.pbtxt`
- 新版 API 差异的出处：`mediapipe/calculators/*/BUILD`、`mediapipe/modules/*/BUILD`
- [Bazel Issue #16223](https://github.com/bazelbuild/bazel/issues/16223)（内嵌 JDK 忽略系统证书）
- 本仓库图文件：`mediapipe/graphs/face_gaze/face_gaze_cpu.pbtxt` + `BUILD`
