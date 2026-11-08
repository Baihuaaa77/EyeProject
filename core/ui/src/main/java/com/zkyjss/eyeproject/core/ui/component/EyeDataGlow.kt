package com.zkyjss.eyeproject.core.ui.component

import android.graphics.BlurMaskFilter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Paint
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
