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
| `EyeBrand.kt` | 品牌绘制：`EyeBrandMark` / `EyeBrandLockup`、`leafEyePath`、吉祥物 `drawMascot`（`MascotEyes` / `MascotMouth` 表情、腮红、叶子摆动）、`drawSparkle`、桌面图标 `EyeAppIcon`、`drawMitten`、`drawCartoonPhone`、`drawTumblingE` |
| `EyeGlyph.kt` | 线性小图标（`EyeGlyph`，Canvas 自绘，跟随 tint），列表行与服务入口用 |

### 容器与卡片

| 组件 | 说明 |
|---|---|
| `EyeSectionCard` | 分区卡片：圆角面板 + 细边框 + 可选标题 |
| `EyeDisclosure` | 默认折叠的次级信息，展开/收起高度+透明度过渡、箭头旋转，带展开状态无障碍语义 |
| `EyeSelectCard` | 入口卡：可选前置图标 + 标题/副标题 + 箭头，按下轻缩，最小 80dp 高 |
| `EyeList.kt` | 设置式分组列表：`EyeListGroup`（组标题 + 圆角面板）、`EyeListRow`（图标块 `EyeIconTile` / 标题 / 副标题 / 右侧值 / 箭头，可禁用）、`EyeSwitchRow`（可带「?」说明），色调 `EyeTileTone` |
| `EyeSubPage` | 二级完整页面骨架：返回栏 + 标题 + 滚动内容 + 可选底栏 |
| `EyeFullScreenPage` | 盖在当前页上的全屏页面（全屏 Dialog），替代底部弹窗，系统返回键关闭 |

### 控件

| 组件 | 说明 |
|---|---|
| `EyeSwitch` | 开关（Box + 动画），选中轨道主色；`onCheckedChange = null` 时仅展示（由外层整行切换） |
| `EyeSegmentedRow` | 分段选择器，泛型 `SegmentedOption<T>`：浅色轨道 + 主色滑块弹簧滑动，RadioButton 语义 |
| `EyeTextField` | 单行输入框（BasicTextField 自绘）：上方标签、浅底细边框、占位文字；`password` 遮挡输入 |
| `AiExplanationCard` | AI 解读 / AI 综合建议卡片（文案由 `AiCardText` 给出，`AiCardText.ADVICE` 为综合建议），状态 `AiCardState`（Locked / Idle（可带同意说明，卡内展开）/ Loading（骨架 + 轮换进度语）/ Done（一句话总结、这说明、可以这样做、何时就医、参考资料、免责说明）/ Failed）；`embedded` 用于嵌在记录行里。苹果端 `AiExplanationView.swift` 同一排版 |
| `EyeStatusChip` | 状态徽标，`StatusTone(NEUTRAL/SUCCESS/DANGER/WARNING)` 语义色；`live` 时带呼吸圆点 |
| `EyeHelp.kt` | `EyeHelpButton`：灰色圆形「?」，点开气泡说明（替代括号小字）；`EyeTitleWithHelp` 标题 + 问号。苹果端 `HelpButton` 同一样式 |
| `EyeCharts.kt` | `EyeBarChart`：近 7 天柱状图（今天高亮、超标换警示色、参考虚线、空值留白） |

### 小瞳与启动

| 组件 | 说明 |
|---|---|
| `XiaoTong` | 会动的吉祥物：待机眨眼与叶子摆动，`MascotMood`（开心 / 思考 / 担心 / 困倦，`MascotMood.of(code)` 取共用核心给出的神情，按结果等级换表情），可点按随机蹦跳 / 眨眼 / 咯咯笑 / 惊讶 / 害羞并配音效 |
| `MascotSounds` | 小瞳音效（`shared/feedback/res/raw` 的 `eye_boop/hop/giggle/wow`，SoundPool），跟随系统设置的音效开关与音量 |
| `EyeSplash` | 冷启动约 1.4 秒的启动动画：小瞳 + 渐变进度条 +「你的主动眼健康助手」，盖在已加载的首页上，不阻塞启动；系统减弱动态效果时缩短 |

### 栏

| 组件 | 说明 |
|---|---|
| `EyeTopBar` | 顶栏：可选前置标志 + 标题/副标题 + 右侧状态徽标或自定义内容（`trailing`，首页放系统设置齿轮） |
| `EyePageHeader` / `EyeHeaderActions` / `EyeAccountButton` / `EyeSettingsGear` | 顶层页标题行：大标题（可带副标题）+ 右上角账户头像按钮与「系统设置」齿轮（首页、服务、我的）；头像按登录状态显示（`LocalEyeAccountBadge`，由 app 提供）。与苹果端 PageHeader / AccountButton / SettingsGear 一致 |
| `TopAppBar` | 通用顶栏（含状态栏 padding 处理） |
| `TopBackBar` | 返回顶栏（背景覆盖状态栏，支持渐变/标题/无文字模式） |

## 依赖关系

- **依赖**：`core:designsystem`；Compose BOM；音效资源目录 `shared/feedback/res`
- **被依赖**：全部 5 个 feature 模块（home / testmodel / services / mine / agent）

## 注意事项

- 组件内部禁止写死颜色/字号，一律读令牌；`EyeText` 的默认样式来自 `LocalEyeTypography`
- 新组件放 `component/` 并在本文档登记；`Eye*` 前缀为业务组件，无前缀的（`Button`/`TopAppBar`/`TopBackBar`）为早期通用组件，命名两族并存，新增一律用 `Eye*` 风格
