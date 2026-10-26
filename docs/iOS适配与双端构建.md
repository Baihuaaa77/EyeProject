# iOS 适配与双端构建

## 本轮范围

三项前台自测的问卷、计时、阶梯、移近法、多距离任务、遮盖顺序、注视校准、质量判断、风险规则和 JSON 记录已抽成 KMP 共用核心，安卓直接使用它。iOS 原生界面通过事件和快照调用相同引擎。

本轮未把所有安卓页面迁为 Compose Multiplatform。苹果界面用原生控件和 Canvas；数据与逻辑共用，呈现仍需苹果实测验收。安卓的休息提醒与实验页尚未移植到 iOS。未引入后端。

iOS 已提供本机记录保存与分享，但尚未接入安卓已有的历史记录趋势对比；这项能力仍需补齐后才能验收为完全一致。

交替遮盖仍保持原有点按标记动作时刻的交互，未实现此前讨论后取消的自动遮挡确认方案。

## 工程位置

| 路径 | 用途 |
|---|---|
| shared/model、domain、screening | 双端共用代码 |
| iosApp/project.yml | XcodeGen 工程规格，生成 Xcode 工程 |
| iosApp/Sources | iPhone 页面、相机、中文语音、本地记录 |
| iosApp/Podfile | MediaPipeTasksVision 1.0.0，与安卓同版 |
| .github/workflows/verify-dual-platform.yml | 安卓回归、共同 JVM 测试、苹果模拟器测试 |
| .github/workflows/package-ios.yml | 配好签名 Secrets 后手动导出 IPA，不自动发布 |
| scripts/ios/verify.sh | 无签名构建和模拟器测试 |
| scripts/ios/archive.sh | 有签名环境下生成 archive 和 IPA |
| scripts/ios/unsigned-ipa.sh | 生成未签名真机 IPA，供个人 Apple ID 免费签名安装 |

同一个 `core/camera/src/main/assets/face_landmarker.task` 被两端打包，SHA256：`64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff`。CI 检查模型、SDK 版本、主题令牌和平台边界，发现漂移即失败。

## Windows 可以做什么

共用代码及安卓开发、JVM 测试、安卓 APK 构建、工程规格检查均可继续在 Windows 进行。iOS 编译与模拟器测试由 GitHub Actions 的 macOS runner 执行，无需本机 Mac 或 Apple Developer Program 会员。安装到真实 iPhone 仍需配置苹果签名。

## 开通云端后进行首次 iOS 验证

1. 在自己控制的 GitHub 仓库保存工程，开启 Actions。
2. 执行“双端回归与 iOS 编译”。苹果 job 使用 macos-26、Xcode 26.4、JDK 17、Ruby 3.3、XcodeGen 和 CocoaPods。
3. 先通过公共算法的 iOS Simulator 测试，再通过 Xcode 的桥接与应用测试。该阶段不需要 Apple 签名。
4. 若 runner 的 Xcode 镜像更新，应更新两个工作流／文档的工具链版本并复跑双端，不要悄悄改诊断参数。

本地或远程 Mac 的等价操作：

```bash
chmod +x gradlew scripts/ios/*.sh
./gradlew -p shared :domain:jvmTest :screening:jvmTest :screening:iosSimulatorArm64Test
./scripts/ios/verify.sh
```

模拟器不能验证真实前摄、测距、角膜反光和中文麦克风效果，仍须 iPhone 实测。

## 免费真机测试（无 Mac、无开发者账号）

每次推送到 `master` 或 `codex/**`，「双端回归与 iOS 编译」在模拟器测试通过后，用 Release 配置编译未签名的真机版本，上传为 artifact `ios-unsigned-ipa`（保留 14 天）。安装由 Windows 上的 Sideloadly 用个人 Apple ID 重新签名完成：

1. 安装苹果官网版 iTunes 与 iCloud（不要用微软商店版），再安装 Sideloadly（sideloadly.io）。
2. 在 GitHub 该次运行页面底部下载 `ios-unsigned-ipa`，解压得到 `EyeProject-unsigned.ipa`。
3. iPhone 用数据线连接电脑并点「信任此电脑」；Sideloadly 选中设备、拖入 IPA、填 Apple ID 后点 Start。建议使用单独的测试用 Apple ID。
4. iPhone「设置 → 通用 → VPN 与设备管理」信任该 Apple ID；iOS 16 起还要在「设置 → 隐私与安全性 → 开发者模式」开启并重启。

免费签名 7 天后失效，重新用 Sideloadly 安装即可，本机测试记录在覆盖安装时保留；同一 Apple ID 最多同时 3 个此类应用。应用只用相机、麦克风、语音识别，不需要付费账号才有的权限。

