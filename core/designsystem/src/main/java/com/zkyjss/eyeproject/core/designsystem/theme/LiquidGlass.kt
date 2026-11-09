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
fun Modifier.eyeLiquidGlass(
    colors: EyeColors,
    shape: Shape,
    tint: Color = colors.brandStart,
    pressed: Boolean = false,
): Modifier = dropShadow(shape, Shadow(radius = EyeLiquidGlass.shadowRadius,
    color = colors.shadow.copy(alpha = colors.shadow.alpha * 0.65f), offset = EyeLiquidGlass.shadowOffset))
    .clip(shape).drawWithCache {
        fun outlinePath(inset: Float): Path = Path().apply {
            when (val outline = shape.createOutline(Size((size.width - inset * 2).coerceAtLeast(0f),
                (size.height - inset * 2).coerceAtLeast(0f)), layoutDirection, this@drawWithCache)) {
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Generic -> addPath(outline.path)
            }
            translate(Offset(inset, inset))
        }
        val reflection = (if (colors.isDark) EyeLiquidGlass.reflectionDark else EyeLiquidGlass.reflectionLight) +
            if (pressed) EyeLiquidGlass.pressedBoost else 0f
        val edge = if (colors.isDark) EyeLiquidGlass.edgeDark else EyeLiquidGlass.edgeLight
        val innerEdge = if (colors.isDark) EyeLiquidGlass.innerEdgeDark else EyeLiquidGlass.innerEdgeLight
        val fill = Brush.linearGradient(listOf(
            lerp(colors.panelBackground, colors.white, if (colors.isDark) 0.06f else 0.60f)
                .copy(alpha = if (colors.isDark) EyeLiquidGlass.fillDark else EyeLiquidGlass.fillLight),
            lerp(colors.panelBackground, tint, if (colors.isDark) EyeLiquidGlass.tintDark else EyeLiquidGlass.tintLight),
        ), Offset.Zero, Offset(size.width, size.height))
        val lens = Brush.radialGradient(listOf(colors.white.copy(alpha = reflection), colors.white.copy(alpha = 0f)),
            Offset(size.width * 0.18f, -size.height * 0.42f), size.maxDimension * 1.05f)
        val sheen = Brush.linearGradient(0f to colors.white.copy(alpha = 0f),
            0.45f to colors.white.copy(alpha = 0f), 0.50f to colors.white.copy(alpha = reflection * 0.16f),
            0.70f to colors.white.copy(alpha = 0f), 1f to tint.copy(alpha = 0.035f),
            start = Offset.Zero, end = Offset(size.width * 0.6f, size.height))
        val rim = Brush.linearGradient(0f to colors.white.copy(alpha = edge),
            0.25f to colors.white.copy(alpha = edge * 0.18f), 0.5f to tint.copy(alpha = edge * 0.22f),
            0.75f to colors.white.copy(alpha = edge * 0.75f), 1f to tint.copy(alpha = edge * 0.40f),
            start = Offset.Zero, end = Offset(size.width, size.height))
        val innerRim = Brush.linearGradient(listOf(tint.copy(alpha = innerEdge), colors.white.copy(alpha = 0f),
            colors.white.copy(alpha = innerEdge)), Offset.Zero, Offset(size.width, size.height))
        val width = EyeLiquidGlass.rimWidth.toPx()
        val outerPath = outlinePath(width / 2)
        val innerPath = outlinePath(EyeLiquidGlass.innerRimInset.toPx())
        onDrawWithContent {
            drawRect(fill)
            drawRect(lens)
            drawRect(sheen)
            drawContent()
            drawPath(outerPath, rim, style = Stroke(width))
            drawPath(innerPath, innerRim, style = Stroke(width * 0.6f))
        }
    }
