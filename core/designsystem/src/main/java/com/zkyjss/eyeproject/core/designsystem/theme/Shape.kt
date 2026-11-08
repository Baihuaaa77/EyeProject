package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 圆角令牌（2026-11-08 视觉改版）：图标块与小元素 [small] 10 → 按钮、输入、内嵌块 [medium] / [large] 14 → 卡片 [card]/[panel] 18 →
 * 每页主卡与取景框 [hero] 22；标签与胶囊 [chip] 10。与苹果端 EyeStyle.radius*（.continuous 平滑圆角）一一对应。
 */
object EyeShapes {
    val small = RoundedCornerShape(10.dp)
    val medium = RoundedCornerShape(14.dp)
    val large = RoundedCornerShape(14.dp)
    val card = RoundedCornerShape(18.dp)
    val panel = RoundedCornerShape(18.dp)
    val hero = RoundedCornerShape(22.dp)
    val chip = RoundedCornerShape(10.dp)
}
