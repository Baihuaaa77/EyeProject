package com.zkyjss.eyeproject.feature.mine

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.data.record.RecordExportFormat
import com.zkyjss.eyeproject.core.ui.component.AiExplanationCard
import com.zkyjss.eyeproject.core.ui.component.EyeSubPage
import com.zkyjss.eyeproject.core.ui.component.MascotMood
import com.zkyjss.eyeproject.core.ui.component.XiaoTong
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeStatusChip
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.StatusTone
import com.zkyjss.eyeproject.core.ui.component.rememberEyeHaptics
import com.zkyjss.eyeproject.feature.services.LocalRecordEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「我的 → 测试记录」完整页面：与苹果端同一份列表（共用 LocalRecordCatalog）。
 * 每行为测试名、时间 · 成员与结论徽标；点开看当时的建议、要点、AI 解读与明细，并可导出报告或原始数据。
 */
@Composable
fun RecordsScreen(onBack: () -> Unit, viewModel: RecordsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val records by viewModel.localRecords.collectAsStateWithLifecycle()
    val haptics = rememberEyeHaptics()
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    val recordExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val file = exporting
        if (uri != null && file != null) viewModel.exportRecord(file, uri) { ok ->
            if (ok) haptics.success() else haptics.failure()
            Toast.makeText(context, if (ok) "已导出测试记录" else "导出失败，请换个位置再试", Toast.LENGTH_SHORT).show()
        }
    }
    val reportExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val file = exporting
        if (uri != null && file != null) viewModel.exportRecord(file, uri, RecordExportFormat.TEXT) { ok ->
            if (ok) haptics.success() else haptics.failure()
            Toast.makeText(context, if (ok) "已导出文字报告" else "导出失败，请换个位置再试", Toast.LENGTH_SHORT).show()
        }
    }
    RecordsContent(records, onBack,
        onExport = { exporting = it.fileName; recordExport.launch(it.fileName) },
        onExportReport = { exporting = it.fileName; reportExport.launch(it.fileName.removeSuffix(".json") + "_报告.txt") },
        aiCard = { record ->
            val card by remember(record.fileName) { viewModel.explanation(record.fileName) }.collectAsStateWithLifecycle()
            card?.let {
                AiExplanationCard(it, onGenerate = { viewModel.explain(record.fileName) },
                    onAgree = { viewModel.explain(record.fileName, agreeFirst = true) }, embedded = true)
            }
        })
}

/** 无副作用的页面内容，供界面检查复用。 */
@Composable
internal fun RecordsContent(records: List<LocalRecordEntry>, onBack: () -> Unit, onExport: (LocalRecordEntry) -> Unit,
    onExportReport: (LocalRecordEntry) -> Unit = {}, aiCard: @Composable (LocalRecordEntry) -> Unit = {}) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    EyeSubPage("测试记录", onBack) {
        if (records.isEmpty()) {
            Column(Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                XiaoTong(MascotMood.THINKING, Modifier.size(120.dp))
                EyeText("还没有测试记录", style = typography.subtitle, color = colors.textPrimary)
                EyeText("在「服务」里做一次自测，结果会保存在这里。", style = typography.body, color = colors.textSecondary)
            }
        }
        records.forEach { record -> RecordRow(record, { onExport(record) }, { onExportReport(record) }) { aiCard(record) } }
    }
}

@Composable
private fun RecordRow(record: LocalRecordEntry, onExport: () -> Unit, onExportReport: () -> Unit, aiCard: @Composable () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    var expanded by rememberSaveable(record.fileName) { mutableStateOf(false) }
    val haptics = rememberEyeHaptics()
    Column(
        Modifier.fillMaxWidth().clip(EyeShapes.large).background(colors.panelBackground)
            .clickable(role = Role.Button) { haptics.tap(); expanded = !expanded }
            .padding(16.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                EyeText(record.title, style = typography.subtitle, color = colors.textPrimary)
                EyeText(listOf(record.testedAtMs?.let(::formatTime) ?: record.fileName, record.subjectLabel).joinToString(" · "),
                    style = typography.bodySecondary, color = colors.textSecondary)
            }
            EyeStatusChip(record.badge, tone(record.level))
        }
        if (expanded) {
            if (record.advice.isNotBlank()) EyeText(record.advice, style = typography.body, color = colors.textPrimary)
            record.lines.forEach { EyeText("· $it", style = typography.bodySecondary, color = colors.textSecondary) }
            aiCard()
            if (record.qualityNotes.isNotEmpty()) RecordSection("数据可靠性说明", record.qualityNotes.map { "" to it })
            record.sections.forEach { section -> RecordSection(section.title, section.rows.map { it.label to it.value }) }
            EyeText("评估版本 ${record.version}", style = typography.bodySecondary, color = colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EyeButton("导出报告", EyeButtonRole.SECONDARY, { haptics.tap(); onExportReport() }, Modifier.weight(1f))
                EyeButton("导出原始数据", EyeButtonRole.SECONDARY, { haptics.tap(); onExport() }, Modifier.weight(1f))
            }
        }
    }
}

/** 报告明细独立展开，避免一条记录把整个列表撑满。 */
@Composable
private fun RecordSection(title: String, rows: List<Pair<String, String>>) {
    var open by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberEyeHaptics()
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(Modifier.fillMaxWidth().clip(EyeShapes.large).background(colors.appBackground).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        EyeText((if (open) "▾ " else "▸ ") + title, Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button) { haptics.tap(); open = !open },
            style = typography.subtitle, color = colors.textPrimary)
        if (open) rows.forEach { (label, value) -> EyeText(if (label.isBlank()) value else "$label：$value",
            style = typography.bodySecondary, color = colors.textSecondary) }
    }
}

private fun tone(level: String) = when (level) {
    "LOW" -> StatusTone.SUCCESS
    "HIGH" -> StatusTone.DANGER
    "MEDIUM", "UNCERTAIN" -> StatusTone.WARNING
    else -> StatusTone.NEUTRAL
}

private fun formatTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ms))
