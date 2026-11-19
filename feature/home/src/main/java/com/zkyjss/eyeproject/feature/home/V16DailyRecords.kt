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
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.eyecare.EyeCareReport
import com.zkyjss.eyeproject.core.ui.component.*
import java.time.LocalDate
import kotlinx.coroutines.launch

private val metrics = listOf(
    "screen" to "今日用屏",
    "longest" to "最长连续使用",
    "light" to "环境光",
    "brightness" to "屏幕亮度",
    "distance" to "用眼距离",
    "night" to "夜间使用",
)

@Composable
internal fun V16DailyRecords(report: EyeCareReport?, onBack: () -> Unit, openUsageAccess: (() -> Unit)?) {
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var detail by rememberSaveable { mutableStateOf<Int?>(null) }
    val today = LocalDate.now()
    val date = today.plusDays(offset.toLong())
    val history = report?.trend?.historyDays.orEmpty()
    val day = history.firstOrNull { it.day == date.toString() }
    val colors = LocalEyeColors.current
    val type = LocalEyeTypography.current
    val total = metrics.size
    val middle = (Int.MAX_VALUE / 2 / total) * total
    val pager = rememberPagerState(initialPage = middle) { Int.MAX_VALUE }
    val scope = rememberCoroutineScope()
    val selected = pager.currentPage % total
    val signals = report?.signals.orEmpty().associateBy { it.key }

    fun value(index: Int): String {
        val key = metrics[index].first
        if (offset == 0) return signals[key]?.value ?: "—"
        val minutes = when (key) {
            "screen" -> day?.screenMinutes
            "longest" -> day?.longestSessionMinutes
            "night" -> day?.nightMinutes
            else -> null
        }
        return minutes?.let { "${it / 60}小时${it % 60}分" } ?: "—"
    }

    EyeSubPage(detail?.let { metrics[it].second } ?: "用眼全记录", {
        if (detail != null) detail = null else onBack()
    }) {
        val item = detail
        if (item != null) {
            EyeWarmCard {
                EyeText("${date.monthValue}月${date.dayOfMonth}日", style = type.label, color = colors.textSecondary)
                EyeText(value(item), style = type.display, color = colors.textPrimary)
                if (offset == 0) signals[metrics[item].first]?.note?.takeIf { it.isNotBlank() }?.let {
                    EyeText(it, style = type.body, color = colors.textSecondary)
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EyeButton("前一天", EyeButtonRole.SECONDARY, { offset-- }, Modifier.weight(1f), offset > -13)
                EyeButton("后一天", EyeButtonRole.SECONDARY, { offset++ }, Modifier.weight(1f), offset < 0)
            }
            EyeWarmCard {
                EyeText("${date.monthValue}月${date.dayOfMonth}日用眼负荷", style = type.subtitle, color = colors.textPrimary)
                EyeText(day?.score?.let { "$it / 100" } ?: "—", style = type.metricValue, color = colors.textPrimary)
                if (offset == 0) report?.tips?.firstOrNull()?.text?.let {
                    EyeText(it, style = type.body, color = colors.textSecondary)
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                HorizontalPager(
                    state = pager,
                    contentPadding = PaddingValues(end = maxWidth / 3),
                    pageSpacing = 10.dp,
                ) { page ->
                    val index = page % total
                    EyeWarmCard(Modifier.clickable(role = Role.Button) {
                        scope.launch { pager.scrollToPage(page) }
                        detail = index
                    }) {
                        EyeText(metrics[index].second, style = type.navigationTitle, color = colors.textPrimary)
                        EyeText(value(index), style = type.metricValue, color = colors.textPrimary)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EyeButton("上一项", EyeButtonRole.SECONDARY, {
                    scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
                }, Modifier.weight(1f))
                EyeButton("下一项", EyeButtonRole.SECONDARY, {
                    scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                }, Modifier.weight(1f))
            }
            EyeText("${selected + 1} / $total", style = type.caption, color = colors.textSecondary)
            openUsageAccess?.let { EyeButton("开启用屏统计", EyeButtonRole.SECONDARY, it) }
        }
    }
}
