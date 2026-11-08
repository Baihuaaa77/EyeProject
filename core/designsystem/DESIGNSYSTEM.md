# core:designsystem

> 全项目视觉的唯一事实源。材质沿用根目录 `DESIGN.md`；字号与图标配色按用户 2026-11-09 的反馈调整。

## 职责

- 提供颜色、排版、圆角、间距、动效令牌与 `EyeProjectTheme`。
- 提供表面绘制及窗口工具；不理解业务模型，不依赖 feature、app 或 MaterialTheme。
- iOS 的 `EyeStyle.swift` 对应同一组令牌，`scripts/ios/check_project.py` 校验基础配色同步。

## 对外 API

| 类型 | 说明 |
|---|---|
| `EyeProjectTheme` | 默认跟随系统，通过 CompositionLocal 分发；「我的 → 深色模式」可手动选择 |
| `EyeTheme` | 读取 colors / typography |
| `EyeColors` | 保留已有字段 API，增加双阴影、标题墨色、蓝青数据令牌；`readableText` 保证数据颜色不直接用作正文 |
| `EyeTypography` | display 60/66、紧凑分数 48/56、headline 26/34、title 20/28、subtitle 18/26、metricValue 30/36、body 16/24、辅助 14/22、图表坐标 12、底栏 tab 11/14；中文最多 SemiBold，数字等宽 |
| `EyeShapes` | 图标块 10、按钮与输入 14、卡片与主卡 20、胶囊 999 |
| `EyeControlSizes` | 作答按键普通 68、大字号 56，避免小屏大字号时下方方向键被挤出可见区；视标物理尺寸不变 |
| `EyeSpacing` | 4/8 节奏；页面边距 20、卡内 20、卡间 16、分区间 24 |
| `EyeMotion` | 按压 150ms、状态切换 300ms、数据绘制 900ms；`rememberReducedMotion()` 读取系统移除动画设置 |
| `EyeGlow` | 数据荧光半径 7、浅 / 深色外晕透明度 0.7 / 0.9、亮芯透明度 0.32；仅用于圆环和数据光条 |
| `Modifier.eyeSoftSurface` | 普通 / 紧凑双阴影；pressed 时使用双向内阴影；深色附加极淡描边。阴影只作用于表面形状，前景文字保持清晰 |

## 配色与表面

| 用途 | 浅色 | 深色 |
|---|---|---|
| 页面 | `#F1F3FA` | `#1E2023` |
| 表面 | `#F4F7FD` | `#26282B` |
| 凹槽 | `#E8EDF7` | `#1B1D20` |
| 标题 | `#141A2B` | `#F2F3F5` |
| 正文 | `#313949` | `#E3E5E8` |
| 辅助 | `#5D6280` | `#9A9DA3` |
| 单位与坐标 | `#878EA3` | `#73767C` |

- 顶左亮阴影与底右暗阴影营造同色材质；浅色卡片不描边，深色描边为白色 3.5% 透明度。
- 数据使用 `#2F7BFF → #18C2FF → #00EFE0`，光晕只画在数据上。小图标用蓝、绿、橙、紫、玫红区分功能；新增紫色图标令牌 `#8B78E6`，依据用户反馈，正文仍保持中性墨色。
- 主按钮同色浮雕表面、深色文字，底部短渐变线识别主操作；次按钮和危险操作保留明确文案。
- 原 glass / hero / button 字段保留兼容名称，表示当前材质，不表示毛玻璃。

## 测试与插画边界

视标、色觉图、相机采集与计算保持原有实现。测试刺激不随应用主题变化。`Illustration.kt` 中的肤色、头发和服装保留原值，避免装饰改造改变演示含义。

## 工具与依赖

- `Modifier.screenHorizontalPaddingLarge()` 使用 EyeSpacing.gutter；其他窗口和 inset 工具位于 util/。
- 本模块仅依赖 Compose ui / graphics / foundation；所有 feature、core:ui 和 app 消费本模块令牌。
- 新增视觉值先在本模块定义，feature 不得新增原始颜色、字号或独立主题。
