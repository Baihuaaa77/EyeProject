# core:designsystem

> 设计令牌与主题：颜色 / 排版 / 圆角 + 自定义主题入口 + 通用 Modifier/过渡/窗口工具。全项目视觉的唯一事实来源。

## 职责

- 定义不依赖 Material 的自定义设计令牌（中性灰白表面、蓝色操作、语义状态色，亮/暗双主题）
- 提供 `EyeProjectTheme` 主题入口与 `CompositionLocal` 分发
- 收敛通用 Modifier 扩展与导航过渡动画

## 对外 API

### 主题（theme/）

| 类型 | 说明 |
|---|---|
| `EyeProjectTheme` | 主题入口：按 `isSystemInDarkTheme()` 选择 `LightEyeColors` / `DarkEyeColors`，同时提供颜色与排版两个 Local |
| `EyeTheme` | 便捷读取器：`EyeTheme.colors` / `EyeTheme.typography` |
| `EyeColors` | 「澄澈」色彩令牌（`@Immutable`）：底色 / 卡片 / 浅填充 `surfaceVariant`、品牌蓝 `primary`（按钮底 `buttonStart` 两套同值 `#0A66D8`）、类别色（用屏 `screen` 靛蓝、眨眼 `iceInk` 青、光照 `sun` 橙、AI 爱科 `violet` 紫，各带 soft 浅底）、列表图标块实心色 `tileBlue/tileIndigo/tileTeal/tileOrange/tileGreen/tileRed/tileGray`、浮动底栏 `tabBarFill/tabBarEdge`、琥珀 `accent`（留意，色块配深色字 `onWarning`）、示意墨色 `ink`、三级文字、语义色及 soft 色。旧的渐变 / 光晕 / 玻璃令牌（`brand*`、`hero*`、`button*`、`flow*`、`glow`、`glass*`）保留名称，值改为纯色或透明，`hasGlow` 恒为 false |
| `EyeTypography` | 页面大标题 headline 32（Bold）→ 分区标题 title 20 → 卡片标题 subtitle 17 → 正文 body 16 → 辅助 bodySecondary 14 → 标签 chip 13 → 最小 caption 11（底栏名称、图表坐标）；数字 display 56 / metricValue 28 用 `EyeNumberFamily` + 等宽数字（tnum），读数单位 unit 14（配次要文字色，见 `eyeReading`）；按钮 17。中文最粗到 SemiBold |
| `EyeShapes` | 圆角令牌（2026-11-08）：small 10（图标块）→ medium / large 14（按钮、输入、内嵌块）→ card / panel 18 → hero 22（每页主卡、取景框），chip 10；苹果端 `EyeStyle.radius*`（`.continuous` 平滑圆角）同值，界面里不写死圆角 |
| `EyeSpacing` | 间距令牌（4/8 节奏）：xxs 4 · xs 8 · sm 12 · md 16 · lg 20 · xl 24 · xxl 32；页面留白 gutter 16、卡片内边距 16、主卡 20、卡片间距 12、分区标题上方 sectionGap 28、块间距 12 |
| `EyeMotion` | 动效令牌：SHORT/MEDIUM/LONG/DEMO_CYCLE 时长、Standard/Gentle 缓动、按压/落定弹簧 |
| `rememberReducedMotion()` | 系统「移除动画」开启时为 true：循环动效停在关键帧 |
| `LocalEyeColors` / `LocalEyeTypography` | CompositionLocal，组件层直接 `LocalEyeColors.current` |

### 工具（util/）

| 类型 | 说明 |
|---|---|
| `Modifier.borderBottom` / `borderTop` | `drawBehind` 画单边线（默认 0.5dp） |
| `Modifier.clickableNoRipple` | 无水波纹点击 |
| `Modifier.screenHorizontalPadding{Small,Medium,Large}` | 屏幕水平留白令牌 |
| `ScreenSlideTransition` | 导航页转场（左右滑动 400ms，enter/exit/popEnter/popExit 四件套） |
| `getScreenHeight()` / `getTopStatusBarPadding()` / `getBottomNavigationBarPadding()` | 窗口尺寸与系统栏 inset |

## 视觉语言「澄澈」（2026-11-08 改版，参照 Apple 健康 / 健身；样稿见设计画布「眼健康助手 · 视觉改版样稿」）

