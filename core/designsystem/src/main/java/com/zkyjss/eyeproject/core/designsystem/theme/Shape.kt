package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** DESIGN.md 圆角：卡片 20、按钮 14、轨道与标签全圆。 */
object EyeShapes {
    val small = RoundedCornerShape(10.dp)
    val medium = RoundedCornerShape(14.dp)
    val large = RoundedCornerShape(14.dp)
    val card = RoundedCornerShape(26.dp)
    val panel = RoundedCornerShape(26.dp)
    val hero = RoundedCornerShape(26.dp)
    val chip = RoundedCornerShape(999.dp)
}

/** 作答控件在大字号时保留触达空间，尺寸均大于最小触摸范围。 */
object EyeControlSizes {
    val guideStageHeight = 200.dp
    val chatAvatar = 48.dp
    val chatIntroPortraitWidth = 112.dp
    val chatIntroPortraitHeight = 144.dp
    val homePortraitWidth = 124.dp
    val homePortraitHeight = 160.dp
    val homePortraitInset = 8.dp
    val homeChatMinHeight = 180.dp
    val gazeCanvasHeight = 480.dp
    val gazeCanvasMinHeight = 160.dp
    val answerKey = 68.dp
    val answerKeyCompact = 56.dp
}
