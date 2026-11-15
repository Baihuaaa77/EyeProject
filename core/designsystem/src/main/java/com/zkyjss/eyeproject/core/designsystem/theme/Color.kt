package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** DESIGN.md：棕色深色界面，炭黑背景、暖棕表面与杏桃强调色；旧字段名仅作 API 兼容。 */
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
    /** 方法演示固定浅灰底，不随深浅主题变化。 */
    val guideBackground: Color = Color(0xFFE9EDF3),
    val cameraSurface: Color,
    val primary: Color,
    val primaryDark: Color,
    val primarySoft: Color,
    val onPrimary: Color,
    val brandStart: Color,
    val brandEnd: Color,
    val accent: Color,
    val accentSoft: Color,
    val ink: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textInverse: Color,
    val borderSubtle: Color,
    val cardBorder: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val heroBorder: Color,
    val shadow: Color,
    val buttonStart: Color,
    val buttonEnd: Color,
    val glow: Color,
    val flowStart: Color,
    val flowEnd: Color,
    val ice: Color,
    val iceInk: Color,
    val iceSoft: Color,
    val violet: Color,
    val violetSoft: Color,
    val screen: Color,
    val screenSoft: Color,
    val sun: Color,
    val sunSoft: Color,
    val glassFill: Color,
    val glassEdgeTop: Color,
    val glassEdgeBottom: Color,
    val tabBarFill: Color,
    val tabBarEdge: Color,
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
    val tooClose: Color,
    val tooFar: Color,
    val onStatus: Color,
    val onWarning: Color,
) {
    /** 字母视标圆圈外的底色；白天保持白底，深色继承所在表面的底色。 */
    val optotypeSurround: Color get() = if (isDark) Color.Transparent else white
    val canvasBackground: Color get() = appBackground
    val textHeading: Color get() = textPrimary
    val shadowLight: Color get() = Color.Transparent
    val dataCyan: Color get() = Color(0xFFF6A267)
    val dataAqua: Color get() = Color(0xFFED9A78)
    val ringTrack: Color get() = Color(0xFF52524A)
    val borderColors: List<Color> get() = listOf(brandStart, dataCyan, violet)
    val borderBrush: Brush get() = Brush.linearGradient(borderColors)
    val dataMint: Color get() = Color(0xFFB6C6B3)
    val chatUserBackground: Color get() = primaryDark
    val chatUserText: Color get() = Color.White
    val moodGold: Color get() = Color(0xFFFFCE65)
    val moodWarm: Color get() = Color(0xFFE8BE7C)
    val moodSilver: Color get() = Color(0xFFCAD0DA)
    val dataBrush: Brush get() = Brush.verticalGradient(listOf(brandStart, dataCyan, dataAqua))

    /** 数据色只画图形；正文和操作文字使用中性墨色或高对比品牌蓝。 */
    fun readableText(color: Color): Color = color
    val brandBrush: Brush get() = Brush.linearGradient(brandColors)
    val brandColors: List<Color> get() = listOf(brandStart, brandEnd)
    val buttonBrush: Brush get() = Brush.linearGradient(listOf(buttonStart, buttonEnd))
    val heroBrush: Brush get() = Brush.linearGradient(listOf(heroStart, heroEnd))
    val flowBrush: Brush get() = Brush.horizontalGradient(listOf(flowStart, flowEnd))
    fun statusFill(color: Color): Pair<Color, Color> = if (color == warning) accent to onWarning else color to onStatus
    val hasGlow: Boolean get() = glow.alpha > 0f
}

