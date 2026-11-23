package com.zkyjss.eyeproject.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.*
import com.zkyjss.eyeproject.core.eyecare.EyeCareReport
import com.zkyjss.eyeproject.core.ui.component.*
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.roundToInt

/** 三张曲线共享星期选择；缺测保留断点，平均值仅计算已有记录。 */
@Composable
internal fun V16WeeklyTrend(report: EyeCareReport?) {
    val colors = LocalEyeColors.current
    val type = LocalEyeTypography.current
    val today = report?.trend?.days?.lastOrNull()?.day?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
    var week by remember { mutableIntStateOf(0) }
    var selected by remember { mutableIntStateOf(today.dayOfWeek.value-1) }
    val monday = today.with(DayOfWeek.MONDAY).minusWeeks(week.toLong())
    val dates = (0..6).map { monday.plusDays(it.toLong()) }
    val history = report?.trend?.historyDays.orEmpty().ifEmpty { report?.trend?.days.orEmpty() }.associateBy { it.day }
    val days = dates.map { history[it.toString()] }
    val labels = listOf("一","二","三","四","五","六","日")
    EyeSegmentedRow(listOf(SegmentedOption(0,"本周"),SegmentedOption(1,"上周")),week,{ week = it })
    EyeText("${monday.monthValue}月${monday.dayOfMonth}日 — ${dates.last().monthValue}月${dates.last().dayOfMonth}日",style = type.subtitle,color = colors.textPrimary)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.SpaceBetween) {
        dates.forEachIndexed { i,date ->
            EyeButton("${labels[i]}\n${date.dayOfMonth}",if (i == selected) EyeButtonRole.PRIMARY else EyeButtonRole.SECONDARY,
                { selected = i },Modifier.widthIn(min = 48.dp),enabled = !date.isAfter(today))
        }
    }
    val metrics = listOf(
        Triple("平均每日用屏","分钟",days.map { it?.screenMinutes?.toFloat() }),
        Triple("平均连续使用时长","分钟",days.map { it?.longestSessionMinutes?.toFloat() }),
        Triple("平均眨眼频率","次/分",days.map { it?.blinksPerMinute }))
    metrics.forEachIndexed { index,(title,unit,values) ->
        val actual = values.filterNotNull()
        val tint = listOf(colors.screen,colors.sun,colors.ice)[index]
        val screenTime = index == 0
        val axisMaximum = screenTimeAxisMaximum(actual.maxOrNull()?.roundToInt() ?: 0)
        fun label(value: Float): String {
            val rounded = value.roundToInt()
            return if (screenTime) "${rounded / 60}时${rounded % 60}分" else "$rounded $unit"
        }
        EyeWarmCard {
            EyeText(title,style = type.subtitle,color = colors.textPrimary)
            if (actual.isEmpty()) {
                EyeText("这周无记录", style = type.body, color = colors.textSecondary)
            } else {
                EyeText(label(actual.average().toFloat()), style = type.metricValue, color = colors.textPrimary)
                EyeText("周${labels[selected]} · ${values[selected]?.let(::label) ?: "无记录"}",
                    style = type.bodySecondary, color = colors.textSecondary)
                Row(Modifier.fillMaxWidth()) {
                    if (screenTime) ScreenTimeAxis(axisMaximum, 112.dp)
                    Canvas(Modifier.weight(1f).height(112.dp)) {
                        val max = if (screenTime) axisMaximum.toFloat() else (actual.maxOrNull() ?: 1f).coerceAtLeast(1f)*1.1f
                        val pad = 8.dp.toPx()
                        fun point(i: Int,v: Float) = Offset(pad+(size.width-2*pad)*i/6f,size.height-pad-(size.height-2*pad)*v/max)
                        repeat(3) { i -> val y = pad+(size.height-2*pad)*i/2f; drawLine(colors.borderSubtle,Offset(0f,y),Offset(size.width,y)) }
                        val path = Path(); var connected = false
                        values.forEachIndexed { i,value ->
                            if (value == null) connected = false else {
                                val p = point(i,value)
                                if (connected) path.lineTo(p.x,p.y) else path.moveTo(p.x,p.y)
                                connected = true
                                drawCircle(tint,if (selected == i) 5.dp.toPx() else 3.dp.toPx(),p)
                            }
                        }
                        drawPath(path,tint,style = Stroke(2.dp.toPx()))
                    }
                }
            }
        }
    }
}

/** 纵轴使用整小时刻度，保留半程一条网格线。 */
internal fun screenTimeAxisMaximum(minutes: Int): Int = maxOf(120, ((minutes + 119) / 120) * 120)

@Composable
internal fun ScreenTimeAxis(maximum: Int, height: Dp, modifier: Modifier = Modifier) {
    val colors = LocalEyeColors.current
    val type = LocalEyeTypography.current
    Column(
        modifier.widthIn(min = 36.dp).height(height).padding(end = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.End,
    ) {
        listOf(maximum / 60, maximum / 120, 0).forEach { hours ->
            EyeText("${hours}时", style = type.caption, color = colors.textSecondary)
        }
    }
}
