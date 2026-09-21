# core:ui

> 可复用自绘 UI 组件库：全部基于 Compose Foundation 自绘，不使用 Material 组件。

## 职责

- 收敛跨 feature 重复的自绘组件，保证视觉与交互一致
- 组件样式一律从 `core:designsystem` 令牌读取（`LocalEyeColors` / `LocalEyeTypography` / `EyeShapes`）

## 组件清单（component/）

### 基础原语

| 组件 | 说明 |
|---|---|
| `EyeText` | 文字原语（BasicText 封装），替代 Material `Text` |
| `EyeIcon` | 图标（Image + ColorFilter.tint），装饰性图标 `contentDescription = null` |
| `Button.kt` | 通用基础按钮（形状/背景/边框/内边距可配，默认矩形） |
| `EyeButton` | 业务按钮，三态 `EyeButtonRole(PRIMARY/SECONDARY/DANGER)`，带按压反馈 |

### 容器与卡片

| 组件 | 说明 |
|---|---|
| `EyeSectionCard` | 分区卡片：圆角面板 + 细边框 + 可选标题 |
| `EyeMetricCard` | 指标卡：可选图标 + 标签 + 大数值 + 说明 |
| `EyeSelectCard` | 选择卡：整行可点的面板（内容文本 + 箭头），72dp 高，用于入口列表 |

### 控件

| 组件 | 说明 |
|---|---|
| `EyeSwitch` | 开关（Box + 动画），选中轨道主色 |
| `EyeSegmentedRow` | 分段选择器，泛型 `SegmentedOption<T>`，选中项高亮主色 |
| `EyeStatusChip` | 状态徽标，`StatusTone(NEUTRAL/SUCCESS/DANGER/WARNING)` 语义色 |

### 栏

| 组件 | 说明 |
|---|---|
| `EyeTopBar` | 首页顶栏：Logo + 标题/副标题 + 状态徽标 |
| `TopAppBar` | 通用顶栏（含状态栏 padding 处理） |
| `TopBackBar` | 返回顶栏（背景覆盖状态栏，支持渐变/标题/无文字模式） |

## 依赖关系

- **依赖**：`core:designsystem`；Compose BOM
- **被依赖**：全部 5 个 feature 模块（fatigue / testmodel / services / mine / agent）

## 注意事项

- 组件内部禁止写死颜色/字号，一律读令牌；`EyeText` 的默认样式来自 `LocalEyeTypography`
- 新组件放 `component/` 并在本文档登记；`Eye*` 前缀为业务组件，无前缀的（`Button`/`TopAppBar`/`TopBackBar`）为早期通用组件，命名两族并存，新增一律用 `Eye*` 风格
