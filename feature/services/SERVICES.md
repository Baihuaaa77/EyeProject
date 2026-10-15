# feature:services

三项协议、状态、问卷模型、状态机、计算规则、注视监督和语音指令解析已迁到 `shared/screening/src/commonMain/kotlin`。安卓 ViewModel 仅连接 `AndroidScreeningPlatform`；修改规则必须修改共享目录。iOS 工程与当前验收边界见 `docs/iOS适配与双端构建.md`。

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
| `StrabismusGaze` | 斜视任务的五点注视校准与宽容监督：各眼独立映射到屏幕坐标，允许单眼有效跟随；实际显示轨迹与眼动比较，避免同步静止或看别处被判为协调正常 |
| `ReportComponents` | 三病种共用的报告排版：结论卡（等级徽标 + 一句话结论 + 建议 + 小瞳）→ 关键指标两列卡片（按正常 / 需关注着色）→ 需要关注 → 折叠的详细数据与问卷回答 |
| `ScreeningHistory`（在 ScreeningMetrics.kt） | 读取本机上一次同病种记录，结果页「与上次对比」 |
| `ScreeningOptotype` / `ScreeningVoice` | 按前摄眼屏距离和系统屏幕像素密度换算视标尺寸及显示门槛；系统中文语音播报 |
| `navigation/ServicesNavigation` | 4 个路由：`ServicesRoute` / `DryEyeRoute` / `PresbyopiaRoute` / `StrabismusRoute` 及对应 navigate / NavGraphBuilder 扩展 |

### 干眼测试（DryEyeTestViewModel）

- 流程：问卷（原文全部条目）→ 准备（提示最佳矫正眼镜、40±5 cm 门禁）→ 练习说明 → 12 秒练习（0.3 起，错/超时放大一档、连对两次缩小一档）→ 正式说明 → 3 秒倒计时 → 自然眨眼 60 秒 → **不停顿**转入持续睁眼 20 秒 → 结果。
- 正式两段全程辨认视标：每题最多 2 秒、超时记错，作答后 300 ms 出下一题，按实测距离换算视标像素，超出本机像素能力自动封顶。
- 眨眼走事件队列（`ConcurrentLinkedQueue`），不会因轮询丢失；测试中双眼不可见 > 1.5 秒或距离超出 30–50 cm > 3 秒判为中断（结果标记无效）。
- 结果：有效性 + 低/中/高风险 + 主要异常指标（眨眼 10–16 次/分、睁眼阶段最长持续睁眼 ≥ 10 秒、视觉维持率、视力下降时间）+ 就医建议；临界时可「延长测试（自然眨眼 3 分钟）」，可「闭眼休息 1 分钟后复测」并与上一次对比。原始逐题数据写入 `dryeye_<时间>.json`。
- 使用 CameraController 帧流；进入后台会中断本次测试，可从设置菜单停止。

### 交互：语音作答与距离反馈

- `TestFeedbackEffect` 接入共用 `ScreeningFeedbackSession`：开始、阶段切换、倒计时、有效作答／跳过、标定点切换、校准失败、遮盖动作及保持结束、完成时提供短音与轻触觉。作答不区分对错，完成音也不区分风险结论。固定距离持续异常 800 ms 后提醒一次；短暂丢脸不重置同一区间的提醒，恢复合适 800 ms 后轻提示一次，再次告警至少间隔 5 秒；连续追踪与注视闪烁不逐帧反馈。
- 音效／震动默认开启，独立开关集中在系统设置并保存；菜单可点「试一下反馈」独立验证输出，普通测试事件在菜单、权限弹窗、后台期间丢弃，返回不补播。安卓 SoundPool 使用媒体音量，静音铃声和关闭系统按键音不再阻断测试音效；Vibrator 使用交互内容的媒体震动用途和系统预定义轻震动，Android 8–9 使用 25 ms 短脉冲兼容，不依赖 View 的触摸反馈调用，也不绕过系统勿扰限制。首次音效载入最多等待 300 ms，关闭页面或切后台即丢弃。iOS 使用 UI 系统音效与轻量反馈生成器。音效为 `shared/feedback/res/raw` 的同一套原创 WAV，生成脚本在 `scripts/generate_feedback_sounds.py`。短音不打断 TTS，麦克风按音长加尾音暂停 110–320 ms，旧识别会话丢弃后恢复；计时、视标和评分不等待反馈。

