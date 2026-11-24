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
    fun loadTrackColor(score: Int): Color = if (score in 30..59) DarkEyeColors.primary else loadColor(score)

    val warm = Color(0xFFEFB285)
    val sage = Color(0xFFB6C6B3)
    val terracotta = Color(0xFFED9A78)
    val rail = Color(0xFF373737)
    val pupil = Color(0xFF10110F)
    val marker = Color(0xFFFFE8CF)
    private val surfaceMiddle = Color(0xFF33261C)
    val surface = Brush.linearGradient(0f to DarkEyeColors.heroStart, .64f to surfaceMiddle, 1f to DarkEyeColors.heroEnd)
    val pressureInk = Color(0xFF20281B)
    val pressureText = Color(0xFF303B27)
    val pressurePaper = Color(0xFFEFF0D9)
    val avatar = Brush.linearGradient(listOf(Color(0xFF6E4E36), Color(0xFF3A2C22)))
}
