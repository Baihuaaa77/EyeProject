package com.zkyjss.eyeproject.feature.home

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.eyecare.EyeCareEngine
import com.zkyjss.eyeproject.core.eyecare.EyeCareReport
import com.zkyjss.eyeproject.core.eyecare.EyeConsult
import com.zkyjss.eyeproject.core.ui.component.*
import kotlin.math.roundToInt

// region 用眼趋势报告

/** 近 7 天用屏、眼健康指数、眨眼频率的趋势与小结，以及今天的主要原因。与苹果端 TrendPage 同一排版与文案。 */
@Composable
fun EyeTrendScreen(onBack: () -> Unit, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    EyeTrendContent(state.report, onBack)
}

@Composable
internal fun EyeTrendContent(report: EyeCareReport?, onBack: () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val trend = report?.trend
    val days = trend?.days.orEmpty()
    EyeSubPage("用眼趋势", onBack) {
        // 本周要点（第一条「日均用屏…」在用屏卡里大字显示）
        val points = trend?.summary.orEmpty().drop(if (trend?.averageScreenMinutes != null) 1 else 0)
        if (points.isNotEmpty()) EyeCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            EyeText("本周要点", style = typography.chip, color = colors.textSecondary)
            points.forEach { Bullet(it, colors.screen) }
        }
        // 用屏时长：日均大字 + 与前一周比较 + 每日柱图
        val avg = trend?.averageScreenMinutes
        val previous = trend?.previousAverageScreenMinutes
        val change = if (avg != null && previous != null && previous > 0) ((avg - previous) * 100f / previous).roundToInt() else null
        ChartCard("用屏时长", EyeGlyph.HOURGLASS, colors.screen, "近 7 天日均用屏", avg?.let(EyeCareEngine::duration) ?: "—",
            trailing = change?.let {
                when {
                    it > 0 -> "较上周 ↑$it%" to colors.warning
                    it < 0 -> "较上周 ↓${-it}%" to colors.success
                    else -> "与上周持平" to colors.textSecondary
                }
            }) {
            EyeBarChart(days.map { d ->
                EyeBar(d.label, d.screenMinutes?.div(60f), d.screenMinutes?.let { "%.1f 小时".format(it / 60f) }, d.label == "今天",
                    (d.screenMinutes ?: 0) > 360)
            }, reference = 6f, tint = colors.screen, description = "近 7 天每日用屏时长")
        }
        // 眼健康指数：今天的分数 + 等级 + 每日柱图
        val todayIndex = report?.score?.let(::healthIndex)
        ChartCard("眼健康指数", EyeGlyph.EYE, colors.primary, "今天", todayIndex?.let { "$it 分" } ?: "—",
            trailing = report?.label?.let { it to colors.levelTone(report.level).first }) {
            EyeBarChart(days.map { d ->
                val index = d.score?.let(::healthIndex)
                EyeBar(d.label, index?.toFloat(), index?.let { "$it 分" }, d.label == "今天", (index ?: 100) <= 40)
            }, maxValue = 100f, tint = colors.primary, description = "近 7 天每日眼健康指数")
        }
        // 眨眼频率：平均 + 参考值 + 每日柱图
        ChartCard("眨眼频率", EyeGlyph.EYE, colors.iceInk, "平均",
            trend?.averageBlinksPerMinute?.let { "${"%.1f".format(it)} 次/分" } ?: "—",
            trailing = "参考 15 次/分" to colors.textSecondary,
            help = "平时每分钟约眨眼 15 次，专注看屏幕时会明显变少。每次实时检测都会记下眨眼频率。") {
            EyeBarChart(days.map { d -> EyeBar(d.label, d.blinksPerMinute, d.blinksPerMinute?.roundToInt()?.let { "$it 次/分" }, d.label == "今天",
                (d.blinksPerMinute ?: 99f) < 12f) }, reference = 15f, maxValue = 20f, tint = colors.ice, description = "近 7 天每日眨眼频率")
        }
        if (!report?.factors.isNullOrEmpty()) EyeListGroup(title = "今天的主要原因（拉低眼健康指数）") {
            report!!.factors.forEachIndexed { i, f ->
                EyeListRow(f.title, onClick = null, subtitle = f.detail, value = "−${f.points}", valueColor = colors.warning,
                    divider = i < report.factors.lastIndex)
            }
        }
        if (trend != null && trend.checkInDays > 0) EyeListGroup(title = "身体感受") {
            EyeListRow("打卡天数", onClick = null, glyph = EyeGlyph.EYE, value = "${trend.checkInDays} 天")
            EyeListRow("有不适的天数", onClick = null, glyph = EyeGlyph.INFO, tone = EyeTileTone.ACCENT, value = "${trend.symptomDays} 天", divider = false)
        }
    }
}

/** 眼健康指数 = 100 − 用眼负荷分（规则仍按负荷算，界面只展示指数，越高越好）。 */
internal fun healthIndex(loadScore: Int): Int = (100 - loadScore).coerceIn(0, 100)

/**
 * 图表卡：类别标签（类别色）+ 右侧一项对比 / 等级，下面小字说明 + 大号读数（数字大、单位灰），再下面是图。
 */
