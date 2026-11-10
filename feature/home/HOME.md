# feature:home

首页 = 主动眼健康助手，材质遵循 `DESIGN.md`，布局与字阶按用户 2026-11-09 的反馈调整：26 号「今日」标题 + 日期，账户入口移至仪表底部 →
仪表 → 「今日数据」→ 「护眼工具」→ 爱科卡 → 实时检测 → 休息提醒 → 开发者工具（只在可调试包里，`HomeNavigation.onNavigateToTestModel` 为 null 时不显示）。
- 仪表（`EyeCareHero`）：直接绘在页面底色上，无卡片色块、提醒句和重复护眼操按钮。左下起点经顶部向右下充能，底部开口放账户头像；
  中央为暖金 / 浅金 / 银色表情、Nunito Black 数字与等级文字，内圈 40 根等长短刻度。头像点开账户，取代原首页顶栏头像。
  护眼操入口保留在「护眼工具」。空数据只显示空轨、银色待感知表情和破折号；评分和业务规则不变。
- 今日数据（`TodayData`）：只在有基础读数时出现。用屏时长整行（右侧近 7 天小柱图，凹槽内蓝青渐变细胶囊），其余读数两列小卡（类别色图标 + 类别标签、需要留意时右侧琥珀「留意」、
  大号读数「数字大、单位灰」）；屏幕亮度不在这里，写在「亮度与蓝光」工具上。未授权用屏统计时最上面一行「去开启」。
- 护眼工具（`ToolRow`）：三张等高浮雕卡——护眼操（绿，约 1 分钟）、用眼趋势（蓝，近 7 天）、亮度与蓝光（橙，当前亮度），浅色块图标 + 名称 + 一项数据，按下轻缩。
- 爱科卡（`EyechoCard`）：右侧爱科半身像（同色内凹衬底），左侧「爱科 · AI 问诊」、「今天眼睛感觉怎么样？」和快捷感受（前两项症状 +「没有不适」，点一下即记下今日感受；
  「更多」弹出完整的 `FeelingDialog`）；已记下时显示「今天的感受已记下」、记下的感受与「修改」。点卡片其余位置进入对话页。原「今日感受」卡与 AI 主动问诊入口并入此卡。
- 实时检测：未开始时一行（功能图标 + 20 号「实时检测」+ 「开始」；说明小字删除，帮助入口保留），开始后展开取景框与读数（见下方 `LiveEyeSection`）。
- 休息提醒：一行（铃铛 + 名称 + 下次时间 + 开关），开启后下方出现「每 20 / 30 / 60 分钟」分段选择；不给出建议间隔。
分区标题（「今日数据」「护眼工具」）20 号写在卡片外，读数 30 号、单位 14 号。时钟用橙、月亮用紫、休息用绿、环境光用橙；首页不放眨眼频率、观看距离等读数（看实时检测即可），也不放专业咨询建议入口（`AdvicePage` 仍在，从其他入口进入）。
本人档案不在首页单独成卡（仍参与评估，在「我的 → 家庭成员」维护）；报告里的建议（`tips`）目前不在首页展示；「休息 20 秒」页（`RestBreakPage`）暂无入口，保留待用。
与苹果端 `EyeHome.swift` 同一顺序、排版与文案（苹果端没有开发者工具，也没有系统不提供的数据卡片）。说明文字一律收进标题旁的「?」（`EyeHelpButton`）。

- `HomeScreen` / `HomeContent`：页面入口与无副作用呈现。跳转经 `HomeNavigation`（账户、设置、模型测试、打开某项自测、编辑本人资料、趋势、建议、亮度）由 app 接线，
  数据操作经 `HomeActions`（提醒开关与间隔、打卡、打开「使用情况访问」）。
- `HomeViewModel` / `HomeUiState`：合并提醒状态、`EyeCareRepository`（账本 + 系统用屏 + 光线 / 亮度）、本人资料（`SubjectRepository`）与本人近期自测（`ScreeningRecordRepository.localRecords`），
  由共用 `EyeCareEngine.report` 得到 `EyeCareReport`，并用 `EyeCareAdvice.request` 预备 AI 综合建议的去标识摘要；`aiAdvice` / `generateAdvice` 走 `core:data` 的 `AdviceRepository`。
  回到前台（`ON_RESUME`）刷新用屏统计。
