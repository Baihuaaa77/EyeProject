# core:designsystem

> 全项目视觉的唯一事实源。材质沿用根目录 `DESIGN.md`；字号与图标配色按用户 2026-11-09 的反馈调整。

## 职责

- 提供颜色、排版、圆角、间距、动效令牌与 `EyeProjectTheme`。
- 提供表面绘制及窗口工具；不理解业务模型，不依赖 feature、app 或 MaterialTheme。
- iOS 的 `EyeStyle.swift` 对应同一组令牌，`scripts/ios/check_project.py` 校验基础配色同步。

## 对外 API

| 类型 | 说明 |
|---|---|
| `EyeProjectTheme` | 默认跟随系统，通过 CompositionLocal 分发；主页顶栏日夜按钮可手动选择 |
| `EyeTheme` | 读取 colors / typography |
| `EyeColors` | 保留已有字段 API，增加双阴影、标题墨色、蓝青数据令牌；`readableText` 保证数据颜色不直接用作正文 |
| `EyeTypography` | display 60/66、紧凑分数 48/56、headline 28/36、title 22/30、subtitle 20/28、metricValue 30/36、body 16/24、辅助 14/22、灰色小标题 label 16/24 半粗、列表标题 navigationTitle 18/26 半粗、图表坐标 12、底栏 tab 11/14；中文最多 SemiBold，数字等宽 |
| `EyeShapes` | 图标块 10、按钮与输入 14、卡片与主卡 20、胶囊 999 |
| `EyeControlSizes` | 作答按键普通 68、大字号 56，避免小屏大字号时下方方向键被挤出可见区；视标物理尺寸不变 |
| `EyeSpacing` | 4/8 节奏；页面边距 20、卡内 20、卡间 16、分区间 24 |
| `EyeMotion` | 按压 150ms、状态切换 300ms、数据绘制 900ms；`rememberReducedMotion()` 读取系统移除动画设置 |
| `EyeGlow` | 数据荧光半径 7、浅 / 深色外晕透明度 0.7 / 0.9；圆环无白色内线，数据柱保留亮芯 |
| `Modifier.eyeSoftSurface` | 普通 / 紧凑双阴影；pressed 时使用双向内阴影；深色附加极淡描边。阴影只作用于表面形状，前景文字保持清晰 |

## 配色与表面

| 用途 | 浅色 | 深色 |
|---|---|---|
| 页面 | `#F5F6F8` | `#1E2023` |
| 表面 | `#FFFFFF` | `#26282B` |
| 凹槽 | `#E9EDF3` | `#1B1D20` |
| 标题 | `#141A2B` | `#F2F3F5` |
| 正文 | `#313949` | `#E3E5E8` |
| 辅助 | `#5D6280` | `#9A9DA3` |
| 单位与坐标 | `#878EA3` | `#73767C` |

- 顶左亮阴影与底右暗阴影营造同色材质；基础表面保留低对比阴影；按当前用户反馈，主要卡片叠加渐变细光边，详见下方试版说明。
- 数据使用 `#2F7BFF → #18C2FF → #00EFE0`，数据光晕与装饰边缘光分层绘制，文字保持清晰。小图标用蓝、橙、紫、玫红区分功能；原绿色图标改用蓝色或紫色，正常状态色统一为主蓝；新增紫色图标令牌 `#8B78E6`，依据用户反馈，正文仍保持中性墨色。
- 主操作与次操作按钮使用整圈渐变边缘光，取消底部短线；浅色主按钮浅蓝底、深色主按钮深蓝灰底，危险操作保留语义提示。
- 原 glass / hero / button 字段保留兼容名称，表示当前材质，不表示毛玻璃。

## 指数、对话与表情

