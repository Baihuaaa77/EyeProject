package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** DESIGN.md：同色系柔和浮雕表面，蓝 → 青 → 水绿的数据渐变，亮暗双主题。 */
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
    val canvasBackground: Color get() = if (isDark) Color(0xFF373B40) else Color(0xFFE3E8F3)
    val textHeading: Color get() = if (isDark) Color(0xFFF2F3F5) else Color(0xFF141A2B)
    val shadowLight: Color get() = Color.White.copy(alpha = if (isDark) 0.045f else 0.95f)
    val dataCyan: Color get() = Color(0xFF18C2FF)
    val dataAqua: Color get() = Color(0xFF00EFE0)
    val ringTrack: Color get() = if (isDark) Color(0xFF344458) else Color(0xFFD1DDEE)
    val borderColors: List<Color> get() = listOf(brandStart, dataCyan, dataAqua)
    val borderBrush: Brush get() = Brush.linearGradient(borderColors)
    val dataMint: Color get() = Color(0xFF2CF2C8)
    val chatUserBackground: Color get() = primaryDark
    val chatUserText: Color get() = Color.White
    val moodGold: Color get() = Color(0xFFFFCE65)
    val moodWarm: Color get() = Color(0xFFE8BE7C)
    val moodSilver: Color get() = Color(0xFFCAD0DA)
    val dataBrush: Brush get() = Brush.verticalGradient(listOf(brandStart, dataCyan, dataAqua))

    /** 数据色只画图形；正文和操作文字使用中性墨色或高对比品牌蓝。 */
    fun readableText(color: Color): Color = when (color) {
        primary, screen, blue, violet, tileIndigo, tileTeal -> textPrimary
        ice, dataCyan, dataAqua, dataMint, sun, accent, warning, danger, success, tooClose -> textPrimary
        else -> color
    }
    val brandBrush: Brush get() = Brush.linearGradient(brandColors)
    val brandColors: List<Color> get() = listOf(brandStart, brandEnd)
    val buttonBrush: Brush get() = Brush.linearGradient(listOf(buttonStart, buttonEnd))
    val heroBrush: Brush get() = Brush.linearGradient(listOf(heroStart, heroEnd))
    val flowBrush: Brush get() = Brush.horizontalGradient(listOf(flowStart, flowEnd))
    fun statusFill(color: Color): Pair<Color, Color> = if (color == warning) accent to onWarning else color to onStatus
    val hasGlow: Boolean get() = glow.alpha > 0f
}

val LightEyeColors = EyeColors(
    isDark = false,
    appBackground = Color(0xFFF5F6F8),
    panelBackground = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9EDF3),
    cameraSurface = Color(0xFF1B1D20),
    primary = Color(0xFF2F7BFF),
    primaryDark = Color(0xFF0A6CFF),
    primarySoft = Color(0xFFE9EDF3),
    onPrimary = Color(0xFF313949),
    brandStart = Color(0xFF2F7BFF),
    brandEnd = Color(0xFF00EFE0),
    accent = Color(0xFFFFB020),
    accentSoft = Color(0xFFE8EDF7),
    ink = Color(0xFF141A2B),
    textPrimary = Color(0xFF313949),
    textSecondary = Color(0xFF5D6280),
    textTertiary = Color(0xFF878EA3),
    textInverse = Color(0xFFF4F7FD),
    borderSubtle = Color(0x1F5D6280),
    cardBorder = Color(0x00000000),
    heroStart = Color(0xFFFFFFFF),
    heroEnd = Color(0xFFFFFFFF),
    heroBorder = Color(0x00000000),
    shadow = Color(0x73A3B1CD),
    buttonStart = Color(0xFFEAF2FF),
    buttonEnd = Color(0xFFEAF2FF),
    glow = Color(0x8C00EFE0),
    flowStart = Color(0xFF2F7BFF),
    flowEnd = Color(0xFF00EFE0),
    ice = Color(0xFF18C2FF),
    iceInk = Color(0xFF5D6280),
    iceSoft = Color(0xFFE8EDF7),
    violet = Color(0xFF8B78E6),
    violetSoft = Color(0xFFE8EDF7),
    screen = Color(0xFF2F7BFF),
    screenSoft = Color(0xFFE8EDF7),
    sun = Color(0xFFFFB020),
    sunSoft = Color(0xFFE8EDF7),
    glassFill = Color(0xFFFFFFFF),
    glassEdgeTop = Color(0x00000000),
    glassEdgeBottom = Color(0x00000000),
    tabBarFill = Color(0xFFFFFFFF),
    tabBarEdge = Color(0x00000000),
    tileBlue = Color(0xFF2F7BFF),
    tileIndigo = Color(0xFF8B78E6),
    tileTeal = Color(0xFF12B886),
    tileOrange = Color(0xFFFFB020),
    tileGreen = Color(0xFF12B886),
    tileRed = Color(0xFFFF5A6E),
    tileGray = Color(0xFF878EA3),
    success = Color(0xFF12B886),
    successSoft = Color(0xFFE8EDF7),
    warning = Color(0xFFFFB020),
    warningSoft = Color(0xFFE8EDF7),
    danger = Color(0xFFFF5A6E),
    dangerSoft = Color(0xFFE8EDF7),
    neutralSoft = Color(0xFFE8EDF7),
    overlayScrim = Color(0x99141A2B),
    tooClose = Color(0xFFFF5A6E),
    tooFar = Color(0xFF2F7BFF),
    onStatus = Color(0xFF141A2B),
    onWarning = Color(0xFF141A2B),
    gray = Color(0xFF878EA3),
    blue = Color(0xFF2F7BFF),
    white = Color.White,
    black = Color.Black,
)

