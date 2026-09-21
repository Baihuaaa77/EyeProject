# feature:mine

> 「我的」页：当前为占位模块，仅静态 UI。

## 职责

- 底部导航「我的」Tab 的页面壳

## 结构

| 类型 | 说明 |
|---|---|
| `MineScreen` | 占位页（标题 + 空状态），使用 designsystem 令牌与 `screenHorizontalPadding*` 工具 |
| `navigation/MineNavigation` | `MineRoute` + `navigateToMine(navOptions)` + `NavGraphBuilder.mineScreen()` |

## 依赖关系

- **依赖**：`core:designsystem`、`core:ui`
- **被依赖**：`app`（NavHost 挂 mineScreen，底部 Tab 直达）

## 注意事项

- 后续填充内容（用户画像编辑 / 历史记录 / 设置入口）时，画像编辑应与 `feature:fatigue` 的 `UserProfile` 对接而非另建数据源