## 安卓实时检测后台继续（2026-10-24）

安卓打开实时检测后，离开首页或退到后台继续用前置相机检测（相机类前台服务，通知栏常驻「小瞳正在检测用眼」与「停止」），
直到关掉开关、点「停止」或从最近任务划掉应用；熄屏自动暂停。长时间检测每 10 分钟或跨天分段记账（共用 `LiveEyeMonitor.takeSession`）。
苹果系统不允许普通应用在后台使用相机，苹果端维持退到后台即停止。详见 `feature/home/HOME.md`。

## 首页减字、小瞳气泡与示范舞台（2026-10-24）

- 首页去掉「个体档案」卡与「为你推荐」卡；「今日感受」改为与「视觉行为」同样的卡片标题；实时检测标题下的小字删除。
- 推荐改为小瞳的对话气泡（不朗读）：共用 `EyeCareEngine` 生成 `tips`（先是今天的提醒，再是日常小贴士），每句约 5 秒，
  可执行的带按钮字，点气泡直接去做；点小瞳换下一句。负荷分越高头顶嫩芽越蔫，负荷偏高时小瞳为新的「疲惫」神情（眼皮耷拉、黑眼圈）。
- 小瞳更活泼：待机时自己蹦一下、打哈欠；点一下随机反应（新增转圈、比心），长按比心飘爱心，连点五下转晕；新增 6 个音效
  （`eye_spin/heart/dizzy/yawn/pop/sigh`，`scripts/generate_feedback_sounds.py` 生成）。
- 服务页去掉「我是小瞳」与说明文字，改为示范舞台：随机轮流播放三项自测的演示动画，气泡说「干眼自测：像平时一样眨眨眼」「跟着我一起做吧」等。
- 两端对应：安卓 `core:ui` 的 `XiaoTong` / `XiaoTongTalk` / `MascotBubble`、`feature:services` 的 `DemoHero`；
  苹果 `MascotStage.swift` 的 `MascotStage` / `MascotTalk` / `MascotBubble`、`EyeShell.swift` 的 `DemoHeroCard`。

## 首页与「我的」改版、主动护眼、小瞳与启动动画（2026-10-23）

两端同一排版与文案，截图见 `docs/界面截图-2026-10-23/安卓/`。

- **平台差异原则**：系统支持就做，不支持就不做，也不放相关按钮或「不支持」说明。
  屏幕使用时间（UsageStatsManager）与环境光只在安卓；苹果端没有对应卡片、入口和文字，评估照常进行，只是少了这几项信号。
- **首页 = 主动护眼闭环**：疲劳评分环与等级、「感知 → 评估 → 归因 → 干预 → 反馈」五段进度、今日感受打卡（症状多选，最后两项为需尽快就医的红旗症状）、
  「为你推荐」干预动作，以及三类数据卡：基础数据（安卓：屏幕时间、最长连续、夜间、休息、环境光、亮度）、
  视觉行为（实时检测汇总的眨眼频率、平均距离、过近占比、头部偏转）、个人档案。下方服务入口为用眼趋势（7 天）、AI 综合建议、亮度调节、休息提醒。
  评分与归因在共用 `EyeCareEngine`（`shared/screening/.../core/eyecare/`），苹果端经 `EyeCareBridge` 以 JSON 进出，阈值为工程默认值，待眼科专家确认。
- **实时眼部检测**改为首页一个开关，默认关闭；关闭检测时把本次会话（≥20 秒）的眨眼、距离、姿态汇总写入护眼账本。
- **AI 综合建议**：新增单独的「AI 综合建议」同意项（不触发同步），发送去标识摘要（年龄段、职业、矫正方式、眼病史、近 7 天平均屏幕数据〔仅安卓〕、
  眨眼与距离、环境光与亮度、各项测试风险等级、今日症状）到后端 `/v1/advice`，经知识库检索、输出校验（禁用词、与等级矛盾、编造数字）和缓存后返回。
- **「我的」改为入口页**：个人卡（小瞳 + 本人称呼与档案完善度 + 账号一行）、三格概览、分组入口；
  测试记录、家庭成员、云端同步、账户、权限与通知都是二级完整页面。顶层页右上角为账户头像按钮 + 系统设置齿轮。
