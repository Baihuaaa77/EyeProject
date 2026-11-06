package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** 一块高饱和色块：自左上到右下由 [top] 渐变到 [bottom]，上面的文字与图标一律用 [VividColors.onVivid]（白）。 */
@Immutable
data class VividTone(val top: Color, val bottom: Color) {
    val brush: Brush get() = Brush.linearGradient(listOf(top, bottom))
}

/**
 * 首页功能色块（护眼操薄荷绿、用眼趋势紫、亮度与蓝光珊瑚红、AI 主动问诊蓝）：用整块颜色做视觉刺激，文字保持白色。
 * 深色主题整体压暗一档，避免在深海蓝底上发荧光。苹果端 EyeStyle.Vivid 同值。
 */
@Immutable
data class VividColors(
    val blue: VividTone,
    val red: VividTone,
    val violet: VividTone,
    val mint: VividTone,
    val onVivid: Color = Color.White,
)

private val LightVivid = VividColors(
    blue = VividTone(Color(0xFF4F8DFF), Color(0xFF2B5FEA)),
    red = VividTone(Color(0xFFFF7D6B), Color(0xFFEC4A5C)),
    violet = VividTone(Color(0xFF9D7CFF), Color(0xFF6B47E6)),
    mint = VividTone(Color(0xFF4FE0DF), Color(0xFF22BCC6)),
)
private val DarkVivid = VividColors(
    blue = VividTone(Color(0xFF3F7DF5), Color(0xFF2550D2)),
    red = VividTone(Color(0xFFF0685C), Color(0xFFD33E52)),
    violet = VividTone(Color(0xFF8C6DF2), Color(0xFF5C3ED2)),
    mint = VividTone(Color(0xFF33C8CC), Color(0xFF169FAA)),
)
val EyeColors.vivid: VividColors get() = if (isDark) DarkVivid else LightVivid
