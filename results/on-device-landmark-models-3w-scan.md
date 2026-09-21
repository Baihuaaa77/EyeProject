# 端侧人脸 / 眼部 Landmark 模型调研（three-way-scan）

- 日期：2026-09-10 | 方式：ARS deep-research `three-way-scan` 模式（3 路并行检索代理，关键数字均经论文/官方文档/源码原文核对，链接已验证可达）
- 范围：MediaPipe 家族、学术可部署模型（PIPNet / 3DDFA-V2 / InsightFace / PFLD）、眼部专用模型（ELG / RT-BENE / OpenEDS / LightIrisNet）、移动端实测数据与模型下载渠道
- 口径说明：MediaPipe 官方一律用 **IOD MAE / MAD %**（私有集），学术模型用 **300W/WFLW NME**，两者数据集与归一方式不同，**不可直接互比**
- 目的：为 EyeProject（Android 眼健康，眨眼 / 虹膜测距 / EAR）的 landmark 选型与升级提供依据

---

## 一、逐模型 WHY / HOW / WHAT

### MediaPipe Face Mesh / Attention Mesh（legacy，468 / 478 点）

- 来源：Google，2019/2020 | 论文 [arXiv:1907.06724](https://arxiv.org/abs/1907.06724)、[arXiv:2006.10962](https://arxiv.org/abs/2006.10962) | [官方文档](https://github.com/google-ai-edge/mediapipe/blob/master/docs/solutions/face_mesh.md)
- **WHY**：单目 RGB（无深度传感器）实时输出 3D 人脸网格；端侧算力下靠级联 + 跟踪省掉逐帧检测。
- **HOW**：BlazeFace 检测 → 256×256 crop 回归 468 点（MobileNetV2-like；合成数据学 3D + 真实数据学轮廓）；Attention Mesh 加眼部/唇部子模型 → 478 点。
- **WHAT**：
  - 精度：IOD MAD 3.96%（Full 256×256，私有 1.7K 集；人标注一致性 2.56%）；V2 卡 IOD MAE 2.76，tracking 2.06-3.27%
  - 速度：Full **2.5ms（iPhone XS）/ 7.4ms（Pixel 3）**，TFLite GPU（论文 Table 1）；Attention Mesh 整模型 16.6ms（Pixel 2 XL）
  - 大小：`face_landmark_with_attention.tflite` 实测 **2,495,952 B**
  - 局限：Z 为相对深度；300W/WFLW NME 官方从未发布

### MediaPipe FaceLandmarker（Tasks，现行方案）

- 来源：Google，2023（替代 legacy） | [任务指南](https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker)
- **WHY**：legacy 分散 API → 统一 Tasks API，单 `.task` 包（检测器+mesh+blendshapes）热插拔，LIVE_STREAM 自带跟踪平滑，原生输出 478 点 + 52 blendshapes。
- **HOW**：包内级联 = blaze 检测 192×192 → FaceMesh-V2 256×256 → blendshapes（146×2 输入）。
- **WHAT**：
  - 大小：`.task` 实测 **3,758,596 B**（float16/latest）；解包 = detector 229,746 B + landmarker 2,553,590 B + blendshapes 955,312 B + metadata 19,376 B
  - **真机实测（Pixel 9 Pro，2025，TFLite benchmark 拆包）**：GPU delegate ≈ **15ms/帧**（3.61 + 11.41）；CPU ≈ 88ms；Tasks API 真实 app 含前处理 **30-70ms/帧**；**0.10.21→0.10.32 同代码约 2x 提速**（[issue #5872](https://github.com/google-ai-edge/mediapipe/issues/5872)）
  - 官方 latency：**未查到官方值**；官方 300W/WFLW NME：不存在（口径为私有集 IOD MAE）
  - 稳定性警示（第三方）：Mali 长跑 `GL_OUT_OF_MEMORY`（[issue #5533](https://github.com/google-ai-edge/mediapipe/issues/5533)）；MTK/Exynos/Unisoc OpenCL 探测崩溃需 try-catch 回退 CPU

### MediaPipe Iris（眼部细化，5 点/眼，已并入 FaceLandmarker）

- 来源：Google，2020 | 论文 [arXiv:2006.11341](https://arxiv.org/abs/2006.11341) | [官方文档](https://github.com/google-ai-edge/mediapipe/blob/master/docs/solutions/iris.md)
- **WHY**：256×256 全脸图里虹膜太小精度不够；虹膜直径近常数可反推**距离**。
- **HOW**：mesh 裁眼区 → 64×64 眼模型输出眼睑 + 虹膜轮廓（左右眼镜像复用）；虹膜索引 468-477（源码核实）。
- **WHAT**：
  - 精度：眼/虹膜 **7.16% MAD IED**；距离实测平均误差 **4.3%（std 2.4%）** vs iPhone 11 深度传感器（200+ 人，官方标称 <10%，按 11.7±0.5mm 虹膜直径）
  - 速度：mesh + eye-refine 合计 Pixel 3 XL 17ms / iPhone X 9.6ms（论文 Table 1，>50 FPS）
  - 大小：`iris_landmark.tflite` 实测 **2,640,568 B**
  - 局限：不输出注视方向；独立模型已被 FaceLandmarker 内置替代

### PIPNet（人脸 landmark）

- 来源：IJCV 2021（注意：常被误传为 NeurIPS） | [arXiv:2003.03771](https://arxiv.org/abs/2003.03771) | [代码/权重](https://github.com/jhb86253817/PIPNet)（权重 Google Drive，README 提供链接）
- **WHY**：热图法稳健但慢、回归法快但泛化差——想兼得。
- **HOW**：低分辨率特征图（stride 32，8×8）上像素分类 score + 偏移回归；NRM 邻域约束。
- **WHAT**：300W full NME **3.36（R18）/ 3.40（MBV2）**；WFLW full **4.57（R18）/ 4.65（MBV3）**；R18 = 12.0M 参数 / 2.4 GFLOPs / **35.7 FPS（服务器 Xeon CPU，论文口径）**。社区 NCNN/MNN 移植存在（[lite.ai.toolkit](https://github.com/xlite-dev/lite.ai.toolkit)，R18 ONNX ≈44MB float32）；**真手机实测未查到**。

### 3DDFA-V2（3D 人脸对齐）

- 来源：ECCV 2020 | [arXiv:2009.09960](https://arxiv.org/abs/2009.09960) | [代码+权重（pth/onnx 随 repo）](https://github.com/cleardusk/3DDFA_V2)
- **WHY**：视频里 3DMM 逐帧独立回归会抖；要移动端实时 3D 对齐。
- **HOW**：MobileNet 回归 62 维 3DMM 参数（WPDC+VDC），合成数据训练。
- **WHAT**：MobileNet 3.27M 参数 / ~13MB checkpoint；全流程 **19.2ms（MacBook Pro i5，>50fps）**；AFLW2000-3D mean 3.51。**⚠️ 官方 FAQ 明确：300W-LP 训练集闭眼样本稀疏 → 闭眼时眼点不准**（对眨眼/眼健康应用是关键排除项）。第三方 TFLite 移植（[1996scarlet/dense-head-pose-estimation](https://github.com/1996scarlet/dense-head-pose-estimation)）桌面 CPU 5.8-7.8ms。

### InsightFace 2d106det / coordinateReg（106 点）

- 来源：DeepInsight，持续更新 | [coordinate_reg](https://github.com/deepinsight/insightface/blob/master/alignment/coordinate_reg/README.md) | [Model Zoo](https://github.com/deepinsight/insightface/blob/master/model_zoo/README.md)（ONNX：Google Drive/百度直链；`buffalo_l.zip` 实测含 `2d106det.onnx`）
- **WHY**：loose-crop 人脸框直接回归 106 点，最小可用。
- **HOW**：192×192 输入，MobileNet-0.5。
- **WHAT**：**~5MB / 1.2M 参数**；官方 NME 未查到（Model Zoo 不发 landmark 精度）；第三方 NCNN Android demo（UltraFace+106 点）**RK3566 单线程 15-18fps**（[repo](https://github.com/455670288/ncnn-android-FaceDetector-Landmarks-master)）。注意模型许可**非商用**。

### PFLD（移动端速度标杆）

- 来源：ACM MM 2019 | [arXiv:1902.10859](https://arxiv.org/abs/1902.10859) | [权重](https://github.com/polarisZhao/PFLD-pytorch)；NCNN 实测 [AnthonyF333/FaceLandmark_PFLD_UltraLight](https://github.com/AnthonyF333/FaceLandmark_PFLD_UltraLight)
- **WHY**：精度-速度权衡显式面向移动端。
- **HOW**：多任务辅助分支（姿态/闭眼）助训练的轻量 backbone。
- **WHAT**：0.25X 版 **2.1MB、>140fps（7ms/脸）@ 骁龙 845 真机**（论文官方手机实测）；300W full NME 4.02（0.25X）；NCNN 单核 3.6-5.5ms（0.41-0.45M 参数）。WFLW NME 未查到。

### 眼部专用模型（眼睑 / 虹膜 / 瞳孔）

| 模型 | 出处 | 输出 | 精度 | 速度/大小 | 权重 | 来源 |
|---|---|---|---|---|---|---|
| ELG | ETRA 2018（非 ECCV） | 每眼 18 点（limbus+虹膜缘+眼球心） | 未查到 | 未查到（TF1 桌面） | 可下载（ETH 直链） | [swook/GazeML](https://github.com/swook/GazeML) |
| RT-BENE | ICCVW 2019 | 眨眼分类（非 landmark） | Eyeblink8 集成 F1 0.976 | 桌面 GPU 20-42fps；Jetson Nano 仅 MobileNetV3-L/ShuffleNetV2 达 33ms | 随 repo | [rt_gene](https://github.com/Tobias-Fischer/rt_gene)、[arXiv:1909.00605](https://arxiv.org/abs/1909.00605) |
| OpenEDS mSegNet | Meta 2019 | iris/pupil/sclera 分割 | 未查到 | **1.6MB / 0.4M**（论文称适合端侧） | **未发布**；数据集 2021 起不公开 | [arXiv:1905.03702](https://arxiv.org/pdf/1905.03702) |
| LightIrisNet | arXiv 2025 | 虹膜/瞳孔分割 | 未查到 | 面向智能手机（未给真机数） | repo 可用 | [repo](https://github.com/naveengv7/LightIrisNet)、[arXiv:2512.15548](https://arxiv.org/pdf/2512.15548v1) |
| pylids | 2023 | 眼睑+瞳孔 17 点 | — | 桌面级 ResNet50，非轻量 | 开源 | [repo](https://github.com/piecesofmindlab/pylids) |

---

## 二、跨模型综合

- **共同 WHY**：单目 RGB、无深度传感器、端侧算力/功耗/延迟约束下，实时输出人脸或眼部几何；眼部应用额外要求虹膜/眼睑细节与闭眼鲁棒。
- **分化 HOW**：① MediaPipe 式**级联回归**（检测→crop→mesh→眼区细化，合成数据学 3D）；② PIPNet 式**分类+偏移混合头**（低分辨率特征图上兼得热图稳健与回归效率）；③ 3DDFA-V2 式 **3DMM 参数回归**（输出稠密 3D 但逐帧参数化）；④ 眼部专用 **patch 小网络**（ELG/RT-BENE/Iris：眼区裁剪后专用模型，左右镜像复用）。
- **最强 WHAT**：
  - 工业完整度：**FaceLandmarker** 唯一——3.76MB 单包覆盖 478 点 + 虹膜 10 点 + blendshapes，Pixel 9 Pro GPU 15ms，虹膜测距误差 4.3%
  - 真机速度：**PFLD**（骁龙 845，2.1MB，>140fps，300W NME 4.02）
  - 精度/体积学术均衡：**PIPNet-R18**（WFLW 4.57 / 12M / 2.4 GFLOPs）
  - 最小体积：**2d106det** ~5MB（非商用）、OpenEDS mSegNet 1.6MB（无权重）
- **未解决缺口（全局 gap）**：
  1. MediaPipe 官方从不发布 300W/WFLW NME → 与学术模型**精度不可直接对比**（口径不同）
  2. Tasks 版 FaceLandmarker **官方无 latency 数据**，官方逐机型数字停留在 2019-2020 的 Pixel/iPhone（legacy 论文）
  3. 眼部专用 landmark 模型**在真手机上的公开实测几乎空白**（RT-BENE/ELG 均为桌面/GPU 数据）
  4. 闭眼鲁棒性普遍无保证：3DDFA-V2 官方承认闭眼眼点不准；其余论文闭眼场景无专项评测
  5. 小模型 GPU delegate 在中低端 SoC（Mali/MTK/Unisoc）有已知稳定性问题，纯 CPU 部署反而是社区主流

---

## 三、模型下载地址汇总（链接已核验）

| 文件/仓库 | 大小（实测） | 地址 |
|---|---|---|
| face_landmarker.task（float16 latest，**现行推荐**） | 3,758,596 B | https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task |
| blaze_face_short_range.tflite | 229,746 B | https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/latest/blaze_face_short_range.tflite |
| iris_landmark.tflite（legacy） | 2,640,568 B | https://storage.googleapis.com/mediapipe-assets/iris_landmark.tflite |
| face_landmark_with_attention.tflite（legacy 478 点） | 2,495,952 B | https://storage.googleapis.com/mediapipe-assets/face_landmark_with_attention.tflite |
| face_landmark.tflite（legacy 468 点） | ~1.24MB | https://storage.googleapis.com/mediapipe-assets/face_landmark.tflite |
| 官方模型索引 / 模型卡 | — | https://github.com/google-ai-edge/mediapipe/blob/master/docs/solutions/models.md |
| 官方 Android 示例 | — | https://github.com/google-ai-edge/mediapipe-samples/tree/main/examples/face_landmarker/android |
| InsightFace 2d106det（106 点，~5MB，非商用） | ~5MB | https://github.com/deepinsight/insightface/tree/master/model_zoo ；打包 https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_l.zip |
| PIPNet 权重（R18/MBV2 等） | — | https://github.com/jhb86253817/PIPNet （README → Google Drive） |
| 3DDFA-V2（pth+onnx 随 repo） | ~13MB | https://github.com/cleardusk/3DDFA_V2 |
| PFLD 权重 | 2.1MB（0.25X） | https://github.com/polarisZhao/PFLD-pytorch |
| ELG 权重（get_trained_weights.bash） | — | https://github.com/swook/GazeML |
| RT-BENE 权重 | — | https://github.com/Tobias-Fischer/rt_gene |
| PINTO model zoo（FaceMesh 转换版） | — | https://github.com/PINTO0309/PINTO_model_zoo （032_FaceMesh、410_FaceMeshV2） |
| Luxonis ONNX 版 FaceLandmarker（0.603M 参数） | — | https://models.luxonis.com/luxonis/mediapipe-face-landmarker/4632304b-91cb-4fcb-b4cc-c8c414e13f56 |
| Qualcomm AI Hub（MediaPipe landmark tflite 2.34MB） | 2.34MB | https://huggingface.co/qualcomm/MediaPipe-Face-Detection （HF 国内直连受限，未逐文件验证） |
| 非官方镜像 | — | https://github.com/sanderdesnaijer/mediapipe-model-mirrors |

注意：官方 `face_blendshapes` 独立 tflite 与 `int8/latest` 路径探测 404；CSDN 博客类速度数据（Xiaomi 13 等）可靠性低，未采信。

---

## 四、对 EyeProject 的选型结论

1. **现有 FaceLandmarker 路线即主流最优**：478 点 + 虹膜 10 点 + 11.7mm 测距口径与项目 `DepthFromIris` 完全吻合；无需引入第二模型。
2. **两个可立刻执行的升级点**：① 升级 `mediapipe tasks-vision` 到 0.10.32+（同代码实测 ~2x 提速）；② GPU delegate 需 try-catch 回退 CPU（Mali/MTK/Unisoc 已知崩溃）——本项目真机 8 核、GPU 节点可用，值得实测 GPU delegate 路径。
3. 若要**更省**的独立 landmark 模型（去 MediaPipe 化）：PFLD（2.1MB，真机最快）或 2d106det（5MB），但眨眼/测距需自研（失去虹膜点与官方跟踪平滑），收益存疑。
4. **排除项**：3DDFA-V2（闭眼眼点不准）、ELG/RT-BENE/OpenEDS（无手机端数据或无权重）、pylids/LightIrisNet（桌面级/未验证）。

---

### 局限与披露

- 本扫描为 three-way-scan 轻量模式：候选集以可部署性与数据完备性优先截断，非穷举（escalate 到 lit-review 可扩全量证据矩阵）
- 「未查到」均指经检索与原文核对后仍无公开官方值；第三方数据已注明设备/框架/可靠性
- AI 辅助研究工具（Claude Code 多代理检索）已用于本报告的检索与初稿整理
