package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 演示插画专用色（测试方法演示里的浮雕人偶与它的手）：全部取自 DESIGN.md 的中性表面与墨色阶，
 * 人偶和卡片是同一种材质，饱和色只留给虹膜（品牌渐变）。苹果端 EyeStyle.Illustration 同值。
 */
@Immutable
data class IllustrationColors(
    /** 头与手：受光面 → 本色（径向渐变） */
    val clayLight: Color,
    val clay: Color,
    /** 脖子、耳内 */
    val clayShade: Color,
    /** 上衣与袖子：受光面 → 本色（线性渐变） */
    val garmentLight: Color,
    val garment: Color,
    val hairLight: Color,
    val hair: Color,
    /** 鼻子、手的描边与指缝 */
    val line: Color,
    /** 上眼睑、闭眼弧线、嘴 */
    val lineStrong: Color,
    /** 眼窝（凹槽），里面是发光的渐变虹膜 */
    val eyeWell: Color,
    /** 示意手机的机身 */
    val device: Color,
)

private val LightIllustration = IllustrationColors(
    clayLight = Color(0xFFFFFFFF), clay = Color(0xFFE8EDF7), clayShade = Color(0xFFD1DDEE),
    garmentLight = Color(0xFFE1EBFB), garment = Color(0xFFB4C6E6),
    hairLight = Color(0xFFAAB1BE), hair = Color(0xFF878EA3),
    line = Color(0xFFAAB1BE), lineStrong = Color(0xFF5D6280),
    eyeWell = Color(0xFFD1DDEE), device = Color(0xFF141A2B),
)
// 深色主题：人偶比舞台亮一档（中灰），头发与线条更深，层次关系与浅色主题一致
private val DarkIllustration = IllustrationColors(
    clayLight = Color(0xFF9A9DA3), clay = Color(0xFF73767C), clayShade = Color(0xFF56595E),
    garmentLight = Color(0xFF3F4F66), garment = Color(0xFF303844),
    hairLight = Color(0xFF373B40), hair = Color(0xFF26282B),
    line = Color(0xFF373B40), lineStrong = Color(0xFF1B1D20),
    eyeWell = Color(0xFF1B1D20), device = Color(0xFF373B40),
)
val EyeColors.illustration: IllustrationColors get() = if (isDark) DarkIllustration else LightIllustration

/** 测试到位提示：沿用 DESIGN.md 的语义图形色。 */
val EyeColors.testReady: Color get() = success
val EyeColors.testReadySoft: Color get() = successSoft

/** 太近状态的图形色；正文由文字原语映射为中性墨色。 */
val EyeColors.testTooClose: Color get() = danger
