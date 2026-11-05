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
import androidx.compose.ui.graphics.Brush
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
 * 光流柱状图（用眼趋势）：每根柱子是细胶囊，背后一条暗色轨道，柱身为光流渐变（主蓝 → 青蓝）；今天满亮（深色带泛光），其余稍暗；
 * 超出参考线的天数柱顶数值用琥珀色标出；
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
                    color = when { bar.warning -> colors.warning; bar.highlight -> colors.textPrimary; else -> colors.textTertiary },
                    textAlign = TextAlign.Center, maxLines = 1)
            }
        }
        Box(Modifier.fillMaxWidth().height(chartHeight)) {
            Canvas(Modifier.fillMaxWidth().height(chartHeight)) {
                val slot = size.width / bars.size.coerceAtLeast(1)
                val barWidth = (slot * 0.3f).coerceIn(6.dp.toPx(), 12.dp.toPx())
                val radius = CornerRadius(barWidth / 2)
                bars.forEachIndexed { i, bar ->
                    val x = slot * i + (slot - barWidth) / 2
                    // 每根柱子背后一条满高的暗色胶囊轨道
                    drawRoundRect(colors.surfaceVariant, Offset(x, 0f), Size(barWidth, size.height), radius)
                    val v = bar.value ?: return@forEachIndexed
                    val h = (v / top * size.height * grow.value).coerceAtLeast(if (v > 0f) barWidth else 0f)
                    val tl = Offset(x, size.height - h)
                    // 光流：底部主蓝 → 顶部青蓝；今天满亮（深色再叠同色泛光），其余稍暗
                    val brush = Brush.verticalGradient(listOf(colors.flowEnd, colors.flowStart), tl.y, size.height)
                    if (bar.highlight && colors.hasGlow) for ((g, a) in listOf(7.dp to 0.10f, 3.5.dp to 0.18f)) {
                        val px = g.toPx()
                        drawRoundRect(brush, Offset(tl.x - px, tl.y - px), Size(barWidth + px * 2, h + px * 2), CornerRadius(radius.x + px), alpha = a)
                    }
                    drawRoundRect(brush, tl, Size(barWidth, h), radius, alpha = if (bar.highlight) 1f else 0.6f)
                }
                reference?.let { ref ->
                    val y = size.height - ref / top * size.height
                    drawLine(colors.textTertiary, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            bars.forEach { bar ->
                EyeText(bar.label, Modifier.weight(1f), style = typography.chip,
                    color = if (bar.highlight) colors.textPrimary else colors.textTertiary, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}
