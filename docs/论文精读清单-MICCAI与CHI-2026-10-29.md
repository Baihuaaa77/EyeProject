# 论文精读清单：MICCAI 与 CHI（眼健康助手项目选题参考）

整理日期：2026-10-29
用途：为本项目（手机前置摄像头眼健康自测 + 主动护眼助手）寻找投稿 MICCAI 或 CHI 的创新思路。

> 每篇论文的标题、会议与年份均经网络检索核对；无法直接核实会议的已单独注明。
> 检索工具对 CHI 收录不全，建议再到 ACM Digital Library 补搜（关键词见文末）。

---

## 一、MICCAI 方向（方法 / 算法）

### A. 眼动、注视与斜视（与本项目最贴近）

#### M1. Causality-Inspired Graph Neural Network for Interpretable Strabismus Subtype Classification
- 会议：MICCAI 2025
- 作者单位：汕头大学、汕头国际眼科中心、上海交通大学医学院附属新华医院等
- 链接：<https://papers.miccai.org/miccai-2025/paper/3330_paper.pdf>
- 读它的理由：用九方位人脸照片做斜视亚型分类，模型可解释，又轻到能在手机上跑。我们的眼位自测和它对口。
- 切入点：它只用静态照片，我们有遮盖试验、跟随运动的视频时序。可以把方法推广到视频，并要求在手机端实时完成。

#### M2. Multi-Modal Progressive Fusion for ASD Screening Using Smartphone Video
- 会议：MICCAI 2025（LNCS 15968，DOI 10.1007/978-3-032-05114-1_38）
- 作者：Wenqi Zhong, Bohan Li, Chen Xia, Kuan Li, Dingwen Zhang（西北工业大学、同济大学等）
- 链接：<https://papers.miccai.org/miccai-2025/0609-Paper3536.html>
- 读它的理由：从手机视频里提取眼动、头姿、表情三种信号做融合筛查，和我们的技术路线最像。
- 切入点：参考它的论文写法，包括多模态融合的结构和手机视频数据集怎么采、怎么描述。

#### M3. Improving Autism Detection with Multimodal Behavioral Analysis
- 会议：MICCAI 2025
- 链接：<https://papers.miccai.org/miccai-2025/paper/4692_paper.pdf>
- 读它的理由：把注视转移当作行为特征之一。
- 切入点：我们的眨眼频率、观看距离、头部偏转也可以作为行为特征。

#### M4. Longitudinal anatomical attention maps for recognizing diagnostic errors from radiologists' eye movements
- 会议：MICCAI 2025
- 链接：<https://papers.miccai.org/miccai-2025/0509-Paper3879.html>
- 读它的理由：看「眼动信号接进深度模型」在 MICCAI 是怎么评测、怎么写的。

### B. 大模型与可解释性

#### M5. Insight: A Multi-Modal Diagnostic Pipeline using LLMs for Ocular Surface Disease Diagnosis
- 会议：MICCAI 2024（LNCS 15001，pp. 711–721）
- 作者：Yeh 等
- 链接：<https://papers.miccai.org/miccai-2024/413-Paper0298.html>
- 读它的理由：用大模型结合睑板腺图像和临床资料，诊断眼表疾病（干眼属于这一类）。
- 切入点：对应我们「AI 解读 + 知识库检索 + 输出校验」这一套，可以写成「规则判等级、大模型做解释」的框架。

### C. 可靠性与质量控制（对应我们的「无法评估 / 结果不确定」）

#### M6. Leveraging Diffusion Models for Continual Test-Time Adaptation in Fundus Image Classification
- 会议：MICCAI 2025
- 链接：<https://papers.miccai.org/miccai-2025/paper/1304_paper.pdf>
- 读它的理由：眼科数据换了设备或环境，模型怎么在测试时自适应。
- 切入点：不同手机的摄像头、光线差别很大，问题是同一类。

#### M7. Difficulty Estimation for Image-Specific Medical Image Segmentation Quality Control
- 会议：MICCAI 2025
- 链接：<https://papers.miccai.org/miccai-2025/0232-Paper4042.html>
- 读它的理由：讲模型怎么判断「这一例我没把握」。
- 切入点：我们拒判（输出「无法评估」）的机制可以形式化成一个贡献点。

