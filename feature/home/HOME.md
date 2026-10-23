# feature:home

首页 = 主动眼健康助手：问候与日期（右上角账户头像 + 系统设置齿轮）→ 今日用眼（负荷分圆环、小瞳、「感知 → 评估 → 归因 → 干预 → 反馈」五段进度）→
今日感受打卡 → 为你推荐 → 多源感知（基础数据 / 视觉行为与实时检测 / 个体档案）→ 主动服务（休息提醒、用眼趋势报告、AI 综合建议、亮度与蓝光）→ 开发者工具（模型测试）。
与苹果端 `EyeHome.swift` 同一顺序、排版与文案（苹果端没有开发者工具，也没有系统不提供的数据卡片）。说明文字一律收进标题旁的「?」（`EyeHelpButton`）。

- `HomeScreen` / `HomeContent`：页面入口与无副作用呈现。跳转经 `HomeNavigation`（账户、设置、模型测试、打开某项自测、编辑本人资料、趋势、建议、亮度）由 app 接线，
  数据操作经 `HomeActions`（提醒开关与间隔、打卡、打开「使用情况访问」）。
- `HomeViewModel` / `HomeUiState`：合并提醒状态、`EyeCareRepository`（账本 + 系统用屏 + 光线 / 亮度）、本人资料（`SubjectRepository`）与本人近期自测（`ScreeningRecordRepository.localRecords`），
  由共用 `EyeCareEngine.report` 得到 `EyeCareReport`，并用 `EyeCareAdvice.request` 预备 AI 综合建议的去标识摘要；`aiAdvice` / `generateAdvice` 走 `core:data` 的 `AdviceRepository`。
  回到前台（`ON_RESUME`）刷新用屏统计。
- 基础数据卡只在有读数时出现：屏幕时间、最长连续、夜间使用、休息次数（UsageStatsManager，需「使用情况访问」，未授权时卡上为「去开启」）、环境光（光线传感器）与屏幕亮度。
- `LiveEyeSection` / `LiveEyeViewModel`：视觉行为卡里的「实时眼部检测」开关，**默认关闭**。开启后镜像预览叠加双眼关键点（眼睑 6 点轮廓 + 虹膜圈，闭眼变红），
  眨眼次数、近 1 分钟次数、睁眼 / 闭眼、EAR 与阈值、帧率、观看距离，下方最近 8 秒 EAR 曲线。逻辑全部在共用 `LiveEyeMonitor`（眨眼判据与干眼测试同一份）；
  相机走 `core:camera` 的 `CameraController`，焦距取相机内参用于测距。关闭、离开首页或 `ON_PAUSE` 即释放相机，并把本次会话（≥20 秒）的眨眼频率、平均距离、过近占比、头部偏转记入账本。
  画面只在本机处理，不保存、不上传。
- 二级完整页面（`EyeCarePages.kt`）：`EyeTrendScreen`（近 7 天用屏、负荷、眨眼柱状图 + 小结 + 今天的主要原因 + 身体感受）、
  `EyeAdviceScreen`（规则建议、就医建议与 AI 综合建议卡 `AiExplanationCard(text = AiCardText.ADVICE)`）、`EyeBrightnessScreen`（当前亮度与环境光、做法，一键打开系统显示设置）。
- `RestBreakPage`：「休息一下」20-20-20 全屏引导，小瞳陪伴，可提前结束。
- navigation/HomeNavigation：`HomeRoute` / `EyeTrendRoute` / `EyeAdviceRoute` / `EyeBrightnessRoute`，`homeScreen(navigation)`、`eyeCareScreens(onBack, onOpenTest)`。

依赖 core:common、core:domain、core:model、core:camera、core:data、core:designsystem、core:ui；不采集后台数据。导航回调由 app 装配。
评分阈值为工程默认值（见 `shared/SHARED.md`「主动护眼」），待眼科专家确认。

本地界面检查：`./gradlew.bat :feature:home:testDebugUnitTest`，截图在 `feature/home/build/reports/ui/` 并同步到 `docs/界面截图-2026-10-23/安卓/`。