- 眼健康指数 = 100 − 用眼负荷分（圈越满越健康，规则与后端仍按负荷分）。
- 今日感受的完整选择在 `FeelingDialog` 弹窗里：「没有不适」占一整行、其余等宽两列，选了才能「保存」。与苹果端 `FeelingSheet` 一致。
- 基础读数：屏幕时间、最长连续、夜间使用、休息次数（UsageStatsManager，需「使用情况访问」）、环境光（光线传感器）与屏幕亮度。
- `live/LiveEyeTracker`（进程级单例）+ `live/LiveEyeService`（相机类前台服务）：实时检测**开始后离开首页、退到后台都继续**，
  直到点「停止检测」、点常驻通知里的「停止」或从最近任务划掉应用（`onTaskRemoved`）。首页可见时相机绑定到首页并接预览，
  离开后改为无预览、只分析，绑定到服务的生命周期；熄屏暂停、亮屏解锁后继续；每 10 分钟或跨天把一段统计
  （`LiveEyeMonitor.takeSession`）记入账本。服务只在前台由用户打开时启动（Android 14 起相机类前台服务的要求），被系统杀掉不自动重启。
  测试页接管相机时后台绑定作废，回到首页再接上。苹果端不做后台检测（系统不允许后台使用相机）。
- `LiveEyeSection` / `LiveEyeViewModel`：首页「实时检测」卡，**默认不检测**：未开始时只有一行与「开始」（2026-11-08 起不再常显空取景框）；
  开始后展开整宽正方形取景框（`Viewfinder`，四角取景角标）+ 「停止检测」按钮。检测中铺满镜像预览（FILL_CENTER / aspectFill 裁成正方形，关键点按同样的居中放大换算坐标），
  下方三格读数（眨眼、近 1 分钟、距离）+ 睁闭眼状态与 EAR / 帧率。ViewModel 只把开始 / 停止与预览接上 / 摘下转给 `LiveEyeTracker`。读数下方「显示眼部关键点」开关（`LiveEyePreferencesRepository.showLandmarks`，默认关闭、持久保存；苹果端 `EyeLiveModel.showLandmarks`，UserDefaults `eye.liveShowLandmarks`）打开才叠加双眼关键点（眼睑 6 点轮廓 + 虹膜圈，闭眼变红），只影响画面、检测照常；
  眨眼次数、近 1 分钟次数、睁眼 / 闭眼、EAR 与阈值、帧率、观看距离，下方最近 8 秒 EAR 曲线。逻辑全部在共用 `LiveEyeMonitor`（眨眼判据与干眼测试同一份）；
  相机走 `core:camera` 的 `CameraController`，焦距取相机内参用于测距；记入账本的是眨眼频率、平均距离、过近占比、头部偏转（每段 ≥20 秒才记）。
  画面只在本机处理，不保存、不上传。
- 二级完整页面（`EyeCarePages.kt`）：`EyeTrendScreen`（页面标题「用眼趋势」：本周要点卡 → 用屏时长、眼健康指数、眨眼频率三张图表卡——类别标签与对比 / 等级、小字说明 + 大号读数、凹槽渐变柱图→ 今天的主要原因（拉低眼健康指数的分值，显示为「−18」）→ 身体感受）、
  `EyeAdviceScreen`（规则建议、就医建议与建议做的自测）、`EyeBrightnessScreen`（当前亮度与环境光、做法，一键打开系统显示设置）。
- `RestBreakPage`：「休息一下」20-20-20 全屏引导，渐变圆环倒计时、中间大号秒数，可提前结束。
- `EyeExercisePage` / `EyeExerciseViewModel`：「护眼操」全屏页（首页入口卡）。提示向左 / 右 / 上 / 下看（两遍），
  前置相机看虹膜在眼裂里的偏移（共用 `EyeExerciseCoach`，先看中间的圆点 1.5 秒取基线），转到位即整页朝该方向翻页、振动，注视点（准星）滑出画面再回来；
  最后闭眼休息 5 秒，每过一秒响一声 `eye_count`（教练的 `COUNTDOWN` 事件）。布局：固定深色页，上方箭头 + 动作，注视点在屏幕正中，下方倒计时与提示语。实时检测开着时借用其相机帧，否则自己开相机、做完即关；没有相机权限时按时间带着做。
- navigation/HomeNavigation：`HomeRoute` / `EyeTrendRoute` / `EyeAdviceRoute` / `EyeBrightnessRoute`，`homeScreen(navigation)`、`eyeCareScreens(onBack, onOpenTest)`。

依赖 core:common、core:domain、core:model、core:camera、core:data、core:notifications（常驻通知渠道）、core:designsystem、core:ui；
清单声明 `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CAMERA` 与 `LiveEyeService`（`foregroundServiceType="camera"`）。导航回调由 app 装配。
评分阈值为工程默认值（见 `shared/SHARED.md`「主动护眼」），待眼科专家确认。

