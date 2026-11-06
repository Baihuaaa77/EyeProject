package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 圆角令牌（统一四级）：图标块与小元素 [small] 12 → 按钮与输入 [medium] 14 → 内嵌面板 [large] 16 → 卡片 [card]/[panel] 20 → 主卡 [hero] 24；
 * 胶囊 [chip]。与苹果端 EyeStyle.radius* 一一对应。
 */
object EyeShapes {
    val small = RoundedCornerShape(12.dp)
    val medium = RoundedCornerShape(14.dp)
    val large = RoundedCornerShape(16.dp)
    val card = RoundedCornerShape(20.dp)
    val panel = RoundedCornerShape(20.dp)
    val hero = RoundedCornerShape(24.dp)
    val chip = RoundedCornerShape(50)
}
