# app

> 应用入口层：MainActivity + 导航宿主 + Hilt 装配 + 全部系统组件（前台服务 / 悬浮窗 / 闹钟提醒）。

## 职责

- 组合全部 feature 与 core 模块，持有导航（对标 NIA 的 NiaNavHost）
- 装配「接口在 domain/feature、实现只能在 app」的 Hilt 绑定
- 承载 Android 系统组件：监测前台服务、色温悬浮窗服务、提醒闹钟与开机恢复

## 结构

### 入口与导航

| 类型 | 说明 |
|---|---|
| `MainActivity` | `@AndroidEntryPoint`，edge-to-edge，`EyeProjectTheme { EyeProjectNavHost() }` |
| `ui/EyeProjectNavHost` | NavHost（startDestination = FatigueRoute）+ 自绘底部导航栏（3 Tab）+ 智能体 FAB；顶层页才显示底栏/FAB（按 `NavDestination.hierarchy` + 路由全限定名判断） |
| `ui/TopLevelDestination` | 顶层目的地枚举：HOME / SERVICES / MINE，切换用 `popUpTo + saveState/restoreState`（NIA 同款） |

### DI（di/）

| 类型 | 说明 |
|---|---|
| `AppModule` | 绑定 `ColorTemperatureController` / `ReminderController` / `MonitoringServiceLauncher`。数据层与通知层的绑定已下沉到各自 core 模块（DataModule / NotifierModule），app 只装 feature/domain 层接口 |
| `ColorTemperatureControllerImpl` | 色温控制实现：读写 `ColorTemperaturePreferences` + 启停悬浮窗服务 |
| `ReminderControllerImpl` | 提醒控制实现：委托 `EyeCareReminderScheduler` |

### 系统组件

| 类型 | 说明 |
|---|---|
| `monitoring/MonitoringService` | 后台实时监测前台服务（`foregroundServiceType="camera"`，`LifecycleService`）：`startForeground` 后 `monitor.start(this)`——**把自身作为 LifecycleOwner 传给 FatigueMonitor，是相机后台持续运行的关键**；`START_NOT_STICKY`（相机型 FGS 在 Android 14+ 不能由系统后台重建）；通知带「停止监测」操作（`ACTION_STOP`）；监测自行停止（相机失败）时 `stopSelf()`；`onDestroy` 时 `monitor.stop()` |
| `monitoring/MonitoringServiceLauncherImpl` | `MonitoringServiceLauncher` 实现：`startForegroundService` / `stopService` |
| `display/ColorTemperatureOverlayService` | 色温滤镜前台服务（`specialUse`）：`WindowManager` 挂全屏半透明 View，按 `ColorTemperatureMode` 着色 |
| `reminder/EyeCareReminderScheduler` | object：`AlarmManager` 精确闹钟调度 + SharedPreferences 状态（开关/间隔/下次触发） |
| `reminder/EyeCareReminderReceiver` | 闹钟触发：发提醒通知 + 重排下一次 |
| `reminder/BootCompletedReceiver` | 开机恢复提醒调度 |
| `EyeProtectApplication` | `@HiltAndroidApp` |

## AndroidManifest

权限：CAMERA、SYSTEM_ALERT_WINDOW、FOREGROUND_SERVICE(+CAMERA/SPECIAL_USE)、POST_NOTIFICATIONS、SCHEDULE_EXACT_ALARM、RECEIVE_BOOT_COMPLETED、VIBRATE；uses-feature 声明前置相机与光线传感器（均非必需）。两个前台服务与三个 receiver 均在此注册。

## 依赖关系

- **依赖**：全部 5 个 feature 模块 + core（model/domain/data/notifications/designsystem/camera/ui 间接）；navigation-compose、material3（Scaffold/Text）、vico 经 feature 传递
- **被依赖**：无（顶层模块，feature 层不得反向依赖本模块）

## 注意事项

- app 层只做「装配 + 系统组件 + 导航」，业务逻辑一律下沉 feature/core；新页面通过 feature 的 navigation 扩展接入 NavHost
- `buildTypes.release` 关闭了优化（optimization.enable = false），混淆规则目录 `src/main/keepRules` 暂未启用
- `abiFilters arm64-v8a`：tasks-vision / LiteRT 预编译 `.so` 限制
