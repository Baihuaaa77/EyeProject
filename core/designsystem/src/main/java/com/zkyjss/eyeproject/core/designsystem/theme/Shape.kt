package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 圆角令牌（2026-11-06 统一收小）：图标块与小元素 [small] 8 → 按钮与输入 [medium] 10 → 内嵌面板与色块 [large] 12 → 卡片 [card]/[panel] 14 →
 * 主卡与取景框 [hero] 16；标签、小按钮 [chip] 8（不再用整圆胶囊，细进度条高度不足 16 时自然仍是圆头）。与苹果端 EyeStyle.radius* 一一对应。
 */
object EyeShapes {
    val small = RoundedCornerShape(8.dp)
    val medium = RoundedCornerShape(10.dp)
    val large = RoundedCornerShape(12.dp)
    val card = RoundedCornerShape(14.dp)
    val panel = RoundedCornerShape(14.dp)
    val hero = RoundedCornerShape(16.dp)
    val chip = RoundedCornerShape(8.dp)
}
