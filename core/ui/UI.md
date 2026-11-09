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
| `EyeButton` | 三种角色保留；浅色分区表面、整圈渐变边缘光、按下内凹；最小高 52、圆角 14，禁用不可点。 |
| `EyeBrand.kt` | 品牌与示意图绘制：`EyeBrandMark` / `EyeBrandLockup`、`leafEyePath`、示意人物 `drawFigure`（平面上半身短发男孩，`figureEyeCenter`，视线 / 闭眼 / 睁大，`bottom` 控制上衣下沿）、`drawHand`（肤色手掌 + 袖子）、`drawPhone`、注视准星 `drawFixationTarget`、应用图标展示 `EyeAppIcon`（与桌面图标同一张图）、`drawTumblingE` |
| `EyeGlyph.kt` | 线性小图标（`EyeGlyph`，Canvas 自绘，跟随 tint），列表行与服务入口用 |

### 容器与卡片

| 组件 | 说明 |
|---|---|
| `EyeSectionCard` | 分区卡片：柔和双阴影表面 + 可选标题 |
| `EyeSurfaces.kt` | `EyeHeroCard` / `EyeCard` 使用 20 圆角双阴影表面；`eyeGlass` 转交设计系统 `eyeSoftSurface`。圆环默认蓝青渐变并带柔光，支持语义 `tint`；`eyeReading` 分离数字与单位排版。 |
| `EyeDataGlow.kt` | 数据图形副本的蓝色模糊外晕，原图形清晰绘制；用于圆环、柱图、迷你光条与按钮整圈边缘 |
| `EyeDisclosure` | 默认折叠的次级信息，展开/收起高度+透明度过渡、箭头旋转，带展开状态无障碍语义 |
| `EyeSelectCard` | 入口卡：可选前置图标 + 标题/副标题 + 箭头，按下轻缩，最小 80dp 高；不传副标题时标题用大一号的 `title` 字号 |
| `EyeList.kt` | 组标题 + 独立浮雕行，取消连续分隔线；图标为同色浮雕底与彩色线稿，名称用中性文字。保留值、箭头、禁用、自绘图标、整行开关与说明入口。 |
| `EyeSubPage` | 二级完整页面骨架：返回栏 + 标题 + 滚动内容 + 可选底栏 |
| `EyeFullScreenPage` | 盖在当前页上的全屏页面（全屏 Dialog），替代底部弹窗，系统返回键关闭 |

| `EyeMoodFace` | 暖金 / 浅金 / 银色的原生矢量表情，接收 HAPPY / CALM / TIRED / WAITING；业务层映射现有评估等级 |

### 控件

| 组件 | 说明 |
|---|---|
| `EyeSwitch` | 内凹轨道与浮雕滑块，选中滑块蓝青渐变；外层整行开关暴露选中状态。 |
| `EyeSegmentedRow` | 分段选择器，泛型 `SegmentedOption<T>`：浅色轨道 + 主色滑块弹簧滑动，RadioButton 语义 |
| `EyeTextField` | 单行输入框：标签 + 内凹表面 + 占位文字，保留密码、键盘类型与禁用行为。 |
| `AiExplanationCard` | AI 解读 / AI 综合建议卡片（文案由 `AiCardText` 给出，`AiCardText.ADVICE` 为综合建议），状态 `AiCardState`（Locked / Idle（可带同意说明，卡内展开）/ Loading（骨架 + 轮换进度语）/ Done（一句话总结、这说明、可以这样做、何时就医、参考资料、免责说明）/ Failed）；`embedded` 用于嵌在记录行里。苹果端 `AiExplanationView.swift` 同一排版 |
| `EyeStatusChip` | 状态徽标，`StatusTone(NEUTRAL/SUCCESS/DANGER/WARNING)` 语义色；`live` 时带呼吸圆点 |
| `EyeHelp.kt` | `EyeHelpButton`：灰色圆形「?」，点开气泡说明（替代括号小字）；`EyeTitleWithHelp` 标题 + 问号。苹果端 `HelpButton` 同一样式 |
| `EyeCharts.kt` | 凹槽内的细胶囊渐变柱，空值只画轨道、零值不伪造柱高；薄荷色虚线参考，文字读数与无障碍描述保留；移除动画时直接到终态。 |

### 启动与提示音

| 组件 | 说明 |
|---|---|
| `EyeSplash` | 冷启动约 1.3 秒的启动画面：标志轻缓放大淡入 + 细进度线 +「你的主动眼健康助手」，盖在已加载的首页上，不阻塞启动；系统减弱动态效果时缩短 |
| `EyeCueSounds` | 护眼操与休息页的提示音（`EyeCue`：START / PHASE / COUNT / COMPLETE，复用 `shared/feedback/res/raw` 的测试提示音），跟随系统设置的音效开关与音量 |

### 栏

| 组件 | 说明 |
|---|---|
| `EyeTopBar` | 顶栏：可选前置标志 + 标题/副标题 + 右侧状态徽标或自定义内容（`trailing`，首页放系统设置齿轮） |
| `EyePageHeader` / `EyeHeaderActions` / `EyeAccountButton` / `EyeThemeButton` / `EyeSettingsGear` | 顶层页标题行：小号副标题（日期）在上、32 号大标题在下，右上角一个圆钮——有账户入口时为账户头像（登录后品牌蓝实心圆 + 白色首字母，`LocalEyeAccountBadge` 由 app 提供），否则为「系统设置」齿轮，都没有则不放（首页、服务放头像，「我的」不放）。白天 / 夜晚切换（`LocalEyeThemeToggle`，由 app 按主题偏好提供）改在「我的 → 通用 → 深色模式」，`EyeThemeButton` 保留未用。与苹果端 PageHeader / AccountButton / SettingsGear 一致 |
| `TopAppBar` | 通用顶栏（含状态栏 padding 处理） |
| `TopBackBar` | 返回顶栏（背景覆盖状态栏，支持渐变/标题/无文字模式） |

## 依赖关系

- **依赖**：`core:designsystem`；Compose BOM；音效资源目录 `shared/feedback/res`
- **被依赖**：全部 5 个 feature 模块（home / testmodel / services / mine / agent）

## 注意事项

- 组件内部禁止写死颜色/字号，一律读令牌；`EyeText` 的默认样式来自 `LocalEyeTypography`
- 新组件放 `component/` 并在本文档登记；`Eye*` 前缀为业务组件，无前缀的（`Button`/`TopAppBar`/`TopBackBar`）为早期通用组件，命名两族并存，新增一律用 `Eye*` 风格

圆环等长刻度按对应位置取弧线同一渐变色，以 tickRadius 窄光晕点亮。类别和读数名称使用 label 半粗令牌，列表行标题使用 navigationTitle；演示人物与动作已还原原版。

`Modifier.eyeGlowBorder` 来自设计系统；EyeHeroCard、EyeCard、列表行和开关行采用整圈渐变光边，列表光边较淡。小图标底、标签、账户头像保持原表面，避免套多层光框。

`EyeNavigationDock` 接收纯展示项、选中下标与事件回调，不持有导航。64dp 玻璃底栏、滑动选中镜片，保留 Tab / selected 语义；系统移除动画时直接定位。`EyeNavigationItem` 的图标资源由 app 提供。
