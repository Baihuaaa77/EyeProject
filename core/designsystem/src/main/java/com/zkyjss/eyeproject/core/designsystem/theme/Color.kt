package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 色彩体系「澄澈」（2026-11-08 视觉改版，样稿见设计画布「眼健康助手 · 视觉改版样稿」）：
 *
 * - 中性优先：白天浅灰底 #F2F2F7 + 白卡，夜晚纯黑底 #000 + #1C1C1E 卡；卡片不描边、不投影，只靠底色深浅分层
 * - 不用光晕和大面积渐变：主按钮、圆环、图表一律纯色（各渐变令牌起止同色，[hasGlow] 恒为 false）
 * - 品牌蓝只有一个：按钮底 #0A66D8（两套主题同值，白字 ≥ 5:1）；夜晚的蓝色文字 / 图标用亮一档的 #4C9BFF
 * - 类别色只出现在小图标、类别标签和图表上，不铺大底：用屏靛蓝 [screen]、眨眼青 [iceInk]、光照橙 [sun]、
 *   休息与正常绿 [success]、AI 爱科紫 [violet]；状态三级：正常绿 / 留意琥珀 / 风险红
 * - 列表图标块用实心色块 + 白色图标（[tileBlue] 等，两套主题同值）
 * - 正文、辅助、三级文字在两套主题下都 ≥ 4.5:1
 */
@Immutable
data class EyeColors(
    val isDark: Boolean,
    val white: Color,
    val black: Color,
    val gray: Color,
    val blue: Color,
    val appBackground: Color,
    val panelBackground: Color,
    /** 浅填充：次要按钮、分段控件底、输入框、圆环底环。 */
    val surfaceVariant: Color,
    val cameraSurface: Color,
    val primary: Color,
    val primaryDark: Color,
    val primarySoft: Color,
    /** 主色填充面上的文字/图标色。 */
    val onPrimary: Color,
    /** 品牌图形色（改版后起止同色，即纯色）。 */
    val brandStart: Color,
    val brandEnd: Color,
    /** 琥珀「留意」：圆环、状态点与色块底（色块上配深色字 [onWarning]；浅底上作文字用 [warning]）。 */
    val accent: Color,
    val accentSoft: Color,
    /** 插图墨色：示意图里的线稿与人物五官，深浅主题都保持深色。 */
    val ink: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    /** 第三级文字：单位、坐标轴、占位符。 */
    val textTertiary: Color,
    val textInverse: Color,
    /** 分隔线。 */
    val borderSubtle: Color,
    /** 卡片描边（改版后透明，保留令牌以免调用处改动）。 */
    val cardBorder: Color,
    /** 主卡底（改版后与普通卡片同色、纯色）。 */
    val heroStart: Color,
    val heroEnd: Color,
    val heroBorder: Color,
    /** 投影色：只给浮在内容上的底栏用。 */
    val shadow: Color,
    /** 主按钮底（纯色）。 */
    val buttonStart: Color,
    val buttonEnd: Color,
    /** 光晕色（改版后透明，不再发光）。 */
    val glow: Color,
    /** 数据图形色（改版后纯色）。 */
    val flowStart: Color,
    val flowEnd: Color,
    /** 眨眼类别：[ice] 图形、[iceInk] 文字与图标、[iceSoft] 浅底。 */
    val ice: Color,
    val iceInk: Color,
    val iceSoft: Color,
    /** AI 爱科类别（紫）。 */
    val violet: Color,
    val violetSoft: Color,
    /** 用屏类别（靛蓝）。 */
    val screen: Color,
    val screenSoft: Color,
    /** 光照类别（橙）。 */
    val sun: Color,
    val sunSoft: Color,
    /** 卡片面（纯色）；描边令牌改版后透明。 */
    val glassFill: Color,
    val glassEdgeTop: Color,
    val glassEdgeBottom: Color,
    /** 浮动底栏：半透明面 + 极淡描边。 */
    val tabBarFill: Color,
    val tabBarEdge: Color,
    /** 列表图标块的实心底色（白色图标），两套主题同值。 */
    val tileBlue: Color,
    val tileIndigo: Color,
    val tileTeal: Color,
    val tileOrange: Color,
    val tileGreen: Color,
    val tileRed: Color,
    val tileGray: Color,
    val success: Color,
    val successSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val neutralSoft: Color,
    val overlayScrim: Color,
    /** 测距反馈：太近用红、太远用蓝（色盲友好的红蓝对比，界面上始终配合箭头与文字）。 */
    val tooClose: Color,
    val tooFar: Color,
    /** 状态色块（太近/太远/合适）上的文字与图标色。 */
    val onStatus: Color,
    /** 琥珀「留意」色块上的文字色（深色字）。 */
    val onWarning: Color,
) {
    val brandBrush: Brush get() = Brush.linearGradient(brandColors)
    val brandColors: List<Color> get() = listOf(brandStart, brandEnd)
    val buttonBrush: Brush get() = Brush.linearGradient(listOf(buttonStart, buttonEnd))
    val heroBrush: Brush get() = Brush.linearGradient(listOf(heroStart, heroEnd))
    /** 横向数据图形色（进度条）。 */
    val flowBrush: Brush get() = Brush.horizontalGradient(listOf(flowStart, flowEnd))
    /**
     * 实心状态色块的底色与字色：「留意」（[warning]）的色块用琥珀 [accent] 配深色字 [onWarning]，其余语义色配 [onStatus]。
     */
    fun statusFill(color: Color): Pair<Color, Color> = if (color == warning) accent to onWarning else color to onStatus
    /** 是否画光晕（改版后恒为 false）。 */
    val hasGlow: Boolean get() = glow.alpha > 0f
}

