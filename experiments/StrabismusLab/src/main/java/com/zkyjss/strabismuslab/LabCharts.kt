package com.zkyjss.strabismuslab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.strabismuslab.CornealCodeV8Result
import com.zkyjss.eyeproject.strabismuslab.LabEmissionPhase
import com.zkyjss.eyeproject.strabismuslab.LabGlintScatterPoint
import com.zkyjss.eyeproject.strabismuslab.LabGlintSource
import com.zkyjss.eyeproject.strabismuslab.LabLiveState
import com.zkyjss.eyeproject.strabismuslab.LabWaveformPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 实时光学取景仪叠加层：绘出双眼虹膜外圈、可配置搜索半径（默认 0.80R）虚线框及候选亮点十字。 */
@Composable
fun ViewfinderOpticsOverlay(
    live: LabLiveState,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val sw = live.sourceWidth.toFloat()
        val sh = live.sourceHeight.toFloat()
        if (sw <= 0f || sh <= 0f) return@Canvas

        // PreviewView 默认 FILL_CENTER 居中裁切 + 前摄水平镜像映射
        val scale = max(size.width / sw, size.height / sh)
        val dx = (size.width - sw * scale) * 0.5f
        val dy = (size.height - sh * scale) * 0.5f
        fun mapPoint(x: Float, y: Float): Offset = Offset(size.width - (x * scale + dx), y * scale + dy)

        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)

        fun drawEye(
            center: com.zkyjss.eyeproject.core.model.Point2f?,
            diameterPx: Float,
            candidates: List<com.zkyjss.eyeproject.strabismuslab.LabCandidateSummary>,
            eyeColor: Color,
        ) {
            center ?: return
            if (diameterPx <= 0f) return
            val c = mapPoint(center.x, center.y)
            val r = diameterPx * 0.5f * scale
            // 完整虹膜边缘
            drawCircle(color = LabColors.hairlineStrong, radius = r, center = c, style = Stroke(width = 1.5f))
            // 0.80R 科研搜索半径虚线圈
            drawCircle(
                color = eyeColor.copy(alpha = 0.85f),
                radius = r * live.searchRadiusFraction,
                center = c,
                style = Stroke(width = 2f, pathEffect = dash),
            )
            // 候选亮点十字标记
            candidates.forEachIndexed { idx, cand ->
                val p = mapPoint(cand.x, cand.y)
                val isTop = idx == 0
                val markColor = if (isTop) LabColors.verified else LabColors.inkSecondary.copy(alpha = 0.7f)
                val arm = if (isTop) 9f else 5f
                drawLine(markColor, Offset(p.x - arm, p.y), Offset(p.x + arm, p.y), strokeWidth = if (isTop) 2.2f else 1.3f)
                drawLine(markColor, Offset(p.x, p.y - arm), Offset(p.x, p.y + arm), strokeWidth = if (isTop) 2.2f else 1.3f)
                if (isTop) {
                    drawCircle(markColor, radius = 4f, center = p, style = Stroke(width = 1.6f))
                }
            }
        }

        drawEye(live.leftIrisCenter, live.leftIrisDiameterPx, live.leftCandidates, LabColors.accent)
        drawEye(live.rightIrisCenter, live.rightIrisDiameterPx, live.rightCandidates, LabColors.rightEye)
    }
}