#### M8. Generalized Robust Fundus Photography-based Vision Loss Estimation for High Myopia
- 会议：MICCAI 2024
- 链接：<https://papers.miccai.org/miccai-2024/355-Paper1834.html>
- 读它的理由：从图像估计视功能，并且强调泛化能力。
- 切入点：参考它的鲁棒性实验设计。

### D. 远程视频筛查（会场待核实）

#### M9. Benchmarking Video Foundation Models for Remote Parkinson's Disease Screening
- 来源：arXiv 2602.13507（Md Saiful Islam、Ehsan Hoque 等，罗切斯特大学）
- 会议：搜索结果显示它可能在 MICCAI 2026 目录里，但未能直接确认
- 链接：<https://arxiv.org/abs/2602.13507>
- 读它的理由：1,888 人、16 种任务的视频基础模型评测。结论是特异性高、灵敏度不够，适合当「远程视频筛查」的对比基线。

> 未找到 MICCAI 上做「手机自测干眼 / 眨眼」的论文，这些相关工作都在临床期刊。这可以作为投稿的空白点。

---

## 二、CHI 方向（人机交互 / 健康系统）

### A. 眼健康与相机健康感知

#### C1. A Human-Centered Evaluation of a Deep Learning System Deployed in Clinics for the Detection of Diabetic Retinopathy
- 会议：CHI 2020（DOI 10.1145/3313831.3376718）
- 作者：Emma Beede 等（Google Health）
- 链接：<https://research.google/pubs/a-human-centered-evaluation-of-a-deep-learning-system-deployed-in-clinics-for-the-detection-of-diabetic-retinopathy/>
- 读它的理由：眼科 AI 落地研究的必读。在泰国 11 家诊所实地观察，核心发现是真实环境的数据质量和模型门槛之间有冲突。
- 切入点：把场景换成家庭自测，我们的异常暂停和测前快问正好可以研究。

#### C2. At-Home Pupillometry using Smartphone Facial Identification Cameras
- 会议：CHI 2022（DOI 10.1145/3491102.3502493）
- 链接：<https://dl.acm.org/doi/fullHtml/10.1145/3491102.3502493>
- 读它的理由：用手机前置人脸识别摄像头在家测瞳孔。和我们「只用前置摄像头做眼部测量」最接近。
- 切入点：照它的写法，用金标准设备对比验证准确度，再做居家可用性研究。

#### C3. OralCam: Enabling Self-Examination and Awareness of Oral Health Using a Smartphone Camera
- 会议：CHI 2020
- 作者：Yuan Liang 等（含 Xiang Anthony Chen）
- 链接：<https://arxiv.org/abs/2001.05621>
- 读它的理由：手机相机自检口腔健康，结果分层、带概率、配可视化解释，做了一周的真实环境使用研究。
- 切入点：结构几乎可以照搬到「手机眼健康自测」：模型准确度、给非专业用户的解释设计、实地使用研究。

#### C4. Stimulating a Blink: Reduction of Eye Fatigue with Visual Stimulus
- 会议：CHI 2014
- 作者：Crnovrsanin, Wang, Ma
- 链接：<https://dl.acm.org/doi/10.1145/2556288.2557129>
- 读它的理由：用视觉刺激提醒眨眼，是我们首页主动护眼的直接前作。
- 切入点：我们能做它没做的：实时检测眨眼，再比较不同干预方式（小瞳气泡、护眼操、休息提醒）。

### B. 行为干预与时机

#### C5. MyTime: Designing and Evaluating an Intervention for Smartphone Non-Use
- 会议：CHI 2016（DOI 10.1145/2858036.2858403）
- 作者：Hiniker, Hong, Kohno, Kientz
- 链接：<https://homes.cs.washington.edu/~yoshi/papers/MyTimeCameraReady.pdf>
- 读它的理由：干预手机使用的经典研究，先做设计空间分类，再做两周部署。
- 切入点：可以参考它，梳理眼健康干预的设计空间。

