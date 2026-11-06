package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 演示插画专用色（测试方法演示里的短发男孩与他的手）：肤色、头发不随主题反转，
 * 衣服在深色主题下略提亮，保证在深蓝舞台上仍读得出轮廓。苹果端 EyeStyle.Illustration 同值。
 */
@Immutable
data class IllustrationColors(
    val skin: Color = Color(0xFFFFCBA8),
    /** 脖子、耳内、领口阴影 */
    val skinShade: Color = Color(0xFFEBA884),
    /** 鼻子、手的轮廓与指缝 */
    val skinLine: Color = Color(0xFFC98463),
    val blush: Color = Color(0xFFF4A58A),
    val hair: Color = Color(0xFF3D4956),
    val mouth: Color = Color(0xFF8E4F42),
    val shirt: Color = Color(0xFF557FC1),
    /** 领口滚边 */
    val shirtLight: Color = Color(0xFF7599D1),
)

private val LightIllustration = IllustrationColors()
private val DarkIllustration = IllustrationColors(shirt = Color(0xFF648BC8), shirtLight = Color(0xFF86A6DA))
val EyeColors.illustration: IllustrationColors get() = if (isDark) DarkIllustration else LightIllustration

/** 测试到位提示：夜间用低饱和蓝青色，避免大片荧光绿；日间保留熟悉的绿色。 */
val EyeColors.testReady: Color get() = if (isDark) Color(0xFF91C8D3) else Color(0xFF137538)
val EyeColors.testReadySoft: Color get() = if (isDark) Color(0xFF1A3547) else successSoft

/** 柔和警示底上的文字色，浅色比实心警示块更深，确保正文对比度。 */
val EyeColors.testTooClose: Color get() = if (isDark) danger else Color(0xFFB42332)
