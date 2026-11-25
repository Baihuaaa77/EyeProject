package com.zkyjss.strabismuslab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.strabismuslab.CornealCodeV8Result
import com.zkyjss.eyeproject.strabismuslab.LabFrameLog
import com.zkyjss.eyeproject.strabismuslab.LabGlintSource
import com.zkyjss.eyeproject.strabismuslab.LabSessionReport
import com.zkyjss.eyeproject.strabismuslab.LabStage

/**
 * 测试后全景数据可视化与 Debug 工作台：
 * Tab 0 总览与眼位 | Tab 1 光码验证 (v0.8) | Tab 2 时序波形 (Debug) | Tab 3 逐帧与导出
 */
@Composable
fun LabDashboardScreen(
    report: LabSessionReport,
    onBack: () -> Unit,
    onExport: (LabExportFormat) -> Unit,
) {
    BackHandler(onBack = onBack)
    var selectedTab by remember { mutableIntStateOf(0) }
    var showExportDialog by remember { mutableStateOf(false) }

    val tabs = listOf("总览与眼位", "光码 v0.8", "时序 Debug", "逐帧与导出")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LabColors.canvas)
            .safeDrawingPadding(),
    ) {
        // 顶部工作台标题栏 + 一键导出
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    LabVectorIcon(LabGlyph.BACK, tint = LabColors.inkPrimary, size = 20.dp)
                }
                Column {
                    Text(report.config.subjectId, style = LabType.section)
                    Text(
                        "${report.algorithmVersion} · ${report.sourceWidth}×${report.sourceHeight} @ ${String.format("%.0f", report.medianFps)}fps",
                        style = LabType.microMono,
                    )
                }
            }
            Button(
                onClick = { showExportDialog = true },
                shape = LabShapes.pill,
                colors = ButtonDefaults.buttonColors(containerColor = LabColors.accent, contentColor = LabColors.onAccent),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                LabVectorIcon(LabGlyph.SHARE, tint = LabColors.onAccent, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text("导出", style = LabType.caption, color = LabColors.onAccent)
            }
        }

        // 精简分段 Tab 栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .background(LabColors.surface, LabShapes.control)
                .border(1.dp, LabColors.hairline, LabShapes.control)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEachIndexed { idx, label ->
                val active = selectedTab == idx
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(LabShapes.control)
                        .background(if (active) LabColors.surfaceElevated else Color.Transparent)
                        .border(1.dp, if (active) LabColors.accent.copy(alpha = 0.6f) else Color.Transparent, LabShapes.control)
                        .clickable { selectedTab = idx }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = LabType.caption,
                        color = if (active) LabColors.accent else LabColors.inkSecondary,
                    )
                }
            }
        }

        when (selectedTab) {
            0 -> OverviewTab(report)
            1 -> CodedVerificationTab(report)
            2 -> TimeSeriesDebugTab(report)
            else -> FrameInspectorTab(report, onExport)
        }
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            containerColor = LabColors.surface,
            title = { Text("导出科研数据包", style = LabType.section) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("包含全部逐帧光学坐标、v0.8 光码检验与协议配置，可直接用于 Python 离线复算。", style = LabType.caption)
                    LabExportFormat.entries.forEach { fmt ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(LabShapes.control)
                                .background(LabColors.surfaceElevated)
                                .border(1.dp, LabColors.hairline, LabShapes.control)
                                .clickable {
                                    showExportDialog = false
                                    onExport(fmt)
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Text(fmt.label, style = LabType.body, color = LabColors.inkPrimary)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("关闭", color = LabColors.inkSecondary)
                }
            },
        )
    }
}