/** 双目虹膜归一化角膜映光靶图：一眼检验映光偏心分布、0.80R 搜索边界与 v0.8 锚点。 */
@Composable
fun BinocularGlintTargetMap(
    leftPoints: List<LabGlintScatterPoint>,
    rightPoints: List<LabGlintScatterPoint>,
    leftAnchor: Pair<Double?, Double?>,
    rightAnchor: Pair<Double?, Double?>,
    searchRadius: Float,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SingleIrisTargetPlot(
            title = "图像左眼 · L",
            points = leftPoints,
            anchor = leftAnchor,
            searchRadius = searchRadius,
            tint = LabColors.accent,
            modifier = Modifier.weight(1f),
        )
        SingleIrisTargetPlot(
            title = "图像右眼 · R",
            points = rightPoints,
            anchor = rightAnchor,
            searchRadius = searchRadius,
            tint = LabColors.rightEye,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SingleIrisTargetPlot(
    title: String,
    points: List<LabGlintScatterPoint>,
    anchor: Pair<Double?, Double?>,
    searchRadius: Float,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(LabColors.surfaceElevated, LabShapes.control)
            .border(1.dp, LabColors.hairline, LabShapes.control)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = LabType.caption, color = tint)
            Text("${points.size} pts", style = LabType.microMono)
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            val cx = size.width * 0.5f
            val cy = size.height * 0.5f
            val r = min(size.width, size.height) * 0.44f
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)

            // 虹膜外圈 (1.0 R)
            drawCircle(LabColors.surfaceSubtle, radius = r, center = Offset(cx, cy))
            drawCircle(LabColors.hairlineStrong, radius = r, center = Offset(cx, cy), style = Stroke(1.5f))
            // 搜索边界 (0.80 R)
            drawCircle(
                color = tint.copy(alpha = 0.55f),
                radius = r * searchRadius,
                center = Offset(cx, cy),
                style = Stroke(1.5f, pathEffect = dash),
            )
            // 十字坐标轴
            drawLine(LabColors.hairline, Offset(cx - r, cy), Offset(cx + r, cy), strokeWidth = 1f)
            drawLine(LabColors.hairline, Offset(cx, cy - r), Offset(cx, cy + r), strokeWidth = 1f)

            // 映光归一化散点
            for (pt in points) {
                val px = cx + pt.relX.coerceIn(-1f, 1f) * r
                val py = cy + pt.relY.coerceIn(-1f, 1f) * r
                val alpha = (0.35f + 0.65f * pt.confidence).coerceIn(0.3f, 1f)
                val dotColor = if (pt.source == LabGlintSource.OBSERVED) tint.copy(alpha = alpha)
                else LabColors.caution.copy(alpha = alpha * 0.8f)
                drawCircle(dotColor, radius = if (pt.source == LabGlintSource.OBSERVED) 3.4f else 2.5f, center = Offset(px, py))
            }

            // v0.8 锚点十字准星
            val ax = anchor.first
            val ay = anchor.second
            if (ax != null && ay != null) {
                val px = cx + ax.toFloat().coerceIn(-1f, 1f) * r
                val py = cy + ay.toFloat().coerceIn(-1f, 1f) * r
                drawLine(LabColors.verified, Offset(px - 8f, py), Offset(px + 8f, py), strokeWidth = 2.2f)
                drawLine(LabColors.verified, Offset(px, py - 8f), Offset(px, py + 8f), strokeWidth = 2.2f)
                drawCircle(LabColors.verified, radius = 5.5f, center = Offset(px, py), style = Stroke(1.5f))
            }
        }
        Text(
            text = "虚线 = ${(searchRadius * 100).toInt()}%R · 绿十字 = v0.8 锚点",
            style = LabType.microMono,
            color = LabColors.inkTertiary,
        )
    }
}

/** 采样亮斑信号 vs 延迟对齐参考光码波形图。 */
@Composable
fun CodedWaveformChart(
    waveform: List<LabWaveformPoint>,
    eyeColor: Color,
    modifier: Modifier = Modifier,
) {
    if (waveform.size < 4) {
        EmptyChartBox("当前眼区缺少可用于波形绘制的光码采样", modifier)
        return
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(LabColors.surfaceElevated, LabShapes.control)
            .border(1.dp, LabColors.hairline, LabShapes.control)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("时域亮斑信号 vs 延迟对齐参考码", style = LabType.caption)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendDot("实测信号", eyeColor)
                LegendDot("参考光码", LabColors.inkSecondary)
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
        ) {
            val tMin = waveform.first().tSec
            val tMax = max(tMin + 0.5, waveform.last().tSec)
            val maxSig = max(10.0, waveform.maxOf { it.signal })
            val padTop = 8f
            val padBottom = 10f
            val h = size.height - padTop - padBottom

            // 背景网格线
            for (g in 0..2) {
                val y = padTop + h * g / 2f
                drawLine(LabColors.hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }

            // 稳定亮灯背景浅色带
            val stepW = size.width / max(1, waveform.size - 1)
            waveform.forEachIndexed { i, pt ->
                if (pt.phase == LabEmissionPhase.ON) {
                    drawRect(
                        color = LabColors.verified.copy(alpha = 0.08f),
                        topLeft = Offset(i * stepW, padTop),
                        size = Size(stepW + 1f, h),
                    )
                }
            }

            // 参考光码方波（折算到 15%..85% 高度）
            val codePath = Path()
            val sigPath = Path()
            waveform.forEachIndexed { i, pt ->
                val x = ((pt.tSec - tMin) / (tMax - tMin)).toFloat().coerceIn(0f, 1f) * size.width
                val codeNorm = ((pt.alignedCode + 1.0) * 0.5).coerceIn(0.0, 1.0)
                val codeY = padTop + h * (1f - (0.15f + 0.70f * codeNorm.toFloat()))
                val sigNorm = (pt.signal / maxSig).coerceIn(0.0, 1.0)
                val sigY = padTop + h * (1f - sigNorm.toFloat())
                if (i == 0) {
                    codePath.moveTo(x, codeY)
                    sigPath.moveTo(x, sigY)
                } else {
                    codePath.lineTo(x, codeY)
                    sigPath.lineTo(x, sigY)
                }
            }
            drawPath(codePath, color = LabColors.inkSecondary.copy(alpha = 0.65f), style = Stroke(width = 1.6f))
            drawPath(sigPath, color = eyeColor, style = Stroke(width = 2.4f, cap = StrokeCap.Round))
        }
    }
}

