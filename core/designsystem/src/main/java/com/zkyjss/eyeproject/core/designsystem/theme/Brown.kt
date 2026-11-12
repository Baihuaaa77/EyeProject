package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** 用户棕色参考稿的材质令牌，业务页面只消费这些画刷。 */
object EyeBrown {
    val warm = Color(0xFFEFB285)
    val peach = Color(0xFFF6A267)
    val sage = Color(0xFFB6C6B3)
    val terracotta = Color(0xFFED9A78)
    val rail = Color(0xFF52524A)
    val pupil = Color(0xFF10110F)
    val marker = Color(0xFFFFE8CF)
    val header = Color(0xFF242520)
    val headerBorder = Color(0xFF454438)
    val hero =
        Brush.linearGradient(
            listOf(Color(0xFF2D251D), Color(0xFF211B16), Color(0xFF191A16)),
            Offset.Zero,
            Offset(800f, 900f),
        )
    val trend = Brush.verticalGradient(listOf(Color(0xFF232923), Color(0xFF181A17)))
    val consult = Brush.linearGradient(listOf(Color(0xFF503624), Color(0xFF30261E)))
    val feeling = Brush.linearGradient(listOf(Color(0xFF443027), Color(0xFF2B2420)))
    val profile = Brush.linearGradient(listOf(Color(0xFF3E3022), Color(0xFF22221D)))
    val surface = Brush.linearGradient(listOf(Color(0xFF242420), Color(0xFF1B1C19)))
    val avatar = Brush.linearGradient(listOf(Color(0xFF6E4E36), Color(0xFF3A2C22)))

    fun tile(index: Int): Brush =
        Brush.linearGradient(
            when (index) {
                1 -> listOf(Color(0xFF312721), Color(0xFF28221F))
                2 -> listOf(Color(0xFF262924), Color(0xFF21231F))
                3 -> listOf(Color(0xFF302A26), Color(0xFF262320))
                else -> listOf(Color(0xFF302920), Color(0xFF25241F))
            }
        )
}
