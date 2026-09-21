# 端侧人脸 / 眼部 / 眨眼检测模型调研（lit-review，2023-2026 前沿）

- 日期：2026-09-10 | 方式：ARS deep-research `lit-review` 模式（4 路并行检索代理：新人脸 landmark / 眨眼专项 / 眼部精细 / 工业界动态；关键数字均从论文 PDF/README/官方文档原文核对）
- **与既有报告的关系**：本文是 `on-device-landmark-models-3w-scan.md`（2026-09-10，覆盖 MediaPipe/PIPNet/3DDFA-V2/InsightFace/PFLD 等经典方案）的**增量扩展**，重心放在 2023-2026 新工作；经典方案不再重复，两文配合阅读
- 口径提醒：学术 NME（300W/WFLW）与 MediaPipe IOD MAE 口径不同不可互比；第三方真机数据均注明设备

---

## 一、注释书目

### A. 人脸 landmark 新模型（2023-2026）

**A1. STAR-Loss** | CVPR 2023 |【训练技巧，零推理开销】
[arXiv:2306.02763](https://arxiv.org/abs/2306.02763) | [代码+权重](https://github.com/ZhenglinZhou/STAR)（WFLW/300W/COFW 三套）
- WHY：热图法左右脸对称点的语义歧义是 WFLW 精度瓶颈。HOW：把预测误差 PCA 分解到歧义主成分方向的自适应各向异性损失，可叠加到任意回归器。
- WHAT：WFLW NME **4.02**、300W full **2.87**、COFW 4.62；13.37M 参数。对现有热图管线是免费精度补丁。

**A2. KeyPosS** | ACM MM 2023 |【解码范式革新，端侧友好】
[arXiv:2305.16437](https://arxiv.org/abs/2305.16437) | [代码+权重](https://github.com/zhiqic/KeyPosS)（4-64 各热图分辨率全公开）
- WHY：热图上采样量化误差限制低分辨率端侧部署。HOW：FCN 预测「POI-锚点距离图」，GPS 式多边定位解码，免重训即插即用。
- WHAT：WFLW **4.0**、300W 3.39；9.7M / 4.7 GFLOPs，热图降到 16×16 仅 0.3、8×8 仅 **0.07 GFLOPs**。

**A3. POPoS** | AAAI 2025 |【KeyPosS 的提速续作】
[arXiv:2410.09583](https://arxiv.org/abs/2410.09583) | [代码+权重](https://github.com/teslatasy/PoPoS)
- WHY：KeyPosS 迭代解码 405ms 太慢。HOW：top-K 锚点伪距 + 单步并行多边定位 + 专用 loss。
- WHAT：WFLW **3.95**（64²）/ 4.28（32²）；9.7M / 1.2-4.7 GFLOPs；**解码 1301 FPS**（vs KeyPosS 96.5）。mmpose 生态兼容。

**A4. EFLD** | arXiv 2024（ICME 2024 PAIR 嵌入式挑战赛冠军）|【精度-体积比最优，权重未放】
[arXiv:2407.10228](https://arxiv.org/abs/2407.10228) | [repo](https://github.com/072jiajia/efficient-fld)（"code will be released soon"，2024-07 后无更新）
- WHAT：**0.17MB / 19.1 MFLOPs / NME 3.72**（挑战赛集）；天玑 9300 实测，总分领先亚军 35 倍。EOSA 可分离骨干 + 直回归 + INT8。

**A5. OpenFace 3.0** | arXiv 2025 |【多任务开源工具包】
[arXiv:2506.02891](https://arxiv.org/abs/2506.02891) | [代码](https://github.com/CMU-MultiComp-Lab/OpenFace-3.0)
- WHAT：关键点+AU+视线+情绪统一；300W **2.87** / WFLW 4.02；整机 29.4M / 4.2 GFLOPs / 38ms（OpenFace 2.0 是 44.8M/75ms）。
- 参照系：SPIGA 60-63M（WFLW 4.06，[代码](https://github.com/andresprados/SPIGA) pip 即用）、FaceXFormer 109M（ICCV 2025，十任务统一，[代码+权重](https://github.com/Kartik-3004/facexformer)）、Proto-Former 61.7M（TMM 2025，WFLW 4.23）——三者精度强但对手机偏重。

### B. 眨眼检测（2023-2026）——对项目决策最关键

**B1. EAR vs CNN 的三方交叉验证**【直接回答「要不要换掉 EAR」】
- 同系统对照（[arXiv:2604.22479](https://arxiv.org/abs/2604.22479)，2026，Table I 核实）：固定 EAR **91.70%** → **个性化 EAR（开眼值×0.75）93.23%** → CNN **99.10%**
- 同基准对比（DE-ViViT, WACV 2024，[PDF](https://openaccess.thecvf.com/content/WACV2024/papers/Hong_Robust_Eye_Blink_Detection_Using_Dual_Embedding_Video_Vision_Transformer_WACV_2024_paper.pdf)）：in-the-wild HUST-LEBW 上经典 EAR F1 仅 **0.463**，深度模型 0.717-0.853
- 端侧临床（Bapp，[arXiv:2511.14361](https://arxiv.org/abs/2511.14361)，2025.11）：Flutter + Google ML Kit「开眼概率+EAR 混合」，眼科临床验证 **Acc 98.3%**（45 患者视频/28,975 帧）——与本项目同构的最强先例
- **结论**：正面固定机位 EAR 足够（91-95%），姿态/光照/个体差异是主要失败源；性价比路线 = blendshape 开眼概率 + 按用户校准阈值；换深度分类仅 +5-7 点且需 10 帧时序模型

**B2. 信号候选：blendshape eyeBlink 的双面性**
[arXiv:2601.08234](https://arxiv.org/abs/2601.08234)（2026.01，HTML 核实）：MediaPipe 关键点派生 ARKit 式 blendshape，**EyeBlinkLeft/Right 事件级 P/R/F1 全 1.00**，但连续分数 Pearson 仅 0.576/0.674 且有抖动 → 当**二值事件**可靠，当**连续量**需平滑。

**B3. 信号候选：ELA（3D 眼睑角）**
[arXiv:2511.19519](https://arxiv.org/abs/2511.19519)（2025.11）：论证 2D EAR 对相机角度敏感，用 MediaPipe 3D 关键点构造眼睑角 ELA 姿态稳定性更好；但工程验证（kNN 疲劳三分类 VA 52.5%）有限。

**B4. 可直接试用的深度模型：BlinkLinMulT**
[Journal of Imaging 2023](https://www.mdpi.com/2313-433X/9/10/196) | [代码](https://github.com/fodorad/BlinkLinMulT) | **MIT 许可，`pip install blinklinmult`，预训练权重自动加载**——最便宜的实验路径。
WHAT：线性注意力多模态（DenseNet121 眼片 + MediaPipe ILM/头姿），F1：CEW 0.997 / ZJU 0.933 / RT-BENE 0.913 / EyeBlink8 0.991。

**B5. 数据集与前沿**
- **SynBlink+BlinkFormer**（BMVC 2023，[paper](https://papers.bmvc2023.org/0127.pdf)/[code](https://github.com/desti-nation/BlinkFormer)）：50k 段 Blender 合成眨眼数据；HUST-LEBW F1 84.31%（当时 SOTA）；合成→真实跨库掉到 70.9%（域差警示）
- **mEBAL2**（[arXiv:2309.07880](https://arxiv.org/abs/2309.07880)，[repo](https://github.com/BiDAlab/mEBAL2)）：21,100 序列 / 180 人 / >200 万标注图，RGB+NIR 双光谱
- **DE-ViViT**（WACV 2024）：10 帧 24×24 眼区视频片段 ViViT，多机位最稳（自建 MAEB F1 0.894）；权重未发布
- 综述锚点：[PeerJ CS 2025 免费综述](https://peerj.com/articles/cs-2594/)（landmark/CNN/RNN/Transformer 四族+数据集表）
- **许可警示：RT-BENE 为 CC BY-NC-SA（禁商用）**
- 未查到：RB-Net（眨眼领域）、ClosedEyeNet、2023-2026 新非合成公开眨眼数据集（除 mEBAL2/SynBlink）

### C. 眼部精细检测（虹膜/瞳孔/眼睑，2023-2026）

**C1. LightIrisNet + CUVIRIS**【与本项目场景最对齐】| arXiv 2025.12
[论文](https://arxiv.org/abs/2512.15548) | [代码+权重](https://github.com/naveengv7/LightIrisNet)（已发布，含 CUVIRIS 10 人子集）|【扩展：3W 扫描仅一笔带过】
- WHAT：MobileNetV3 多任务（mask+边界+SDT+椭圆先验）+ INT8，可见光手机端虹膜/瞳孔分割，**附 Android 采集 App**（ISO 29794-6 质检）；4 数据集 iris IoU **0.905** / pupil IoU 0.895。论文未报参数量/FPS（网传 40FPS 查无实据）。

**C2. OcularSeg** | Electronics 2024 | [paper](https://www.mdpi.com/2079-9292/13/10/1967) + [code](https://github.com/koala0623/OcularSeg)
单网络联合分割眼周/巩膜/虹膜/瞳孔四类（EfficientNetV2+EM 注意力+眼形先验）；22.65M / 6.55 GFLOPs / 41.5ms（设备未注明）；MOBIUS mIoU 86.28%。权重未确认。

**C3. 开源眼周分割数据集（眼睑方向）** | Ophthalmology Science 2025 | [arXiv:2409.20407](https://arxiv.org/abs/2409.20407)
唯一含**眼睑/泪阜/眉**细粒度开源标注（n=2842，5 标注者，标注间 Dice 0.82）；DeepLabV3 基线 lid Dice 0.87；**标注+工具+权重全开源**——做眼睑闭合度/睑裂距离的数据基础。

**C4. SAM2 零样本瞳孔分割** | ETRA 2025 | [arXiv:2410.08926](https://arxiv.org/abs/2410.08926)（代码+掩码已发布）
单次点击零样本瞳孔分割，mIoU 最高 93% 与专用模型持平。**定位：离线标注器/教师模型，不宜端侧**。同系 [Iris-SAM](https://github.com/ParisaFarmanifard/Iris-SAM)（ND-IRIS-0405 99.58%）。

**C5. PicoEyes**【值得跟踪】| arXiv 2026.05 | [arXiv:2605.07188](https://arxiv.org/abs/2605.07188)
端到端回归**眼球中心/半径、角膜、瞳孔 3D 参数 + optical/visual axis** + 四类分割——对标「MediaPipe Iris 但给全 3D 眼球」；眼球中心误差 0.35mm（双目）/0.60mm（单目）。**权重承诺开源、检索时未查到发布链接**。

**C6. 3DeepVOG**【与 C++ 算法层互补】| Digital Biomarkers 2026 | [论文](https://doi.org/10.1159/000549948) + [代码（已发布）](https://github.com/DSGZ-MotionLab/3DeepVOG)
普通单目相机出 **3D 注视**（水平/垂直/旋转）：双球解剖模型+角膜折射校正，>300 FPS，三维注视误差 ~0.1°。

**C7. 泛化警示（AmbientEye）** | arXiv 2026.06 | [arXiv:2606.03774](https://arxiv.org/abs/2606.03774)
260 万张自然光红外（无主动光源）户外眼图：受控 IR 瞳孔分割 mIoU **0.928 → 环境 IR 仅 0.767**。**户外/无受控照明场景选型必须带泛化压测**。数据集发布链接未查到。

**C8. 极端低功耗参照：EETnet** | IJCNN 2025（EssilorLuxottica+POLIMI）| [arXiv:2511.04779](https://arxiv.org/abs/2511.04779)
事件相机 + 微控制器级瞳孔跟踪（STM32U5/H7/MAX78000），QAT 4/8-bit，权重仅 **84-156 kB**。代码未发布。

**线索纠错**：IrisFormer 实为虹膜**识别**（IEEE SPL 2025）非分割；「EPSFormer 虹膜分割」查无此文；「EPW 瞳孔挑战赛」不存在（对应 CVPRW 2024 AIS 事件相机挑战赛，冠军 MambaPupil，事件相机方向）；OpenEDS 数据集 2021 起不再公开。

### D. 工业界动态（2024-2026）

**D1. MediaPipe tasks-vision 版本线**（[releases](https://github.com/google-ai-edge/mediapipe/releases) + [Maven](https://dl.google.com/android/maven2/com/google/mediapipe/tasks-vision/maven-metadata.xml)，已核验）
- 0.10.26（2025-07）：Android 16KB page size 支持；0.10.33（2026-03）：FULL_RANGE 人脸检测；**0.10.35（2026-04）：Android NPU 加速 + JIT 编译**；**1.0.0**（2026-07-27，本项目已升级）
- **FaceLandmarker 模型两年无官方更新**（478 点 float16 `latest` 滚动托管，无 v2）；1.0.0 无 FaceLandmarker API breaking change，纯工程版本
- 真正的升级价值 = **0.10.35 起 Android 可走 NPU delegate**（替代 GPU 路线的官方出口）

**D2. 性能瓶颈定位**（[issue #5872](https://github.com/google-ai-edge/mediapipe/issues/5872)）：Pixel 9 Pro 上整管线 30-70ms 而单独跑 `face_landmarks_detector.tflite`（GPU）明显更快 → **瓶颈在图管线/前处理，不在模型**（与本项目实测的位图 churn 症状同源）。

**D3. Qualcomm AI Hub `facemap_3dmm`**（2025.02 上新，[hub 页](https://aihub.qualcomm.com/iot/models/facemap_3dmm)）：68 点 3DMM，5.42M 参数，float 20.7MB / **INT8 5.27MB**，NPU 延迟 **658μs**（QCS6490，页面动态渲染、来自搜索快照未静态复现），BSD-3。无虹膜点 → EAR 精度受限，但为 NPU 化 landmark 的少数官方量化候选。

**D4. 跨平台与厂商**：Apple Vision `DetectFaceLandmarksRequest` 新增 revision4（iOS 18 代），仍是分区制无 mesh 化（[WWDC25](https://developer.apple.com/videos/play/wwdc2025/272/)）；HMS ML Kit 宣称 845 点网格 + 闭眼判断 ≤50ms（厂商宣传、无基准条件）；虹软 ArcFace 公开数据偏旧（RK3399 检测 50-100ms/跟踪 7-10ms）；字节/火山无公开数字（不采信）。

**D5. InspireFace**（[HyperInspire](https://github.com/HyperInspire/InspireFace)，基于 InsightFace）：2024-07 起带眨眼检测，v1.2.3 stable（2025-08），Android SDK 上 JitPack，后端含 RKNPU/CPU/GPU，repo 活跃——**唯一开源「眨眼开箱即用 + Android AAR」对照实现**（旗舰真机数据未查到）。

---

## 二、主题综合

**T1. 眨眼信号谱系（对本项目最重要）**：证据收敛为一条阶梯——固定 EAR（91.7%）< 个性化校准 EAR/开眼概率混合（93-98%）< 时序深度模型（99%+，需 10 帧片段）。失败源不在「EAR 公式」而在**阈值未按人校准**与姿态/光照。我们的实测（用户 EAR 基线 0.26-0.28 贴 0.23 阈值、33% 误判）与文献完全吻合。
**T2. 端侧 landmark 的两条路**：① 继续 MediaPipe 且把前处理做轻（瓶颈在管线不在模型，issue #5872 证实）+ 尝试 0.10.35 NPU delegate；② 自研轻量线（POPoS/KeyPosS 9.7M 可压 0.07-1.2 GFLOPs、EFLD 0.17MB）——但都无虹膜点，眨眼/测距需重建，切换成本高于收益，除非 NPU delegate 实测仍不达标。
**T3. 眼部精细化路线图**：虹膜分割升级 = LightIrisNet（Android 全链路+权重）；3D 眼球/注视 = 等 PicoEyes 开源或用 3DeepVOG（已开源）；眼睑精细化 = C3 数据集 + OcularSeg；SAM2 系做离线标注器。
**T4. 泛化与许可陷阱**：受控环境 SOTA 在自然光下掉 16 mIoU（AmbientEye）；商用红线 = RT-BENE（CC BY-NC-SA）、2d106det（非商用）；OpenEDS 数据已下架；paperswithcode 已下线（302 → HF papers，榜单获取方式变了）。

## 三、对 EyeProject 的行动建议（优先级）

1. **立即（零成本）**：眨眼判定加「个性化校准」——UI 采开眼基线 EAR，阈值 = 基线 × 0.75（文献 93.2% vs 固定 91.7%，且正好治我们实测的 33% 误判）；并行启用 blendshape eyeBlink 作二值事件校验（F1=1.00）
2. **短期**：真机确认 tasks-vision 1.0.0 提速后，试 GPU/NPU delegate（0.10.35+ 能力）+ try-catch CPU 回退；用 TFLite benchmark tool 拆 `.task` 逐子模型定位（issue #5872 方法）
3. **中期（若要 >95% 眨眼精度）**：BlinkLinMulT（MIT，pip 即用）做离线对照实验；或复刻 Bapp 的「开眼概率+EAR 混合」
4. **跟踪清单**：PicoEyes 权重发布、EFLD 权重发布、MediaPipe FaceLandmarker 模型 v2
5. **红线**：商用方案避开 RT-BENE / 2d106det；户外场景先做泛化压测再上虹膜分割升级

## 四、局限与披露

- lit-review 模式（biblio+verification+synthesis 三代理职能由 4 路并行检索代理承担）；候选集以「端侧可部署性 + 2023-2026」截断，非穷举系统性综述
- 「未查到」= 经检索+原文核对仍无公开值；NPU 数字（Qualcomm hub）来自搜索快照未静态复现；HF 资源国内直连受限未逐文件验证
- 与 3W 扫描重叠条目（MediaPipe 家族、RT-BENE、LightIrisNet）只记增量，基础数据见前文
- AI 辅助研究工具（Claude Code 多代理检索）已用于本报告检索与初稿整理