/** 全部 63 个错位码零分布柱状图 + 真码相关性高亮对比。 */
@Composable
fun ShiftNullBarChart(
    result: CornealCodeV8Result,
    eyeColor: Color,
    modifier: Modifier = Modifier,
) {
    val nulls = result.shiftNullCorrelations
    val held = result.heldOutCorrelation
    if (nulls.isEmpty() || held == null) {
        EmptyChartBox("未形成留出段与 63 错位码分布（请确认编码模式并完成至少 8 秒采集）", modifier)
        return
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(LabColors.surfaceElevated, LabShapes.control)
            .border(1.dp, LabColors.hairline, LabShapes.control)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("0 移位真码 vs 63 错位码 |r| 分布", style = LabType.caption)
            Text(
                text = "margin ${if ((result.codeMargin ?: 0.0) >= 0) "+" else ""}${fmt2Local(result.codeMargin ?: 0.0)}",
                style = LabType.microMono,
                color = if (result.codeVerified) LabColors.verified else LabColors.caution,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp),
        ) {
            val totalBars = nulls.size + 1 // 第 0 根是真码，后 63 根是错位码
            val barW = (size.width / totalBars).coerceAtLeast(2f)
            val padTop = 8f
            val h = size.height - padTop - 4f

            // 阈值参考线：r = 0.30 与 maxWrong + 0.15
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
            val y030 = padTop + h * (1f - 0.30f)
            drawLine(LabColors.hairlineStrong, Offset(0f, y030), Offset(size.width, y030), strokeWidth = 1f, pathEffect = dash)
            val req = ((result.maxWrongCodeCorrelation ?: 0.0) + 0.15).coerceIn(0.0, 1.0).toFloat()
            val yReq = padTop + h * (1f - req)
            drawLine(LabColors.caution.copy(alpha = 0.7f), Offset(0f, yReq), Offset(size.width, yReq), strokeWidth = 1.4f, pathEffect = dash)

            // 真码柱（首柱加宽高亮）
            val trueH = held.coerceIn(0.0, 1.0).toFloat() * h
            drawRoundRect(
                color = if (result.codeVerified) LabColors.verified else eyeColor,
                topLeft = Offset(0f, padTop + h - trueH),
                size = Size(max(barW * 1.4f, 6f), trueH),
                cornerRadius = CornerRadius(3f, 3f),
            )
            // 63 错位码 |r|
            nulls.forEachIndexed { idx, v ->
                val mag = abs(v).coerceIn(0.0, 1.0).toFloat() * h
                val x = (idx + 1) * barW + 2f
                drawRect(
                    color = LabColors.inkTertiary.copy(alpha = 0.65f),
                    topLeft = Offset(x, padTop + h - mag),
                    size = Size((barW - 1.2f).coerceAtLeast(1.5f), mag),
                )
            }
        }
    }
}

data class ChartSeries(
    val name: String,
    val color: Color,
    val points: List<Pair<Double, Double>>,
)

