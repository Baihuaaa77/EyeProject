# EyeProject — 眼健康助手

安卓 / iOS 眼健康自测应用：三项前台测试共用 Kotlin Multiplatform 核心，
CameraX / AVFoundation + MediaPipe Face Landmarker 采集眼部数据，安卓 Compose 与苹果 SwiftUI 呈现。
项目结构严格对齐 [Now in Android (NIA)](docs/NIA项目开发规范.md)。

## 协作约定

- 交流、代码注释、文档一律**中文**
- 代码风格向周围代码看齐；UI 样式只从 `core:designsystem` 令牌读，禁止写死颜色/字号

## 构建与验证

```bash
./gradlew.bat :app:compileDebugKotlin   # 快速验证（改 Kotlin 后先跑这个）
./gradlew.bat assembleDebug             # 完整构建（含 C++/CMake）
```

- 环境：JDK 17、**NDK 27.3.13750724**（本机 NDK 28 安装不完整，勿改 ndkVersion）、CMake 3.22.1
- 已知坑：
  - tasks-vision / LiteRT 预编译 `.so` 仅 **arm64-v8a**，`abiFilters` 勿删
  - 首次构建 CMake `FetchContent` 需联网拉 abseil / flatbuffers / OpenCL
  - `gradle.properties` 中的 `android.disallowKotlinSourceSets=false` 等是 AGP 9 + KSP 必需项，勿动
  - Windows 控制台是 GBK：脚本里 `print()` Unicode 会炸，用 `sys.stdout.buffer.write`

## 模块地图（每个模块根目录有同名 `.md` 详细文档，改动前先读）

```
app/            入口 + EyeProjectNavHost + DI 装配 + 系统组件（提醒闹钟）
build-logic/    约定插件（eyeproject.*，见其 CONVENTIONS.md）
core/
  model/        纯 Kotlin 领域模型（零 Android 依赖）
  common/       格式化纯函数
  domain/       算法（BlinkDetector/DepthFromIris）+ app 实现的端口接口
  data/         休息提醒偏好持久化
  camera/       相机管线唯一绑定点（预览可选）+ MediaPipe FaceLandmarker + 相机内参
  notifications/ Notifier 抽象 + 渠道 + NotifierModule
  designsystem/ 设计令牌 + EyeProjectTheme（唯一视觉事实源）
  ui/           自绘通用组件（EyeButton/EyeText/…）
feature/
  home/         首页：品牌、休息提醒、模型测试入口
  services/     干眼/老视/斜视自检
  testmodel/    实验页：主导航 + gaze/（FaceDetector + LiteRT L2CS-Net 注视估计）+ blink/（FaceLandmarker EAR 眨眼检测）
  mine/ agent/  占位页
```

## 必读规范（动手前）

1. `docs/NIA项目开发规范.md` —— §0 快速判定清单 + 各层规则 + §9 与本项目的差异对照
2. `build-logic/CONVENTIONS.md` —— 插件组合与模块接入配方

## 关键约束（违反 = 重构）

- **feature 模块禁止互相依赖**；core 不依赖 feature/app
- Repository「接口 + 实现 + DI 绑定」同处一个 core 模块；`app/di/AppModule` 只装 domain/feature 端口
- 仓储读数据返回 Flow，**禁止快照 getter**；写用 suspend
- 导航三件套：`XRoute` + `navigateToX()` + `NavGraphBuilder.xScreen()`；app 持有 NavHost
- UI 状态 = `StateFlow<XUiState>`，事件用 lambda 上抛；不要 mock 库写测试（NIA 原则）

## 可用技能

| 技能 | 用途 |
|---|---|
| `/new-screen <feature> <页面> [top-level]` | 新增页面：导航三件套 + NavHost/Tab 接线 |
| `/new-module <模块路径>` | 新增模块：插件组合决策 + settings 注册 + 文档骨架 |
| `/nia-check` | 对当前未提交改动跑 NIA 合规审查 |
| `/perf-capture [秒数]` | 真机性能采集与分析（CPU/GPU/内存，adb + 设备端脚本） |
| `/session-wrap [主题]` | 阶段收尾：把本段对话有价值内容同步到 auto memory，汇报下次起点 |
| `/session-continue [关键词]` | 会话续接：从记忆恢复进度/决定/下一步，缺失内容明说，确认后直接继续 |
| `/memory-maintain` | 记忆库定期体检：合并/修正/删除/拆分，10 项检查单，拿不准的先问 |
| `/memory-init` | 记忆库初始化/检查：开启确认闸门 + 盘点 + 写入纪律 + 幂等出口 |

## 技术深水区（docs/）

MediaPipe 编译集成、LiteRT、L2CS-Net、MediaPipe 自定义图、IMU 融合方案——
涉及模型/原生层改动前先查 `docs/` 对应实录，别凭印象动手。

## Agent skills

### Issue tracker

Issue 以本地 Markdown 文件形式存放在 `.scratch/<feature-slug>/`（仓库无远程追踪器）。
详见 `docs/agents/issue-tracker.md`。

### Triage labels

使用五个规范标签：needs-triage / needs-info / ready-for-agent / ready-for-human / wontfix。
详见 `docs/agents/triage-labels.md`。

### Domain docs

Single-context：根目录 `CONTEXT.md` + `docs/adr/`，文件惰性创建。
详见 `docs/agents/domain.md`。
