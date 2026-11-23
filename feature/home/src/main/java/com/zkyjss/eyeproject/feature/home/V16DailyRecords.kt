package com.zkyjss.eyeproject.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.*
import com.zkyjss.eyeproject.core.eyecare.DailyEyeRecord
import com.zkyjss.eyeproject.core.ui.component.*

/** 在首页原位切换日期和展开指标。缺测值仍以破折号呈现。 */
@Composable
internal fun V16DailyRecords(records: List<DailyEyeRecord>, onClose: () -> Unit, openUsageAccess: (() -> Unit)?) {
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    val dateIndex = records.indexOfFirst { it.day == selectedDate }.takeIf { it >= 0 } ?: records.lastIndex
    val day = records.getOrNull(dateIndex)
    var selected by rememberSaveable(day?.day) { mutableIntStateOf(-1) }
    val colors = LocalEyeColors.current
    val type = LocalEyeTypography.current
    EyeWarmCard(fill = EyeBrown.homeSurface, edge = EyeBrown.homeEdge, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EyeText(day?.dateLabel ?: "用眼全记录", Modifier.weight(1f), style = type.subtitle, color = colors.textPrimary)
            EyeButton("收起", EyeButtonRole.SECONDARY, onClose)
        }
        if (day == null) {
            EyeText("暂无用眼记录", style = type.body, color = colors.textSecondary)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EyeButton("前一天", EyeButtonRole.SECONDARY, { selectedDate = records[dateIndex - 1].day },
                    Modifier.weight(1f), dateIndex > 0)
                EyeButton("后一天", EyeButtonRole.SECONDARY, { selectedDate = records[dateIndex + 1].day },
                    Modifier.weight(1f), dateIndex < records.lastIndex)
            }
            val accents = listOf(EyeBrown.feelingComfort, EyeBrown.feelingDry, EyeBrown.feelingSore, EyeBrown.feelingTired)
            BoxWithConstraints {
                val columns = if (maxWidth / LocalDensity.current.fontScale < 260.dp) 2 else 3
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    day.metrics.withIndex().chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { (index, metric) ->
                                val accent = accents[index % accents.size]
                                EyeWarmCard(Modifier.weight(1f).clickable(role = Role.Button) {
                                    selected = if (selected == index) -1 else index
                                }, padding = 8.dp, edge = accent.copy(alpha = if (selected == index) 0.9f else 0.35f)) {
                                    EyeText(metric.title, style = type.caption, color = colors.textSecondary)
                                    EyeText(metric.value ?: "—", style = type.subtitle, color = if (metric.value != null) accent else colors.textSecondary)
                                }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            val note = day.metrics.getOrNull(selected)?.note ?: day.tip
            note?.let { EyeText(it, style = type.bodySecondary, color = colors.textSecondary) }
            openUsageAccess?.let { EyeButton("开启用屏统计", EyeButtonRole.SECONDARY, it) }
        }
    }
}
