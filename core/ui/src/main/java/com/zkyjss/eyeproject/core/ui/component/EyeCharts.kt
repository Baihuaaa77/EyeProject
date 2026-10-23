package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography

/** 柱状图的一根柱子：[value] 为 null 表示没有数据（只画底部短线）；[text] 显示在柱顶。 */
@Immutable
data class EyeBar(val label: String, val value: Float?, val text: String? = null, val highlight: Boolean = false, val warning: Boolean = false)

/**
 * 简洁柱状图（用眼趋势）：柱顶标数值、柱下标日期，今天用主色、其余浅主色，超出参考线的柱子用琥珀色；
 * [reference] 画一条虚线参考线。出现时柱子从底部长出。苹果端 BarChartView 同一画法。
 */
@Composable
fun EyeBarChart(
    bars: List<EyeBar>,
    modifier: Modifier = Modifier,
    reference: Float? = null,
    maxValue: Float? = null,
    chartHeight: Dp = 140.dp,
    description: String = "",
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val grow = remember { Animatable(0f) }
    LaunchedEffect(bars) { grow.snapTo(0f); grow.animateTo(1f, tween(EyeMotion.LONG, easing = EyeMotion.Standard)) }
    val top = maxOf(maxValue ?: 0f, bars.maxOfOrNull { it.value ?: 0f } ?: 0f, (reference ?: 0f) * 1.15f).coerceAtLeast(1f)
    Column(modifier.fillMaxWidth().semantics { if (description.isNotEmpty()) contentDescription = description }) {
        Row(Modifier.fillMaxWidth()) {
            bars.forEach { bar ->
                EyeText(bar.text ?: "", Modifier.weight(1f), style = typography.chip,
                    color = if (bar.highlight) colors.primary else colors.textSecondary, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
        Box(Modifier.fillMaxWidth().height(chartHeight)) {
            Canvas(Modifier.fillMaxWidth().height(chartHeight)) {
                val slot = size.width / bars.size.coerceAtLeast(1)
                val barWidth = (slot * 0.46f).coerceAtMost(28.dp.toPx())
                drawLine(colors.borderSubtle, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                bars.forEachIndexed { i, bar ->
                    val x = slot * i + (slot - barWidth) / 2
                    val v = bar.value
                    if (v == null) {
                        drawLine(colors.borderSubtle, Offset(x, size.height - 3.dp.toPx()), Offset(x + barWidth, size.height - 3.dp.toPx()),
                            3.dp.toPx())
                        return@forEachIndexed
                    }
                    val h = (v / top * size.height * grow.value).coerceAtLeast(if (v > 0f) 4.dp.toPx() else 0f)
                    val color = when {
                        bar.warning -> colors.accent
                        bar.highlight -> colors.primary
                        else -> colors.primary.copy(alpha = 0.35f)
                    }
                    drawRoundRect(color, Offset(x, size.height - h), Size(barWidth, h), CornerRadius(barWidth / 2.6f))
                }
                reference?.let { ref ->
                    val y = size.height - ref / top * size.height
                    drawLine(colors.textSecondary, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            bars.forEach { bar ->
                EyeText(bar.label, Modifier.weight(1f), style = typography.chip,
                    color = if (bar.highlight) colors.primary else colors.textSecondary, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}