- **去掉底部弹窗**：系统设置、测试中设置、成员编辑、报告数据页均为完整页面（苹果 `NavigationStack` 推入或 `fullScreenCover`，安卓 NavHost 路由或全屏 Dialog）。
- **说明文字收进「?」**：括号小字改为标题旁的灰色圆形问号，点开气泡说明（安卓 `EyeHelpButton`，苹果 `HelpButton`）。
- **小瞳**：首页、我的、记录空状态都有会眨眼、晃叶子的小瞳；点按随机蹦跳 / 眨眼 / 咯咯笑 / 惊讶 / 害羞，配音效（`shared/feedback/res/raw/eye_boop|hop|giggle|wow.wav`，跟随系统设置的音效开关与音量）。
  测试结果按等级换表情。我的页品牌标识换成桌面图标；版本号移到「系统设置 → 关于」。
- **启动动画**：冷启动约 1.4 秒小瞳 + 进度条 +「你的主动眼健康助手」，盖在已加载的首页之上，不阻塞启动；苹果端系统启动页底色 `LaunchBackground` 与动画衔接。
- **离开实时检测后界面卡住（苹果）**：根因是预览层与会话在主线程释放，与相机队列上的 `stopRunning` 互等。
  现在相机与预览视图为全局单例（`EyeLiveModel.shared`），视图销毁只摘下预览不释放；`EyeCamera` 的 deinit 把停止与释放放到相机队列。需真机复验。

## 首页实时视窗、系统设置齿轮与退出卡死（2026-10-23）

真机对比结论：EAR 与 OCEC 都能可靠检测眨眼，保留 EAR，OCEC 全部删除（苹果端对比页、手写推理与权重、导出脚本；
共用 `BlinkDetector.ocecOpenProbability`；安卓「模型测试 → 全替换眨眼检测」页与 `ocec_p.tflite`）。研究记录留在 `results/`。

- 首页「实时眼部检测」视窗（两端同一排版）：镜像预览叠加双眼关键点（眼睑 6 点轮廓 + 虹膜圈，闭眼变红），眨眼次数、
  近 1 分钟次数、睁眼 / 闭眼状态、EAR 与阈值、帧率，下方最近 8 秒 EAR 曲线（眨眼处绿线），右上角状态「运行正常 / 未检测到人脸 / 画面中断」。
  逻辑在共用 `LiveEyeMonitor`（眨眼判据与干眼测试同一份）；苹果端 `EyeLiveSection` / `EyeLiveModel`，安卓 `feature:home` 的 `LiveEyeSection`。
  默认关闭，由首页开关开启；离开首页或退到后台即关闭相机。
- 「系统设置」齿轮常驻首页、服务、我的右上角，打开完整的系统设置页；服务页大标题由「视力与眼健康」改为「服务」。
- 退出测试卡死：退出时在主线程停止录音引擎、移除收音并停用音频会话，录音 I/O 未停就停用会话会阻塞主线程。
  现在主线程只停播报、取消识别；停引擎与停用会话放到后台串行队列（`EyeAudioSession.teardown`），
  并以激活序号避免迟到的停用影响下一项测试刚激活的会话。

## 相机卡顿与「未收到相机数据」修正（2026-10-22）

真机反馈：眨眼对比页只有约 13 帧/秒、越用越迟钝，三项测试频繁提示「未收到相机数据」（截图时手机在充电）。

- 原因一：相机帧每帧各派发一次主线程任务，主线程跟不上时积压无上限。画面处理越来越滞后，
  看门狗按帧时间判断已超过 2.5 秒没有新帧，于是报「未收到相机数据」，同时距离等读数仍在更新（其实是旧帧）。
  现经 `FrameMailbox`（`EyeCamera.swift`）成批送达：测试最多积压 8 帧（约 0.27 秒），超出丢最旧的帧；
  眨眼对比页最多 90 帧（判定在相机队列完成，事件随帧带出，尽量不丢）。页面关闭时清空未送达的帧。
- 原因二：眨眼对比页每帧刷新整页（预览叠加、两张卡片、8 秒曲线、列表），主线程占满、手机发热。
  现逐帧数据不再用 `@Published`，界面最多每 80 ms 刷新一次；每轮留存的逐帧数据不再保存眼片图像。
- 原因三：没有处理系统压力。发热到 serious / critical 时系统可能直接停掉相机。现观察 `systemPressureState`，
  升高时主动降到每秒 15 帧并提示「手机较热…」，恢复后回到默认帧率；会话运行出错或被打断结束后自动恢复采集。
- 顺带：AI 解读卡片每次重绘要读十几个字段，原先每次都重建 JSON，现按文件缓存到云端状态下次变化。
- 验证：`FrameMailboxTests`（积压上限、按序成批送达、关闭后不再送达）随云端编译在模拟器运行；
  帧率与发热表现需真机确认（眨眼对比页标题栏显示帧率，主线程丢帧时显示「丢 n」）。

