# EyeProject — 眼健康助手

安卓 / iOS 眼健康自测应用。用手机前置摄像头和 MediaPipe Face Landmarker 采集眼部数据，
四项自测共用一套 Kotlin Multiplatform 测试引擎，安卓用 Compose、苹果用 SwiftUI 呈现。

> 自测结果只用于筛查提示，不是诊断。

## 能做什么

| 模块 | 内容 |
|---|---|
| 服务 | 干眼、老视、斜视（眼位）、色觉四项自测：测前示范、距离与姿态监督、语音作答、结果报告与原始数据导出 |
| 首页 | 眼健康指数、今日感受打卡、用屏与环境感知、实时眨眼与距离检测、护眼操、休息提醒、用眼趋势、爱科 AI 问询 |
| 我的 | 账户、家庭成员资料、测试记录、云端同步（分项同意、导出）、权限与通知 |

联网功能（账户、同步、AI 解读与问询）需要自行部署后端；不配置后端地址时应用完全离线可用。

## 目录

```
app/            入口、导航宿主、DI 装配、系统组件（提醒闹钟）
build-logic/    Gradle 约定插件
core/           model / common / domain / data / network / camera / notifications / designsystem / ui
feature/        home / services / mine / testmodel（实验页，仅调试构建可见入口）
shared/         KMP 共用代码：领域模型、算法、四项自测引擎、同步契约（安卓、iOS、服务端共用）
iosApp/         SwiftUI 应用（XcodeGen 工程，CocoaPods 引入 MediaPipe）
backend/        Ktor 服务端与离线训练脚本
scripts/        图标、提示音生成脚本；scripts/ios 为 iOS 校验与打包脚本
docs/           规范、设计与实录文档
```

每个模块根目录有同名的 `.md` 说明（如 `feature/services/SERVICES.md`），改动前先读。
协作约定、模块地图与关键约束见 [CLAUDE.md](CLAUDE.md)（与 `AGENTS.md` 内容一致）。

## 构建

环境：JDK 17、Android NDK 27.3.13750724、CMake 3.22.1。预编译的原生库只有 arm64-v8a。

```bash
./gradlew.bat :app:compileDebugKotlin      # 快速验证
./gradlew.bat assembleDebug                # 完整构建（含 C++，首次需联网拉取依赖）
./gradlew.bat :feature:services:testDebugUnitTest :feature:home:testDebugUnitTest
./gradlew.bat -p shared :model:jvmTest :domain:jvmTest :screening:jvmTest
python scripts/ios/check_project.py        # 双端令牌、模型、文档一致性检查
```

- iOS 的构建、签名与打包见 [docs/iOS适配与双端构建.md](docs/iOS适配与双端构建.md)。
- 后端见 [backend/server/SERVER.md](backend/server/SERVER.md) 与 [docs/后端部署说明.md](docs/后端部署说明.md)。
- 推送到 `master` 会触发 GitHub Actions「双端回归与 iOS 编译」：共用算法测试、安卓回归与调试包、iOS 模拟器测试与未签名安装包。

## 文档

- [docs/NIA项目开发规范.md](docs/NIA项目开发规范.md)：分层与依赖规则（项目结构对齐 Now in Android）
- [build-logic/CONVENTIONS.md](build-logic/CONVENTIONS.md)：约定插件与模块接入
- [DESIGN.md](DESIGN.md)：视觉规范；[core/designsystem/DESIGNSYSTEM.md](core/designsystem/DESIGNSYSTEM.md)：设计令牌
- [docs/三病种风险评估现状与改进方案-2026-10-16.md](docs/三病种风险评估现状与改进方案-2026-10-16.md)：判定规则与改进方向
- [docs/科研方向-2026-11-10/](docs/科研方向-2026-11-10/)：两份 CHI 选题建议幻灯片
