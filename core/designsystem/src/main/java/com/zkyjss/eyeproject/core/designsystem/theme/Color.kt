package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 色彩体系以「深海蓝」为标准（2026-11 视觉改版），深浅两套同一蓝色主调，不使用 Material 默认配色。
 *
 * - 深色「深海蓝」：近黑蓝底，主卡深蓝渐变 + 淡蓝描边浮起；发光只用在圆环、主按钮和图表的当前项上
 * - 浅色「瓷白深蓝」：冷白底、白卡，主色藏青 #23367A，卡片带描边与柔和投影；不发光
 * - 两套主色同为蓝系，跟随系统深浅色切换，是同一个产品的白天与夜里
 * - 卡片是「玻璃」：半透明面 + 上沿高光描边（[glassFill] / [glassEdgeTop]），透出背后的渐变光
 * - 页面为纯色底，不加渐变
 * - 数据图形（圆环、柱、进度条）用光流渐变 [flowStart] → [flowEnd]（蓝 → 青蓝，深色带泛光）
 * - 颜色只用蓝色主调；柠檬黄 [accent] 只表示「留意」（色块配深色字；浅色下作文字时用深一档的 [warning]）（等级徽标、提示点），语义色只用在状态标签上
 *
 * 对比度：主按钮文字与按钮底 ≥ 4.5:1（深色渐变亮端除外，按钮字号属大字）；正文文字与面板 ≥ 4.5:1。
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
    /** 光晕色：深色主题下圆环、主按钮与图表当前项的泛光；浅色为透明（不发光）。 */
    val glow: Color,
    /** 光流渐变（进度圆环、柱状图、进度条）：从主蓝流向青蓝，只用于数据图形，不作底色或文字色。 */
    val flowStart: Color,
    val flowEnd: Color,
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

/** 浅色「瓷白深蓝」：深海蓝的白天版，主色藏青 #23367A，白卡 + 极淡藏青渐变光，不发光。 */
val LightEyeColors = EyeColors(
    isDark = false,
    appBackground = Color(0xFFF3F4F7),
    panelBackground = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFECEEF4),
    cameraSurface = Color(0xFF05070C),
    primary = Color(0xFF23367A),
    primaryDark = Color(0xFF1A2860),
    primarySoft = Color(0xFFE6EAF5),
    onPrimary = Color(0xFFFFFFFF),
    brandStart = Color(0xFFA9B8EE),
    brandEnd = Color(0xFF23367A),
    accent = Color(0xFFF5D90A),
    accentSoft = Color(0xFFFFF7CC),
    ink = Color(0xFF141A33),
    textPrimary = Color(0xFF141A33),
    textSecondary = Color(0xFF464C66),
    textTertiary = Color(0xFF7D8298),
    textInverse = Color(0xFFFFFFFF),
    borderSubtle = Color(0xFFDFE2EA),
    cardBorder = Color(0x24141E50),
    heroStart = Color(0xFFFFFFFF),
    heroEnd = Color(0xFFFFFFFF),
    heroBorder = Color(0x1A141E50),
    shadow = Color(0x3319235A),
    buttonStart = Color(0xFF23367A),
    buttonEnd = Color(0xFF2F4795),
    glow = Color.Transparent,
    flowStart = Color(0xFF23367A),
    flowEnd = Color(0xFF4A9BE0),
    glassFill = Color(0xE6FFFFFF),
    glassEdgeTop = Color(0xF2FFFFFF),
    glassEdgeBottom = Color(0x29141E50),
    success = Color(0xFF17804D),
    successSoft = Color(0xFFE3F3EA),
    warning = Color(0xFF857400),
    warningSoft = Color(0xFFFFF7CC),
    danger = Color(0xFFC9363F),
    dangerSoft = Color(0xFFFBE9EA),
    neutralSoft = Color(0xFFECEEF4),
    overlayScrim = Color(0x660A0F1F),
    tooClose = Color(0xFFD23A3A),
    tooFar = Color(0xFF2F5FD0),
    onStatus = Color(0xFFFFFFFF),
    onWarning = Color(0xFF2A2400),
    white = Color.White,
    black = Color.Black,
    gray = Color(0xFF7D8298),
    blue = Color(0xFF23367A)
)

/** 深色「深海蓝」：主色提亮为电光蓝，主卡深蓝渐变，光晕只给圆环、主按钮与当前项。 */
val DarkEyeColors = EyeColors(
    isDark = true,
    appBackground = Color(0xFF050912),
    panelBackground = Color(0xFF0C1220),
    surfaceVariant = Color(0xFF121B2E),
    cameraSurface = Color(0xFF02040A),
    primary = Color(0xFF4A7DFF),
    primaryDark = Color(0xFF7AA4FF),
    primarySoft = Color(0xFF13214A),
    onPrimary = Color(0xFFFFFFFF),
    brandStart = Color(0xFFB4D0FF),
    brandEnd = Color(0xFF2E6BFF),
    accent = Color(0xFFFFE45C),
    accentSoft = Color(0xFF2B2710),
    ink = Color(0xFF0B1020),
    textPrimary = Color(0xFFF2F5FF),
    textSecondary = Color(0xFFB4BCD2),
    textTertiary = Color(0xFF7A84A0),
    textInverse = Color(0xFFFFFFFF),
    borderSubtle = Color(0xFF1E2840),
    cardBorder = Color(0x1AFFFFFF),
    heroStart = Color(0xFF18284F),
    heroEnd = Color(0xFF0C1428),
    heroBorder = Color(0x3382A5FF),
    shadow = Color(0xCC000000),
    buttonStart = Color(0xFF2556F5),
    buttonEnd = Color(0xFF5B95FF),
    glow = Color(0xFF3C78FF),
    flowStart = Color(0xFF2E6BFF),
    flowEnd = Color(0xFF3DDCE8),
    glassFill = Color(0xA60C1220),
    glassEdgeTop = Color(0x40FFFFFF),
    glassEdgeBottom = Color(0x14FFFFFF),
    success = Color(0xFF5EE3A1),
    successSoft = Color(0xFF0F2A22),
    warning = Color(0xFFFFE45C),
    warningSoft = Color(0xFF2B2710),
    danger = Color(0xFFFF7A86),
    dangerSoft = Color(0xFF2D1219),
    neutralSoft = Color(0xFF141C2E),
    overlayScrim = Color(0x99000000),
    tooClose = Color(0xFFFF7A6E),
    tooFar = Color(0xFF7AA4FF),
    onStatus = Color(0xFF050912),
    onWarning = Color(0xFF1A1600),
    white = Color.White,
    black = Color.Black,
    gray = Color(0xFF6C7590),
    blue = Color(0xFF6E9BFF)
)

val LocalEyeColors = staticCompositionLocalOf { LightEyeColors }