第二轮真机反馈：眨眼对比页眨 10 次只判到 1 次，关键点不跟随眼睛。原因是 iOS 的 MediaPipe 一直用 CPU 推理
（安卓用 GPU），每帧约 60–70 ms，只有十几帧/秒：一次眨眼（100–150 ms）往往只落在一帧上甚至落空，
OCEC「连续 3 帧」在这个帧率下基本判不到，关键点也明显滞后。

- `EyeCamera` 与安卓一致优先用 GPU 推理，创建或运行时失败自动退回 CPU。
- 瞳孔 / 角膜反光测量只有斜视用到：干眼与眨眼对比关闭（`measureOptics`，共用 `LandmarkGeometry.face(…, optics)`）。
- 478 个关键点先写入 Swift 连续数组，再经 `BgraPixels.floats` 一次拷给 Kotlin，不再逐个跨桥写 1434 次。
- 眨眼对比页标题下显示诊断：相机送来几帧、实际分析几帧、每帧检测与整帧耗时、GPU / CPU。

第三轮真机反馈（诊断行「相机 1 · 分析 1 帧/秒 · 检测 12 ms · GPU」）：检测已很快，但相机每秒只送来 1 帧，
卡几秒又恢复到十几帧，预览画面却一直流畅；退出测试偶尔整页卡死。原因是相机像素缓冲没有及时归还：

- `CameraGrayImage`（引用像素缓冲）要传进 Kotlin 做几何与光学计算，Kotlin/Native 会持有它直到自己下一次垃圾回收。
  上一版关掉了干眼与眨眼对比的光学计算，Kotlin 分配变少、回收变稀，缓冲池被占满，相机只能等回收后才送帧
  （此前 CPU 推理时分配多、回收勤，问题被掩盖，但「未收到相机数据」的偶发提示同源）。
  现在本帧处理完立即 `release()` 归还缓冲，Kotlin 只留不含像素的空壳。
- MediaPipe 的 Objective-C 临时对象也引用缓冲：每帧包在 `autoreleasepool` 里，相机队列 `autoreleaseFrequency: .workItem`。
- 诊断行增加「系统丢帧 n/秒（原因）」：`OutOfBuffers` 即缓冲未归还，`FrameWasLate` 即处理太慢；相机完全停帧时读数也会刷新。

## 首次真机反馈修正（2026-10-16）

| 现象 | 根因 | 处理 |
|---|---|---|
| 近视力反复提示「测距短时波动」 | 苹果相机每帧内参焦距有千分之几抖动，共用控制器把每次抖动当作新标定、每帧清空测距窗口，永远凑不够稳定样本 | `focalChanged`：变化超过 2% 才视为新标定（共用代码，`PlatformInputTest`） |
| 播报僵硬、把 E 读成「鹅」 | 自动挑声音时可能选中 Eloquence 机器腔声音；字母 E 无读法 | 过滤 Eloquence 与趣味声音、同音质优先系统默认普通话声音，设置里可选发声人并试听；共用读法把单独的 E 读作「视标」（两端一致） |
| 测试中无震动和音效 | 系统音效受静音键限制、触觉强度过轻 | Core Haptics 按安卓分级播放；音效改 AVAudioPlayer、走媒体音量；测试期间统一音频会话，收听时仍允许触觉 |
| 语音无法作答 | 默认关闭；本机无离线中文模型时静默不收听 | 与安卓一致默认开启，进入测试时申请权限；无离线模型时改用系统在线识别并在设置里说明 |
| 测试不是全屏 | 用卡片式弹窗呈现 | 改为全屏呈现，背景铺满、内容留在安全区 |
| 「小瞳」与安卓不一致、按键观感不自然 | iOS 自绘简化版；系统导航栏玻璃胶囊与系统按压变暗 | 逐项移植 `drawMascot`；自绘顶栏（返回箭头、标题、齿轮）；按钮、方向键、入口卡按安卓尺寸与按压动效重写 |

## 第二轮真机反馈修正（2026-10-16）

