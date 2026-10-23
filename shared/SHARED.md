# 双端共用核心

这是独立的 Kotlin Multiplatform 构建，通过根目录 `includeBuild("shared")` 向安卓提供 JVM 包，通过 `EyeScreening.framework` 向 iOS 提供相同核心。

## 模块

- `model`：原 core:model 的纯数据类型，包名不变。
- `domain`：原 core:domain 的眨眼、虹膜测距及系统端口；共用瞳孔／反光点算法、478 点索引与头姿转换。锁分别用 JVM synchronized 和 iOS NSRecursiveLock。
  `LiveEyeMonitor`：首页实时眼部检测视窗的状态（最近 8 秒 EAR 曲线、眨眼事件与次数、帧率、双眼关键点、断流判断），眨眼判据与干眼测试同一份 `BlinkDetector.dryEyeEar()`；两端首页共用。
  OCEC 睁闭眼模型的对比实验已结束（实测 EAR 足够），相关判据与代码已删除。
- `screening`：被测者资料（原三项问卷合并，`SubjectProfiles`）、本机记录摘要（`LocalRecordCatalog`）、完整状态机、质量门控、分级、注视监督、视标尺寸、语音指令解析和记录格式。
- `sync`：云端同步契约（v1 DTO、错误代码）、`RecordSummaries`（从记录 JSON 抽取病种/版本/风险/质量/特征/问卷年龄）、纯 Kotlin `Sha256`、`RecordSyncEngine`（端口 `RecordOutbox` / `RecordRemote`）、`Explanations`（AI 解读的去标识输入抽取、年龄段、缓存键与同意文案）。安卓 `core:network`/`core:data` 与 `backend/server` 共用；经 `screening` 导出到 iOS framework。
  `client/`：苹果端云端客户端 `CloudClient`（登录与令牌刷新、分项同意、成员与资料双向同步、记录按成员归属上传、导出、注销），
  AI 解读 `explanationJson` / `explain` 与安卓 `ExplanationRepository` 同一规则；规则与安卓 `core:data` 一致；平台只实现 `HttpTransport`（URLSession）与 `CloudPlatform`（钥匙串、沙盒文件、本机记录与成员资料）。
  端到端测试在 `backend/server` 的 `CloudClientEndToEndTest`，直接对接真实服务端。
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