@Composable
private fun ChartCard(
    title: String,
    glyph: EyeGlyph,
    tint: androidx.compose.ui.graphics.Color,
    caption: String,
    value: String,
    trailing: Pair<String, androidx.compose.ui.graphics.Color>? = null,
    help: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    EyeCard(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EyeCategoryLabel(title, glyph, tint)
            if (help != null) EyeHelpButton(title, help)
            Spacer(Modifier.weight(1f))
            trailing?.let { (text, color) -> EyeText(text, style = typography.chip, color = color) }
        }
        EyeText(caption, Modifier.padding(top = 6.dp), style = typography.bodySecondary, color = colors.textSecondary)
        EyeText(eyeReading(value), style = typography.metricValue, color = colors.textPrimary, maxLines = 1)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
internal fun Bullet(text: String, dot: androidx.compose.ui.graphics.Color = LocalEyeColors.current.primary) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 8.dp).size(6.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        EyeText(text, style = LocalEyeTypography.current.body, color = LocalEyeColors.current.textPrimary)
    }
}

// endregion

// region 健康建议

/**
 * 健康建议：专业咨询建议（规则按近期自测、身体感受与年龄给出）+ 建议做的自测。AI 综合建议在单独的对话页（EyeChatScreen）。
 * 与苹果端 AdvicePage 同一排版。
 */
@Composable
fun EyeAdviceScreen(onBack: () -> Unit, onOpenTest: (String) -> Unit, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    EyeAdviceContent(state.report, onBack, onOpenTest)
}

@Composable
internal fun EyeAdviceContent(report: EyeCareReport?, onBack: () -> Unit, onOpenTest: (String) -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    EyeSubPage("健康建议", onBack) {
        EyeHeroCard {
            EyeText(report?.headline ?: "正在汇总今天的用眼数据…", style = typography.subtitle, color = colors.textPrimary)
        }
        EyeText("专业咨询建议", Modifier.padding(start = 4.dp, top = 4.dp), style = typography.title, color = colors.textPrimary)
        report?.consults.orEmpty().forEach { ConsultCard(it) }
        val doable = report?.actions.orEmpty().filter { it.kind == "TEST" }
        if (doable.isNotEmpty()) EyeListGroup(title = "建议做的自测") {
            doable.forEachIndexed { i, a ->
                val (glyph, tone) = glyphFor(a.kind)
                EyeListRow(a.title, onClick = { a.target?.let(onOpenTest) }, glyph = glyph, tone = tone, subtitle = a.detail, divider = i < doable.lastIndex)
            }
        }
    }
}

@Composable
private fun ConsultCard(consult: EyeConsult) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val (strong, soft) = if (consult.urgent) colors.danger to colors.dangerSoft else colors.primary to colors.primarySoft
    Column(Modifier.fillMaxWidth().eyeGlass(colors, EyeShapes.panel).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(strong))
            Spacer(Modifier.width(10.dp))
            EyeText(consult.title, Modifier.weight(1f), style = typography.subtitle, color = colors.textPrimary)
            if (consult.urgent) EyeStatusChip("尽快", StatusTone.DANGER)
        }
        EyeText(consult.detail, style = typography.body, color = colors.textPrimary)
        if (consult.department.isNotEmpty()) EyeText(consult.department, style = typography.chip, color = strong, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(EyeShapes.chip).background(soft).padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

// endregion

// region 亮度与蓝光

internal const val BLUE_LIGHT_HELP = "目前没有证据表明手机屏幕的蓝光会损伤眼睛，但晚上看屏幕会影响睡眠节律。夜间模式让屏幕颜色偏暖，更适合晚上使用。"

/** 亮度与蓝光：当前屏幕亮度与环境光、评估与做法，一键打开系统显示设置（夜间模式、亮度）。 */
@Composable
fun EyeBrightnessScreen(onBack: () -> Unit, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    EyeBrightnessContent(state, onBack) { runCatching { context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) } }
}

@Composable
internal fun EyeBrightnessContent(state: HomeUiState, onBack: () -> Unit, onOpenDisplay: () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val factor = state.report?.factors?.firstOrNull { it.key == "dark-bright" || it.key == "night-bright" || it.key == "night" }
    val signals = state.report?.signals.orEmpty()
    EyeSubPage("亮度与蓝光", onBack, bottomBar = {
        EyeButton("打开系统显示设置", EyeButtonRole.PRIMARY, onOpenDisplay, Modifier.fillMaxWidth())
    }) {
        Column(Modifier.fillMaxWidth().eyeGlass(colors, EyeShapes.panel).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SignalCell(signals.firstOrNull { it.key == "brightness" }, Modifier.weight(1f))
                SignalCell(signals.firstOrNull { it.key == "light" }, Modifier.weight(1f))
            }
            EyeText(factor?.let { "${it.title}：${it.detail}" } ?: "屏幕亮度和周围环境比较协调。", style = typography.body,
                color = if (factor != null) colors.warning else colors.textPrimary)
        }
        Column(Modifier.fillMaxWidth().eyeGlass(colors, EyeShapes.panel).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EyeText("这样做更舒服", style = typography.subtitle, color = colors.textPrimary)
                EyeHelpButton("关于蓝光", BLUE_LIGHT_HELP)
            }
            Bullet("屏幕亮度和周围环境接近：暗处调低，户外调高。")
            Bullet("晚上打开夜间模式或护眼模式，让屏幕颜色偏暖。")
            Bullet("睡前一小时尽量少看屏幕。")
            Bullet("打开自动亮度，让手机跟着环境光调整。")
        }
    }
}

// endregion