本地界面检查：`./gradlew.bat :feature:home:testDebugUnitTest`，截图仅在 `feature/home/build/reports/ui/`，CI 上传为产物。
- AI 综合建议对话（`EyeChatPage.kt`）：由爱尔眼科数字人「爱科 Eyecho」主持（真人形象，入口为玻璃卡 + 头像 + 「聊聊今天的用眼感受」）。
  图片在 `core:designsystem` 的 `drawable-nodpi/eyecho_avatar.png`（256 圆形头像）与 `eyecho_half.png`（半身、底边平切），
  苹果端同名素材 `EyechoAvatar` / `EyechoHalf`；`EyechoAvatar(size)` 用在入口、对话气泡、「正在想」与同意弹窗。
  首页爱科卡（`EyechoCard`）→ `EyeChatScreen`（`EyeChatRoute`）：顶部 `EyechoIntro`（居中的爱科大头像 + 问候 + 一句介绍，不加卡片），
  接着是综合建议卡（`AiExplanationCard(text = AiCardText.ADVICE)`，取自 `HomeViewModel.aiAdvice`），下面是与爱科的对话（`ChatViewModel` → `core:data` `ChatRepository`），
  未登录提示去登录；按钮一律「发送」，同意说明不在页面上，点发送时按需弹 `ChatConsentDialog`（还没同意时每次都弹；已同意但没勾「以后不再提示」时每次进页面首次发送弹一次；
  「不同意」只关闭、保留输入）；提到危险信号时在提问下方醒目提示尽快就医；记录只存本机，可清空。对话气泡为信息式：用户的品牌蓝底白字靠右，爱科的卡片底靠左（左侧 28dp 头像）；提到危险信号时提问下方是「建议尽快就医」提示块。与苹果端 `AiChatView.swift` 一致。
  逐步问询：选「帮我一步步梳理眼部不适」并发送，爱科一次问一个关键问题；问题卡支持选项与自由回答；点选选项弹出确认，确认即发送，可勾选「不再询问」后直接发送。
  历史问题只读；当前问题重启后仍可回答；最后确认补充后生成五段总结，也可发送「结束问询」或直接换话题。协议与流程见 `backend/server/SERVER.md`。

## 当前对话视觉

用户消息为主蓝高对比变体底色与白字；双方正文 18 号，Android 行高 28，iOS 增加行距。隐私同意、危险信号提示与问询逻辑不变。

首页灰色类别标题采用 label 16/24 半粗；分区标题 22、卡片标题 20。等长刻度按圆环对应位置取蓝青渐变并发光，底部头像开口保持。

光边试版：今日数据卡、护眼工具与爱科卡增加蓝、青到青绿色的渐变细光边。仪表不增加底色块；人物、刻度、字号与上一轮确认的实现保持。

Liquid Glass 试版：护眼工具与爱科入口改为轻量玻璃模拟，按压时高光略微增强；今日数据卡仍用渐变光边。仪表留白、暖金 / 银色表情和原版演示动画保持。

iOS 圆环首帧直接读取当前分数；独立 Animatable 绘制层负责后续插值，取消默认零值与 onAppear 初始化依赖。新增 iOS 像素回归覆盖首帧、禁用动画事务、无数据与零值，截图保存在云端测试附件。

首次使用无数据时仍保留清晰的空轨道；不把空值当作有分数处理，也不绘制虚假充能进度。

最新简化：首页工具只保留图标和单行标题「护眼操 / 用眼趋势 / 屏幕光」，去掉时长、近七天与亮度小字。问诊顶部采用大头像与左对齐介绍，正文 18；消息头像 48 并与气泡顶部对齐。去掉操作提示、问询总结小标签、历史选项回显、消息参考小字与底部说明；问题、选项、输入和发送行为保留。iOS 圆环扩大滤镜画布，保留布局尺寸；增加四边外晕像素回归。

最新布局：问诊顶部改用完整透明半身像；首页半身像取消外移、取消背后圆形衬底，右侧留白，完整展示上半身。首页右上角统一放日夜切换与系统设置。

最新交互：点选问询选项后弹出回答确认，确认即发送、取消保留草稿；勾选「不再询问」后点选直接发送，仍保留自由输入与发送按钮。选项确认偏好独立于 AI 数据处理同意。首页问诊卡采用紫色图标在上、22 号主标题在下，感受状态降为辅助信息，编辑使用铅笔图标。形象缩小并顶部对齐裁去下摆，保留胸前 logo。

首页订阅期间由 HomeClock 每个本地整分钟触发重算，跨午夜无需等待其他数据变化；取消订阅后停止，重新订阅立即读时钟。iOS EyeCareStore 在前台注册同样的整分钟 Timer，退后台释放，回前台立即刷新。
