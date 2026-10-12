package com.zkyjss.eyeproject.feature.services

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.drawMascot

/*
 * 三项测试共用的报告排版：
 * 结论卡（等级徽标 + 一句话结论 + 建议）→ 关键指标（两列卡片，按是否正常着色）→ 需要关注 → 折叠的详细数据 → 一行说明。
 */

internal enum class MetricStatus { NORMAL, ATTENTION, UNKNOWN }

/** 一个关键指标：名称、数值、参考范围（短）、状态。 */
internal data class ReportMetric(val label: String, val value: String, val reference: String, val status: MetricStatus)

private fun EyeColors.toneFor(level: ScreeningRiskLevel): Pair<Color, Color> = when (level) {
    ScreeningRiskLevel.LOW -> success to successSoft
    ScreeningRiskLevel.MEDIUM -> warning to warningSoft
    ScreeningRiskLevel.HIGH -> danger to dangerSoft
    ScreeningRiskLevel.UNKNOWN -> textSecondary to neutralSoft
}

/** 结论卡：左侧等级徽标与结论，右侧小瞳（表情随等级变化），下方一句建议。 */
@Composable
internal fun ReportHero(level: ScreeningRiskLevel, badge: String, title: String, advice: String) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val (strong, soft) = colors.toneFor(level)
    Column(
        Modifier.fillMaxWidth().clip(EyeShapes.panel).background(soft).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EyeText(
                    badge, style = typography.chip, color = colors.onStatus,
                    modifier = Modifier.clip(EyeShapes.chip).background(strong).padding(horizontal = 12.dp, vertical = 6.dp)
                )
                EyeText(title, style = typography.title, color = colors.textPrimary)
            }
            Canvas(Modifier.size(72.dp)) {
                val s = size.minDimension
                drawMascot(
                    colors, Offset(s / 2, s * 0.58f), s * 0.82f,
                    smile = level == ScreeningRiskLevel.LOW || level == ScreeningRiskLevel.UNKNOWN,
                    lid = if (level == ScreeningRiskLevel.UNKNOWN) 0.85f else 0f,
                )
            }
        }
        EyeText(advice, style = typography.body, color = colors.textPrimary)
    }
}

/** 关键指标：两列卡片；异常项用琥珀色标出数值与参考。 */
@Composable
internal fun ReportMetricGrid(metrics: List<ReportMetric>) {
    if (metrics.isEmpty()) return
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EyeText("关键指标", style = typography.subtitle, color = colors.textPrimary)
        metrics.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { m ->
                    val accent = when (m.status) {
                        MetricStatus.NORMAL -> colors.success
                        MetricStatus.ATTENTION -> colors.warning
                        MetricStatus.UNKNOWN -> colors.textSecondary
                    }
                    Column(
                        Modifier.weight(1f).clip(EyeShapes.large).background(colors.panelBackground).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(8.dp).clip(EyeShapes.chip).background(accent))
                            EyeText(m.label, style = typography.bodySecondary, color = colors.textSecondary)
                        }
                        EyeText(m.value, style = typography.title, color = if (m.status == MetricStatus.ATTENTION) colors.warning else colors.textPrimary)
                        EyeText(m.reference, style = typography.bodySecondary, color = colors.textSecondary)
                    }
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

/** 要点列表（需要关注 / 无法评估的原因）：每条一行，前面一个色点。 */
@Composable
internal fun ReportFindings(title: String, items: List<String>, tone: Color) {
    if (items.isEmpty()) return
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(
        Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        EyeText(title, style = typography.subtitle, color = colors.textPrimary)
        items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.padding(top = 8.dp).size(8.dp).clip(EyeShapes.chip).background(tone))
                EyeText(item, Modifier.weight(1f), style = typography.body, color = colors.textPrimary)
            }
        }
    }
}

/** 页尾一行说明（唯一的小字）。 */
@Composable
internal fun ReportFootnote(text: String = "手机初筛结果仅供参考，不能代替医院检查。") {
    EyeText(text, style = LocalEyeTypography.current.bodySecondary, color = LocalEyeColors.current.textSecondary,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
}

/** 填写过的问卷（折叠区内）：问题 + 回答，按问卷顺序。 */
@Composable
internal fun ProfileAnswers(questions: List<Question>, fields: Map<String, String>) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    questions.filter { !fields[it.key].isNullOrBlank() }.forEach { q ->
        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            EyeText(q.title, style = typography.bodySecondary, color = colors.textSecondary)
            EyeText(fields[q.key].orEmpty(), style = typography.body, color = colors.textPrimary)
        }
    }
    Box(Modifier.height(1.dp))
}

