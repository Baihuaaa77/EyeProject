package com.zkyjss.eyeproject.core.ui.component

import android.graphics.BlurMaskFilter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import com.zkyjss.eyeproject.core.designsystem.theme.EyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.EyeGlow

/** 只模糊数据图形的副本；清晰的主图形仍由调用方绘制。 */
private fun DrawScope.dataGlowPaint(colors: EyeColors): Paint = Paint().apply {
    color = colors.brandStart
    alpha = if (colors.isDark) EyeGlow.opacityDark else EyeGlow.opacityLight
    asFrameworkPaint().maskFilter = BlurMaskFilter(EyeGlow.radius.toPx(), BlurMaskFilter.Blur.NORMAL)
}

fun DrawScope.drawDataGlowArc(colors: EyeColors, topLeft: Offset, arcSize: Size, sweep: Float, stroke: Float) {
    val paint = dataGlowPaint(colors).apply {
        style = PaintingStyle.Stroke
        strokeWidth = stroke * 1.7f
        strokeCap = StrokeCap.Round
    }
    drawIntoCanvas { it.drawArc(Rect(topLeft, arcSize), 0f, sweep, false, paint) }
}

fun DrawScope.drawDataGlowBar(colors: EyeColors, topLeft: Offset, barSize: Size) {
    val paint = dataGlowPaint(colors)
    val radius = barSize.width.coerceAtMost(barSize.height) / 2
    drawIntoCanvas { it.drawRoundRect(topLeft.x, topLeft.y, topLeft.x + barSize.width, topLeft.y + barSize.height,
        radius, radius, paint) }
}

/** 刻度使用对应位置的弧线颜色，只为短线副本添加窄光晕。 */
fun DrawScope.drawDataGlowTick(colors: EyeColors, color: Color, start: Offset, end: Offset, stroke: Float) {
    val paint = dataGlowPaint(colors).apply {
        this.color = color
        style = PaintingStyle.Stroke
        strokeWidth = stroke * 2f
        strokeCap = StrokeCap.Round
        asFrameworkPaint().maskFilter = BlurMaskFilter(EyeGlow.tickRadius.toPx(), BlurMaskFilter.Blur.NORMAL)
    }
    drawIntoCanvas { it.drawLine(start, end, paint) }
}

/** 主按钮整圈边缘光效，不为文字和按钮内部增加模糊。 */
fun DrawScope.drawDataGlowBorder(colors: EyeColors, radius: Float, stroke: Float) {
    val paint = dataGlowPaint(colors).apply {
        style = PaintingStyle.Stroke
        strokeWidth = stroke
        colors.borderBrush.applyTo(size, this, if (colors.isDark) EyeGlow.opacityDark else EyeGlow.opacityLight)
        asFrameworkPaint().maskFilter = BlurMaskFilter(EyeGlow.radius.toPx() * 0.5f, BlurMaskFilter.Blur.NORMAL)
    }
    val inset = stroke * 1.5f
    drawIntoCanvas { it.drawRoundRect(inset, inset, size.width - inset, size.height - inset, radius, radius, paint) }
}