| 现象 | 根因 | 处理 |
|---|---|---|
| 测试中仍无震动和音效（「试一下」正常） | 共用 `RecordArray` 未覆盖 `toString`，`feedbackJson` 返回对象地址而非 JSON，iOS 解析失败，测试事件从未送达 | 补 `toString`（`PlatformInputTest` 覆盖） |
| 说「跳过」不能作答 | 解析器只认「看不清」等 | 两端共用解析器加入「跳过」及常见误识别；安卓离线模型语法、iOS 识别提示词同步加入 |
| 注视校准常需多次 | 一轮 9 点全有或全无；最多剔除 2 点；垂直轴相关门槛与水平相同 | 两端共用：先补测没看稳的点（≤4 个）再判定；剩余点覆盖三行三列时最多剔除 3 点；垂直相关门槛 0.7 |
| 遮盖环节反复播报「保持两秒」 | 每次点按后播报完整长句 | 每个动作显示保持进度条（2 秒／交替 1 秒）；保持期间不播报，结束时震动+音效并只读下一动作（`coverSpokenPrompt`，两端共用） |
| iOS 缺底部导航、首页、我的及图标 | iOS 原为单页简化版 | 移植安卓三个顶层页与自绘导航栏：首页（品牌顶栏、休息提醒本地通知）、服务（小瞳待机卡、三项带图标入口）、我的（本机记录、权限、关于）；图标按安卓矢量逐笔移植。安卓的「模型测试」实验页不移植 |

## 第三轮真机反馈修正（2026-10-16）

由两份 iPhone 记录发现：苹果端相机分析只有约 5–10 帧/秒（斜视追随 22 秒仅 117 个样本、干眼 80 秒只检到 4 次眨眼），
且斜视 2,310 帧中 2,258 帧光学测量被判「瞳孔对比度不足」。

| 现象 | 根因 | 处理 |
|---|---|---|
| 斜视标定难完成、追随判不出、干眼眨眼偏少 | 关键点几何与光学测量在主线程执行，眼区像素逐个跨 Swift→Kotlin 桥读取；分析帧率上限 15 | 几何与光学测量移到相机队列（`MobileScreeningController.cameraFace`）；`BgraPixels`（domain iosMain）直接读锁定的像素内存；上限提到约 30 帧/秒 |
| 光学测量几乎全部拒绝 | 待真机数据确认（对比度不足或眼区与图像未对齐） | 斜视记录新增 `opticalPupilContrastMedian`、`opticalIrisDiameterMedianPx` 供排查 |
| 问卷选完自动翻页；无法先跳过 | iOS 单选后自动前进；双端无跳过 | iOS 由「下一题」确认；双端加「跳过问卷，直接测试」，记录 `profileSkipped` |
| 播报生硬、过慢；安卓干眼/斜视播报怪 | iOS 语速偏慢；频繁的提醒逐句打断正在说的话 | 非紧急提示等当前这句说完（距离提醒仍即时）；双端设置加播报音量、语速 |
| 说一个字连跳两题；iOS 有时听不出「右」 | 同一段语音的部分与最终结果各作答一次；「右」常被识别成「有」等 | 共用 `VoiceRepeatGuard`（语音作答至少间隔 0.9 秒）；补充常见同音字 |
| 反馈强弱、音量不可调 | — | 双端设置加音效音量、震动强度滑块（默认即原分级） |
| 应用图标 | — | `scripts/generate_app_icon.py` 改为浅薄荷绿底彩色小瞳，双端重新生成 |

## 第四轮真机反馈修正（2026-10-16）

共同规律：人一进入「距离合适」就停下，停在容差边缘（iPhone 记录：老视 40±4 cm 停在 36–37 cm，斜视 33±5 cm 停在 28.6 cm），
测距正常抖动就把作答或画面判成越界。提示容差不变，测量放宽。

| 现象 | 根因 | 处理 |
|---|---|---|
| 斜视注视校准反复失败 | 画面越过 ±5 cm 即作废，圆点采不到帧；手机屏窄、水平眼动信号弱，旧记录水平拟合误差 0.14–0.17，而门槛为 0.10 | 采样容差放宽到 ±7 cm（`DISTANCE_GATE_CM`，提醒仍在 ±5）；拟合门槛改为相关 ≥ 0.6、误差 ≤ 注视容差的 60%（水平 0.15、垂直 0.12；校准只用于宽容的注视监督）；失败时说明是「采不到画面」还是「眼动对不上圆点」 |
| 老视距离稍有波动就不让作答 | 作答瞬间仍要求距离「稳定」且在 ±4.5 cm 内 | 作答只核对：距离在 ±6 cm 内（`ANSWER_TOLERANCE_CM`）、与呈现时相差 ≤ 12%；呈现前的稳定核对不变；点按挡脸短暂丢测距 1.5 秒内沿用最后估计 |
| 多距离视力 33 / 25 cm 结果为空 | 起始档在近距离画不出时整个距离记为「像素受限」 | 与近视力一致先改测较大视标 |
| 换距离时提示「太近了 / 太远了」 | 目标距离一变，原监督立刻按新目标判偏离 | 共用状态 `distanceMoveToCm`：换目标后提示并播报「请移动到 xx 厘米处」（主色横幅、无警示音），到达容差内恢复监督，双端一致 |

