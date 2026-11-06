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
| `EyeColors` | 「深海蓝 / 瓷白深蓝」色彩令牌（`@Immutable`）：主色深蓝 + `onPrimary`、品牌渐变 `brandStart/brandEnd`、主卡 `heroStart/heroEnd/heroBorder`、按钮渐变 `buttonStart/buttonEnd`、光晕 `glow`（仅深色）、玻璃 `glassFill/glassEdgeTop/glassEdgeBottom`、柠檬黄 `accent`（留意，色块配深色字 `onWarning`）、示意墨色 `ink`、三级文字、语义色及 soft 色 |
| `EyeTypography` | 字号阶梯 13/14/16/18/22/30/56：大数字 display 56 → 页标题 headline 30 → 分区标题 title 22 → 卡片标题 subtitle 18 → 正文 body 16 → 辅助 bodySecondary 14 → 标签 chip 13；读数 metricValue 26、按钮 button 16 |
| `EyeShapes` | 圆角令牌（2026-11-06 统一收小）：small 8（图标块）→ medium 10（按钮 / 输入）→ large 12（内嵌面板、功能色块）→ card / panel 14 → hero 16（取景框），chip 8（标签与小按钮，不再用整圆胶囊）；苹果端 `EyeStyle.radius*` / `chipShape` 同值，界面里不写死圆角 |
| `EyeSpacing` | 间距令牌（4/8 节奏）：xxs 4 · xs 8 · sm 12 · md 16 · lg 20 · xl 24 · xxl 32；页面留白 gutter 16、卡片内边距 20、主卡 24、卡片间距 16、块间距 12 |
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

## 视觉语言「深海蓝 / 瓷白深蓝」（2026-11 改版，去卡通化）

- **两套主题**（默认跟随系统，右上角太阳 / 月亮可手动切换）：夜晚深海蓝底 `#081426`、深蓝卡片 `#132644`、主操作电光蓝 `#3578FF`（按钮 `#2A66F0 → #3578FF`，深色上的蓝色文字 `#6E9BFF`）；白天冷白底 `#F2F5FA`、白卡、主色 `#2563EB`。点缀：冰蓝 `ice #49D9F5`（光流末端、主卡描边、状态点，少量）与香槟金 `accent #F2CB70`（「留意」，色块配深色字）。文字与分隔线在两套主题下都按 ui-ux-pro-max 规范核对对比度（正文 / 辅助 / 三级 ≥ 4.5:1），是同一产品的夜与昼。iOS `EyeStyle.swift` 用动态颜色逐项对应
- **层次**：字号只分三级（大数字 → 卡片标题与数值 → 标签，最小 13）；一张卡一件事，同卡多项用细竖线分隔；每页一张主卡（`EyeHeroCard`，更亮、深投影）浮在最上，其余卡平铺；一屏一个主按钮（按钮渐变，深色带泛光）
- **质感**：页面为纯色底（`eyeBackdrop`），卡片为玻璃面（半透明 + 上亮下淡描边 + 斜向柔光，`eyeGlass`）。安卓不依赖系统模糊，所有版本一致；iOS 用系统毛玻璃材质，iOS 26 起叠加 Liquid Glass
- **发光**：只在深色主题，且只用于进度圆环、主按钮与图表当前项（多层半透明描边叠出，不用模糊）
- **单色**：卡片分类头（`EyeCategoryHeader`）与图标块统一用主色；只有警示红、「留意」柠檬黄与状态标签用语义色
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

`Vivid.kt` 是首页功能色块令牌 `EyeColors.vivid`：蓝 / 珊瑚红 / 紫 / 薄荷绿四组左上到右下渐变（`VividTone.brush`），色块上的文字与图标一律 `onVivid`（白），深色主题整体压暗一档；苹果端 `EyeStyle.Vivid` 同值。

`Illustration.kt` 集中维护演示男孩的肤色、头发、嘴、腮红与蓝色上衣，两套主题保持自然肤色（深色只把上衣略提亮）。`testReady/testReadySoft` 是测试到位状态专用令牌：日间沿用绿色，夜间蓝青色 `#91C8D3` 配低饱和底 `#1A3547`。距离横幅使用柔和底色配语义文字和方向图形，不再整块填充高亮状态色。报告风险等级仍使用原有 success 语义。