private val TileBlue = Color(0xFF2F7CF6)
private val TileIndigo = Color(0xFF5E5CE6)
private val TileTeal = Color(0xFF14A3B3)
private val TileOrange = Color(0xFFF28C28)
private val TileGreen = Color(0xFF30B158)
private val TileRed = Color(0xFFF0454F)
private val TileGray = Color(0xFF8E8E93)

/** 白天：浅灰底 + 白卡，纯色、无描边无投影。 */
val LightEyeColors = EyeColors(
    isDark = false,
    appBackground = Color(0xFFF2F2F7),
    panelBackground = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9E9EE),
    cameraSurface = Color(0xFF05070C),
    primary = Color(0xFF0A66D8),
    primaryDark = Color(0xFF0852B0),
    primarySoft = Color(0xFFE8F0FB),
    onPrimary = Color(0xFFFFFFFF),
    brandStart = Color(0xFF0A66D8),
    brandEnd = Color(0xFF0A66D8),
    accent = Color(0xFFF5A524),
    accentSoft = Color(0xFFFDF1DE),
    ink = Color(0xFF1C1C1E),
    textPrimary = Color(0xFF1C1C1E),
    textSecondary = Color(0xFF636366),
    textTertiary = Color(0xFF737378),
    textInverse = Color(0xFFFFFFFF),
    borderSubtle = Color(0xFFE3E3E8),
    cardBorder = Color.Transparent,
    heroStart = Color(0xFFFFFFFF),
    heroEnd = Color(0xFFFFFFFF),
    heroBorder = Color.Transparent,
    shadow = Color(0x1F000000),
    buttonStart = Color(0xFF0A66D8),
    buttonEnd = Color(0xFF0A66D8),
    glow = Color.Transparent,
    flowStart = Color(0xFF0A66D8),
    flowEnd = Color(0xFF0A66D8),
    ice = Color(0xFF14A3B3),
    iceInk = Color(0xFF08808D),
    iceSoft = Color(0xFFE3F3F4),
    violet = Color(0xFF7A4FD8),
    violetSoft = Color(0xFFF1ECFC),
    screen = Color(0xFF5856D6),
    screenSoft = Color(0xFFEEEEFC),
    sun = Color(0xFFB4500A),
    sunSoft = Color(0xFFFCEFE3),
    glassFill = Color(0xFFFFFFFF),
    glassEdgeTop = Color.Transparent,
    glassEdgeBottom = Color.Transparent,
    tabBarFill = Color(0xE6FFFFFF),
    tabBarEdge = Color(0x0F000000),
    tileBlue = TileBlue,
    tileIndigo = TileIndigo,
    tileTeal = TileTeal,
    tileOrange = TileOrange,
    tileGreen = TileGreen,
    tileRed = TileRed,
    tileGray = TileGray,
    success = Color(0xFF1C7F3B),
    successSoft = Color(0xFFE6F4EA),
    warning = Color(0xFFA15C00),
    warningSoft = Color(0xFFFDF1DE),
    danger = Color(0xFFD70015),
    dangerSoft = Color(0xFFFDECEE),
    neutralSoft = Color(0xFFE9E9EE),
    overlayScrim = Color(0x66000000),
    tooClose = Color(0xFFD70015),
    tooFar = Color(0xFF0A66D8),
    onStatus = Color(0xFFFFFFFF),
    onWarning = Color(0xFF2A1F00),
    white = Color.White,
    black = Color.Black,
    gray = Color(0xFF737378),
    blue = Color(0xFF0A66D8)
)