#### C6. Time2Stop: Adaptive and Explainable Human-AI Loop for Smartphone Overuse Intervention
- 会议：CHI 2024（DOI 10.1145/3613904.3642747）
- 作者：Orzikulova 等（含 Xuhai Xu、Anind K. Dey）
- 链接：<https://arxiv.org/abs/2403.05584>
- 读它的理由：用机器学习选干预时机，给出解释，再根据用户反馈改进；做了 8 周、71 人的实地研究。
- 切入点：我们的小瞳气泡什么时候弹，可以借鉴它的方法：根据用眼负荷选时机，并解释原因。

### C. 健康场景下的大模型对话与解释

#### C7. Understanding the Benefits and Challenges of Deploying Conversational AI Leveraging Large Language Models for Public Health Intervention
- 会议：CHI 2023（最佳论文）
- 作者：Eunkyung Jo, Daniel A. Epstein, Hyunhoon Jung, Young-Ho Kim
- 链接：<https://dblp1.uni-trier.de/rec/conf/chi/JoEJK23.html>
- 读它的理由：大模型健康对话系统的真实部署研究，访谈了用户、话务员、开发者三方。
- 切入点：参考它来写「AI 解读 / AI 综合建议」的部署研究。

#### C8. Conversational AI in health: Design considerations from a Wizard-of-Oz dermatology case study with users, clinicians and a medical LLM
- 会议：CHI EA 2024（扩展摘要，非正式长文）
- 链接：<https://dl.acm.org/doi/10.1145/3613905.3651891>
- 读它的理由：用户、医生、医疗大模型三方的研究设计，篇幅短，读得快。

### 相邻会议 / 期刊（不是 CHI，可选读）

- **EyeSpyVR: Interactive Eye Sensing Using Off-the-Shelf, Smartphone-Based VR Headsets**（ACM IMWUT 2018）<https://dl.acm.org/doi/10.1145/3214260>
  用手机前置摄像头做眨眼检测和粗略视线追踪。
- **The Impact of Explanations on Layperson Trust in Artificial Intelligence–Driven Symptom Checker Apps**（JMIR 2021）<https://www.ncbi.nlm.nih.gov/pmc/articles/PMC8600426/>
  不同的解释方式对普通人信任度的影响。

---

## 三、候选选题与先读论文

| 选题 | 会议 | 一句话贡献 | 先读 |
|---|---|---|---|
| 手机前置摄像头多任务眼健康筛查（眨眼时序、角膜映光 / 注视、虹膜测距），信号质量差时主动报告「无法评估」 | MICCAI | 不用额外硬件的视频多任务模型，外加可证明的拒判机制 | M1、M2、M6、M7 |
| 规则判等级、大模型做解释的家庭眼健康自测：解释是否可信、是否安全 | MICCAI 或 CHI | 规则 + 知识库检索 + 输出校验的解释框架，并做用户研究 | M5、C7、C8、C3 |
| 主动式眼健康助手：拟人化干预（小瞳、护眼操）对用眼行为和家庭自测数据质量的影响 | CHI | 多周实地研究，比较小瞳提醒、护眼操、普通通知 | C1、C4、C5、C6、C2 |

### 本项目已具备、可直接支撑论文的系统能力

- 共用核心（安卓 / iOS）：干眼眨眼时序、眼位遮盖与跟随、老视近视力与调节、色觉假同色图（按视锥单独刺激生成）
- 数据质量机制：测前快问、测试中异常暂停（方案二）、视标随距离实时缩放、数据不足时拒判
- 主动护眼：实时眼部检测（可后台持续）、用眼负荷评估、小瞳气泡提醒、护眼操（虹膜偏移判断视线方向）
- AI：规则判等级 + DeepSeek 解读 / 综合建议（知识库检索、输出校验、分项同意、去标识）

---

## 四、建议阅读顺序

1. 先读 C1、C3、M1、M2，用来确定写法和选题。
2. 再按选定的方向，读上表中对应的几篇。
3. 最后读其余几篇，补充相关工作部分。

## 五、补充检索建议

检索工具收录 CHI 不全，有关护眼休息提醒的 CHI 正式长文这次没找到。建议在 ACM Digital Library 用以下关键词补搜：

- digital eye strain、blink、computer vision syndrome
- vision screening、self-testing、at-home testing
- receptivity、just-in-time adaptive intervention
- personal informatics、explainable AI patients
