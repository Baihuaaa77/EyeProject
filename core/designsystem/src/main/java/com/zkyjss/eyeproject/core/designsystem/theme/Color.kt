package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 自定义颜色令牌，完全映射自旧项目的 colors.xml（亮/暗双主题），
 * 不使用 Material 默认配色。
 */
@Immutable
data class EyeColors(
    val white: Color,
    val black: Color,
    val gray: Color,
    val appBackground: Color,
    val panelBackground: Color,
    val surfaceVariant: Color,
    val cameraSurface: Color,
    val primary: Color,
    val primaryDark: Color,
    val primarySoft: Color,
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
    val overlayScrim: Color
)

val LightEyeColors = EyeColors(
    appBackground = Color(0xFFF5F6F1),
    panelBackground = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEF1E8),
    cameraSurface = Color(0xFF0E1111),
    primary = Color(0xFFFFD21E),
    primaryDark = Color(0xFFD9A900),
    primarySoft = Color(0xFFFFF4B8),
    textPrimary = Color(0xFF111512),
    textSecondary = Color(0xFF5F685F),
    textInverse = Color(0xFFFFFFFF),
    borderSubtle = Color(0xFFDDE3D6),
    success = Color(0xFF2E7D32),
    successSoft = Color(0xFFE4F7DD),
    warning = Color(0xFFC47B00),
    warningSoft = Color(0xFFFFF0C7),
    danger = Color(0xFFE53935),
    dangerSoft = Color(0xFFFFE2E0),
    neutralSoft = Color(0xFFF0F2EA),
    overlayScrim = Color(0x66000000),
    white = Color.White,
    black = Color.Black,
    gray = Color.Gray
)

val DarkEyeColors = EyeColors(
    appBackground = Color(0xFF101820),
    panelBackground = Color(0xFF18242D),
    surfaceVariant = Color(0xFF22313C),
    cameraSurface = Color(0xFF070B10),
    primary = Color(0xFFFFD54A),
    primaryDark = Color(0xFFFFE082),
    primarySoft = Color(0xFF3E3210),
    textPrimary = Color(0xFFEAF2F6),
    textSecondary = Color(0xFFA7B6C0),
    textInverse = Color(0xFFFFFFFF),
    borderSubtle = Color(0xFF2D424F),
    success = Color(0xFF63D68B),
    successSoft = Color(0xFF163826),
    warning = Color(0xFFF3C15F),
    warningSoft = Color(0xFF3D3015),
    danger = Color(0xFFF3847E),
    dangerSoft = Color(0xFF421D1C),
    neutralSoft = Color(0xFF24333D),
    overlayScrim = Color(0x80000000),
    white = Color.White,
    black = Color.Black,
    gray = Color.Gray
)

val LocalEyeColors = staticCompositionLocalOf { LightEyeColors }