/** 夜晚：纯黑底 + #1C1C1E 卡；蓝色文字提亮为 #4C9BFF，按钮底仍是品牌蓝。 */
val DarkEyeColors = EyeColors(
    isDark = true,
    appBackground = Color(0xFF000000),
    panelBackground = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFF2C2C2E),
    cameraSurface = Color(0xFF000000),
    primary = Color(0xFF4C9BFF),
    primaryDark = Color(0xFF8AB8FF),
    primarySoft = Color(0xFF243042),
    onPrimary = Color(0xFFFFFFFF),
    brandStart = Color(0xFF4C9BFF),
    brandEnd = Color(0xFF4C9BFF),
    accent = Color(0xFFFFB340),
    accentSoft = Color(0x29FFB340),
    ink = Color(0xFF0B0B0D),
    textPrimary = Color(0xFFF5F5F7),
    textSecondary = Color(0xFFAEAEB2),
    textTertiary = Color(0xFF8E8E93),
    textInverse = Color(0xFFFFFFFF),
    borderSubtle = Color(0xFF38383A),
    cardBorder = Color.Transparent,
    heroStart = Color(0xFF1C1C1E),
    heroEnd = Color(0xFF1C1C1E),
    heroBorder = Color.Transparent,
    shadow = Color(0x8C000000),
    buttonStart = Color(0xFF0A66D8),
    buttonEnd = Color(0xFF0A66D8),
    glow = Color.Transparent,
    flowStart = Color(0xFF4C9BFF),
    flowEnd = Color(0xFF4C9BFF),
    ice = Color(0xFF3CC8D6),
    iceInk = Color(0xFF3CC8D6),
    iceSoft = Color(0x293CC8D6),
    violet = Color(0xFFB49CFF),
    violetSoft = Color(0x29B49CFF),
    screen = Color(0xFF8E8CFF),
    screenSoft = Color(0x298E8CFF),
    sun = Color(0xFFFF9F43),
    sunSoft = Color(0x29FF9F43),
    glassFill = Color(0xFF1C1C1E),
    glassEdgeTop = Color.Transparent,
    glassEdgeBottom = Color.Transparent,
    tabBarFill = Color(0xE62C2C2E),
    tabBarEdge = Color(0x14FFFFFF),
    tileBlue = TileBlue,
    tileIndigo = TileIndigo,
    tileTeal = TileTeal,
    tileOrange = TileOrange,
    tileGreen = TileGreen,
    tileRed = TileRed,
    tileGray = TileGray,
    success = Color(0xFF4CD675),
    successSoft = Color(0x294CD675),
    warning = Color(0xFFFFB340),
    warningSoft = Color(0x29FFB340),
    danger = Color(0xFFFF6B6B),
    dangerSoft = Color(0x29FF6B6B),
    neutralSoft = Color(0xFF2C2C2E),
    overlayScrim = Color(0x99000000),
    tooClose = Color(0xFFFF6B6B),
    tooFar = Color(0xFF4C9BFF),
    onStatus = Color(0xFF000000),
    onWarning = Color(0xFF1B1608),
    white = Color.White,
    black = Color.Black,
    gray = Color(0xFF8E8E93),
    blue = Color(0xFF4C9BFF)
)

val LocalEyeColors = staticCompositionLocalOf { LightEyeColors }