/** 通用多曲线科研时序图（HDE/VDE、距离/头姿、遮盖对比度等）。 */
@Composable
fun TimeSeriesLineChart(
    title: String,
    unit: String,
    series: List<ChartSeries>,
    referenceLines: List<Pair<Double, Color>> = emptyList(),
    eventMarkersSec: List<Double> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val allPoints = series.flatMap { it.points }
    if (allPoints.size < 2) {
        EmptyChartBox("$title：有效时序采样不足", modifier)
        return
    }
    val xMin = allPoints.minOf { it.first }
    val xMax = max(xMin + 0.5, allPoints.maxOf { it.first })
    val rawYMin = min(allPoints.minOf { it.second }, referenceLines.minOfOrNull { it.first } ?: Double.POSITIVE_INFINITY)
    val rawYMax = max(allPoints.maxOf { it.second }, referenceLines.maxOfOrNull { it.first } ?: Double.NEGATIVE_INFINITY)
    val pad = max(1.0, (rawYMax - rawYMin) * 0.12)
    val yMin = rawYMin - pad
    val yMax = rawYMax + pad

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(LabColors.surfaceElevated, LabShapes.control)
            .border(1.dp, LabColors.hairline, LabShapes.control)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = LabType.caption)
            Text(
                text = "${fmt1Local(rawYMin)} .. ${fmt1Local(rawYMax)} $unit",
                style = LabType.microMono,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            series.forEach { s -> LegendDot(s.name, s.color) }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(145.dp),
        ) {
            val h = size.height - 12f
            val top = 6f
            fun mapX(x: Double) = ((x - xMin) / (xMax - xMin)).toFloat().coerceIn(0f, 1f) * size.width
            fun mapY(y: Double) = top + h * (1f - ((y - yMin) / (yMax - yMin)).toFloat().coerceIn(0f, 1f))

            for (g in 0..2) {
                val y = top + h * g / 2f
                drawLine(LabColors.hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }

            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
            referenceLines.forEach { (valY, color) ->
                val y = mapY(valY)
                drawLine(color.copy(alpha = 0.65f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.4f, pathEffect = dash)
            }
            eventMarkersSec.forEach { t ->
                val x = mapX(t)
                drawLine(LabColors.verified.copy(alpha = 0.65f), Offset(x, top), Offset(x, top + h), strokeWidth = 1.6f, pathEffect = dash)
            }

            series.forEach { s ->
                if (s.points.size < 2) return@forEach
                val path = Path()
                s.points.forEachIndexed { idx, (tx, ty) ->
                    val px = mapX(tx)
                    val py = mapY(ty)
                    if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(path, color = s.color, style = Stroke(width = 2.2f, cap = StrokeCap.Round))
            }
        }
    }
}

/** 拒判原因分布水平条形图，用于 Debug 定位丢帧与噪声来源。 */
@Composable
fun RejectionBreakdownCard(
    counts: Map<String, Int>,
    totalFrames: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(LabColors.surfaceElevated, LabShapes.control)
            .border(1.dp, LabColors.hairline, LabShapes.control)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("帧级质量拒判分布", style = LabType.caption)
        if (counts.isEmpty()) {
            Text("本次采集无质量拒判帧", style = LabType.body, color = LabColors.verified)
            return@Column
        }
        val denom = max(1, totalFrames)
        counts.entries.sortedByDescending { it.value }.forEach { (reason, count) ->
            val ratio = (count.toFloat() / denom).coerceIn(0f, 1f)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(reason, style = LabType.body)
                    Text("$count (${(ratio * 100).toInt()}%)", style = LabType.microMono, color = LabColors.danger)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(LabColors.surfaceSubtle, LabShapes.pill),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(ratio)
                            .height(6.dp)
                            .background(LabColors.danger, LabShapes.pill),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyChartBox(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(LabColors.surfaceElevated, LabShapes.control)
            .border(1.dp, LabColors.hairline, LabShapes.control)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, style = LabType.caption, color = LabColors.inkTertiary)
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, LabShapes.pill),
        )
        Spacer(Modifier.width(5.dp))
        Text(label, style = LabType.microMono)
    }
}

private fun fmt1Local(v: Double): String = String.format("%.1f", v)
private fun fmt2Local(v: Double): String = String.format("%.2f", v)
