# 双端共用核心

这是独立的 Kotlin Multiplatform 构建，通过根目录 `includeBuild("shared")` 向安卓提供 JVM 包，通过 `EyeScreening.framework` 向 iOS 提供相同核心。

## 模块

- `model`：原 core:model 的纯数据类型，包名不变。
- `domain`：原 core:domain 的眨眼、虹膜测距及系统端口；共用瞳孔／反光点算法、478 点索引与头姿转换。锁分别用 JVM synchronized 和 iOS NSRecursiveLock。
  `LiveEyeMonitor`：首页实时眼部检测视窗的状态（最近 8 秒 EAR 曲线、眨眼事件与次数、帧率、双眼关键点、断流判断、虹膜测距与头部偏转），眨眼判据与干眼测试同一份 `BlinkDetector.dryEyeEar()`；`session()` 汇总本次会话（≥20 秒：眨眼频率、平均距离、过近 <30 cm 占比、平均头部偏转）供主动护眼账本记录。两端首页共用。
  `EyeExerciseCoach`：首页护眼操的教练（虹膜相对眼角中点的偏移减基线判断「看向左 / 右 / 上 / 下」，保持 250 ms 算到位；看不到脸时按时间带着做；闭眼休息时每少一秒发一次 `COUNTDOWN`，界面播倒计时音）。
  OCEC 睁闭眼模型的对比实验已结束（实测 EAR 足够），相关判据与代码已删除。
- `screening`：被测者资料（原三项问卷合并，`SubjectProfiles`，只放长期信息）、测前快问（`PreTestCheck`：干眼、眼位开始前现问两三个会随时间变化的问题，答案只写入本次记录；旧资料里存的这些值不再带入测试）、本机记录摘要（`LocalRecordCatalog`）、完整状态机、质量门控、分级、注视监督、视标尺寸、语音指令解析和记录格式。
- `sync`：云端同步契约（v1 DTO、错误代码）、`RecordSummaries`（从记录 JSON 抽取病种/版本/风险/质量/特征/问卷年龄）、纯 Kotlin `Sha256`、`RecordSyncEngine`（端口 `RecordOutbox` / `RecordRemote`）、`Explanations`（AI 解读的去标识输入抽取、年龄段、缓存键与同意文案）。安卓 `core:network`/`core:data` 与 `backend/server` 共用；经 `screening` 导出到 iOS framework。
  `client/`：苹果端云端客户端 `CloudClient`（登录与令牌刷新、分项同意、成员与资料双向同步、记录按成员归属上传、导出、注销），
  AI 解读 `explanationJson` / `explain` 与安卓 `ExplanationRepository` 同一规则；规则与安卓 `core:data` 一致；平台只实现 `HttpTransport`（URLSession）与 `CloudPlatform`（钥匙串、沙盒文件、本机记录与成员资料）。
  端到端测试在 `backend/server` 的 `CloudClientEndToEndTest`，直接对接真实服务端。
- `screening/core/eyecare`：**主动护眼**（首页「感知 → 评估 → 归因 → 干预 → 反馈」）。`EyeCareLedger`（本机账本 JSON 格式，两端一致）、`EyeCareEngine.report`（`EyeCareContext` → `EyeCareReport`：
  负荷分 0–100 与等级、闭环五段、读数、主要原因、推荐动作、首页建议 `tips`、就医建议、近 7 天趋势；
  神情：危险信号 CONCERNED、负荷偏高 TIRED、深夜 SLEEPY、轻松 HAPPY、其余 THINKING）、`EyeCareAdvice`（AI 综合建议的去标识摘要）、`EyeCareBridge`（苹果端 JSON 门面）、`EyeCareDays`（本地日期换算）。
  平台读不到的数据（`usageAccess = UNSUPPORTED`、`ambientSupported = false`）对应读数、规则与摘要字段整项不出现。
  负荷分为各因素加分之和（<30 轻松、<60 留意、其余偏累），**阈值是工程默认值，待眼科专家确认**：屏幕时间成人 4/6/8 小时（儿童 2/3/4 小时）加 10/18/25，最长连续 40/60/120 分钟（儿童 20/40/60）加 8/15/20，
  用屏 ≥2 小时且平均每 2 小时休息（白天离屏 ≥5 分钟）不到 1 次加 8，夜间（22 点后）用屏 ≥20/≥60 分钟加 5/10，眨眼 <12/<8 次每分加 8/15，观看距离 <30/<25 cm 加 8/12，头部偏转 >25° 加 4；
  今日不适按严重程度加负荷分（对应眼健康指数扣分）：眼睛干涩 10、眼睛酸胀 10、看东西模糊 20、头痛 10、眼睛疼痛 25、突然重影或看不清 30；每种只计一次，多项累加最多 80。「没有不适」不扣分，总负荷分仍最多 100，首页与历史趋势共用此规则，旧打卡按新规则重新计算。
  环境与档案因素：暗处高亮 6、夜间高亮 4、干眼自测中/高 6/10、隐形眼镜 4、干眼史 5、睡眠不足 4。「突然重影或看不清」「眼睛疼痛」为红旗症状，直接给出尽快就医建议。
