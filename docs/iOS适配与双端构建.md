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