## 第五轮真机反馈修正（2026-10-16，安卓实测）

| 现象 | 根因 | 处理 |
|---|---|---|
| 干眼语音作答没反应（双端） | 每次播报、每个作答音效都停止再重启收听，视标 2 秒一题，识别器总在重启；iOS 只在视标显示的 2 秒内开麦 | 收听不再因播报与音效暂停；iOS 干眼整个作答阶段持续收听 |
| 语音不灵敏；播报时说话无效 | 同上；安卓系统识别服务每句话后要重启一轮，间隙里说的话丢失；离线结果夹带 `[unk]` 时整句被丢弃 | 安卓改以内置离线模型（连续收听）为主，无法启动时退回系统识别；去掉 `[unk]` 再解析；播报期间改为**回声过滤**：只丢弃提示语里也出现的指令（如播报「右眼测试」时的「右」），整句吞掉（`VoiceCommandParser.mentions`，双端共用） |
| 斜视注视监督太严，遮单眼常被判未注视 | 「无法判断」的帧（被遮眼、手在脸前、只有被遮眼完成校准）计作未注视；容差偏紧 | 只用能判断的帧算注视比例（≥ 50%），遮盖阶段可判断帧太少时不据此判失败；遮盖动作前后 0.8 秒不判断；遮盖阶段不提示「无法确认注视」；注视容差放宽到 0.30 / 0.25 屏幕，跟随位置达标率 0.5 |
| 远近切换中反复说「好……」 | 每题换距离，到位后都说「好，就保持这个距离」 | 「请移动到 xx 厘米处」到位后改为一声提示音，主提示没变不重读（双端） |
| 「我的」没有本机记录入口（安卓） | — | 双端「我的 → 本机测试记录」：共享 `LocalRecordCatalog` 解析测试名、时间、结论徽标与要点，点开可导出完整 JSON |

## 「我的」精简与触感（2026-10-21）

- 与安卓同一排版与文案：家庭成员（头像字行）、测试记录（「共 N 条」）、云端同步（状态徽标，说明收进「数据如何处理 · 服务器设置」）、权限与通知、关于。
- `EyeHaptics.swift`：点按 light impact、开关 selection、成功 / 失败 notification；与安卓 `core:ui` 的 `EyeHaptics` 同一分级。云端操作结果来自共用 `CloudClient` 快照的 `outcomeId` / `outcomeOk`；回到前台的自动补传（`syncNow(userInitiated: false)`）不振动。
- 安卓也支持在界面填写服务器地址（`core:network` 的 `ServerAddressStore`），两端不再依赖构建期地址。

## iOS 接入后端（2026-10-21）

- 共用客户端 `shared/sync/client/CloudClient`（Kotlin）实现登录与令牌刷新、分项同意、成员与资料双向同步、记录按成员归属上传、导出与注销，
  规则与安卓一致；经 `screening` 导出到 EyeScreening.framework。
- `EyeCloud.swift`：`CloudTransport`（URLSession）、`CloudStorage`（会话存钥匙串，发件箱与同步簿记存应用支持目录）、
  `CloudSection`（「我的 → 云端同步」：服务器地址、登录/注册、三项同意、立即同步、导出、退出、注销）。
- 触发上传：保存测试记录后、改动成员资料后、回到前台时；未登录或未同意时不联网。
- 服务器地址在界面填写（IPA 由云端编译，无法写死局域网地址）。局域网试用为 http：Info.plist 允许本地网络与明文连接，正式部署改 https 后去掉 `NSAllowsArbitraryLoads`。
- 验证：`CloudClientEndToEndTest` 在服务端测试中对接真实接口；Swift 部分经云端编译与 `testCloudClientBridgeStaysOfflineUntilAServerIsSet` 检查，需真机确认网络与钥匙串。

## 眨眼检测对比实验页（2026-10-22）

首页「开发者工具 → 眨眼检测对比」：同一路前置相机帧（与正式测试同一 `EyeCamera`：720p、约 30 帧/秒、同一 MediaPipe 设置）同时喂两种判据，实时对比。

