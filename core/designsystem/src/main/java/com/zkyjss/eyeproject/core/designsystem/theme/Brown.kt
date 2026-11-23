package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** 用户棕色参考稿的材质令牌，业务页面只消费这些画刷。 */
object EyeBrown {
    val loadLow = Color(0xFF01FE00)
    val loadMedium = Color(0xFFFF7900)
    val loadHigh = Color(0xFFFF0000)
    fun loadColor(score: Int): Color = when {
        score < 30 -> loadLow
        score < 60 -> loadMedium
        else -> loadHigh
    }
    val warm = Color(0xFFEFB285)
    val sage = Color(0xFFB6C6B3)
    val terracotta = Color(0xFFED9A78)
    val rail = Color(0xFF373737)
    val pupil = Color(0xFF10110F)
    val marker = Color(0xFFFFE8CF)
    val surface = Brush.verticalGradient(listOf(DarkEyeColors.heroStart, DarkEyeColors.heroEnd))
    val avatar = Brush.linearGradient(listOf(Color(0xFF6E4E36), Color(0xFF3A2C22)))
}
