package com.zkyjss.eyeproject.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.R
import com.zkyjss.eyeproject.core.designsystem.theme.*
import com.zkyjss.eyeproject.core.eyecare.DailyEyeMetric
import com.zkyjss.eyeproject.core.eyecare.DailyEyeRecord
import com.zkyjss.eyeproject.core.ui.component.*
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.launch

/** 日期和轮播位置保留在本页，从指标详情返回时仍定位到同一天和同一张卡。 */
@Composable
internal fun V16DailyRecords(records: List<DailyEyeRecord>, onClose: () -> Unit, openUsageAccess: (() -> Unit)?) {
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    val dateIndex = records.indexOfFirst { it.day == selectedDate }.takeIf { it >= 0 } ?: records.lastIndex
    val day = records.getOrNull(dateIndex)
    val metrics = day?.metrics.orEmpty()
    val count = metrics.size
    val pageCount = count * 2_000
    val pager = rememberPagerState(initialPage = pageCount / 2) { pageCount }
    LaunchedEffect(count) {
        if (count > 0 && (pager.currentPage / count < 2 || pager.currentPage >= pageCount - count * 2)) {
            pager.scrollToPage(pageCount / 2 + pager.currentPage % count)
        }
    }
    var selectedMetric by rememberSaveable { mutableStateOf<Int?>(null) }
    val detail = selectedMetric?.let(metrics::getOrNull)
    val colors = LocalEyeColors.current
    val type = LocalEyeTypography.current
    val reduceMotion = rememberReducedMotion()
    val scope = rememberCoroutineScope()
    val overviewScroll = rememberScrollState()
    val detailScroll = rememberScrollState()
    val back: () -> Unit = { if (detail == null) onClose() else selectedMetric = null }

    EyeFullScreenPage(back, animateContent = !reduceMotion) {
        Column(Modifier.fillMaxSize()) {
            TopBackBar(title = detail?.title ?: "用眼全记录", noText = true, onBackClick = back)
            Column(
                Modifier.fillMaxWidth()
                    .verticalScroll(if (detail == null) overviewScroll else detailScroll)
                    .navigationBarsPadding()
                    .padding(horizontal = EyeSpacing.gutter, vertical = EyeSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(EyeSpacing.cardGap),
            ) {
                if (day == null) {
                    EyeText("暂无用眼记录", style = type.body, color = colors.textSecondary)
                } else if (detail != null) {
                    EyeText(day.dateLabel, style = type.bodySecondary, color = colors.textPrimary)
                    DailyMetricReading(detail, Modifier.fillMaxWidth())
                    EyeText(detail.note, style = type.body, color = colors.textSecondary)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DailyMetricArrow("前一天", forward = false, enabled = dateIndex > 0) {
                            selectedDate = records[dateIndex - 1].day
                        }
                        EyeText(day.dateLabel, Modifier.weight(1f), style = type.subtitle,
                            color = colors.textPrimary, textAlign = TextAlign.Center)
                        DailyMetricArrow("后一天", forward = true, enabled = dateIndex < records.lastIndex) {
                            selectedDate = records[dateIndex + 1].day
                        }
                    }
                    DailyRecordWeek(day, records) { selectedDate = it }
                    DailyPressureCard(day)
                    if (count > 0) {
                        fun stepMetric(step: Int) {
                            scope.launch {
                                val current = pager.currentPage
                                val target = current + step
                                if (target !in 0 until pageCount) {
                                    pager.scrollToPage(pageCount / 2 + Math.floorMod(target, count))
                                } else if (reduceMotion) {
                                    pager.scrollToPage(target)
                                } else {
                                    pager.animateScrollToPage(target)
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            EyeText("当日读数", Modifier.weight(1f), style = type.subtitle, color = colors.textPrimary)
                            DailyMetricArrow("上一项", forward = false, enabled = count > 1) { stepMetric(-1) }
                            EyeText("${pager.currentPage % count + 1}/$count", Modifier.padding(horizontal = EyeSpacing.xs),
                                style = type.caption, color = colors.textPrimary)
                            DailyMetricArrow("下一项", forward = true, enabled = count > 1) { stepMetric(1) }
                        }
                        val pageSize = remember {
                            object : PageSize {
                                override fun Density.calculateMainAxisPageSize(
                                    availableSpace: Int,
                                    pageSpacing: Int,
                                ): Int = ((availableSpace - pageSpacing) * 2 / 3).coerceAtLeast(1)
                            }
                        }
                        HorizontalPager(
                            state = pager,
                            pageSize = pageSize,
                            pageSpacing = EyeSpacing.xs,
                            verticalAlignment = Alignment.Top,
                            userScrollEnabled = count > 1,
                        ) { page ->
                            val index = page % count
                            EyeWarmCard(
                                modifier = Modifier.clip(EyeShapes.card).clickable(role = Role.Button) {
                                    selectedMetric = index
                                    scope.launch { detailScroll.scrollTo(0) }
                                },
                                padding = EyeSpacing.md,
                            ) {
                                EyeText(metrics[index].title, style = type.subtitle, color = colors.textPrimary)
                                DailyMetricReading(metrics[index])
                            }
                        }
                    }
                }
                if (detail == null) {
                    openUsageAccess?.let { EyeButton("开启用屏统计", EyeButtonRole.SECONDARY, it) }
                }
            }
        }
    }
}

@Composable
private fun DailyRecordWeek(day: DailyEyeRecord, records: List<DailyEyeRecord>, onSelect: (String) -> Unit) {
    val colors = LocalEyeColors.current
    val type = LocalEyeTypography.current
    val dayKeys = remember(records) { records.mapTo(hashSetOf()) { it.day } }
    val monday = remember(day.day) { LocalDate.parse(day.day).with(DayOfWeek.MONDAY) }
    BoxWithConstraints {
        val cellWidth = (maxWidth / 7).coerceAtLeast(48.dp)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, weekday ->
                val date = monday.plusDays(index.toLong())
                val key = date.toString()
                val current = key == day.day
                val enabled = key in dayKeys
                val foreground = when {
                    current -> colors.onPrimary
                    enabled -> colors.textPrimary
                    else -> colors.textTertiary
                }
                Column(
                    Modifier.width(cellWidth).heightIn(min = 48.dp).clip(EyeShapes.medium)
                        .then(if (current) Modifier.background(colors.primary) else Modifier)
                        .clickable(role = Role.Button, enabled = enabled) { onSelect(key) }
                        .semantics {
                            selected = current
                            contentDescription = "${date.monthValue}月${date.dayOfMonth}日，周$weekday"
                        }
                        .padding(vertical = EyeSpacing.xxs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    EyeText("周$weekday", style = type.caption, color = foreground)
                    EyeText(date.dayOfMonth.toString(), style = type.subtitle, color = foreground)
                }
            }
        }
    }
}

@Composable
private fun DailyPressureCard(day: DailyEyeRecord) {
    val (title, tip) = when (day.score) {
        null -> "当日暂无负荷记录" to null
        in 0..29 -> "当日用眼压力较轻" to "请继续保持"
        in 30..59 -> "当日用眼压力适中" to "记得间歇休息"
        else -> "当日用眼压力较重" to "请注意休息"
    }
    val type = LocalEyeTypography.current
    Box(
        Modifier.fillMaxWidth().heightIn(min = 128.dp).clip(EyeShapes.card).background(EyeBrown.pressurePaper),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.v16_pressure_garden),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
        )
        Column(
            Modifier.padding(horizontal = EyeSpacing.xxl, vertical = EyeSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(EyeSpacing.xxs),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EyeText(title, style = type.title, color = EyeBrown.pressureInk, textAlign = TextAlign.Center)
            tip?.let { EyeText(it, style = type.body, color = EyeBrown.pressureText, textAlign = TextAlign.Center) }
        }
    }
}

@Composable
private fun DailyMetricReading(metric: DailyEyeMetric, modifier: Modifier = Modifier) {
    val colors = LocalEyeColors.current
    EyeText(eyeReading(metric.value ?: "—", unitColor = colors.textPrimary),
        modifier, style = LocalEyeTypography.current.display, color = colors.textPrimary)
}

@Composable
private fun DailyMetricArrow(label: String, forward: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalEyeColors.current
    Box(
        Modifier.size(48.dp).clip(EyeShapes.medium)
            .clickable(role = Role.Button, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        EyeIcon(
            painter = painterResource(R.drawable.ic_pointer_left),
            contentDescription = null,
            modifier = Modifier.size(EyeSpacing.sm).rotate(if (forward) 180f else 0f),
            tint = if (enabled) colors.cardBorder else colors.textTertiary,
        )
    }
}
