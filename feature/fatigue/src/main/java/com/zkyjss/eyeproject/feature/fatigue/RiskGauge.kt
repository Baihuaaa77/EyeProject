package com.zkyjss.eyeproject.feature.fatigue

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.theme.rememberReducedMotion
import com.zkyjss.eyeproject.core.model.RiskLevel
import com.zkyjss.eyeproject.core.ui.component.EyeText
import kotlin.math.roundToInt

/**
 * 疲劳风险仪表盘（0~1，270° 弧形 + 中心数值），纯 Canvas 自绘。
 * - 数值变化以缓动过渡，弧色按等级平滑切换（低=绿、中=琥珀、高=红）
 * - 刻度 + 等级分界点；[live] 时外圈品牌色光晕缓慢呼吸，表示正在实时监测
 */
@Composable
fun RiskGauge(
    risk: Float?,
    modifier: Modifier = Modifier,
    live: Boolean = false
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val valid = risk?.isFinite() == true
    val target = if (valid) risk!!.coerceIn(0f, 1f) else 0f
    val value by animateFloatAsState(target, tween(EyeMotion.LONG, easing = EyeMotion.Standard), label = "风险数值")
    val level = when {
        target < 0.3f -> RiskLevel.LOW
        target < 0.7f -> RiskLevel.MEDIUM
        else -> RiskLevel.HIGH
    }
    // 无有效数据时用中性色，避免「待测」被误读为低风险的绿色
    val arcColor by animateColorAsState(
        if (!valid) colors.textSecondary else when (level) {
            RiskLevel.LOW -> colors.success
            RiskLevel.MEDIUM -> colors.warning
            RiskLevel.HIGH -> colors.danger
        },
        tween(EyeMotion.LONG),
        label = "风险颜色"
    )
    val reduced = rememberReducedMotion()
    // 只在实时监测时才创建无限动画，待机时不持续请求帧
    val breath = if (live && !reduced) {
        rememberInfiniteTransition(label = "监测呼吸").animateFloat(
            0f, 1f, infiniteRepeatable(tween(2400, easing = EyeMotion.Gentle), RepeatMode.Reverse), label = "呼吸"
        )
    } else {
        null
    }

    Box(
        modifier = modifier.semantics {
            contentDescription = if (valid) "用眼风险 ${(target * 100).roundToInt()}，${level.label}" else "用眼风险待测"
        },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 16.dp.toPx()
            val inset = stroke / 2f + 12.dp.toPx()
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            val topLeft = Offset(inset, inset)
            val startAngle = 135f
            val sweep = 270f

            if (breath != null) {
                val b = breath.value
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(colors.brandStart.copy(alpha = 0.10f + 0.10f * b), colors.brandStart.copy(alpha = 0f)),
                        center = center,
                        radius = size.minDimension / 2f
                    ),
                    radius = size.minDimension / 2f
                )
            }

            // 轨道
            drawArc(
                color = colors.neutralSoft,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            // 刻度：每 10% 一格，等级分界（30% / 70%）加长
            val radius = arcSize.width / 2f
            for (i in 0..10) {
                val angle = startAngle + sweep * i / 10f
                val major = i == 3 || i == 7
                rotate(angle, center) {
                    val outer = center.x + radius + stroke / 2f + 4.dp.toPx()
                    val len = if (major) 8.dp.toPx() else 4.dp.toPx()
                    drawLine(
                        color = if (major) colors.textSecondary else colors.borderSubtle,
                        start = Offset(outer, center.y),
                        end = Offset(outer + len, center.y),
                        strokeWidth = if (major) 2.dp.toPx() else 1.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
            if (valid) {
                drawArc(
                    color = arcColor,
                    startAngle = startAngle,
                    sweepAngle = sweep * value,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                // 末端指示点
                rotate(startAngle + sweep * value, center) {
                    drawCircle(colors.panelBackground, stroke * 0.32f, Offset(center.x + radius, center.y))
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            EyeText(
                text = if (valid) "${(value * 100).roundToInt()}" else "—",
                style = typography.headline,
                color = arcColor
            )
            EyeText(text = if (valid) level.label else "待测", style = typography.subtitle, color = colors.textSecondary)
        }
    }
}