- `TestSessionLayout` 在进行中的任务里居中呈现内容，保留小屏滚动；顶栏仅保留返回、测试名和实心齿轮。需要固定距离的任务在顶栏下常驻距离横幅，合适时也保留标题、距离和小标尺，状态变化不挤动测试舞台。移近法和遮盖仍不提示固定距离。移除重复目的说明与实时采集计数；校准目标仍上报实际绘制位置，视标物理尺寸、计时与质量门控不变。
- `TestStageCard` 为测试区域增加主色细框和轻阴影；E 周围增加固定留白的主色圆框，箭头与跳过按钮增加细框及浅阴影。装饰不增加说明文字，不缩放视标或改变作答门控；iOS 同步呈现。
- 齿轮打开「系统设置」，三项测试共用并持久化语音播报、语音作答偏好；麦克风授权、识别状态、测量详情与干眼停止操作集中在菜单中。测试界面不放语音开关或常驻语音提示栏。

- 所有看 E 选方向的练习和正式题共用 `OptotypeAnswerPad`：四个箭头在视标四周，下方提供「看不清 / 跳过」。该选项只提交当前题无法辨认（-1），沿用原有评分与阶梯；视标未显示时五个按钮全部禁用。安卓与 iOS 同步布局，视标物理尺寸不变。

- `VoiceAnswer.kt`：测试中说「上 / 下 / 左 / 右」作答（「看不清」记错），移近法说「模糊」确认、「开始」开始；近视力换眼时说「好了」。`VoiceCommandParser` 为纯函数（含常见同音字、取最后提到的方向）；`VoiceAnswerRecognizer` 用部分结果尽早出结果，优先设备端识别，不支持中文时回退系统识别服务；TTS 播报期间忽略识别结果，防止把「左眼」「右眼」当作答案。逐题记录 `byVoice`（反应时含识别延迟）。不可用或未授权麦克风时回退按钮。
- 近视力改为停稳后视标自动出现，整个视力类任务可以完全不碰屏幕。
- `DistanceFeedback.kt`：`distanceStatus` 分区（太近 / 合适 / 太远 / 未知）；`DistanceBanner` 整块红 / 绿 / 蓝色块 + 方向箭头 + 具体移动厘米数 + 示意标尺，进入异常区间时轻震；`DistanceEdgeGlow` 屏幕四周呼吸光边，余光可察觉；语音提示「太近了，请把手机往远处移」等，偏离持续时每 6 秒重复，回到区间即停。

### 视觉与引导

- `TestGuide`：十二种卡通方法演示（POSTURE/ACUITY/FVA/BLINK/OPEN/NEAR/MOVE/CURVE/SWITCH/FIXATE/COVER/FOLLOW），演示内容与原文任务一一对应（如遮盖按 右2s→移开2s→左2s→移开2s→右左各1s×2），吉祥物「小瞳」在 320×200 舞台单位的 Canvas 中出镜；每个场景配 2–3 个与动画同步高亮的分镜步骤，可点「暂停演示」，系统移除动画时停在关键帧。`guidePending` 期间不启动正式计时、不计入采样，确认后才进入任务。
- `ScreeningGuidance`：距离和光照映射为简短行动提示；保留原始呈现、作答和医学质量门控。遮盖任务不因单眼不可见提示移开遮盖。
- 问卷逐题展示；距离、光照和详细结果通过 `EyeDisclosure` 默认收起。
- 三个测试共用中文发声人选择、语速（1.08）和音调（1.0），按 TTS 引擎保存发声人名称。播报及结束后 600 ms 暂停收听，恢复时旧识别会话不可提交答案。设备端识别不可用时直接启用随 APK 打包的 Vosk 中文短指令模型；系统识别失败也回退到内置模型，离线录音不保存、不上传。界面明确显示模型准备和实际收听状态；麦克风权限仍需用户授予。模型来源、哈希和 Apache-2.0 许可证见 `src/main/assets/offline-voice-README.md`。
- 斜视正式注视前增加约 15 秒五点校准，每点等待 1.2 秒后采样 1.8 秒，校准失败停留并提供重试；正式注视仍为 12 秒。以实际绘制位置及分析帧单调时间对齐，给出约 25% 屏幕宽、20% 屏幕高的容差，追踪允许最多 600 ms 延迟；持续明显偏离时提示，整体有效注视不足或任一追踪段不匹配时拒判并要求重测。遮盖时只监督未遮眼；短暂眨眼不作为偏离，另一眼的异常仍保留供原斜视指标分析。屏幕注视估计仅为任务质量监督，准确度仍需真机验证。
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
