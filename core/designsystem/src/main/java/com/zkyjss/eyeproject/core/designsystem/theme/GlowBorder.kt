package com.zkyjss.eyeproject.core.designsystem.theme

import android.graphics.BlurMaskFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas

/** 整圈渐变细光边：图形缓存后叠在内容上方，不改变尺寸、点击区域和内容清晰度。 */
fun Modifier.eyeGlowBorder(colors: EyeColors, shape: Shape, strength: Float = 1f): Modifier = drawWithCache {
    val inset = EyeGlow.cardBorderInset.toPx()
    val outline = shape.createOutline(Size((size.width - inset * 2).coerceAtLeast(0f),
        (size.height - inset * 2).coerceAtLeast(0f)), layoutDirection, this)
    val path = Path().apply {
        when (outline) {
            is Outline.Rectangle -> addRect(outline.rect)
            is Outline.Rounded -> addRoundRect(outline.roundRect)
            is Outline.Generic -> addPath(outline.path)
        }
        translate(Offset(inset, inset))
    }
    val brush = Brush.linearGradient(colors.borderColors,
        Offset.Zero, Offset(size.width, size.height))
    val opacity = (if (colors.isDark) EyeGlow.cardBorderOpacityDark else EyeGlow.cardBorderOpacityLight) * strength
    val width = EyeGlow.cardBorderWidth.toPx()
    val glow = Paint().apply {
        style = PaintingStyle.Stroke
        strokeWidth = width * 2
        brush.applyTo(size, this, opacity * EyeGlow.cardGlowOpacity)
        asFrameworkPaint().maskFilter = BlurMaskFilter(EyeGlow.cardGlowRadius.toPx(), BlurMaskFilter.Blur.NORMAL)
    }
    onDrawWithContent {
        drawContent()
        drawIntoCanvas { it.drawPath(path, glow) }
        drawPath(path, brush, alpha = opacity, style = Stroke(width))
    }
}
