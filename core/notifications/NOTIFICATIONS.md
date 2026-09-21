# core:notifications

> 通知抽象层：渠道定义、通知构建，接口 + 系统实现 + DI 绑定同模块（NIA 规范）。

## 职责

- 统一管理通知渠道与通知 ID，避免魔法值散落
- 屏蔽系统通知的权限检查与渠道创建细节，业务层只面对 `Notifier`

## 对外 API

| 类型 | 说明 |
|---|---|
| `Notifier` | 接口：`postReminderNotification(openAppIntent)`；`buildColorTemperatureNotification(mode, openAppIntent, stopIntent)` |
| `SystemNotifier` | 系统实现（`@Singleton`，`@Inject constructor`）：自动建渠道、检查 `POST_NOTIFICATIONS` 权限（T+ 无权限静默跳过） |
| `NotificationChannels` | 渠道与 ID 常量：提醒（高优先级、闹铃音、振动）、色温服务（低）、监测服务（低） |
| `di/NotifierModule` | Hilt 绑定：`SystemNotifier → Notifier` |

### 渠道与 ID

| 渠道 | 通知 ID | 用途 |
|---|---|---|
| `eye_care_reminders` | 3201 | 定时护眼提醒（`IMPORTANCE_HIGH`，闹铃音 + 振动） |
| `color_temperature_service` | 2101 | 色温前台服务常驻通知 |
| `eye_care_monitoring` | 2201 | 后台监测前台服务常驻通知 |

## 依赖关系

- **依赖**：`core:model`（`ColorTemperatureMode`）
- **被依赖**：`app`（MonitoringService、提醒调度、色温服务）

## 注意事项

- 新增通知类型：先在 `NotificationChannels` 加渠道常量 + 创建函数，再扩展 `Notifier` 接口，不要绕过抽象直接用 `NotificationManager`
