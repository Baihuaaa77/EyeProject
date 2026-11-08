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
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.padding
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
 * 柱状图（用眼趋势）：纯色圆头柱子（[tint]，默认主色），今天实色、其余变淡；不画底槽、不发光。
 * 只有今天的读数写在柱顶，超出参考的天数柱顶用琥珀色；横向虚线为刻度，[reference] 画一条类别色虚线参考线。
 * 出现时柱子从底部长出。苹果端 BarChartView 同一画法。
 */
@Composable
fun EyeBarChart(
    bars: List<EyeBar>,
    modifier: Modifier = Modifier,
    reference: Float? = null,
    maxValue: Float? = null,
    chartHeight: Dp = 140.dp,
    description: String = "",
    tint: Color? = null,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val color = tint ?: colors.primary
    val grow = remember { Animatable(0f) }
    LaunchedEffect(bars) { grow.snapTo(0f); grow.animateTo(1f, tween(EyeMotion.LONG, easing = EyeMotion.Standard)) }
    val top = maxOf(maxValue ?: 0f, bars.maxOfOrNull { it.value ?: 0f } ?: 0f, (reference ?: 0f) * 1.15f).coerceAtLeast(1f)
    Column(modifier.fillMaxWidth().semantics { if (description.isNotEmpty()) contentDescription = description }) {
        Row(Modifier.fillMaxWidth()) {
            bars.forEach { bar ->
                EyeText(if (bar.highlight || bar.warning) bar.text ?: "" else "", Modifier.weight(1f), style = typography.caption,
                    color = when { bar.warning -> colors.warning; else -> colors.textPrimary },
                    textAlign = TextAlign.Center, maxLines = 1)
            }
        }
        Box(Modifier.fillMaxWidth().height(chartHeight)) {
            Canvas(Modifier.fillMaxWidth().height(chartHeight)) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                listOf(0f, 0.5f).forEach { f ->
                    drawLine(colors.borderSubtle, Offset(0f, size.height * f), Offset(size.width, size.height * f), 1.dp.toPx(), pathEffect = dash)
                }
                drawLine(colors.borderSubtle, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                val slot = size.width / bars.size.coerceAtLeast(1)
                val barWidth = (slot * 0.5f).coerceIn(10.dp.toPx(), 24.dp.toPx())
                val radius = CornerRadius(6.dp.toPx().coerceAtMost(barWidth / 2))
                bars.forEachIndexed { i, bar ->
                    val v = bar.value ?: return@forEachIndexed
                    val x = slot * i + (slot - barWidth) / 2
                    val h = (v / top * size.height * grow.value).coerceAtLeast(if (v > 0f) radius.x * 2 else 0f)
                    drawRoundRect(color, Offset(x, size.height - h), Size(barWidth, h), radius, alpha = if (bar.highlight) 1f else 0.32f)
                }
                reference?.let { ref ->
                    val y = size.height - ref / top * size.height
                    drawLine(color.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(), pathEffect = dash)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            bars.forEach { bar ->
                EyeText(bar.label, Modifier.weight(1f), style = typography.caption,
                    color = if (bar.highlight) colors.textPrimary else colors.textTertiary, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}
