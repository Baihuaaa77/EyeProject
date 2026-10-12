package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 品牌「青瞳」色彩体系（亮/暗双主题），不使用 Material 默认配色。
 *
 * - 主色 湖青：绿色系对眼睛最友好，兼顾医疗的冷静感
 * - 品牌渐变 湖青 → 靛蓝：Logo、仪表盘弧、强调面使用
 * - 点缀 晨光琥珀：只用于高光/徽标点缀，不用作正文文字
 *
 * 对比度：主色按钮文字（[onPrimary]）与主色 ≥ 4.5:1；主色作文字放在面板/浅主色上 ≥ 4.5:1。
 */
@Immutable
data class EyeColors(
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
    /** 品牌渐变起止色。 */
    val brandStart: Color,
    val brandEnd: Color,
    /** 晨光琥珀点缀色（高光、吉祥物腮红、徽标点）。 */
    val accent: Color,
    val accentSoft: Color,
    /** 插画墨色：吉祥物瞳孔、线稿，深浅主题都保持深色。 */
    val ink: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textInverse: Color,
    val borderSubtle: Color,
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
) {
    /** 品牌线性渐变（左上 → 右下由调用方决定方向时可用 [brandColors]）。 */
    val brandBrush: Brush get() = Brush.linearGradient(brandColors)
    val brandColors: List<Color> get() = listOf(brandStart, brandEnd)
}

val LightEyeColors = EyeColors(
    appBackground = Color(0xFFF3F6F5),
    panelBackground = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9EFEE),
    cameraSurface = Color(0xFF0B1211),
    primary = Color(0xFF0B7A78),
    primaryDark = Color(0xFF075E5C),
    primarySoft = Color(0xFFE3F4F2),
    onPrimary = Color(0xFFFFFFFF),
    brandStart = Color(0xFF12A39A),
    brandEnd = Color(0xFF2A5BD7),
    accent = Color(0xFFF2A33A),
    accentSoft = Color(0xFFFFF1DC),
    ink = Color(0xFF13201F),
    textPrimary = Color(0xFF13201F),
    textSecondary = Color(0xFF566664),
    textInverse = Color(0xFFFFFFFF),
    borderSubtle = Color(0xFFDCE4E2),
    success = Color(0xFF2E7D4F),
    successSoft = Color(0xFFE6F4EC),
    warning = Color(0xFF986000),
    warningSoft = Color(0xFFFFF3DD),
    danger = Color(0xFFC93430),
    dangerSoft = Color(0xFFFFE4E1),
    neutralSoft = Color(0xFFE9EFEE),
    overlayScrim = Color(0x66000000),
    tooClose = Color(0xFFD7372C),
    tooFar = Color(0xFF2563EB),
    onStatus = Color(0xFFFFFFFF),
    white = Color.White,
    black = Color.Black,
    gray = Color(0xFF8A9896),
    blue = Color(0xFF2A5BD7)
)

// 深色模式主色提亮为薄荷青，填充面文字改用深色（白字在亮青上对比度不足）
val DarkEyeColors = EyeColors(
    appBackground = Color(0xFF0B1211),
    panelBackground = Color(0xFF16201F),
    surfaceVariant = Color(0xFF213030),
    cameraSurface = Color(0xFF060A0A),
    primary = Color(0xFF4FD1C5),
    primaryDark = Color(0xFF7FE0D6),
    primarySoft = Color(0xFF123A37),
    onPrimary = Color(0xFF032B28),
    brandStart = Color(0xFF2CC3B5),
    brandEnd = Color(0xFF5B83F0),
    accent = Color(0xFFF6B85C),
    accentSoft = Color(0xFF3D2E15),
    ink = Color(0xFF0B1211),
    textPrimary = Color(0xFFECF3F2),
    textSecondary = Color(0xFFA3B3B1),
    textInverse = Color(0xFFFFFFFF),
    borderSubtle = Color(0xFF2E3D3C),
    success = Color(0xFF6BD69A),
    successSoft = Color(0xFF163826),
    warning = Color(0xFFF3C15F),
    warningSoft = Color(0xFF3D3015),
    danger = Color(0xFFF3847E),
    dangerSoft = Color(0xFF421D1C),
    neutralSoft = Color(0xFF213030),
    overlayScrim = Color(0x80000000),
    tooClose = Color(0xFFFF7A6E),
    tooFar = Color(0xFF7AA7FF),
    onStatus = Color(0xFF0B1211),
    white = Color.White,
    black = Color.Black,
    gray = Color(0xFF7E8E8C),
    blue = Color(0xFF5B83F0)
)

val LocalEyeColors = staticCompositionLocalOf { LightEyeColors }