// region 各病种的关键指标

internal fun dryEyeMetrics(report: DryEyeReport): List<ReportMetric> {
    fun status(ok: Boolean?) = when (ok) { true -> MetricStatus.NORMAL; false -> MetricStatus.ATTENTION; null -> MetricStatus.UNKNOWN }
    val rate = report.blinkRatePerMin
    val open = report.longestOpenSec
    val vmr = report.vmr
    val acc = report.accuracyOpen
    return listOf(
        ReportMetric("眨眼频率", rate?.let { "%.0f 次/分".format(it) } ?: "—", "正常 10–16 次/分",
            status(rate?.let { it in DryEyeProtocol.BLINK_RATE_MIN..DryEyeProtocol.BLINK_RATE_MAX })),
        ReportMetric("最长坚持睁眼", open?.let { "%.0f 秒".format(it) } ?: "—", "正常 ≥ 10 秒",
            status(open?.let { it >= DryEyeProtocol.OPEN_INTERVAL_MIN_SEC })),
        ReportMetric("视力保持度", vmr?.let { "%.0f%%".format(it * 100) } ?: "—", "越接近 100% 越好",
            status(vmr?.let { it >= DryEyeProtocol.VMR_THRESHOLD })),
        // 答对率没有统一的正常范围，只作为信息展示（灰色）
        ReportMetric("睁眼时答对率", acc?.let { "%.0f%%".format(it * 100) } ?: "—", "坚持睁眼时看 E 的表现", MetricStatus.UNKNOWN),
    )
}

internal fun presbyopiaMetrics(f: Map<String, Double?>): List<ReportMetric> {
    val amp = f["amplitude_d"]
    val min = f["hofstetter_min_d"]
    val near = f["near_point_cm"]
    val both = f["near_both_decimal"]
    val cycles = f["flipper_cycles_per_min"]
    return listOf(
        ReportMetric("调节能力", amp?.let { "%.1f D".format(it) } ?: "—", min?.let { "同龄最低约 %.1f D".format(it) } ?: "与同龄人比较",
            if (amp == null || min == null) MetricStatus.UNKNOWN else if (amp >= min) MetricStatus.NORMAL else MetricStatus.ATTENTION),
        ReportMetric("看清最近距离", near?.let { "%.0f cm".format(it) } ?: "—", "越近说明调节越好",
            if (near == null) MetricStatus.UNKNOWN else if (near <= 40.0) MetricStatus.NORMAL else MetricStatus.ATTENTION),
        ReportMetric("40 cm 双眼视力", both?.let { "%.1f".format(it) } ?: "—", "正常 ≥ 0.5",
            if (both == null) MetricStatus.UNKNOWN else if (both >= 0.5) MetricStatus.NORMAL else MetricStatus.ATTENTION),
        ReportMetric("远近切换", cycles?.let { "%.0f 次/分".format(it) } ?: "—", "参考 ≥ 3 次/分",
            if (cycles == null) MetricStatus.UNKNOWN else if (cycles >= 3) MetricStatus.NORMAL else MetricStatus.ATTENTION),
    )
}

internal fun strabismusMetrics(f: Map<String, Double?>): List<ReportMetric> {
    fun prism(key: String, limit: Double, label: String) = f[key]?.let { kotlin.math.abs(it) }.let { v ->
        ReportMetric(label, v?.let { "%.1f Δ".format(it) } ?: "—", "正常 < ${limit.toInt()} Δ",
            if (v == null) MetricStatus.UNKNOWN else if (v < limit) MetricStatus.NORMAL else MetricStatus.ATTENTION)
    }
    val bsi = f["bsi"]
    return listOf(
        prism("fixation_hde_prism", StrabismusProtocol.HIGH_H_PRISM, "水平偏斜"),
        prism("fixation_vde_prism", StrabismusProtocol.HIGH_V_PRISM, "垂直偏斜"),
        prism("cover_refix_median_prism", StrabismusProtocol.REFIX_PRISM, "遮盖后眼位移动"),
        ReportMetric("双眼协调", bsi?.let { "%.2f".format(it) } ?: "—", "正常 ≥ 0.7",
            if (bsi == null) MetricStatus.UNKNOWN else if (bsi >= StrabismusProtocol.BSI_SUSPECT) MetricStatus.NORMAL else MetricStatus.ATTENTION),
    )
}

// endregion
