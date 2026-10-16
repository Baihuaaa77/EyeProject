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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.zkyjss.eyeproject.feature.services.LocalRecordEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「本机测试记录」底板：与苹果端同一份列表（共用 LocalRecordCatalog）。
 * 每行为测试名、时间与结论徽标；点开看要点并可导出完整记录（JSON）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalRecordsSheet(records: List<LocalRecordEntry>, onExport: (LocalRecordEntry) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.appBackground,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                EyeText("本机测试记录", style = typography.title, color = colors.textPrimary)
                EyeText("记录只保存在这台手机上；卸载应用会一并删除。", style = typography.bodySecondary, color = colors.textSecondary)
            }
            if (records.isEmpty()) item {
                EyeText("还没有测试记录", Modifier.padding(vertical = 24.dp), style = typography.body, color = colors.textSecondary)
            }
            items(records, key = { it.fileName }) { record -> RecordRow(record) { onExport(record) } }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun RecordRow(record: LocalRecordEntry, onExport: () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    var expanded by rememberSaveable(record.fileName) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(EyeShapes.large).background(colors.panelBackground)
            .clickable(role = Role.Button) { expanded = !expanded }
            .padding(16.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                EyeText(record.title, style = typography.subtitle, color = colors.textPrimary)
                EyeText(record.testedAtMs?.let(::formatTime) ?: record.fileName, style = typography.bodySecondary, color = colors.textSecondary)
            }
            EyeStatusChip(record.badge, tone(record.level))
        }
        if (expanded) {
            record.lines.forEach { EyeText("· $it", style = typography.bodySecondary, color = colors.textSecondary) }
            EyeButton("导出完整记录", EyeButtonRole.SECONDARY, onExport, Modifier.fillMaxWidth())
        }
    }
}

private fun tone(level: String) = when (level) {
    "LOW" -> StatusTone.SUCCESS
    "HIGH" -> StatusTone.DANGER
    "MEDIUM", "UNCERTAIN" -> StatusTone.WARNING
    else -> StatusTone.NEUTRAL
}

private fun formatTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ms))
