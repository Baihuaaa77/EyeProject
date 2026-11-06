package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 色彩体系「深海蓝」（2026-11 视觉升级，按 ui-ux-pro-max 规范核对对比度）：
 *
 * - 夜晚：深海蓝底 #081426、深蓝卡片 #132644，主操作电光蓝 #3578FF（按钮 #2A66F0 → #3578FF 渐变，白字 ≥ 4.5:1），
 *   深色上的蓝色文字 / 图标用亮一档的 #6E9BFF（卡片上 5.6:1）
 * - 白天：冷白底 #F2F5FA、白卡，主色 #2563EB（白底文字 5.2:1），按钮同样渐变到电光蓝 #3578FF
 * - 点缀：冰蓝 [ice] #49D9F5 只做少量荧光（光流末端、实时状态、主卡描边）；香槟金 [accent] #F2CB70 克制地表示「留意」，
 *   色块配深色字 [onWarning]，白天作文字时用深一档 [warning] #8A6200
 * - 数据图形（圆环、柱、进度条）用光流渐变 [flowStart] → [flowEnd]（电光蓝 → 冰蓝），两套主题都带柔光
 * - 正文、辅助、三级文字在两套主题下都 ≥ 4.5:1；分隔线与描边两套都可见
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
    val surfaceVariant: Color,
    val cameraSurface: Color,
    val primary: Color,
    val primaryDark: Color,
    val primarySoft: Color,
    /** 主色填充面上的文字/图标色。 */
    val onPrimary: Color,
    /** 品牌渐变起止色（圆环、进度、图表当前项）：起点浅、终点为主色系。 */
    val brandStart: Color,
    val brandEnd: Color,
    /** 柠檬黄点缀色：表示「留意」，用于等级徽标底色、提示点与插图高光（不直接作浅色底上的文字）。 */
    val accent: Color,
    val accentSoft: Color,
    /** 插图墨色：示意图里的线稿与人物五官，深浅主题都保持深色。 */
    val ink: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    /** 第三级文字：单位、坐标轴、占位符。 */
    val textTertiary: Color,
    val textInverse: Color,
    val borderSubtle: Color,
    /** 普通卡片的极淡描边（深色下让卡片从底色里分出来）。 */
    val cardBorder: Color,
    /** 主卡（每页最重要的一张）：渐变底 + 描边 + 投影色。 */
    val heroStart: Color,
    val heroEnd: Color,
    val heroBorder: Color,
    val shadow: Color,
    /** 主按钮底（深色为渐变，浅色起止同色）。 */
    val buttonStart: Color,
    val buttonEnd: Color,
    /** 光晕色：圆环、主按钮与图表当前项的泛光（深色电光蓝，浅色半透明青蓝柔光）。 */
    val glow: Color,
    /** 光流渐变（进度圆环、柱状图、进度条）：从主蓝流向青蓝，只用于数据图形，不作底色或文字色。 */
    val flowStart: Color,
    val flowEnd: Color,
    /** 冰蓝荧光点缀（实时状态点、光流末端、主卡描边）：少量使用，不作大面积底色；浅色下不作文字。 */
    val ice: Color,
    /** 图标配色（分类图标块）：冰蓝、薰衣草紫两组，底色为浅 / 深的同色调，图标色对底 ≥ 3:1。 */
    val iceInk: Color,
    val iceSoft: Color,
    val violet: Color,
    val violetSoft: Color,
    /** 玻璃面（卡片、底栏）：半透明填充 + 上亮下淡的描边，透出背后的渐变光。 */
    val glassFill: Color,
    val glassEdgeTop: Color,
    val glassEdgeBottom: Color,
    val success: Color,
    val successSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val neutralSoft: Color,
    val overlayScrim: Color,
    /** 测距反馈：太近用暖红、太远用冷蓝（色盲友好的红蓝对比，界面上始终配合箭头与文字）。 */
    val tooClose: Color,
    val tooFar: Color,
    /** 状态色块（太近/太远/合适）上的文字与图标色。 */
    val onStatus: Color,
    /** 柠檬黄「留意」色块上的文字色（深色字，白字在黄底上看不清）。 */
    val onWarning: Color,
) {
    /** 品牌线性渐变（左上 → 右下由调用方决定方向时可用 [brandColors]）。 */
    val brandBrush: Brush get() = Brush.linearGradient(brandColors)
    val brandColors: List<Color> get() = listOf(brandStart, brandEnd)
    val buttonBrush: Brush get() = Brush.linearGradient(listOf(buttonStart, buttonEnd))
    val heroBrush: Brush get() = Brush.linearGradient(listOf(heroStart, heroEnd))
    /** 横向光流渐变（进度条）。 */
    val flowBrush: Brush get() = Brush.horizontalGradient(listOf(flowStart, flowEnd))
    /**
     * 实心状态色块的底色与字色：「留意」（[warning]）的色块用柠檬黄 [accent] 配深色字 [onWarning]，其余语义色配 [onStatus]。
     */
    fun statusFill(color: Color): Pair<Color, Color> = if (color == warning) accent to onWarning else color to onStatus
    /** 是否画光晕（只有深色主题发光）。 */
    val hasGlow: Boolean get() = glow.alpha > 0f
}

