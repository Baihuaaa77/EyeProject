# feature:home

首页：品牌与日期（右上角常驻「系统设置」齿轮）、实时眼部检测视窗、休息提醒、开发者工具（模型测试）。与苹果端首页同一顺序与排版（苹果端没有开发者工具）。

- HomeScreen / HomeContent：页面入口与无副作用呈现；`onOpenSettings` 由 app 打开共用的「系统设置」面板。
- HomeViewModel / HomeUiState：提醒状态与一次性通知、精确闹钟权限事件。
- LiveEyeCard / LiveEyeContent / LiveEyeViewModel：实时眼部检测视窗。镜像预览叠加双眼关键点（眼睑 6 点轮廓 + 虹膜圈，闭眼变红），
  眨眼次数、近 1 分钟次数、睁眼 / 闭眼状态、EAR 与阈值、帧率，下方最近 8 秒 EAR 曲线（阈值虚线、闭眼底色、眨眼绿线）。
  逻辑全部在共用 `LiveEyeMonitor`（眨眼判据与干眼测试同一份 `BlinkDetector.dryEyeEar()`）；相机走 `core:camera` 的 `CameraController`，
  每 80 ms 取一次快照刷新。已授权相机时进入首页自动开启，离开首页或 `ON_PAUSE` 即释放相机（只释放自己这一代绑定）；未授权时显示「开始检测」再申请权限。
  画面只在本机处理，不保存、不上传。
- navigation/HomeNavigation：HomeRoute、navigateToHome、homeScreen(onNavigateToTestModel, onOpenSettings)。

依赖 core:common、core:domain、core:model、core:camera、core:designsystem、core:ui；不采集后台数据。导航回调由 app 装配。

本地界面检查：`./gradlew.bat :feature:home:testDebugUnitTest`，截图在 `feature/home/build/reports/ui/`（含 `home-live-idle.png` / `home-live-running.png`）。
