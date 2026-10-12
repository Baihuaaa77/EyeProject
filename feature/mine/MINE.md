# feature:mine

> 「我的」页：系统权限入口、本地用眼数据管理、关于与免责声明。

## 职责

- 底部导航「我的」Tab
- 展示本机用眼记录条数，二次确认后清除（`MonitoringRepository.clear()`）

## 结构

| 类型 | 说明 |
|---|---|
| `MineScreen` / `MineContent` | 有状态入口 / 无副作用内容（权限入口、本地数据、关于） |
| `MineViewModel` + `MineUiState` | 由 `MonitoringRepository.samples` 派生记录条数；清除走 IO 协程 |
| `navigation/MineNavigation` | `MineRoute` + `navigateToMine(navOptions)` + `NavGraphBuilder.mineScreen()` |

## 依赖关系

- **依赖**：`core:designsystem`、`core:ui`、`core:model`、`core:data`（Hilt）
- **被依赖**：`app`（NavHost 挂 mineScreen，底部 Tab 直达）

## 注意事项

- 后续填充内容（用户画像编辑 / 历史记录 / 设置入口）时，画像编辑应与 `feature:fatigue` 的 `UserProfile` 对接而非另建数据源