- **两套主题**（默认跟随系统，「我的 → 通用 → 深色模式」可手动切换）：白天浅灰底 `#F2F2F7` + 白卡；夜晚纯黑底 `#000` + 深灰卡 `#1C1C1E`、浅填充 `#2C2C2E`。品牌蓝按钮底 `#0A66D8`（两套同值，白字 ≥ 5:1），夜晚的蓝色文字 / 图标用 `#4C9BFF`。文字三级：`#1C1C1E / #636366 / #737378`（夜 `#F5F5F7 / #AEAEB2 / #8E8E93`），正文 / 辅助 / 三级在两套主题下都 ≥ 4.5:1。iOS `EyeStyle.swift` 用动态颜色逐项对应（`scripts/ios/check_project.py` 校验）
- **颜色只表达含义**：类别色只出现在小图标、类别标签（`EyeCategoryLabel`）和图表上，不铺大底——用屏靛蓝、眨眼青、光照橙、休息与「正常」绿、AI 爱科紫；状态三级：正常绿 / 留意琥珀 / 风险红，用「小圆点 + 状态色文字」，实心色块只留给页面主结论
- **层次**：卡片纯色、不描边、不投影，只靠底色明度差分层；每页一张主卡（`EyeHeroCard`，圆角 22），其余 `EyeCard`（圆角 18）；分区标题 20 号写在卡片外；一屏一个主按钮（品牌蓝纯色）
- **数字**：读数一律「大号数字 + 小一半的灰色单位」（`eyeReading` / iOS `EyeStyle.reading`），数字等宽；iOS 数字用 SF Pro Rounded
- **不发光、不渐变**：圆环、柱状图、按钮都是纯色；柱状图今天实色、其余 32% 透明度，不画底槽
- **列表**：iOS 设置式分组列表，图标块为实心色块 + 白色图标（`EyeIconTile`）；分隔线 0.5dp 从图标右侧开始
- **导航**：底栏是左右留白的浮动胶囊，三项「图标 + 名称」（iOS 26 起为系统 Liquid Glass——玻璃只用于浮在内容上的导航层）；顶层页右上角只有账户头像
- **示意图**：方法演示用平面上半身短发男孩（纯色平涂，颜色见 `Illustration.kt`）、肤色手掌与扁平手机；斜视注视目标为准星（`drawFixationTarget`，白底画布固定深蓝）。视标、色觉图等测试画面恒为白底黑字，不随主题
- **标志**：主色渐变圆角底 + 白色同心环与中心点（`drawBrandMark`）；导航 `ic_nav_home` 为房屋线稿，状态栏 `ic_stat_eye` 为眼形剪影。启动图标为爱科真人头像 + 浅灰蓝圆盘（`scripts/generate_eyecho_icon.py`，应用内 `EyeAppIcon` 用同一张图）
- **测距反馈色**：`tooClose`（太近，暖红）、`tooFar`（太远，冷蓝）、`onStatus`（色块上的文字）；始终配合箭头与文字，不单靠颜色
- **动效**：轻、柔、可预期——交互用低弹性弹簧，状态切换用 Standard 缓动；无限循环动效只在可见且需要时创建，并尊重 `rememberReducedMotion()`

## 依赖关系

- **依赖**：仅 Compose（BOM ui/foundation）——不含 material3、不含其他 core 模块
- **被依赖**：`core:ui`、全部 5 个 feature 模块、`app`
- 资源：`res/drawable`（如 ic_arrow_right）、`res/values`

## 注意事项

- **不要**在本模块引入 MaterialTheme/Material 配色；组件样式一律走令牌
- 新增颜色/字号先加令牌再使用，禁止在 feature 里写死 `Color(0xFF…)`

### 教学与测试反馈（2026-11-06）

首页功能色块令牌 `Vivid.kt`（2026-11-06 加入）已在 2026-11-08 改版中删除：护眼工具改为白卡 + 类别色小图标。

`Illustration.kt` 集中维护演示男孩的肤色、头发、嘴、腮红与蓝色上衣，两套主题保持自然肤色（深色只把上衣略提亮）。`testReady/testReadySoft` 是测试到位状态专用令牌，改版后与 `success / successSoft` 同值。距离横幅使用柔和底色配语义文字和方向图形，各状态同一高度（横幅一变高，下方注视画布就会移动）。报告风险等级仍使用原有 success 语义。
