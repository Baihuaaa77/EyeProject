# core:designsystem

> 设计令牌与主题：颜色 / 排版 / 圆角 + 自定义主题入口 + 通用 Modifier/过渡/窗口工具。全项目视觉的唯一事实来源。

## 职责

- 定义不依赖 Material 的自定义设计令牌（颜色映射自旧项目 colors.xml，亮/暗双主题）
- 提供 `EyeProjectTheme` 主题入口与 `CompositionLocal` 分发
- 收敛通用 Modifier 扩展与导航过渡动画

## 对外 API

### 主题（theme/）

| 类型 | 说明 |
|---|---|
| `EyeProjectTheme` | 主题入口：按 `isSystemInDarkTheme()` 选择 `LightEyeColors` / `DarkEyeColors`，同时提供颜色与排版两个 Local |
| `EyeTheme` | 便捷读取器：`EyeTheme.colors` / `EyeTheme.typography` |
| `EyeColors` | 23 个颜色令牌（`@Immutable`）：主色/文本/边框/success·warning·danger 及其 soft 色/相机背景等 |
| `EyeTypography` | 8 个字号令牌：headline/title/subtitle/body/bodySecondary/metricValue/chip/button |
| `EyeShapes` | 圆角令牌：small(12dp) → panel(22dp) |
| `LocalEyeColors` / `LocalEyeTypography` | CompositionLocal，组件层直接 `LocalEyeColors.current` |

### 工具（util/）

| 类型 | 说明 |
|---|---|
| `Modifier.borderBottom` / `borderTop` | `drawBehind` 画单边线（默认 0.5dp） |
| `Modifier.clickableNoRipple` | 无水波纹点击 |
| `Modifier.screenHorizontalPadding{Small,Medium,Large}` | 屏幕水平留白令牌 |
| `ScreenSlideTransition` | 导航页转场（左右滑动 400ms，enter/exit/popEnter/popExit 四件套） |
| `getScreenHeight()` / `getTopStatusBarPadding()` / `getBottomNavigationBarPadding()` | 窗口尺寸与系统栏 inset |

## 依赖关系

- **依赖**：仅 Compose（BOM ui/foundation）——不含 material3、不含其他 core 模块
- **被依赖**：`core:ui`、全部 5 个 feature 模块、`app`
- 资源：`res/drawable`（如 ic_arrow_right）、`res/values`

## 注意事项

- **不要**在本模块引入 MaterialTheme/Material 配色；组件样式一律走令牌
- 新增颜色/字号先加令牌再使用，禁止在 feature 里写死 `Color(0xFF…)`
