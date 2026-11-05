package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** 圆角令牌：主卡最圆（[hero]），普通卡片 [panel]，按钮与输入 [medium]，胶囊 [chip]。 */
object EyeShapes {
    val small = RoundedCornerShape(10.dp)
    val medium = RoundedCornerShape(14.dp)
    val large = RoundedCornerShape(16.dp)
    val card = RoundedCornerShape(18.dp)
    val panel = RoundedCornerShape(22.dp)
    val hero = RoundedCornerShape(26.dp)
    val chip = RoundedCornerShape(50)
}