- **EAR**：共用 `LandmarkGeometry` 的 6 点 + `BlinkDetector.dryEyeEar()`，喂法与 `DryEyeEngine.handleFrame` 一致（双眼都在才更新，否则中断；双眼平均；≥60 ms；自适应阈值）。干眼引擎也改为调用同一个 `dryEyeEar()`，两处不会漂移。
- **OCEC**：按 newpart 干眼 OCEC 方案移植——每只眼 6 个眼睑点外扩 15%（高不低于眼宽 0.33）裁片，最近邻拉伸到 40×24 送 OCEC-P，双眼概率平均，`BlinkDetector.ocecOpenProbability()`（连续 3 帧 < 0.5 判闭眼）。裁片参数与 0.5 阈值均未标定。
- **推理实现**：iOS 不另引 TFLite（应用已静态链接 MediaPipe，再链一份有符号冲突风险），`OcecEyeClassifier.swift` 用 Accelerate 手写同一前向。权重 `iosApp/Models/ocec_p_weights.bin` 由 `scripts/ios/export_ocec_weights.py` 从 `results/models/ocec_p_float32.tflite` 导出；脚本用 numpy 同算法与 TFLite 在 50 张真机眼片上对拍（最大误差 2e-6），并生成单测夹具 `iosApp/Tests/ocec_fixture.json`，`OcecEyeClassifierTests` 在模拟器上再对拍 Swift 实现。换模型须重跑脚本（`check_project.py` 校验模型哈希与权重格式）。
- **界面**：镜像预览叠加 EAR 6 点（蓝）与 OCEC 裁片框（橙），闭眼时变红；两张判据卡片（读数 / 阈值 / 闭眼灯 / 计数，判到时闪烁并显示闭眼时长）；OCEC 实际看到的双眼 40×24 眼片；8 秒双泳道曲线，每次判定一条竖线（绿 = 两者在 ±0.5 s 内都判到，红 = 仅一方）；事件列表。
- **分轮测试**：选眨眼方式（自然 / 快 / 慢 / 用力大眨 / 轻微小眨 / 不眨眼）与计划次数，开始 → 按计划眨眼 → 结束，得到两种方法各自计数、与计划的差（漏检 / 误检提示）和配对情况。每轮逐帧数据与事件写入「文件」App → 我的 iPhone → 眼健康助手 → `BlinkCompare/`，`summary.csv` 汇总所有轮次。

## 资料移入「我的」（2026-10-19）

三项测试不再逐题填写问卷：问卷合并为被测者资料（共享 `SubjectProfiles`，键沿用原问卷），在「我的 → 家庭成员」按成员维护（本人、孩子、父母……），
测试开始页只选「这次测谁」，成员缺本测试必需项时点开即去补充。iOS 端 `EyeSubjects.swift`：`SubjectStore` 本机保存（应用沙盒 `subjects.json`，尚未接入云端）、
`SubjectEditView` 编辑（题目分组与追问由共享 `editorJson` 给出）、`SubjectPickerView` 选人（必需项由 `missingJson` 判断）。安卓另有云端同步，见 `core/data/DATA.md`。

## 测试演示动画（2026-10-16）

iOS 原先只显示步骤文字。现移植安卓 `TestGuide`：`TestGuideView.swift` 含全部 12 个场景（姿势、视标练习、连续视力、眨眼、睁眼、近视力、移近、多距离、远近切换、注视、遮盖、跟随），
320×200 舞台单位、相同循环时长与分镜高亮、暂停 / 继续，系统减弱动态效果时停在关键帧；`EyeCartoon.swift` 移植小瞳、连指手套、卡通手机、视标 E 的绘制。
出现位置与安卓相同：准备页（姿势示范）、干眼练习与正式说明、老视 / 斜视每个任务开始前。

## 生成 iPhone 可安装包

需要 Apple Developer Program 团队与合法签名环境。没有账号时不要填虚构 Team ID 或提交证书。

1. 注册自己的 Bundle ID；在 App Store Connect 创建应用。
2. 在远程 Mac 的 Xcode 登录账号并配置签名，或者在受控 CI 中配置签名证书、描述文件和相应认证。
3. 复制 `scripts/ios/ExportOptions.example.plist` 到忽略的 `iosApp/ExportOptions.local.plist`，填 Team ID。TestFlight 用 `app-store-connect`；预先登记设备的 Ad Hoc 用 `release-testing`。
4. 执行：

```bash
export APPLE_TEAM_ID='自己的团队 ID'
export IOS_BUNDLE_ID='自己注册的 Bundle ID'
export IOS_EXPORT_OPTIONS_PLIST="$PWD/iosApp/ExportOptions.local.plist"
./scripts/ios/archive.sh
```

输出为 `iosApp/build/EyeProject.xcarchive` 和 `iosApp/build/export/*.ipa`。签名与导出完成后，才能上传 App Store Connect，并通过 TestFlight 安装到 iPhone。该脚本不会自动发布。

