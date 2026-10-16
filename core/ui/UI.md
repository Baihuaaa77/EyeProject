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
| `EyeButton` | 业务按钮，三态 `EyeButtonRole(PRIMARY/SECONDARY/DANGER)`，按下弹簧缩放、角色切换颜色过渡 |
| `EyeBrand.kt` | 品牌绘制：`EyeBrandMark` / `EyeBrandLockup`、`leafEyePath`、吉祥物 `drawMascot`、`drawMitten`、`drawCartoonPhone`、`drawTumblingE` |

### 容器与卡片

| 组件 | 说明 |
|---|---|
| `EyeSectionCard` | 分区卡片：圆角面板 + 细边框 + 可选标题 |
| `EyeDisclosure` | 默认折叠的次级信息，展开/收起高度+透明度过渡、箭头旋转，带展开状态无障碍语义 |
| `EyeSelectCard` | 入口卡：可选前置图标 + 标题/副标题 + 箭头，按下轻缩，最小 80dp 高 |

### 控件

| 组件 | 说明 |
|---|---|
| `EyeSwitch` | 开关（Box + 动画），选中轨道主色；`onCheckedChange = null` 时仅展示（由外层整行切换） |
| `EyeSegmentedRow` | 分段选择器，泛型 `SegmentedOption<T>`：浅色轨道 + 主色滑块弹簧滑动，RadioButton 语义 |
| `EyeTextField` | 单行输入框（BasicTextField 自绘）：上方标签、浅底细边框、占位文字；`password` 遮挡输入 |
| `EyeStatusChip` | 状态徽标，`StatusTone(NEUTRAL/SUCCESS/DANGER/WARNING)` 语义色；`live` 时带呼吸圆点 |

### 栏

| 组件 | 说明 |
|---|---|
| `EyeTopBar` | 顶栏：可选前置标志 + 标题/副标题 + 状态徽标 |
| `TopAppBar` | 通用顶栏（含状态栏 padding 处理） |
| `TopBackBar` | 返回顶栏（背景覆盖状态栏，支持渐变/标题/无文字模式） |

## 依赖关系

- **依赖**：`core:designsystem`；Compose BOM
- **被依赖**：全部 5 个 feature 模块（home / testmodel / services / mine / agent）

## 注意事项

- 组件内部禁止写死颜色/字号，一律读令牌；`EyeText` 的默认样式来自 `LocalEyeTypography`
- 新组件放 `component/` 并在本文档登记；`Eye*` 前缀为业务组件，无前缀的（`Button`/`TopAppBar`/`TopBackBar`）为早期通用组件，命名两族并存，新增一律用 `Eye*` 风格