/** 浅色「雾白天蓝」：浅灰蓝底 + 略亮的卡片柔和浮起（拟物投影 + 白色上沿），主色天蓝，数据图形蓝 → 青光流并带柔光。 */
val LightEyeColors = EyeColors(
    isDark = false,
    appBackground = Color(0xFFF2F5FA),
    panelBackground = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9EEF7),
    cameraSurface = Color(0xFF05070C),
    primary = Color(0xFF2563EB),
    primaryDark = Color(0xFF1D4ED8),
    primarySoft = Color(0xFFE6EEFE),
    onPrimary = Color(0xFFFFFFFF),
    brandStart = Color(0xFF9CC0FF),
    brandEnd = Color(0xFF2563EB),
    accent = Color(0xFFF2CB70),
    accentSoft = Color(0xFFFBF3DE),
    ink = Color(0xFF0F1B33),
    textPrimary = Color(0xFF0F1B33),
    textSecondary = Color(0xFF44506A),
    textTertiary = Color(0xFF5F6B85),
    textInverse = Color(0xFFFFFFFF),
    borderSubtle = Color(0xFFDCE3EE),
    cardBorder = Color(0x1F0F1B33),
    heroStart = Color(0xFFFFFFFF),
    heroEnd = Color(0xFFF4F7FD),
    heroBorder = Color(0xFFFFFFFF),
    shadow = Color(0x2E1B2B55),
    buttonStart = Color(0xFF2563EB),
    buttonEnd = Color(0xFF3578FF),
    glow = Color(0x5549D9F5),
    flowStart = Color(0xFF3578FF),
    flowEnd = Color(0xFF36C5E8),
    ice = Color(0xFF36C5E8),
    iceInk = Color(0xFF0E8FB0),
    iceSoft = Color(0xFFE3F6FB),
    violet = Color(0xFF6D5BD0),
    violetSoft = Color(0xFFEEEBFD),
    glassFill = Color(0xF5FFFFFF),
    glassEdgeTop = Color(0xFFFFFFFF),
    glassEdgeBottom = Color(0x1F0F1B33),
    success = Color(0xFF15803D),
    successSoft = Color(0xFFE3F5EA),
    warning = Color(0xFF8A6200),
    warningSoft = Color(0xFFFBF3DE),
    danger = Color(0xFFD92D3A),
    dangerSoft = Color(0xFFFDE8EA),
    neutralSoft = Color(0xFFE9EEF7),
    overlayScrim = Color(0x660A0F1F),
    tooClose = Color(0xFFD23A3A),
    tooFar = Color(0xFF2563EB),
    onStatus = Color(0xFFFFFFFF),
    onWarning = Color(0xFF2A1F00),
    white = Color.White,
    black = Color.Black,
    gray = Color(0xFF5F6B85),
    blue = Color(0xFF2563EB)
)

/** 深色「深海蓝」：主色提亮为电光蓝，主卡深蓝渐变，光晕只给圆环、主按钮与当前项。 */
val DarkEyeColors = EyeColors(
    isDark = true,
    appBackground = Color(0xFF081426),
    panelBackground = Color(0xFF132644),
    surfaceVariant = Color(0xFF1B3157),
    cameraSurface = Color(0xFF040A14),
    primary = Color(0xFF6E9BFF),
    primaryDark = Color(0xFF9DBBFF),
    primarySoft = Color(0xFF1C3566),
    onPrimary = Color(0xFFFFFFFF),
    brandStart = Color(0xFF9DC4FF),
    brandEnd = Color(0xFF3578FF),
    accent = Color(0xFFF2CB70),
    accentSoft = Color(0xFF2E2814),
    ink = Color(0xFF0B1324),
    textPrimary = Color(0xFFF2F6FF),
    textSecondary = Color(0xFFAFBCD8),
    textTertiary = Color(0xFF8A9AC0),
    textInverse = Color(0xFFFFFFFF),
    borderSubtle = Color(0xFF243C66),
    cardBorder = Color(0x14FFFFFF),
    heroStart = Color(0xFF1C3868),
    heroEnd = Color(0xFF132644),
    heroBorder = Color(0x3349D9F5),
    shadow = Color(0xCC000000),
    buttonStart = Color(0xFF2A66F0),
    buttonEnd = Color(0xFF3578FF),
    glow = Color(0xFF3578FF),
    flowStart = Color(0xFF3578FF),
    flowEnd = Color(0xFF49D9F5),
    ice = Color(0xFF49D9F5),
    iceInk = Color(0xFF49D9F5),
    iceSoft = Color(0xFF12324A),
    violet = Color(0xFFA99BFF),
    violetSoft = Color(0xFF29285A),
    glassFill = Color(0xF2132644),
    glassEdgeTop = Color(0x2EFFFFFF),
    glassEdgeBottom = Color(0x0FFFFFFF),
    success = Color(0xFF5EE3A1),
    successSoft = Color(0xFF10302A),
    warning = Color(0xFFF2CB70),
    warningSoft = Color(0xFF2E2814),
    danger = Color(0xFFFF7A86),
    dangerSoft = Color(0xFF3A1A24),
    neutralSoft = Color(0xFF1B3157),
    overlayScrim = Color(0x99000000),
    tooClose = Color(0xFFFF7A6E),
    tooFar = Color(0xFF6E9BFF),
    onStatus = Color(0xFF081426),
    onWarning = Color(0xFF1B1608),
    white = Color.White,
    black = Color.Black,
    gray = Color(0xFF8A9AC0),
    blue = Color(0xFF6E9BFF)
)

val LocalEyeColors = staticCompositionLocalOf { LightEyeColors }