@Composable
private fun OverviewTab(report: LabSessionReport) {
    val gradeColor = riskColor(report.riskGrade)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 结论摘要卡
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(LabColors.surface, LabShapes.card)
                .border(1.dp, gradeColor.copy(alpha = 0.55f), LabShapes.card)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = report.riskGrade.label,
                    style = LabType.caption,
                    color = gradeColor,
                    modifier = Modifier
                        .background(gradeColor.copy(alpha = 0.16f), LabShapes.pill)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Text(
                    text = "证据置信 ${(report.confidenceScore * 100).toInt()}%",
                    style = LabType.microMono,
                    color = LabColors.inkSecondary,
                )
            }
            Text(report.headline, style = LabType.section)
            if (report.config.clinicalNote.isNotBlank()) {
                Text("临床 PACT 备注：${report.config.clinicalNote}", style = LabType.microMono, color = LabColors.accent)
            }
            report.summaryNotes.forEach { note ->
                Text("· $note", style = LabType.caption, color = LabColors.inkSecondary)
            }
        }

        // 核心量化指标 2×2（优先展示 3D 球面光学主指标，保留线性 175.5 消融对照）
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val nearH = report.nearMetrics?.hde3dPrism ?: report.nearMetrics?.hdePrism
            val nearV = report.nearMetrics?.vde3dPrism ?: report.nearMetrics?.vdePrism
            MetricTile(
                title = "看近 3D HDE",
                primary = nearH?.let { String.format("%+.1fΔ", it) } ?: "—",
                sub = report.nearMetrics?.let {
                    "V ${nearV?.let { v -> String.format("%+.1fΔ", v) } ?: "—"} · 线性 ${it.hdePrism?.let { h -> String.format("%+.1f", h) } ?: "—"}"
                } ?: "未测",
                accent = LabColors.accent,
                modifier = Modifier.weight(1f),
            )
            val farH = report.farMetrics?.hde3dPrism ?: report.farMetrics?.hdePrism
            val farV = report.farMetrics?.vde3dPrism ?: report.farMetrics?.vdePrism
            MetricTile(
                title = "看远 3D HDE",
                primary = farH?.let { String.format("%+.1fΔ", it) } ?: "—",
                sub = report.farMetrics?.let {
                    "V ${farV?.let { v -> String.format("%+.1fΔ", v) } ?: "—"} · ${it.validBinocularFrames} 帧"
                } ?: "未测",
                accent = LabColors.rightEye,
                modifier = Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                title = "遮盖 显斜/隐斜",
                primary = (report.coverPhoriaMedianPrism ?: report.coverTropiaMedianPrism)?.let { String.format("%.1fΔ", it) } ?: "—",
                sub = "对侧 ${report.coverTropiaMedianPrism?.let { String.format("%.1fΔ", it) } ?: "—"} · 噪声 ${report.coverNoiseFloorPrism?.let { String.format("%.1fΔ", it) } ?: "—"}",
                accent = LabColors.verified,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                title = "追踪 BSI (400ms)",
                primary = report.pursuitMetrics?.bsi?.let { String.format("%.2f", it) } ?: "—",
                sub = report.pursuitMetrics?.let {
                    "rH ${it.rHorizontal?.let { r -> String.format("%.2f", r) } ?: "—"} · rV ${it.rVertical?.let { r -> String.format("%.2f", r) } ?: "—"}"
                } ?: "未测",
                accent = LabColors.caution,
                modifier = Modifier.weight(1f),
            )
        }

        // 双目虹膜角膜映光靶图
        LabCard("双目虹膜归一化角膜映光靶图") {
            BinocularGlintTargetMap(
                leftPoints = report.leftScatter,
                rightPoints = report.rightScatter,
                leftAnchor = report.nearLeftCode?.referenceRelX to report.nearLeftCode?.referenceRelY,
                rightAnchor = report.nearRightCode?.referenceRelX to report.nearRightCode?.referenceRelY,
                searchRadius = report.config.searchRadiusFraction,
            )
        }

        if (report.coverRefixations.isNotEmpty()) {
            LabCard("去遮盖再注视明细") {
                report.coverRefixations.forEach { ev ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(LabColors.surfaceElevated, LabShapes.control)
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("${ev.eye} · ${ev.label} (${ev.source})", style = LabType.caption, color = LabColors.inkPrimary)
                        Text(
                            String.format("%.1fΔ · %.0f°/s", ev.amplitudePrism, ev.speedDegPerSec),
                            style = LabType.microMono,
                            color = LabColors.accent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CodedVerificationTab(report: LabSessionReport) {
    var stageChoice by remember { mutableIntStateOf(0) } // 0 = 看近, 1 = 看远
    var eyeChoice by remember { mutableIntStateOf(0) }   // 0 = 左眼, 1 = 右眼
    val activeResult: CornealCodeV8Result? = when (stageChoice) {
        0 -> if (eyeChoice == 0) report.nearLeftCode else report.nearRightCode
        else -> if (eyeChoice == 0) report.farLeftCode else report.farRightCode
    }
    val eyeColor = if (eyeChoice == 0) LabColors.accent else LabColors.rightEye

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleSegment("看近阶段", stageChoice == 0, Modifier.weight(1f)) { stageChoice = 0 }
            ToggleSegment("看远阶段", stageChoice == 1, Modifier.weight(1f)) { stageChoice = 1 }
            ToggleSegment("左眼 L", eyeChoice == 0, Modifier.weight(1f)) { eyeChoice = 0 }
            ToggleSegment("右眼 R", eyeChoice == 1, Modifier.weight(1f)) { eyeChoice = 1 }
        }

        if (activeResult == null) {
            LabCard("光码解调结果") {
                Text("当前所选阶段/眼别没有光学采集样本。", style = LabType.body, color = LabColors.inkTertiary)
            }
            return
        }

        val statusColor = if (activeResult.codeVerified) LabColors.verified else LabColors.caution
        LabCard("v0.8 光码独立校验摘要") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = if (activeResult.codeVerified) "光码验证通过（独立留出显著）" else "未通过光码显著性门槛",
                    style = LabType.section,
                    color = statusColor,
                )
                Text(
                    text = "lag ${activeResult.lagMs?.let { String.format("%.0f ms", it) } ?: "—"}",
                    style = LabType.microMono,
                    color = LabColors.accent,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("训练 r", activeResult.trainCorrelation?.let { String.format("%.3f", it) } ?: "—", "门槛 ≥0.35", eyeColor, Modifier.weight(1f))
                MetricTile("留出 r", activeResult.heldOutCorrelation?.let { String.format("%.3f", it) } ?: "—", "门槛 ≥0.30", statusColor, Modifier.weight(1f))
                MetricTile("最大错码 |r|", activeResult.maxWrongCodeCorrelation?.let { String.format("%.3f", it) } ?: "—", "63 错码对照", LabColors.inkSecondary, Modifier.weight(1f))
            }
            Text(
                text = "观测 ${activeResult.observedFrames} · 弱观测 ${activeResult.weakFrames} · 短时预测 ${activeResult.predictedFrames} · 无观测 ${activeResult.unavailableFrames}（中位质量 ${String.format("%.2f", activeResult.medianConfidence)}）",
                style = LabType.microMono,
            )
        }

        CodedWaveformChart(waveform = activeResult.waveform, eyeColor = eyeColor)
        ShiftNullBarChart(result = activeResult, eyeColor = eyeColor)

        activeResult.emissionAudit?.let { audit ->
            LabCard("亮灭时序发射审计（EmissionAudit）") {
                AuditRow("稳定亮灯 / 灭灯 / 低电平", "${audit.stableOnFrames} / ${audit.stableOffFrames} / ${audit.stableDimFrames}")
                AuditRow("切换曝光混合 / 未知帧", "${audit.transitionFrames} / ${audit.unknownFrames}")
                AuditRow("门控前独立灭灯检出", "${audit.independentOffMeasurements} 帧")
                AuditRow("时序抑制数 / 最终灭灯检出", "${audit.suppressedMeasurements} / ${audit.finalOffMeasurements}")
            }
        }

        activeResult.opticalBudget?.let { opt ->
            LabCard("近轴角膜凸面镜斑径预算") {
                AuditRow("中位测距 / 虹膜像素", "${String.format("%.1f cm", opt.distanceCm)} / ${String.format("%.1f px", opt.irisDiameterPx)}")
                AuditRow("预计斑径（±0.6mm 曲率敏感带）", "${String.format("%.2f px", opt.expectedDiameterPx)}（${String.format("%.2f", opt.expectedMinPx)}–${String.format("%.2f", opt.expectedMaxPx)}）")
                AuditRow("实测中位斑径 / 实测预计比", "${opt.observedMedianDiameterPx?.let { String.format("%.2f px", it) } ?: "—"} / ${opt.observedToExpectedRatio?.let { String.format("%.2f×", it) } ?: "—"}")
            }
        }
    }
}

