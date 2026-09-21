package com.zkyjss.eyeproject.feature.fatigue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.continuous
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.model.MonitoringSample
import com.zkyjss.eyeproject.core.ui.component.EyeText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val MAX_POINTS = 100

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val ZONE: ZoneId = ZoneId.systemDefault()

/**
 * 基于 Vico 的折线图。
 *
 * 数据映射：横轴用采样序号（0..N-1，恒为整数、步长 1，规避 Vico 对 x 小数精度的限制），
 * 序号在轴 formatter 里再映射回真实时间（HH:mm）；纵轴即指标值。
 */
@Composable
fun EyeLineChart(
    samples: List<MonitoringSample>,
    valueOf: (MonitoringSample) -> Float,
    color: Color,
    title: String,
    valueText: String,
    modifier: Modifier = Modifier
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current

    val downsampled = remember(samples) { downsample(samples, MAX_POINTS) }
    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(downsampled) {
        if (downsampled.size >= 2) {
            modelProducer.runTransaction {
                lineSeries {
                    series(
                        x = downsampled.indices.toList(),
                        y = downsampled.map { sample ->
                            val v = valueOf(sample)
                            if (v.isNaN() || v.isInfinite()) 0.0 else v.toDouble()
                        }
                    )
                }
            }
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            EyeText(text = title, style = typography.subtitle, color = colors.textPrimary)
            EyeText(text = valueText, style = typography.bodySecondary, color = color)
        }
        Spacer(Modifier.height(4.dp))

        ProvideVicoTheme(
            rememberM3VicoTheme(
                lineColor = colors.borderSubtle,
                textColor = colors.textSecondary
            )
        ) {
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(
                        lineProvider = LineCartesianLayer.LineProvider.series(
                            LineCartesianLayer.rememberLine(
                                fill = LineCartesianLayer.LineFill.single(fill(color)),
                                stroke = LineCartesianLayer.LineStroke.continuous(
                                    thickness = 2.dp,
                                    cap = StrokeCap.Round
                                )
                            )
                        ),
                        pointSpacing = 3.dp
                    ),
                    startAxis = VerticalAxis.rememberStart(),
                    bottomAxis = HorizontalAxis.rememberBottom(
                        valueFormatter = CartesianValueFormatter { _, value, _ ->
                            val index = value.toInt().coerceIn(0, downsampled.lastIndex)
                            formatTime(downsampled[index].timestampMillis)
                        }
                    )
                ),
                modelProducer = modelProducer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
            )
        }
    }
}

/** 均匀抽取最多 [maxPoints] 个点，始终保留最后一个（最新）点。 */
private fun downsample(samples: List<MonitoringSample>, maxPoints: Int): List<MonitoringSample> {
    if (samples.size <= maxPoints) return samples
    val result = ArrayList<MonitoringSample>(maxPoints)
    val step = (samples.size - 1).toDouble() / (maxPoints - 1)
    var index = 0.0
    repeat(maxPoints - 1) {
        result.add(samples[index.toInt()])
        index += step
    }
    result.add(samples.last())
    return result
}

private fun formatTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZONE).format(TIME_FORMATTER)
