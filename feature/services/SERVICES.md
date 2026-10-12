# feature:services

> 眼病症自检服务集合：干眼 / 老视 / 斜视三项测试，流程对齐《智能眼科-整合9.18》（对照表见 `docs/三病种流程对齐-2026-10-11.md`）。

## 职责

- 服务页列出病症检测入口（`EyeSelectCard`）
- 干眼自测：问卷 → 视标练习 → 60 秒自然眨眼 + 紧接 20 秒持续睁眼的功能性连续视力测试，同步记录眨眼
- 提供通用的「摄像头预览 + 眼部关键点标注」检测界面组件

## 结构

### 页面与导航

| 类型 | 说明 |
|---|---|
| `ServicesScreen` | 服务首页：三个 `EyeSelectCard` 入口 |
| `DryEyeScreen` | 干眼自测页（动画引导、距离门控、两阶段记录、折叠结果曲线） |
| `ScreeningScreens` | 老视 / 斜视选择式问卷、环境检查、大字及语音引导、无预览测试与感知结果页 |
| `ScreeningTestViewModel` / `ScreeningMetrics` | 两病种测试状态机、共享相机帧与光照采集、实时距离状态；斜视另含前摄估计距离/头姿质量门控、瞳孔—角膜反光候选点质量标记及归一化眼位指标 |
| `ScreeningAssessment` | 质量门控 + 规则评估：老视按 Hofstetter 15−0.25×年龄 给出三档调节状态；斜视按 Hirschberg 估算 HDE/VDE（Δ）、遮盖再注视、BSI 输出「未见异常 / 可疑阳性 / 高度疑似斜视」；`StrabismusMeasures` 为可单测的纯计算 |
| `ScreeningProtocol` | 老视 / 斜视的原文参数与候选阈值、遮盖动作序列、追踪轨迹（±10°）、问卷题目 |
| `DryEyeProtocol` | 干眼协议参数、阶梯 `DryEyeStaircase`、评估 `DryEyeAssessment`、问卷 `dryEyeQuestions` |
| `Questionnaire` | 三病种共用的逐题问卷：`key` 存储字段（评估读取，保持稳定）与 `title` 大白话问题分离；整行选项、单选自动翻页、进度条、条件追问 |
| `ReportComponents` | 三病种共用的报告排版：结论卡（等级徽标 + 一句话结论 + 建议 + 小瞳）→ 关键指标两列卡片（按正常 / 需关注着色）→ 需要关注 → 折叠的详细数据与问卷回答 |
| `ScreeningHistory`（在 ScreeningMetrics.kt） | 读取本机上一次同病种记录，结果页「与上次对比」 |
| `ScreeningOptotype` / `ScreeningVoice` | 按前摄眼屏距离和系统屏幕像素密度换算视标尺寸及显示门槛；系统中文语音播报 |
| `navigation/ServicesNavigation` | 4 个路由：`ServicesRoute` / `DryEyeRoute` / `PresbyopiaRoute` / `StrabismusRoute` 及对应 navigate / NavGraphBuilder 扩展 |

### 干眼测试（DryEyeTestViewModel）

- 流程：问卷（原文全部条目）→ 准备（提示最佳矫正眼镜、40±5 cm 门禁）→ 练习说明 → 12 秒练习（0.3 起，错/超时放大一档、连对两次缩小一档）→ 正式说明 → 3 秒倒计时 → 自然眨眼 60 秒 → **不停顿**转入持续睁眼 20 秒 → 结果。
- 正式两段全程辨认视标：每题最多 2 秒、超时记错，作答后 300 ms 出下一题，按实测距离换算视标像素，超出本机像素能力自动封顶。
- 眨眼走事件队列（`ConcurrentLinkedQueue`），不会因轮询丢失；测试中双眼不可见 > 1.5 秒或距离超出 30–50 cm > 3 秒判为中断（结果标记无效）。
- 结果：有效性 + 低/中/高风险 + 主要异常指标（眨眼 10–16 次/分、睁眼阶段最长持续睁眼 ≥ 10 秒、视觉维持率、视力下降时间）+ 就医建议；临界时可「延长测试（自然眨眼 3 分钟）」，可「闭眼休息 1 分钟后复测」并与上一次对比。原始逐题数据写入 `dryeye_<时间>.json`。
- 与首页共享 CameraController 帧流；进入后台会中断本次测试，允许随时停止。

### 交互：语音作答与距离反馈

