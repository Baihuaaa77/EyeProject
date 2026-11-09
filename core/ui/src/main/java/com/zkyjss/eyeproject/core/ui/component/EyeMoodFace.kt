package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import kotlin.math.cos
import kotlin.math.sin

/** 表情只表达当前已有评估等级，不参与评分计算。 */
enum class EyeMood { HAPPY, CALM, TIRED, WAITING }

/** 原生矢量表情：两端使用同一轮廓和五官，不依赖系统 emoji 字库。 */
@Composable
fun EyeMoodFace(mood: EyeMood, modifier: Modifier = Modifier) {
    val colors = LocalEyeColors.current
    Canvas(modifier) {
        val s = size.minDimension
        val c = center
        val base = when (mood) {
            EyeMood.HAPPY -> colors.moodGold
            EyeMood.CALM -> colors.moodWarm
            EyeMood.TIRED -> colors.moodSilver
            EyeMood.WAITING -> colors.moodSilver
        }
        val rays = Path().apply {
            repeat(161) { i ->
                val a = i * 2.0 * Math.PI / 160
                val r = s * (0.44f + 0.035f * cos(a * 10).toFloat())
                val p = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
        drawPath(rays, base.copy(alpha = 0.52f))
        drawCircle(Brush.radialGradient(listOf(colors.white.copy(alpha = 0.9f), base),
            Offset(c.x - s * 0.1f, c.y - s * 0.14f), s * 0.62f), s * 0.36f)
        val ink = colors.ink
        val line = s * 0.045f
        for (side in listOf(-1, 1)) {
            val eye = Offset(c.x + side * s * 0.12f, c.y - s * 0.04f)
            if (mood == EyeMood.TIRED) {
                drawLine(ink, eye - Offset(s * 0.045f, 0f), eye + Offset(s * 0.045f, 0f), line, StrokeCap.Round)
            } else drawCircle(ink, s * 0.033f, eye)
        }
        when (mood) {
            EyeMood.HAPPY -> drawArc(ink, 0f, 180f, false, Offset(c.x - s * 0.15f, c.y + s * 0.04f),
                Size(s * 0.3f, s * 0.18f), style = Stroke(line, cap = StrokeCap.Round))
            EyeMood.CALM -> drawArc(ink, 10f, 160f, false, Offset(c.x - s * 0.1f, c.y + s * 0.08f),
                Size(s * 0.2f, s * 0.08f), style = Stroke(line, cap = StrokeCap.Round))
            EyeMood.TIRED -> drawCircle(ink, s * 0.045f, Offset(c.x, c.y + s * 0.14f), style = Stroke(line * 0.75f))
            EyeMood.WAITING -> drawLine(ink, Offset(c.x - s * 0.08f, c.y + s * 0.14f),
                Offset(c.x + s * 0.08f, c.y + s * 0.14f), line, StrokeCap.Round)
        }
    }
}
