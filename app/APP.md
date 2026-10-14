# app

> 应用入口层：MainActivity + 导航宿主 + Hilt 装配 + 系统组件（闹钟提醒）。

## 职责

- 组合全部 feature 与 core 模块，持有导航（对标 NIA 的 NiaNavHost）
- 装配「接口在 domain/feature、实现只能在 app」的 Hilt 绑定
- 承载 Android 系统组件：提醒闹钟与开机恢复

## 结构

### 入口与导航

| 类型 | 说明 |
|---|---|
| `MainActivity` | `@AndroidEntryPoint`，edge-to-edge，`EyeProjectTheme { EyeProjectNavHost() }` |
| `ui/EyeProjectNavHost` | NavHost（startDestination = HomeRoute）+ 自绘底部导航栏（3 Tab）+ 智能体 FAB；顶层页才显示底栏/FAB（按 `NavDestination.hierarchy` + 路由全限定名判断） |
| `ui/TopLevelDestination` | 顶层目的地枚举：HOME / SERVICES / MINE，切换用 `popUpTo + saveState/restoreState`（NIA 同款） |

### DI（di/）

| 类型 | 说明 |
|---|---|
| `AppModule` | 绑定 `ReminderController`；通知层的绑定位于 core:notifications 的 NotifierModule |
| `ReminderControllerImpl` | 提醒控制实现：委托 `EyeCareReminderScheduler` |

### 系统组件

| 类型 | 说明 |
|---|---|
| `reminder/EyeCareReminderScheduler` | object：`AlarmManager` 精确闹钟调度 + SharedPreferences 状态（开关/间隔/下次触发） |
| `reminder/EyeCareReminderReceiver` | 闹钟触发：发提醒通知 + 重排下一次 |
| `reminder/BootCompletedReceiver` | 开机恢复提醒调度 |
| `EyeProtectApplication` | `@HiltAndroidApp` |

## AndroidManifest

权限：CAMERA、RECORD_AUDIO、POST_NOTIFICATIONS、SCHEDULE_EXACT_ALARM、RECEIVE_BOOT_COMPLETED、VIBRATE；uses-feature 声明前置相机与光线传感器（均非必需）。提醒与开机恢复 receiver 在此注册。

## 依赖关系

- **依赖**：全部 5 个 feature 模块 + core（model/domain/data/notifications/designsystem/camera/ui 间接）；navigation-compose、material3（Scaffold/Text）、vico 由自测模块使用
- **被依赖**：无（顶层模块，feature 层不得反向依赖本模块）

## 注意事项

- app 层只做「装配 + 系统组件 + 导航」，业务逻辑一律下沉 feature/core；新页面通过 feature 的 navigation 扩展接入 NavHost
- `buildTypes.release` 关闭了优化（optimization.enable = false），混淆规则目录 `src/main/keepRules` 暂未启用
- `abiFilters arm64-v8a`：tasks-vision / LiteRT 预编译 `.so` 限制