- `screening` 的色觉自测：`ColorVision.kt`（假同色图生成：简笔数字 + 六角排布色点；线性 sRGB → XYZ → Hunt-Pointer-Estevez LMS，
  红色觉图数字只提高 L 视锥、绿色觉图只提高 M 视锥，对比度 30/22/16/11%，色点亮度随机 ±14%；固定随机种子，两端出图一致；判定标准与结果页）
  与 `ColorVisionEngine`（流程、记录 `color_*.json`，kind = COLOR_VISION）。云端同步接受 COLOR_VISION；AI 解读与 AI 综合建议不发送色觉结果
  （`RecordSummaries.EXPLAINABLE_KINDS`、`EyeCareAdvice` 只取用户确认过的三项）。
- `sync/Chat`：AI 对话契约（`ChatRequest` / `ChatReplyDto` / 本机记录 `ChatEntry`、`Chats.request` 取最近 12 条并带上开场建议、危险信号 `redFlag`、同意与就医提示文案）；`CloudClient.chatJson` / `sendChat` / `clearChat` 为苹果端入口，记录存应用沙盒。
- `SyncContract.DEFAULT_SERVER`：两端没填写过服务器地址时默认用课题组部署的 `https://geometrylearning.com/eyeapi`；用户清空地址即关闭云端（存空串以区分）。
- `sync/Advice`：AI 综合建议契约（`AdviceRequest` / `AdviceDto`、`Advices.cacheKey`、同意文案、取值裁剪 `bounded()`），安卓、苹果与服务端共用；`CloudClient.adviceJson` / `advise` 为苹果端入口。
- `ScreeningFeedback`：双端共用的交互反馈事件、距离防抖、阶段及标定点切换提示；引擎只在有效输入后投递作答事件。`shared/feedback/res/raw` 提供同一套短音，平台层各自播放声音与原生轻触觉。

安卓的 `core:model` / `core:domain` 是 API 转发模块；原页面和相机生命周期保留。两个测试 ViewModel 仅适配安卓系统端口，不能重新实现测试规则。

## 工具链

共享构建用 Kotlin 2.4.21（支持项目 Gradle 9.5 与 Xcode 26.4），JVM API 与语言级别保持 2.2，显式使用 stdlib 2.2.10，现有安卓 Compose/Hilt/KSP 工具链不升级。JVM 输出目标为 Java 17。

## 约束

1. commonMain 禁止 Android、Java、UIKit 等平台依赖。
2. 时间为单调毫秒；像素坐标来自已转正、不镜像的图像；左右眼表示受测者的左右眼。
3. 灰度统一按 0.2126R + 0.7152G + 0.0722B 取整；每个模型帧对应的图像必须保持到光学计算结束。
4. 改测试规则、问卷或记录格式只能改本目录。新增样本用 commonTest 同时在 JVM 与 iOS Simulator 验证。
5. 图像与显示坐标不能混用；目标必须上报实际显示位置。设备校准属于平台输入，不能改疾病阈值补偿设备误差。

## 检查

```powershell
.\gradlew.bat -p shared :domain:jvmTest :screening:jvmTest :sync:jvmTest
.\gradlew.bat :app:compileDebugKotlin :feature:services:testDebugUnitTest :app:assembleDebug
python scripts/ios/check_project.py
```

macOS 上增加 `./gradlew -p shared :screening:iosSimulatorArm64Test` 和 `./scripts/ios/verify.sh`。Windows 不具备 iOS 链接与真机签名环境；JVM 通过不能替代苹果编译通过。

## 2026-11-10 工程修正

- ScreeningEngine 作为统一入口与会话上下文；PresbyopiaStateMachine / StrabismusStateMachine 分别处理病种转移，ScreeningFrameProcessor 负责相机观测，ScreeningResultWriter 负责结果快照与写入。
- 干眼、老视、眼位、色觉结果写入使用进程级 ScreeningPersistence.scope，与页面采集作用域分离；重测后旧保存回调不覆盖新状态。
- 遮盖以连续多帧的单眼虹膜像素可见性变化自动记录。遮盖前建立双眼局部对比基线，单眼显著下降且另一眼保持可见、距离与头姿合格才接受；闭眼、双眼不可见和断流拒绝推进。持续至少 350 ms、4 帧后确认，事件时间回溯到证据首帧；同时记录 CAMERA 来源与确认时刻。保持期间不稳定则重做当前动作，不用点按代替遮盖。
- 新眼位记录 protocolVersion=7，规则版本 protocol-9.18-rules-3-camera-cover；解除遮盖后分析窗口恢复为 500 ms，移除原 800 ms 注视豁免。像素可见性独立于反光点可测性；这仍是待真机标注验证的启发式检测，不能将合成测试通过当作真实识别率。
- 测前快问选择自己完成时显示自遮演示；其余选择及未填写时显示协助遮盖演示。两端同用 usesSelfCover 与 coverGuideCue。
