# feature:home

首页 = 主动眼健康助手：标题「今日」与日期（右上角账户头像 + 白天 / 夜晚切换 + 系统设置齿轮）→ 大号光流仪表圈包住眼健康指数（不加卡片框）→ 基础数据 →
功能卡 → 今日感受 → 休息提醒 → 实时检测 → 开发者工具（模型测试）。
- 功能卡（`FeatureCard`，2026-11-06）：一张玻璃卡里上排三个等高彩色方块 `VividTile`——护眼操（绿，只写「约 1 分钟」，点了直接进护眼操，没有「开始」按钮）、
  用眼趋势（蓝）、亮度与蓝光（珊瑚红），下排整行紫色的 AI 主动问诊（`ChatEntryCard`）。色块取 `core:designsystem` 的 `vivid` 渐变令牌，文字与图标一律白色，
  左上白色半透明底图标块、右下角放大的半透明图标做装饰。首页不再用彩色文字做强调，颜色靠色块。
不分小节标题；首页不放眨眼频率、观看距离等读数（看实时检测即可），也不放专业咨询建议入口（`AdvicePage` 仍在，从其他入口进入）。
本人档案不在首页单独成卡（仍参与评估，在「我的 → 家庭成员」维护）；报告里的建议（`tips`）目前不在首页展示；「休息 20 秒」页（`RestBreakPage`）暂无入口，保留待用。
与苹果端 `EyeHome.swift` 同一顺序、排版与文案（苹果端没有开发者工具，也没有系统不提供的数据卡片）。说明文字一律收进标题旁的「?」（`EyeHelpButton`）。

- `HomeScreen` / `HomeContent`：页面入口与无副作用呈现。跳转经 `HomeNavigation`（账户、设置、模型测试、打开某项自测、编辑本人资料、趋势、建议、亮度）由 app 接线，
  数据操作经 `HomeActions`（提醒开关与间隔、打卡、打开「使用情况访问」）。
- `HomeViewModel` / `HomeUiState`：合并提醒状态、`EyeCareRepository`（账本 + 系统用屏 + 光线 / 亮度）、本人资料（`SubjectRepository`）与本人近期自测（`ScreeningRecordRepository.localRecords`），
  由共用 `EyeCareEngine.report` 得到 `EyeCareReport`，并用 `EyeCareAdvice.request` 预备 AI 综合建议的去标识摘要；`aiAdvice` / `generateAdvice` 走 `core:data` 的 `AdviceRepository`。
  回到前台（`ON_RESUME`）刷新用屏统计。
- 今日用眼仪表圈：页面正中 296dp 光流圆环（`EyeProgressRing`），不加卡片框；圈内显示眼健康指数（= 100 − 用眼负荷分，圈越满越健康，规则与后端仍按负荷分）；自上而下居中：「眼健康指数」（卡片标题字号）、
  大号负荷分与「/100」、等级徽标（轻松 / 留意 / 偏累 / 待感知）；不放建议与操作按钮。
- 今日感受卡：标题行右侧「记录」/「修改」，卡上只显示已记下的感受标签（有不适提醒色、没有不适绿色）；选择在 `FeelingDialog` 弹窗里，
  「没有不适」占一整行、其余等宽两列，选了才能「保存」。不加说明小字。与苹果端 `CheckInCard` / `FeelingSheet` 一致。
- 基础数据卡只在有读数时出现：屏幕时间、最长连续、夜间使用、休息次数（UsageStatsManager，需「使用情况访问」，未授权时卡上为「去开启」）、环境光（光线传感器）与屏幕亮度。
- `live/LiveEyeTracker`（进程级单例）+ `live/LiveEyeService`（相机类前台服务）：实时检测**开始后离开首页、退到后台都继续**，
  直到点「停止检测」、点常驻通知里的「停止」或从最近任务划掉应用（`onTaskRemoved`）。首页可见时相机绑定到首页并接预览，
  离开后改为无预览、只分析，绑定到服务的生命周期；熄屏暂停、亮屏解锁后继续；每 10 分钟或跨天把一段统计
  （`LiveEyeMonitor.takeSession`）记入账本。服务只在前台由用户打开时启动（Android 14 起相机类前台服务的要求），被系统杀掉不自动重启。
  测试页接管相机时后台绑定作废，回到首页再接上。苹果端不做后台检测（系统不允许后台使用相机）。
