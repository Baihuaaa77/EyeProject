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
| `EyeTypography` | 9 个字号令牌：display（主卡大数字 56）/headline/title/subtitle/body/bodySecondary/metricValue/chip/button，最小 13 |
| `EyeShapes` | 圆角令牌：small(10dp) → panel(22dp) → hero(26dp)，chip 为胶囊 |
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

- **两套主题跟随系统**：深色「深海蓝」（近黑蓝底 `#050912`、主色电光蓝 `#4A7DFF`）与浅色「瓷白深蓝」（冷白底 `#F3F4F7`、主色藏青 `#23367A`），是同一产品的夜与昼。iOS `EyeStyle.swift` 用动态颜色逐项对应
- **层次**：字号只分三级（大数字 → 卡片标题与数值 → 标签，最小 13）；一张卡一件事，同卡多项用细竖线分隔；每页一张主卡（`EyeHeroCard`，更亮、深投影）浮在最上，其余卡平铺；一屏一个主按钮（按钮渐变，深色带泛光）
- **质感**：页面为纯色底（`eyeBackdrop`），卡片为玻璃面（半透明 + 上亮下淡描边 + 斜向柔光，`eyeGlass`）。安卓不依赖系统模糊，所有版本一致；iOS 用系统毛玻璃材质，iOS 26 起叠加 Liquid Glass
- **发光**：只在深色主题，且只用于进度圆环、主按钮与图表当前项（多层半透明描边叠出，不用模糊）
- **单色**：卡片分类头（`EyeCategoryHeader`）与图标块统一用主色；只有警示红、「留意」柠檬黄与状态标签用语义色
- **示意图**：方法演示用中性线面人物（只画两只眼，随视线 / 闭眼变化）、扁平手与手机；斜视注视目标为准星（`drawFixationTarget`，白底画布固定深蓝）。视标、色觉图等测试画面恒为白底黑字，不随主题
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
