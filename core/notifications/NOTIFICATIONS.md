# core:notifications

> 通知抽象层：渠道定义、通知构建，接口 + 系统实现 + DI 绑定同模块（NIA 规范）。

## 职责

- 统一管理通知渠道与通知 ID，避免魔法值散落
- 屏蔽系统通知的权限检查与渠道创建细节，业务层只面对 `Notifier`

## 对外 API

| 类型 | 说明 |
|---|---|
| `Notifier` | 接口：`postReminderNotification(openAppIntent)` |
| `SystemNotifier` | 系统实现（`@Singleton`，`@Inject constructor`）：自动建渠道、检查 `POST_NOTIFICATIONS` 权限（T+ 无权限静默跳过） |
| `NotificationChannels` | 渠道与 ID 常量：提醒（高优先级、闹铃音、振动）；升级时清理旧版本服务渠道 |
| `di/NotifierModule` | Hilt 绑定：`SystemNotifier → Notifier` |

### 渠道与 ID

| 渠道 | 通知 ID | 用途 |
|---|---|---|
| `eye_care_reminders` | 3201 | 定时护眼提醒（`IMPORTANCE_HIGH`，闹铃音 + 振动） |

## 依赖关系

- **依赖**：Android Core
- **被依赖**：`app`（提醒调度）

## 注意事项

- 新增通知类型：先在 `NotificationChannels` 加渠道常量 + 创建函数，再扩展 `Notifier` 接口，不要绕过抽象直接用 `NotificationManager`