- 首页独立指数令牌 `healthScore / healthScoreCompact` 使用 Nunito Black 900 字重，仅限数字；对话正文 `chatBody` 为 18/28。
- 字体与 OFL 授权双端共用，详见 [FONTS.md](FONTS.md)。用户消息使用 `chatUserBackground / chatUserText`（主蓝高对比变体 + 白字）。
- `moodGold / moodWarm / moodSilver` 对应暖金、浅金、银色的矢量表情，只表达已有评估等级。
- 仪表从左下向上再向右下充能，底部留出账户头像开口；轨道内侧等长短刻度按对应位置取同一蓝青渐变，并用独立窄光晕点亮。头像用实际账户首字母或默认人像，不虚构用户照片。

## 测试与插画边界

视标、色觉图、相机采集与计算保持原有实现。测试刺激不随应用主题变化。测试引导已使用 `shared/guide/` 的爱科数字人照片分镜，两端共用图片；舞台高度令牌 `guideStageHeight=200`，服务页采用紧凑舞台。`Illustration.kt` 的旧插画令牌保留供通用绘制原语兼容，测试引导不再绘制卡通人物。

## 工具与依赖

- `Modifier.screenHorizontalPaddingLarge()` 使用 EyeSpacing.gutter；其他窗口和 inset 工具位于 util/。
- 本模块仅依赖 Compose ui / graphics / foundation；所有 feature、core:ui 和 app 消费本模块令牌。
- 新增视觉值先在本模块定义，feature 不得新增原始颜色、字号或独立主题。

标题层次：headline 28/36、title 22/30、subtitle 20/28；灰色类别标题 label 16/24 半粗，列表标题 navigationTitle 18/26 半粗。双端同步。

卡片光边试版：`eyeGlowBorder` 缓存形状，叠加蓝 → 青蓝 → 紫的完整渐变细边；宽 1.2、内缩 1.5、光晕 3，浅 / 深色线条透明度 0.72 / 0.64。列表采用 0.65 倍强度。光效只作用在边缘，不新增循环动画，不改变内容尺寸与点击范围。

Liquid Glass 试版：`EyeLiquidGlass` / `eyeLiquidGlass` 为 Android API 26+ 的分层视觉模拟，使用半透明填充、轻微蓝色环境染色、弯曲高光和双层镜片边缘；轮廓与画刷按尺寸缓存，不采集页面快照、不做实时背景折射。仅用于护眼工具、爱科入口、自测入口与底部导航，普通数据卡保留渐变光边。

光边独立配色 borderColors：蓝 `#2F7BFF` → 青蓝 `#18C2FF` → 紫 `#8B78E6`，卡片与按钮共用；圆环和柱图仍使用原蓝青数据配色。

距离反馈：合适使用主蓝 `#2F7BFF`，太远的图形与边缘光使用黄 `#FFE45C`；太远的标题文字在浅色主题使用深金黄 `#8A6500`、深色主题使用亮黄，说明保持中性文字。太近仍为红色。

仪表空轨道 ringTrack 为浅色 `#D1DDEE` / 深色 `#344458`，提高无数据时的可见度；未知分数仍显示破折号，不绘制虚假进度。

圆环光晕按三倍模糊半径与宽描边增量预留外扩画布，抵消布局尺寸，避免四边裁切；环体、刻度与中心内容大小不变。问诊消息头像 48、介绍头像 104。

人物使用透明半身素材：问诊顶部 132×184，首页卡片内 144×200，右侧留 8 的空间；不加圆形裁切或圆形衬底。三个主页顶栏统一为日夜切换与系统设置齿轮。

人物尺寸：问诊 112×144，首页 124×160；顶端对齐裁切，保留胸前 logo，首页卡片最小高 180。首页问诊标题使用 title，与实时检测同级。

测试令牌：EyeColors.optotypeSurround 为视标圆外底色（浅色白、深色页面底色），圆形场内仍用固定 white / black。EyeControlSizes.gazeCanvasHeight=480、gazeCanvasMinHeight=160，标定与追踪共用自适应高度，紧凑横屏降低基础高度避免目标出屏；iOS EyeStyle 对应。
