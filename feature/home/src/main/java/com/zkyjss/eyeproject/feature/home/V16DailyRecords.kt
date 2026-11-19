package com.zkyjss.eyeproject.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeBrown
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.eyecare.DailyEyeRecord
import com.zkyjss.eyeproject.core.ui.component.*
import kotlinx.coroutines.launch

@Composable
internal fun V16DailyRecords(
    records: List<DailyEyeRecord>,
    detail: Int?,
    onOpenDetail: (Int) -> Unit,
    onBack: () -> Unit,
    openUsageAccess: (() -> Unit)?,
) {
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    val dateIndex = records.indexOfFirst { it.day == selectedDate }
        .takeIf { it >= 0 } ?: records.lastIndex
    val day = records.getOrNull(dateIndex)
    val metrics = day?.metrics.orEmpty()
    val item = detail?.let(metrics::getOrNull)
    val colors = LocalEyeColors.current
    val type = LocalEyeTypography.current
    val pager = rememberPagerState { metrics.size }
    val scope = rememberCoroutineScope()

    EyeSubPage(item?.title ?: "用眼全记录", onBack) {
        if (day == null) {
            EyeWarmCard { EyeText("正在读取记录…", style = type.body, color = colors.textSecondary) }
        } else if (item != null) {
            EyeWarmCard {
                EyeText(day.dateLabel, style = type.label, color = colors.textSecondary)
                EyeText(item.value ?: "暂无记录", style = type.metricValue, color = colors.textPrimary)
                EyeText(item.note, style = type.body, color = colors.textSecondary)
            }
        } else {
            EyeText(day.dateLabel, style = type.subtitle, color = colors.textPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EyeButton("前一天", EyeButtonRole.SECONDARY, {
                    selectedDate = records.getOrNull(dateIndex - 1)?.day
                }, Modifier.weight(1f), dateIndex > 0)
                EyeButton("后一天", EyeButtonRole.SECONDARY, {
                    selectedDate = records.getOrNull(dateIndex + 1)?.day
                }, Modifier.weight(1f), dateIndex < records.lastIndex)
            }
            EyeWarmCard {
                EyeText("用眼负荷", style = type.subtitle, color = colors.textPrimary)
                val score = day.score
                EyeText(score?.let { "$it / 100" } ?: "暂无记录", style = type.metricValue,
                    color = score?.let(EyeBrown::loadColor) ?: colors.textSecondary)
                day.tip?.takeIf { score != null }?.let {
                    EyeText(it, style = type.body, color = colors.textSecondary)
                }
            }
            HorizontalPager(state = pager) { index ->
                val metric = metrics[index]
                EyeWarmCard(Modifier.clickable(role = Role.Button) { onOpenDetail(index) }) {
                    EyeText(metric.title, style = type.navigationTitle, color = colors.textPrimary)
                    EyeText(metric.value ?: "暂无记录", style = type.metricValue, color = colors.textPrimary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EyeButton("上一项", EyeButtonRole.SECONDARY, {
                    scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
                }, Modifier.weight(1f), pager.currentPage > 0)
                EyeButton("下一项", EyeButtonRole.SECONDARY, {
                    scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                }, Modifier.weight(1f), pager.currentPage < metrics.lastIndex)
            }
            EyeText("${pager.currentPage + 1} / ${metrics.size}", style = type.caption, color = colors.textSecondary)
            openUsageAccess?.let { EyeButton("开启用屏统计", EyeButtonRole.SECONDARY, it) }
        }
    }
}