@Composable
private fun TimeSeriesDebugTab(report: LabSessionReport) {
    val frames = report.frames
    val hdeSeries = remember(frames) {
        frames.mapNotNull { f -> f.hdePrism?.let { f.elapsedSec to it } }
    }
    val vdeSeries = remember(frames) {
        frames.mapNotNull { f -> f.vdePrism?.let { f.elapsedSec to it } }
    }
    val distSeries = remember(frames) {
        frames.filter { it.distanceCm > 0f }.map { it.elapsedSec to it.distanceCm.toDouble() }
    }
    val yawSeries = remember(frames) {
        frames.mapNotNull { f -> f.yawDeg?.let { f.elapsedSec to it.toDouble() } }
    }
    val pitchSeries = remember(frames) {
        frames.mapNotNull { f -> f.pitchDeg?.let { f.elapsedSec to it.toDouble() } }
    }
    val leftVisSeries = remember(frames) {
        frames.mapNotNull { f -> f.leftVisibilityContrast?.let { f.elapsedSec to it.toDouble() } }
    }
    val rightVisSeries = remember(frames) {
        frames.mapNotNull { f -> f.rightVisibilityContrast?.let { f.elapsedSec to it.toDouble() } }
    }
    val coverMarkers = remember(report.coverRefixations) {
        report.coverRefixations.map { it.uncoveredAtSec }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TimeSeriesLineChart(
            title = "双目眼位偏斜 HDE / VDE 时序（绿虚线 = 去遮盖时刻）",
            unit = "Δ",
            series = listOf(
                ChartSeries("水平 HDE", LabColors.accent, hdeSeries),
                ChartSeries("垂直 VDE", LabColors.rightEye, vdeSeries),
            ),
            referenceLines = listOf(10.0 to LabColors.danger, -10.0 to LabColors.danger, 0.0 to LabColors.hairlineStrong),
            eventMarkersSec = coverMarkers,
        )

        TimeSeriesLineChart(
            title = "测距稳定性轨迹（参考 33 ± 5 cm）",
            unit = "cm",
            series = listOf(ChartSeries("虹膜测距", LabColors.verified, distSeries)),
            referenceLines = listOf(28.0 to LabColors.caution, 33.0 to LabColors.accent, 38.0 to LabColors.caution),
        )

        TimeSeriesLineChart(
            title = "头姿欧拉角轨迹（Yaw / Pitch）",
            unit = "°",
            series = listOf(
                ChartSeries("Yaw", LabColors.accent, yawSeries),
                ChartSeries("Pitch", LabColors.rightEye, pitchSeries),
            ),
            referenceLines = listOf(0.0 to LabColors.hairlineStrong),
        )

        TimeSeriesLineChart(
            title = "左右眼像素可见性对比度（遮盖自动识别信号）",
            unit = "gray",
            series = listOf(
                ChartSeries("左眼对比度", LabColors.accent, leftVisSeries),
                ChartSeries("右眼对比度", LabColors.rightEye, rightVisSeries),
            ),
            eventMarkersSec = coverMarkers,
        )

        RejectionBreakdownCard(counts = report.rejectionCounts, totalFrames = report.totalFrames)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FrameInspectorTab(
    report: LabSessionReport,
    onExport: (LabExportFormat) -> Unit,
) {
    var filterMode by remember { mutableIntStateOf(0) } // 0=全部 1=仅有效测量 2=仅拒判/无观测
    val filtered = remember(report.frames, filterMode) {
        when (filterMode) {
            1 -> report.frames.filter { it.leftGlintSource.measured || it.rightGlintSource.measured }
            2 -> report.frames.filter { !it.valid || (!it.leftGlintSource.measured && !it.rightGlintSource.measured) }
            else -> report.frames
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            LabCard("一键科研数据导出") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabExportFormat.entries.forEach { fmt ->
                        Button(
                            onClick = { onExport(fmt) },
                            shape = LabShapes.control,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (fmt == LabExportFormat.BUNDLE_ZIP) LabColors.accent else LabColors.surfaceElevated,
                                contentColor = if (fmt == LabExportFormat.BUNDLE_ZIP) LabColors.onAccent else LabColors.inkPrimary,
                            ),
                        ) {
                            Text(fmt.label, style = LabType.caption, color = if (fmt == LabExportFormat.BUNDLE_ZIP) LabColors.onAccent else LabColors.inkPrimary)
                        }
                    }
                }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleSegment("全部 (${report.frames.size})", filterMode == 0, Modifier.weight(1f)) { filterMode = 0 }
                ToggleSegment("有效测点", filterMode == 1, Modifier.weight(1f)) { filterMode = 1 }
                ToggleSegment("拒判/缺失", filterMode == 2, Modifier.weight(1f)) { filterMode = 2 }
            }
        }
        items(filtered.take(250), key = { it.index }) { f ->
            FrameDebugRow(f)
        }
        if (filtered.size > 250) {
            item {
                Text(
                    "界面仅预览前 250 帧（共 ${filtered.size} 帧），请使用上方导出完整 JSONL / CSV。",
                    style = LabType.caption,
                    color = LabColors.inkTertiary,
                )
            }
        }
    }
}