- `VoiceAnswer.kt`：测试中说「上 / 下 / 左 / 右」作答（「看不清」记错），移近法说「模糊」确认、「开始」开始；近视力换眼时说「好了」。`VoiceCommandParser` 为纯函数（含常见同音字、取最后提到的方向）；`VoiceAnswerRecognizer` 用部分结果尽早出结果，优先设备端识别，不支持中文时回退系统识别服务；TTS 播报期间忽略识别结果，防止把「左眼」「右眼」当作答案。逐题记录 `byVoice`（反应时含识别延迟）。不可用或未授权麦克风时回退按钮。
- 近视力改为停稳后视标自动出现，整个视力类任务可以完全不碰屏幕。
- `DistanceFeedback.kt`：`distanceStatus` 分区（太近 / 合适 / 太远 / 未知）；`DistanceBanner` 整块红 / 绿 / 蓝色块 + 方向箭头 + 具体移动厘米数 + 示意标尺，进入异常区间时轻震；`DistanceEdgeGlow` 屏幕四周呼吸光边，余光可察觉；语音提示「太近了，请把手机往远处移」等，偏离持续时每 6 秒重复，回到区间即停。

### 视觉与引导

- `TestGuide`：十二种卡通方法演示（POSTURE/ACUITY/FVA/BLINK/OPEN/NEAR/MOVE/CURVE/SWITCH/FIXATE/COVER/FOLLOW），演示内容与原文任务一一对应（如遮盖按 右2s→移开2s→左2s→移开2s→右左各1s×2），吉祥物「小瞳」在 320×200 舞台单位的 Canvas 中出镜；每个场景配 2–3 个与动画同步高亮的分镜步骤，可点「暂停演示」，系统移除动画时停在关键帧。`guidePending` 期间不启动正式计时、不计入采样，确认后才进入任务。
- `ScreeningGuidance`：距离和光照映射为简短行动提示；保留原始呈现、作答和医学质量门控。遮盖任务不因单眼不可见提示移开遮盖。
- 问卷逐题展示；距离、光照和详细结果通过 `EyeDisclosure` 默认收起。
- 视标保持固定黑白对比及原有物理尺寸算法，与界面深浅主题无关。
- 本地验证：`./gradlew.bat :feature:services:testDebugUnitTest`；截图输出 `feature/services/build/reports/ui/`，不连接设备。

### 通用检测组件（detection/）

| 类型 | 说明 |
|---|---|
| `DetectionViewModel` | 经 `core:camera/CameraController` 启动**带预览**的相机管线，业务侧算距离（Depth-from-Iris）与眨眼（EAR），输出 `DetectionUiState`（人脸/关键点/距离/眨眼）；分析线程只投 `CONFLATED` 通道，由协程统一写状态 |
| `DetectionContent` | 通用检测界面：预览 + 关键点标记 + 距离/眨眼指标行 |
| `EyeOverlay` | 预览上自绘眼部关键点（红点 + 虹膜中心） |

### 视标

| 类型 | 说明 |
|---|---|
| `VisionOptotype.kt` | `TumblingE(direction, size, color)` 自绘 E 字视标（0=右 1=下 2=左 3=上）；`OPTOTYPE_SIZES_DP` 三档（150/105/65dp） |

## 依赖关系

- **依赖**：`core:camera`（`FaceLandmarkerAnalyzer`、`CameraIntrinsicsProvider`）、`core:domain`（`BlinkDetector`、`DepthFromIris`）、`core:model`、`core:designsystem`、`core:ui`；vico、material3（Slider）
- **被依赖**：`app`（NavHost 挂 servicesScreen / dryEyeScreen / presbyopiaScreen / strabismusScreen）

## 注意事项

- 本模块与 `core:camera` 的集成方式是「自带预览绑定」：`FaceLandmarkerAnalyzer` 共享单例引擎（回调列表机制），但相机生命周期由本模块 ViewModel 管理
- 干眼测试的计时/相位状态在 ViewModel 内协程驱动，`onCleared` 时务必停相机
- 老视流程：问卷（「近用 Add」不再单独询问，由「平时戴什么眼镜」推断，见 `nearCorrectionInUse`）→ 「这次测试戴哪副眼镜」→ 40 cm 近视力（右/左/双）→ 连续移近法 ×3（≥45 cm 起、速度 > 5 cm/秒本次不计）→ 多距离视力 60/33/25 cm → 远近切换 60 秒 → 结果
- 斜视流程：问卷 → 33±5 cm → 卡通闪烁注视 12 秒 → 交替遮盖 9 个动作（协助者点按标记真实动作时刻，界面给出保持计时）→ 追踪 22 秒（±10° 按实测距离换算，屏幕不足时缩小并记录）→ 结果
- 原文中被批注删除的「远近注视切换」未实现；参数冲突与实现约定见 `docs/三病种流程对齐-2026-10-11.md`