- `LiveEyeSection` / `LiveEyeViewModel`：首页最后一张卡「实时检测」，**始终展开**、**默认不检测**：整宽正方形取景框（`Viewfinder`，四角取景角标）+ 「开始检测 / 停止检测」按钮（不再用开关）。
  未检测时取景框为浅主色底 + 人头肩形虚线框占位；检测中铺满镜像预览（FILL_CENTER / aspectFill 裁成正方形，关键点按同样的居中放大换算坐标），
  下方三格读数（眨眼、近 1 分钟、距离）+ 睁闭眼状态与 EAR / 帧率。ViewModel 只把开始 / 停止与预览接上 / 摘下转给 `LiveEyeTracker`。读数下方「显示眼部关键点」开关（`LiveEyePreferencesRepository.showLandmarks`，默认关闭、持久保存；苹果端 `EyeLiveModel.showLandmarks`，UserDefaults `eye.liveShowLandmarks`）打开才叠加双眼关键点（眼睑 6 点轮廓 + 虹膜圈，闭眼变红），只影响画面、检测照常；
  眨眼次数、近 1 分钟次数、睁眼 / 闭眼、EAR 与阈值、帧率、观看距离，下方最近 8 秒 EAR 曲线。逻辑全部在共用 `LiveEyeMonitor`（眨眼判据与干眼测试同一份）；
  相机走 `core:camera` 的 `CameraController`，焦距取相机内参用于测距；记入账本的是眨眼频率、平均距离、过近占比、头部偏转（每段 ≥20 秒才记）。
  画面只在本机处理，不保存、不上传。
- 二级完整页面（`EyeCarePages.kt`）：`EyeTrendScreen`（近 7 天用屏、负荷、眨眼柱状图 + 小结 + 今天的主要原因 + 身体感受）、
  `EyeAdviceScreen`（规则建议、就医建议与建议做的自测）、`EyeBrightnessScreen`（当前亮度与环境光、做法，一键打开系统显示设置）。
- `RestBreakPage`：「休息一下」20-20-20 全屏引导，渐变圆环倒计时、中间大号秒数，可提前结束。
- `EyeExercisePage` / `EyeExerciseViewModel`：「护眼操」全屏页（首页入口卡）。提示向左 / 右 / 上 / 下看（两遍），
  前置相机看虹膜在眼裂里的偏移（共用 `EyeExerciseCoach`，先看中间的圆点 1.5 秒取基线），转到位即整页朝该方向翻页、振动，注视点（准星）滑出画面再回来；
  最后闭眼休息 5 秒，每过一秒响一声 `eye_count`（教练的 `COUNTDOWN` 事件）。布局：固定深色页，上方箭头 + 动作，注视点在屏幕正中，下方倒计时与提示语。实时检测开着时借用其相机帧，否则自己开相机、做完即关；没有相机权限时按时间带着做。
- navigation/HomeNavigation：`HomeRoute` / `EyeTrendRoute` / `EyeAdviceRoute` / `EyeBrightnessRoute`，`homeScreen(navigation)`、`eyeCareScreens(onBack, onOpenTest)`。

依赖 core:common、core:domain、core:model、core:camera、core:data、core:notifications（常驻通知渠道）、core:designsystem、core:ui；
清单声明 `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CAMERA` 与 `LiveEyeService`（`foregroundServiceType="camera"`）。导航回调由 app 装配。
评分阈值为工程默认值（见 `shared/SHARED.md`「主动护眼」），待眼科专家确认。

本地界面检查：`./gradlew.bat :feature:home:testDebugUnitTest`，截图在 `feature/home/build/reports/ui/` 并同步到 `docs/界面截图-2026-10-23/安卓/`。
- AI 综合建议对话（`EyeChatPage.kt`）：由爱尔眼科数字人「爱科 Eyecho」主持（真人形象，入口为玻璃卡 + 头像 + 「聊聊今天的用眼感受」）。
  图片在 `core:designsystem` 的 `drawable-nodpi/eyecho_avatar.png`（256 圆形头像）与 `eyecho_half.png`（半身、底边平切），
  苹果端同名素材 `EyechoAvatar` / `EyechoHalf`；`EyechoAvatar(size)` 用在入口、对话气泡、「正在想」与同意弹窗。
  首页功能卡下排的 `ChatEntryCard`（紫色渐变色块 + 爱科头像 + 白色标题）→ `EyeChatScreen`（`EyeChatRoute`）：顶部 `EyechoIntro`（半身像站在卡片底边 + 自我介绍），
  接着是综合建议卡（`AiExplanationCard(text = AiCardText.ADVICE)`，取自 `HomeViewModel.aiAdvice`），下面是与爱科的对话（`ChatViewModel` → `core:data` `ChatRepository`），
  未登录提示去登录；按钮一律「发送」，同意说明不在页面上，点发送时按需弹 `ChatConsentDialog`（还没同意时每次都弹；已同意但没勾「以后不再提示」时每次进页面首次发送弹一次；
  「不同意」只关闭、保留输入）；提到危险信号时在提问下方醒目提示尽快就医；记录只存本机，可清空。与苹果端 `AiChatView.swift` 一致。
  逐步问询：选「帮我一步步梳理眼部不适」并发送，爱科一次问一个关键问题；问题卡支持选项与自由回答，选项只填入输入框，确认发送后才进入下一轮。
  历史问题只读；当前问题重启后仍可回答；最后确认补充后生成五段总结，也可发送「结束问询」或直接换话题。协议与流程见 `backend/server/SERVER.md`。