@Composable
private fun FrameDebugRow(f: LabFrameLog) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(LabColors.surface, LabShapes.control)
            .border(1.dp, if (f.valid) LabColors.hairline else LabColors.danger.copy(alpha = 0.45f), LabShapes.control)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("#${f.index} · ${String.format("%.2fs", f.elapsedSec)} · ${f.stage.title}", style = LabType.microMono, color = LabColors.inkPrimary)
            Text(
                text = f.hdePrism?.let { String.format("HDE %+.1fΔ", it) } ?: (f.rejectionReason ?: f.leftEmissionPhase.label),
                style = LabType.microMono,
                color = if (f.hdePrism != null) LabColors.accent else LabColors.inkSecondary,
            )
        }
        Text(
            text = String.format(
                "d=%.1fcm · L:%s(%.2f) R:%s(%.2f) · 相位:%s · 候选:%d/%d",
                f.distanceCm,
                f.leftGlintSource.label,
                f.leftGlintConfidence ?: 0f,
                f.rightGlintSource.label,
                f.rightGlintConfidence ?: 0f,
                f.leftEmissionPhase.label,
                f.leftCandidateCount,
                f.rightCandidateCount,
            ),
            style = LabType.microMono,
            color = LabColors.inkTertiary,
        )
    }
}

@Composable
private fun MetricTile(
    title: String,
    primary: String,
    sub: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(LabColors.surface, LabShapes.card)
            .border(1.dp, LabColors.hairline, LabShapes.card)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = LabType.caption)
        Text(primary, style = LabType.metricHero, color = accent)
        Text(sub, style = LabType.microMono, color = LabColors.inkSecondary)
    }
}

@Composable
private fun ToggleSegment(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(LabShapes.control)
            .background(if (selected) LabColors.accentSoft else LabColors.surface)
            .border(1.dp, if (selected) LabColors.accent else LabColors.hairline, LabShapes.control)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = LabType.caption, color = if (selected) LabColors.accent else LabColors.inkSecondary)
    }
}

@Composable
private fun AuditRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = LabType.caption)
        Text(value, style = LabType.microMono, color = LabColors.inkPrimary)
    }
}