完全通过云端打包时，在 GitHub 仓库 Secrets 配置 `APPLE_TEAM_ID`、`IOS_BUNDLE_ID`、`IOS_CERTIFICATE_BASE64`（Apple Distribution P12 的 base64）、`IOS_CERTIFICATE_PASSWORD`、`IOS_PROFILE_BASE64`（与所选分发方式匹配的描述文件 base64）。运行“手动生成签名 iOS 测试包”，下载 IPA artifact。该流程不把证书存进源码，不自动上传 TestFlight。

## iPhone 首次使用

- 首次进入测试，用尺将屏幕线段调到实际 5 cm，建立该手机的屏幕物理尺寸尺度；线段和 E 视标不随文字字号放大。
- 前摄输出竖屏、不镜像 BGRA。图像、478 点和头姿矩阵对应同一帧；内参不可用时以视场角估算焦距。准备页可在实物 40 cm 位置校正测距尺度。
- 允许相机权限。语音作答还需要麦克风与语音识别权限；在支持离线中文的 iPhone 上使用设备端识别，不支持时明确提示使用按钮。本轮尚未为苹果打包安卓使用的 Vosk 后备引擎。
- 三项测试右上角齿轮共用「系统设置」，语音播报与语音作答偏好保存到本机；进行中的任务居中显示，语音开关与状态不占用测试舞台。
- 不把画面相对亮度伪装成 lux；苹果没有读到 lux 时保留缺失状态。
- 切到后台中断测试，重新开始；正式数据不会在后台继续累计。
- JSON 写入 Documents/Screening，可在“本机测试记录”中分享给开发者。记录不包含完整视频或原始音频，包含设备校准信息。

## 验收顺序

1. 首先证明 Xcode 编译与模拟器测试通过；Windows 的 Swift 语法检查只能发现语法问题。
2. 真机检查相机许可、拒绝后重试、退出停止相机、切后台中断、旧帧不可进入新测试。仅进入后台（background）才中断；权限弹窗、控制中心等短暂失焦（inactive）不打断。问卷与结果页切后台不丢内容，回到前台由 `MobileScreeningController.resume()` 重新挂接相机。
3. 用尺检查 25/33/40/50/60 cm，核对 E 的毫米尺寸、固定注视目标和实际追踪角度。
4. 完整执行三项任务，校准失败、无法跟随、映光缺失等情况下必须输出无法评估，不能当作正常。
5. 固定 JSON 样本在 JVM 和 iOS 侧得到一致指标与风险；额外检查规则边界。
6. 安卓原有测试与构建也通过后，才标记该功能“双端完成”。实际测量准确性仍需设备及临床对照验证。

## 目前未验证项

签名、IPA 导出、TestFlight 上传和真实 iPhone 测量尚未执行。模拟器通过仅证明本轮编译、桥接和自动化用例通过，真实相机、测距、语音与测量准确性仍需按上述验收顺序验证。

## 首次云端验证（2026-10-15）

[GitHub Actions 验证记录](https://github.com/Baihuaaa77/EyeProject/actions/runs/38672546754)，代码提交 `4045d7f58c7cc1387e621e1a9914b782d72257bc`，分支 `codex/ios-shared-screening`。使用 macOS 26、Xcode 26.4.1，未使用苹果签名。

- 共用算法 JVM 回归通过。
- iOS Simulator 共用 domain 3 项、screening 10 项测试通过。
- Swift 应用编译、MediaPipe 链接与模拟器运行通过；两项 XCTest 验证三项任务的共用引擎桥接和 RGB 灰度／区域像素转换，均为零失败。
- 安卓编译、原有 home/services 单元测试及 Debug APK 打包通过。
- 构建附件 `ios-verification` 保存 `.xcresult`、共用核心测试报告和生成的桥接头文件；`android-debug` 保存 APK。

首次云端运行修正了 Android SDK 安装包名与过时的 tools 默认包、苹果时间戳属性与 Combine 导入、Swift 无返回值回调适配，以及 XCTest 重复加载 MediaPipe 静态图的问题。测试目标有意覆盖 `OTHER_LDFLAGS`，由宿主应用加载静态图，避免继承 Pods 的 `-force_load` 后重复注册 Calculator；应用自身的 MediaPipe 链接保留。

## 本轮本地验证（2026-10-14）

- 安卓编译、Debug APK 打包与原有 services 单元测试 64 项通过。
- 共用 domain 3 项、screening 10 项 JVM 测试通过，涵盖三项风险结论、光学像素、屏幕尺寸、校准失败和中断。
- Swift 源码通过语法解析，XcodeGen 与 GitHub Actions YAML 通过解析，隐私清单和图标格式通过静态检查。此类检查不包含 Apple SDK 类型检查与链接。
