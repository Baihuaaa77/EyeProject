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
| `EyeColors` | 品牌「青瞳」色彩令牌（`@Immutable`）：主色湖青 + `onPrimary`、品牌渐变 `brandStart/brandEnd`（`brandBrush`）、晨光琥珀 `accent/accentSoft`、插画墨色 `ink`、文本/边框/语义色及 soft 色 |
| `EyeTypography` | 8 个字号令牌：headline/title/subtitle/body/bodySecondary/metricValue/chip/button |
| `EyeShapes` | 圆角令牌：small(12dp) → panel(22dp) |
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

## 品牌语言「青瞳」

- **色彩**：主色湖青（亮 `#0B7A78` / 暗 `#4FD1C5`，暗色下填充面文字用深色 `onPrimary`）；品牌渐变湖青 → 靛蓝，只用于标志、吉祥物、仪表盘光晕等强调面；琥珀 `accent` 只做点缀，不用作正文文字
- **吉祥物「小瞳」（v2）**：圆润的「团子」脸 + 豆豆眼（无眼白、无写实瞳孔，只留一点高光）+ 头顶双叶嫩芽 + 腮红与微笑。v1 的写实杏仁眼让人有「被盯着看」的不适感，已弃用。所有方法演示、欢迎卡和斜视注视目标都由它出镜
- **标志**：品牌渐变圆角底上的白色小瞳头像——标志即吉祥物。Compose 绘制在 `core:ui` 的 `EyeBrand.kt`（`drawBrandMark` 直接使用 108dp 视口坐标），启动图标 `app/src/main/res/drawable/ic_launcher_*`、状态栏 `ic_stat_eye`、导航 `ic_nav_home` 为同一造型，改一处须同步其他
- **测距反馈色**：`tooClose`（太近，暖红）、`tooFar`（太远，冷蓝）、`onStatus`（色块上的文字）；始终配合箭头与文字，不单靠颜色
- **动效**：轻、柔、可预期——交互用低弹性弹簧，状态切换用 Standard 缓动；无限循环动效只在可见且需要时创建，并尊重 `rememberReducedMotion()`

## 依赖关系

- **依赖**：仅 Compose（BOM ui/foundation）——不含 material3、不含其他 core 模块
- **被依赖**：`core:ui`、全部 5 个 feature 模块、`app`
- 资源：`res/drawable`（如 ic_arrow_right）、`res/values`

## 注意事项

- **不要**在本模块引入 MaterialTheme/Material 配色；组件样式一律走令牌
- 新增颜色/字号先加令牌再使用，禁止在 feature 里写死 `Color(0xFF…)`
