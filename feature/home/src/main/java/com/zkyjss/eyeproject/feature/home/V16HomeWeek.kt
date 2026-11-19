package com.zkyjss.eyeproject.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeBrown
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.eyecare.TrendDay
import com.zkyjss.eyeproject.core.ui.component.*
import java.time.DayOfWeek
import java.time.LocalDate

@Composable
internal fun V16HomeWeek(history: List<TrendDay>, onFeeling: () -> Unit, onTrend: () -> Unit) {
    val colors = LocalEyeColors.current
    val type = LocalEyeTypography.current
    val today = LocalDate.now()
    var week by rememberSaveable { mutableIntStateOf(1) }
    val monday = today.with(DayOfWeek.MONDAY).minusWeeks(week.toLong())
    val dates = List(7) { monday.plusDays(it.toLong()) }
    val daysByDate = history.associateBy { it.day }
    val days = dates.map { date -> if (date > today) null else daysByDate[date.toString()] }
    var chosen by rememberSaveable(week) { mutableIntStateOf(-1) }
    val selected = chosen.takeIf { it >= 0 } ?: days.indexOfLast { it?.screenMinutes != null }
    val reading = days.getOrNull(selected)
    val peak = screenTimeAxisMaximum(days.mapNotNull { it?.screenMinutes }.maxOrNull() ?: 0)
    fun tone(day: TrendDay?) = day?.score?.let(EyeBrown::loadColor) ?: colors.textSecondary

    EyeWarmCard(fill = EyeBrown.trend) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EyeText("用眼爱记录", Modifier.weight(1f), style = type.subtitle, color = colors.textPrimary)
            EyeButton("记录感受", EyeButtonRole.SECONDARY, onFeeling)
        }
        EyeSegmentedRow(listOf(SegmentedOption(1, "上周"), SegmentedOption(0, "本周")), week, { week = it })
        val minutes = reading?.screenMinutes
        val amount = minutes?.let { "${it / 60}小时${it % 60}分" } ?: "—"
        EyeText(amount, style = type.metricValue, color = tone(reading))
        if (minutes == null) EyeText("该日暂无用屏记录", style = type.bodySecondary, color = colors.textSecondary)
        Row(Modifier.fillMaxWidth()) {
            ScreenTimeAxis(peak, 100.dp)
            Canvas(Modifier.weight(1f).height(100.dp).pointerInput(Unit) {
                detectTapGestures { chosen = (7 * it.x / size.width).toInt().coerceIn(0, 6) }
            }) {
                val inset = 8.dp.toPx()
                val bottom = size.height - inset
                listOf(inset, (inset + bottom) / 2, bottom).forEach { y ->
                    drawLine(colors.borderSubtle, Offset(0f, y), Offset(size.width, y))
                }
                val points = days.mapIndexed { i, day ->
                    day?.screenMinutes?.let { Offset(size.width * (i + 0.5f) / 7, bottom - (bottom - inset) * it / peak) }
                }
                points.zipWithNext().forEachIndexed { i, (first, next) ->
                    if (first != null && next != null) {
                        val color = tone(days[i])
                        val light = Path().apply {
                            moveTo(first.x, first.y)
                            lineTo(next.x, next.y)
                            lineTo(next.x, size.height)
                            lineTo(first.x, size.height)
                            close()
                        }
                        drawPath(light, Brush.verticalGradient(listOf(color.copy(alpha = 0.4f), color.copy(alpha = 0f)), startY = minOf(first.y, next.y), endY = size.height))
                        drawLine(color, first, next, 2.dp.toPx())
                    }
                }
                points.forEachIndexed { i, point ->
                    if (point != null) {
                        val color = tone(days[i])
                        if (i == selected) {
                            val wide = 9.dp.toPx()
                            val tall = 5.dp.toPx()
                            val eye = Path().apply {
                                moveTo(point.x - wide, point.y)
                                quadraticTo(point.x, point.y - tall * 2, point.x + wide, point.y)
                                quadraticTo(point.x, point.y + tall * 2, point.x - wide, point.y)
                                close()
                            }
                            drawPath(eye, color)
                            drawCircle(colors.appBackground, 3.dp.toPx(), point)
                        } else drawCircle(color, 3.dp.toPx(), point)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(36.dp))
            listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { i, weekday ->
                Box(
                    Modifier.weight(1f).heightIn(min = 48.dp)
                        .semantics { this.selected = i == selected }
                        .clickable(role = Role.Button) { chosen = i },
                    contentAlignment = Alignment.Center,
                ) { EyeText(weekday, style = type.caption, color = if (i == selected) tone(days[i]) else colors.textSecondary) }
            }
        }
        EyeButton("查看用眼趋势", EyeButtonRole.SECONDARY, onTrend, Modifier.fillMaxWidth())
    }
}
