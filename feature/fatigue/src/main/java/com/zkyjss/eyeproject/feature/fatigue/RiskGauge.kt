package com.zkyjss.eyeproject.feature.fatigue

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.model.RiskLevel
import com.zkyjss.eyeproject.core.ui.component.EyeText
import java.util.Locale

/** 疲劳风险仪表盘（0~1，270° 弧形 + 中心数值），纯 Canvas 自绘。 */
@Composable
fun RiskGauge(
    risk: Float?,
    modifier: Modifier = Modifier
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val value = (risk ?: 0f).coerceIn(0f, 1f)
    val level = when {
        value < 0.3f -> RiskLevel.LOW
        value < 0.7f -> RiskLevel.MEDIUM
        else -> RiskLevel.HIGH
    }
    val arcColor: Color = when (level) {
        RiskLevel.LOW -> colors.success
        RiskLevel.MEDIUM -> colors.warning
        RiskLevel.HIGH -> colors.danger
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 20.dp.toPx()
            val inset = stroke / 2f + 2.dp.toPx()
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            val topLeft = Offset(inset, inset)
            val startAngle = 135f
            val sweep = 270f

            drawArc(
                color = colors.neutralSoft,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = arcColor,
                startAngle = startAngle,
                sweepAngle = sweep * value,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            EyeText(
                text = String.format(Locale.US, "%.0f", value * 100f),
                style = typography.headline,
                color = arcColor
            )
            EyeText(text = level.label, style = typography.subtitle, color = colors.textSecondary)
        }
    }
}
