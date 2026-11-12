package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** 安卓玻璃视觉模拟令牌：不采集背景截图，不对文字做模糊，也不声称实现实时背景折射。 */
object EyeLiquidGlass {
    val rimWidth = 1.2.dp
    val innerRimInset = 3.dp
    val shadowRadius = 18.dp
    val shadowOffset = DpOffset(0.dp, 8.dp)
    const val fillLight = 0.78f
    const val fillDark = 0.88f
    const val tintLight = 0.045f
    const val tintDark = 0.11f
    const val reflectionLight = 0.72f
    const val reflectionDark = 0.23f
    const val edgeLight = 0.92f
    const val edgeDark = 0.50f
    const val innerEdgeLight = 0.24f
    const val innerEdgeDark = 0.15f
    const val pressedBoost = 0.10f
    val dockHeight = 64.dp
    val dockInset = 6.dp
    val dockGap = 4.dp
    val dockIconSize = 24.dp
}

/**
 * 透明填充、环境色染色、弯曲高光与双层镜片边缘组成的轻量模拟。
 * 所有画刷与轮廓按尺寸缓存，只有按压状态改变时更新，不引入持续动画或后台采集。
 */
fun Modifier.eyeLiquidGlass(colors: EyeColors, shape: Shape, tint: Color = colors.brandStart,
    pressed: Boolean = false): Modifier = eyeSoftSurface(colors, shape, pressed = pressed)
