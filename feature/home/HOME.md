# feature:home

首页 = 主动眼健康助手：问候与日期（右上角账户头像 + 系统设置齿轮）→ 今日用眼（负荷分圆环、会说话的小瞳、「感知 → 评估 → 归因 → 干预 → 反馈」五段进度）→
今日感受打卡 → 多源感知（基础数据 / 视觉行为与实时检测）→ 主动服务（休息提醒、用眼趋势报告、AI 综合建议、亮度与蓝光）→ 开发者工具（模型测试）。
本人档案不在首页单独成卡（仍参与评估，在「我的 → 家庭成员」维护）；原「为你推荐」改为小瞳的气泡提醒。
与苹果端 `EyeHome.swift` 同一顺序、排版与文案（苹果端没有开发者工具，也没有系统不提供的数据卡片）。说明文字一律收进标题旁的「?」（`EyeHelpButton`）。

- `HomeScreen` / `HomeContent`：页面入口与无副作用呈现。跳转经 `HomeNavigation`（账户、设置、模型测试、打开某项自测、编辑本人资料、趋势、建议、亮度）由 app 接线，
  数据操作经 `HomeActions`（提醒开关与间隔、打卡、打开「使用情况访问」）。
- `HomeViewModel` / `HomeUiState`：合并提醒状态、`EyeCareRepository`（账本 + 系统用屏 + 光线 / 亮度）、本人资料（`SubjectRepository`）与本人近期自测（`ScreeningRecordRepository.localRecords`），
  由共用 `EyeCareEngine.report` 得到 `EyeCareReport`，并用 `EyeCareAdvice.request` 预备 AI 综合建议的去标识摘要；`aiAdvice` / `generateAdvice` 走 `core:data` 的 `AdviceRepository`。
  回到前台（`ON_RESUME`）刷新用屏统计。
- 今日用眼卡：`core:ui` 的 `XiaoTongTalk` 依次弹出报告里的 `tips`（共用 `EyeCareEngine` 生成，不朗读），每句约 5 秒；
  可执行的气泡带按钮字（去休息 / 开启 / 去调节 / 去自测 / 去开启 / 去检测 / 去完善），点气泡直接去做。点小瞳立刻换下一句。
  负荷分 30→80 对应嫩芽打蔫 0→1；负荷偏高时小瞳为「疲惫」神情。
- 今日感受卡与「视觉行为」同样的卡片标题（图标块 + 标题），打卡后显示结果与「修改」。
- 基础数据卡只在有读数时出现：屏幕时间、最长连续、夜间使用、休息次数（UsageStatsManager，需「使用情况访问」，未授权时卡上为「去开启」）、环境光（光线传感器）与屏幕亮度。
- `live/LiveEyeTracker`（进程级单例）+ `live/LiveEyeService`（相机类前台服务）：实时检测**打开后离开首页、退到后台都继续**，
  直到关掉开关、点常驻通知里的「停止」或从最近任务划掉应用（`onTaskRemoved`）。首页可见时相机绑定到首页并接预览，
  离开后改为无预览、只分析，绑定到服务的生命周期；熄屏暂停、亮屏解锁后继续；每 10 分钟或跨天把一段统计
  （`LiveEyeMonitor.takeSession`）记入账本。服务只在前台由用户打开时启动（Android 14 起相机类前台服务的要求），被系统杀掉不自动重启。
  测试页接管相机时后台绑定作废，回到首页再接上。苹果端不做后台检测（系统不允许后台使用相机）。
- `LiveEyeSection` / `LiveEyeViewModel`：视觉行为卡里的「实时眼部检测」开关，**默认关闭**；ViewModel 只把开关与预览接上 / 摘下转给 `LiveEyeTracker`。开启后镜像预览叠加双眼关键点（眼睑 6 点轮廓 + 虹膜圈，闭眼变红），
  眨眼次数、近 1 分钟次数、睁眼 / 闭眼、EAR 与阈值、帧率、观看距离，下方最近 8 秒 EAR 曲线。逻辑全部在共用 `LiveEyeMonitor`（眨眼判据与干眼测试同一份）；
  相机走 `core:camera` 的 `CameraController`，焦距取相机内参用于测距；记入账本的是眨眼频率、平均距离、过近占比、头部偏转（每段 ≥20 秒才记）。
  画面只在本机处理，不保存、不上传。
- 二级完整页面（`EyeCarePages.kt`）：`EyeTrendScreen`（近 7 天用屏、负荷、眨眼柱状图 + 小结 + 今天的主要原因 + 身体感受）、
  `EyeAdviceScreen`（规则建议、就医建议与 AI 综合建议卡 `AiExplanationCard(text = AiCardText.ADVICE)`）、`EyeBrightnessScreen`（当前亮度与环境光、做法，一键打开系统显示设置）。
- `RestBreakPage`：「休息一下」20-20-20 全屏引导，小瞳陪伴，可提前结束。
- `EyeExercisePage` / `EyeExerciseViewModel`：「护眼操」全屏页（主动服务里的入口卡、或小瞳气泡「去做」）。小瞳提示向左 / 右 / 上 / 下看（两遍），
  前置相机看虹膜在眼裂里的偏移（共用 `EyeExerciseCoach`，先看小瞳 1.5 秒取基线），转到位即整页朝该方向翻页、振动，小瞳飞出画面打滚再回来；
  最后闭眼休息 5 秒。实时检测开着时借用其相机帧，否则自己开相机、做完即关；没有相机权限时按时间带着做。
- navigation/HomeNavigation：`HomeRoute` / `EyeTrendRoute` / `EyeAdviceRoute` / `EyeBrightnessRoute`，`homeScreen(navigation)`、`eyeCareScreens(onBack, onOpenTest)`。

依赖 core:common、core:domain、core:model、core:camera、core:data、core:notifications（常驻通知渠道）、core:designsystem、core:ui；
清单声明 `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CAMERA` 与 `LiveEyeService`（`foregroundServiceType="camera"`）。导航回调由 app 装配。
评分阈值为工程默认值（见 `shared/SHARED.md`「主动护眼」），待眼科专家确认。

本地界面检查：`./gradlew.bat :feature:home:testDebugUnitTest`，截图在 `feature/home/build/reports/ui/` 并同步到 `docs/界面截图-2026-10-23/安卓/`。
