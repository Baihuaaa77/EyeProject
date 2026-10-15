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
2. 真机检查相机许可、拒绝后重试、退出停止相机、切后台中断、旧帧不可进入新测试。
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