val DarkEyeColors = EyeColors(
    isDark = true,
    appBackground = Color(0xFF1E2023),
    panelBackground = Color(0xFF26282B),
    surfaceVariant = Color(0xFF1B1D20),
    cameraSurface = Color(0xFF1B1D20),
    primary = Color(0xFF2F7BFF),
    primaryDark = Color(0xFF0A6CFF),
    primarySoft = Color(0xFF1B1D20),
    onPrimary = Color(0xFFE3E5E8),
    brandStart = Color(0xFF2F7BFF),
    brandEnd = Color(0xFF00EFE0),
    accent = Color(0xFFFFB020),
    accentSoft = Color(0xFF1B1D20),
    ink = Color(0xFF141A2B),
    textPrimary = Color(0xFFE3E5E8),
    textSecondary = Color(0xFF9A9DA3),
    textTertiary = Color(0xFF73767C),
    textInverse = Color(0xFFF2F3F5),
    borderSubtle = Color(0x12FFFFFF),
    cardBorder = Color(0x09FFFFFF),
    heroStart = Color(0xFF26282B),
    heroEnd = Color(0xFF26282B),
    heroBorder = Color(0x09FFFFFF),
    shadow = Color(0x8C000000),
    buttonStart = Color(0xFF303844),
    buttonEnd = Color(0xFF303844),
    glow = Color(0xB300EFE0),
    flowStart = Color(0xFF2F7BFF),
    flowEnd = Color(0xFF00EFE0),
    ice = Color(0xFF18C2FF),
    iceInk = Color(0xFF9A9DA3),
    iceSoft = Color(0xFF1B1D20),
    violet = Color(0xFF8B78E6),
    violetSoft = Color(0xFF1B1D20),
    screen = Color(0xFF2F7BFF),
    screenSoft = Color(0xFF1B1D20),
    sun = Color(0xFFFFB020),
    sunSoft = Color(0xFF1B1D20),
    glassFill = Color(0xFF26282B),
    glassEdgeTop = Color(0x09FFFFFF),
    glassEdgeBottom = Color(0x00000000),
    tabBarFill = Color(0xFF26282B),
    tabBarEdge = Color(0x09FFFFFF),
    tileBlue = Color(0xFF2F7BFF),
    tileIndigo = Color(0xFF8B78E6),
    tileTeal = Color(0xFF12B886),
    tileOrange = Color(0xFFFFB020),
    tileGreen = Color(0xFF12B886),
    tileRed = Color(0xFFFF5A6E),
    tileGray = Color(0xFF878EA3),
    success = Color(0xFF12B886),
    successSoft = Color(0xFF1B1D20),
    warning = Color(0xFFFFB020),
    warningSoft = Color(0xFF1B1D20),
    danger = Color(0xFFFF5A6E),
    dangerSoft = Color(0xFF1B1D20),
    neutralSoft = Color(0xFF1B1D20),
    overlayScrim = Color(0x99141A2B),
    tooClose = Color(0xFFFF5A6E),
    tooFar = Color(0xFF2F7BFF),
    onStatus = Color(0xFFF2F3F5),
    onWarning = Color(0xFFF2F3F5),
    gray = Color(0xFF73767C),
    blue = Color(0xFF2F7BFF),
    white = Color.White,
    black = Color.Black,
)

val LocalEyeColors = staticCompositionLocalOf { LightEyeColors }
