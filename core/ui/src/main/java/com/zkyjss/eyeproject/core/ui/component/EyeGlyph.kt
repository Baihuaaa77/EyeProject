package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

/**
 * 线性图标：24 单位视口、1.8 线宽、圆头圆角，与底部导航图标同一风格。苹果端 EyeGlyphView 逐笔移植，两端一致。
 * 弧线一律按「x 轴正向起、顺时针」的角度取点绘制，避免两端弧线方向约定不同。
 */
enum class EyeGlyph {
    DOCUMENT, FAMILY, CLOUD, SHIELD, BELL, SUN, CHART, BULB, EYE, CLOCK, PERSON, INFO, MOON, EXPORT, SYNC,
    LOGOUT, TRASH, SERVER, CAMERA, MIC, HOURGLASS, CHAT, RULER, COFFEE, GEAR, HEART,
}

@Composable
fun EyeGlyphIcon(glyph: EyeGlyph, modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier) { drawEyeGlyph(glyph, tint) }
}

fun DrawScope.drawEyeGlyph(glyph: EyeGlyph, tint: Color) {
    val k = size.minDimension / 24f
    val ox = (size.width - 24f * k) / 2
    val oy = (size.height - 24f * k) / 2
    fun p(x: Float, y: Float) = Offset(ox + x * k, oy + y * k)
    val line = Stroke(1.8f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun seg(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, p(x1, y1), p(x2, y2), 1.8f * k, StrokeCap.Round)
    fun poly(vararg xy: Float, close: Boolean = false) = drawPath(Path().apply {
        moveTo(p(xy[0], xy[1]).x, p(xy[0], xy[1]).y)
        var i = 2
        while (i < xy.size) { lineTo(p(xy[i], xy[i + 1]).x, p(xy[i], xy[i + 1]).y); i += 2 }
        if (close) close()
    }, tint, style = line)
    fun box(x: Float, y: Float, w: Float, h: Float, r: Float) =
        drawRoundRect(tint, p(x, y), Size(w * k, h * k), CornerRadius(r * k), style = line)
    fun ring(cx: Float, cy: Float, r: Float) = drawCircle(tint, r * k, p(cx, cy), style = line)
    fun dot(cx: Float, cy: Float, r: Float) = drawCircle(tint, r * k, p(cx, cy))
    /** 椭圆弧：中心、半径、起始角与扫过角（度，x 轴正向起顺时针）。 */
    fun arc(cx: Float, cy: Float, rx: Float, ry: Float, start: Float, sweep: Float) = drawPath(Path().apply {
        val steps = 24
        for (i in 0..steps) {
            val a = Math.toRadians((start + sweep * i / steps).toDouble())
            val q = p(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat())
            if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y)
        }
    }, tint, style = line)
    fun curve(vararg xy: Float, close: Boolean = false) = drawPath(Path().apply {
        moveTo(p(xy[0], xy[1]).x, p(xy[0], xy[1]).y)
        var i = 2
        while (i + 5 < xy.size) {
            val a = p(xy[i], xy[i + 1]); val b = p(xy[i + 2], xy[i + 3]); val c = p(xy[i + 4], xy[i + 5])
            cubicTo(a.x, a.y, b.x, b.y, c.x, c.y); i += 6
        }
        if (close) close()
    }, tint, style = line)

    when (glyph) {
        EyeGlyph.DOCUMENT -> { box(5f, 3f, 14f, 18f, 2.5f); seg(8.5f, 8f, 15.5f, 8f); seg(8.5f, 12f, 15.5f, 12f); seg(8.5f, 16f, 12.5f, 16f) }
        EyeGlyph.FAMILY -> { ring(9f, 8f, 3.2f); arc(9f, 19.5f, 5.5f, 5f, 180f, 180f); ring(17f, 10.5f, 2.3f); arc(17f, 19.5f, 3.8f, 3.6f, 180f, 180f) }
        EyeGlyph.CLOUD -> curve(7.5f, 18.5f, 12f, 18.5f, 14f, 18.5f, 17f, 18.5f, 19.8f, 18.5f, 21.5f, 16.4f, 20.6f, 13.9f,
            19.9f, 12f, 18f, 11.2f, 16.6f, 11.6f, 15.9f, 8.4f, 13.3f, 6.5f, 10.6f, 7.1f, 8.3f, 7.6f, 6.8f, 9.6f, 6.9f, 11.9f,
            4.6f, 12.2f, 3f, 14.2f, 3.3f, 16.3f, 3.6f, 17.7f, 5.2f, 18.6f, 7.5f, 18.5f, close = true)
        EyeGlyph.SHIELD -> {
            curve(12f, 3f, 14.3f, 4f, 16.7f, 5f, 19f, 6f, 19f, 7.8f, 19f, 9.7f, 19f, 11.5f, 19f, 15.8f, 16.2f, 19.2f, 12f, 21f,
                7.8f, 19.2f, 5f, 15.8f, 5f, 11.5f, 5f, 9.7f, 5f, 7.8f, 5f, 6f, 7.3f, 5f, 9.7f, 4f, 12f, 3f, close = true)
            poly(9f, 12f, 11.2f, 14.2f, 15.2f, 10f)
        }
        EyeGlyph.BELL -> {
            curve(6f, 16.5f, 6f, 14.7f, 6f, 12.8f, 6f, 11f, 6f, 7.7f, 8.7f, 5f, 12f, 5f, 15.3f, 5f, 18f, 7.7f, 18f, 11f,
                18f, 12.8f, 18f, 14.7f, 18f, 16.5f)
            poly(18f, 16.5f, 19.5f, 18f, 4.5f, 18f, 6f, 16.5f)
            arc(12f, 19.5f, 2f, 1.8f, 0f, 180f); seg(12f, 3f, 12f, 5f)
        }
        EyeGlyph.SUN -> {
            ring(12f, 12f, 4f)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                seg(12f + 7f * cos(a).toFloat(), 12f + 7f * sin(a).toFloat(), 12f + 9.5f * cos(a).toFloat(), 12f + 9.5f * sin(a).toFloat())
            }
        }
        EyeGlyph.CHART -> { poly(4f, 4f, 4f, 20f, 20f, 20f); seg(8.5f, 16.5f, 8.5f, 12f); seg(12.5f, 16.5f, 12.5f, 8f); seg(16.5f, 16.5f, 16.5f, 10.5f) }
        EyeGlyph.BULB -> {
            curve(9f, 15.5f, 7f, 14.3f, 6f, 12.5f, 6f, 10.5f, 6f, 7f, 8.7f, 4.5f, 12f, 4.5f, 15.3f, 4.5f, 18f, 7f, 18f, 10.5f,
                18f, 12.5f, 17f, 14.3f, 15f, 15.5f)
            poly(15f, 15.5f, 15f, 17.5f, 9f, 17.5f, 9f, 15.5f); seg(9.5f, 20.5f, 14.5f, 20.5f)
        }
        EyeGlyph.EYE -> {
            curve(2.5f, 12f, 5f, 7.5f, 8.3f, 5.5f, 12f, 5.5f, 15.7f, 5.5f, 19f, 7.5f, 21.5f, 12f,
                19f, 16.5f, 15.7f, 18.5f, 12f, 18.5f, 8.3f, 18.5f, 5f, 16.5f, 2.5f, 12f, close = true)
            ring(12f, 12f, 3f)
        }
        EyeGlyph.CLOCK -> { ring(12f, 12f, 8.5f); poly(12f, 7.5f, 12f, 12f, 15.5f, 14f) }
        EyeGlyph.PERSON -> { ring(12f, 8.5f, 3.8f); arc(12f, 20.5f, 7.5f, 6.5f, 200f, 140f) }
        EyeGlyph.INFO -> { ring(12f, 12f, 8.5f); seg(12f, 11f, 12f, 16.5f); dot(12f, 7.8f, 1.15f) }
        EyeGlyph.HEART -> curve(12f, 20f, 5f, 15.5f, 3f, 12f, 3f, 8.8f, 3f, 6f, 5.2f, 4f, 7.8f, 4f, 9.6f, 4f, 11.1f, 5f, 12f, 6.6f,
            12.9f, 5f, 14.4f, 4f, 16.2f, 4f, 18.8f, 4f, 21f, 6f, 21f, 8.8f, 21f, 12f, 19f, 15.5f, 12f, 20f, close = true)
        EyeGlyph.MOON -> curve(14.5f, 4.2f, 10.5f, 5f, 8.2f, 8.7f, 8.9f, 12.8f, 9.6f, 16.6f, 13.2f, 19.3f, 17f, 18.8f,
            15.6f, 20.3f, 13.6f, 21f, 11.6f, 21f, 6.9f, 20.8f, 3.2f, 16.9f, 3.3f, 12.2f, 3.4f, 7.8f, 6.9f, 4.2f, 11.4f, 4f,
            12.5f, 3.95f, 13.5f, 4.05f, 14.5f, 4.2f, close = true)
        EyeGlyph.EXPORT -> { poly(8f, 10f, 6.5f, 10f, 6.5f, 20f, 17.5f, 20f, 17.5f, 10f, 16f, 10f); seg(12f, 3.5f, 12f, 14f); poly(8.8f, 6.7f, 12f, 3.5f, 15.2f, 6.7f) }
        EyeGlyph.SYNC -> {
            arc(12f, 12f, 7.5f, 7.5f, 200f, 140f); poly(16.6f, 9.1f, 19.05f, 9.43f, 19.5f, 6.9f)
            arc(12f, 12f, 7.5f, 7.5f, 20f, 140f); poly(7.4f, 14.9f, 4.95f, 14.57f, 4.5f, 17.1f)
        }
        EyeGlyph.LOGOUT -> {
            curve(13f, 4f, 10.8f, 4f, 8.7f, 4f, 6.5f, 4f, 5.1f, 4f, 4f, 5.1f, 4f, 6.5f, 4f, 10.2f, 4f, 13.8f, 4f, 17.5f,
                4f, 18.9f, 5.1f, 20f, 6.5f, 20f, 8.7f, 20f, 10.8f, 20f, 13f, 20f)
            seg(10f, 12f, 20f, 12f); poly(17f, 9f, 20f, 12f, 17f, 15f)
        }
        EyeGlyph.TRASH -> { seg(4.5f, 6.5f, 19.5f, 6.5f); poly(9.5f, 6.5f, 9.5f, 4.5f, 14.5f, 4.5f, 14.5f, 6.5f); poly(6.5f, 6.5f, 7.5f, 20f, 16.5f, 20f, 17.5f, 6.5f); seg(10.5f, 10f, 10.5f, 16.5f); seg(13.5f, 10f, 13.5f, 16.5f) }
        EyeGlyph.SERVER -> { box(4f, 4.5f, 16f, 6.5f, 2f); box(4f, 13f, 16f, 6.5f, 2f); dot(7.5f, 7.75f, 1f); dot(7.5f, 16.25f, 1f) }
        EyeGlyph.CAMERA -> { box(3f, 7f, 18f, 13f, 3f); poly(8.5f, 7f, 10f, 4.5f, 14f, 4.5f, 15.5f, 7f); ring(12f, 13.5f, 3.5f) }
        EyeGlyph.MIC -> { box(9f, 3f, 6f, 11f, 3f); arc(12f, 11.5f, 6.5f, 5.5f, 0f, 180f); seg(12f, 17f, 12f, 20.5f); seg(9f, 20.5f, 15f, 20.5f) }
        EyeGlyph.HOURGLASS -> {
            seg(6.5f, 3.5f, 17.5f, 3.5f); seg(6.5f, 20.5f, 17.5f, 20.5f)
            curve(7.5f, 3.5f, 7.5f, 9f, 12f, 10f, 12f, 12f, 12f, 14f, 7.5f, 15f, 7.5f, 20.5f)
            curve(16.5f, 3.5f, 16.5f, 9f, 12f, 10f, 12f, 12f, 12f, 14f, 16.5f, 15f, 16.5f, 20.5f)
        }
        EyeGlyph.CHAT -> { box(3.5f, 4.5f, 17f, 12f, 4f); poly(8f, 16.5f, 7f, 20.5f, 12f, 16.5f); dot(8.5f, 10.5f, 1f); dot(12f, 10.5f, 1f); dot(15.5f, 10.5f, 1f) }
        EyeGlyph.RULER -> { box(3f, 8f, 18f, 8f, 1.5f); seg(7f, 8f, 7f, 11f); seg(11f, 8f, 11f, 12f); seg(15f, 8f, 15f, 11f) }
        EyeGlyph.COFFEE -> {
            curve(5f, 9f, 8.7f, 9f, 12.3f, 9f, 16f, 9f, 16f, 10.7f, 16f, 12.3f, 16f, 14f, 16f, 16.2f, 14.2f, 18f, 12f, 18f,
                11f, 18f, 10f, 18f, 9f, 18f, 6.8f, 18f, 5f, 16.2f, 5f, 14f, 5f, 12.3f, 5f, 10.7f, 5f, 9f)
            arc(16f, 12f, 2.5f, 2.2f, -90f, 180f); seg(8.5f, 4f, 8.5f, 6f); seg(12f, 4f, 12f, 6f)
        }
        EyeGlyph.GEAR -> {
            ring(12f, 12f, 3f)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0 + 22.5)
                seg(12f + 6f * cos(a).toFloat(), 12f + 6f * sin(a).toFloat(), 12f + 8.8f * cos(a).toFloat(), 12f + 8.8f * sin(a).toFloat())
            }
            ring(12f, 12f, 6f)
        }
    }
}
