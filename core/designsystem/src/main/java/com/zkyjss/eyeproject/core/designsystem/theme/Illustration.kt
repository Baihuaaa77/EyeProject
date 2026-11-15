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
    /** 领口与袖口滚边 */
    val shirtLight: Color = Color(0xFF7599D1),
    /** 袖子的描边：手臂叠在身体上时把两者分开 */
    val shirtShade: Color = Color(0xFF4A72B3),
)

private val LightIllustration = IllustrationColors()
private val DarkIllustration = IllustrationColors(shirt = Color(0xFF648BC8), shirtLight = Color(0xFF86A6DA), shirtShade = Color(0xFF5479B4))
val EyeColors.illustration: IllustrationColors get() = if (isDark) DarkIllustration else LightIllustration

/** 测试到位提示使用品牌主蓝，距离过远使用黄色；浅色主题的黄色文字加深以保证可读。 */
val EyeColors.testReady: Color get() = primary
val EyeColors.testReadySoft: Color get() = primarySoft
val EyeColors.testTooFarText: Color get() = if (isDark) tooFar else Color(0xFF8A6500)

/** 太近状态的图形色；正文由文字原语映射为中性墨色。 */
val EyeColors.testTooClose: Color get() = danger