val LightEyeColors = EyeColors(
    isDark = true,
    appBackground = Color(0xFF10110F),
    panelBackground = Color(0xFF22231E),
    surfaceVariant = Color(0xFF171814),
    cameraSurface = Color(0xFF10110F),
    primary = Color(0xFFF6A267),
    primaryDark = Color(0xFFF6A267),
    primarySoft = Color(0xFF453327),
    onPrimary = Color(0xFF2B2119),
    brandStart = Color(0xFFFFD2AE),
    brandEnd = Color(0xFFF6AB76),
    accent = Color(0xFFF6A267),
    accentSoft = Color(0xFF453327),
    ink = Color(0xFF14130F),
    textPrimary = Color(0xFFF3F0E9),
    textSecondary = Color(0xFFBDB7AC),
    textTertiary = Color(0xFFAAA599),
    textInverse = Color(0xFF2B2119),
    borderSubtle = Color(0xFF2E3029),
    cardBorder = Color(0xFF36382F),
    heroStart = Color(0xFF29241D),
    heroEnd = Color(0xFF191A16),
    heroBorder = Color(0xFF493B2C),
    shadow = Color(0x33000000),
    buttonStart = Color(0xFFFFD2AE),
    buttonEnd = Color(0xFFF6AB76),
    glow = Color(0xFFC9885D),
    flowStart = Color(0xFFB6C6B3),
    flowEnd = Color(0xFFED9A78),
    ice = Color(0xFFD8B494),
    iceInk = Color(0xFFD8B494),
    iceSoft = Color(0xFF33291F),
    violet = Color(0xFFE8A68B),
    violetSoft = Color(0xFF352821),
    screen = Color(0xFFF6A267),
    screenSoft = Color(0xFF33291F),
    sun = Color(0xFFEDB28B),
    sunSoft = Color(0xFF33291F),
    glassFill = Color(0xFF22231E),
    glassEdgeTop = Color(0xFF36382F),
    glassEdgeBottom = Color(0xFF2E3029),
    tabBarFill = Color(0xFF272824),
    tabBarEdge = Color(0xFF45473C),
    tileBlue = Color(0xFFEFB285),
    tileIndigo = Color(0xFFE8A68B),
    tileTeal = Color(0xFFEFB285),
    tileOrange = Color(0xFFEFB285),
    tileGreen = Color(0xFFD4B69A),
    tileRed = Color(0xFFD4B69A),
    tileGray = Color(0xFFB5A994),
    success = Color(0xFFB6C6B3),
    successSoft = Color(0xFF262924),
    warning = Color(0xFFF6A267),
    warningSoft = Color(0xFF453327),
    danger = Color(0xFFE99A86),
    dangerSoft = Color(0xFF422C29),
    neutralSoft = Color(0xFF272824),
    overlayScrim = Color(0xAD050604),
    tooClose = Color(0xFFE99A86),
    tooFar = Color(0xFFFFE19A),
    onStatus = Color(0xFF2B2119),
    onWarning = Color(0xFF2B2119),
    gray = Color(0xFFAAA599),
    blue = Color(0xFFF6A267),
    white = Color.White,
    black = Color.Black,
)


val DarkEyeColors = EyeColors(
    isDark = true,
    appBackground = Color(0xFF10110F),
    panelBackground = Color(0xFF22231E),
    surfaceVariant = Color(0xFF171814),
    cameraSurface = Color(0xFF10110F),
    primary = Color(0xFFF6A267),
    primaryDark = Color(0xFFF6A267),
    primarySoft = Color(0xFF453327),
    onPrimary = Color(0xFF2B2119),
    brandStart = Color(0xFFFFD2AE),
    brandEnd = Color(0xFFF6AB76),
    accent = Color(0xFFF6A267),
    accentSoft = Color(0xFF453327),
    ink = Color(0xFF14130F),
    textPrimary = Color(0xFFF3F0E9),
    textSecondary = Color(0xFFBDB7AC),
    textTertiary = Color(0xFFAAA599),
    textInverse = Color(0xFF2B2119),
    borderSubtle = Color(0xFF2E3029),
    cardBorder = Color(0xFF36382F),
    heroStart = Color(0xFF29241D),
    heroEnd = Color(0xFF191A16),
    heroBorder = Color(0xFF493B2C),
    shadow = Color(0x33000000),
    buttonStart = Color(0xFFFFD2AE),
    buttonEnd = Color(0xFFF6AB76),
    glow = Color(0xFFC9885D),
    flowStart = Color(0xFFB6C6B3),
    flowEnd = Color(0xFFED9A78),
    ice = Color(0xFFD8B494),
    iceInk = Color(0xFFD8B494),
    iceSoft = Color(0xFF33291F),
    violet = Color(0xFFE8A68B),
    violetSoft = Color(0xFF352821),
    screen = Color(0xFFF6A267),
    screenSoft = Color(0xFF33291F),
    sun = Color(0xFFEDB28B),
    sunSoft = Color(0xFF33291F),
    glassFill = Color(0xFF22231E),
    glassEdgeTop = Color(0xFF36382F),
    glassEdgeBottom = Color(0xFF2E3029),
    tabBarFill = Color(0xFF272824),
    tabBarEdge = Color(0xFF45473C),
    tileBlue = Color(0xFFEFB285),
    tileIndigo = Color(0xFFE8A68B),
    tileTeal = Color(0xFFEFB285),
    tileOrange = Color(0xFFEFB285),
    tileGreen = Color(0xFFD4B69A),
    tileRed = Color(0xFFD4B69A),
    tileGray = Color(0xFFB5A994),
    success = Color(0xFFB6C6B3),
    successSoft = Color(0xFF262924),
    warning = Color(0xFFF6A267),
    warningSoft = Color(0xFF453327),
    danger = Color(0xFFE99A86),
    dangerSoft = Color(0xFF422C29),
    neutralSoft = Color(0xFF272824),
    overlayScrim = Color(0xAD050604),
    tooClose = Color(0xFFE99A86),
    tooFar = Color(0xFFFFE19A),
    onStatus = Color(0xFF2B2119),
    onWarning = Color(0xFF2B2119),
    gray = Color(0xFFAAA599),
    blue = Color(0xFFF6A267),
    white = Color.White,
    black = Color.Black,
)


val LocalEyeColors = staticCompositionLocalOf { DarkEyeColors }
