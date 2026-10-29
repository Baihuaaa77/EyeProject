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

/** 近 7 天用屏、用眼负荷、眨眼频率的趋势与小结，以及今天的主要原因。与苹果端 TrendPage 同一排版与文案。 */
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
    EyeSubPage("用眼趋势报告", onBack) {
        // 小结卡：日均用屏、与前一周比较、要点
        Column(Modifier.fillMaxWidth().clip(EyeShapes.panel)
            .background(Brush.linearGradient(listOf(colors.primarySoft, colors.panelBackground))).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    EyeText("近 7 天日均用屏", style = typography.body, color = colors.textSecondary)
                    EyeText(trend?.averageScreenMinutes?.let(EyeCareEngine::duration) ?: "—", style = typography.headline, color = colors.textPrimary)
                    val avg = trend?.averageScreenMinutes
                    val previous = trend?.previousAverageScreenMinutes
                    if (avg != null && previous != null && previous > 0) {
                        val change = ((avg - previous) * 100f / previous).roundToInt()
                        val (label, tone) = when {
                            change > 0 -> "比前一周多 $change%" to StatusTone.WARNING
                            change < 0 -> "比前一周少 ${-change}%" to StatusTone.SUCCESS
                            else -> "与前一周持平" to StatusTone.NEUTRAL
                        }
                        EyeStatusChip(label, tone)
                    }
                }
                XiaoTong(MascotMood.of(report?.mood), Modifier.size(104.dp))
            }
            // 第一条「日均用屏…」已在上方大字显示
            trend?.summary.orEmpty().drop(if (trend?.averageScreenMinutes != null) 1 else 0).forEach { Bullet(it) }
        }
        val days = trend?.days.orEmpty()
        ChartCard("每日用屏", "小时") {
            EyeBarChart(days.map { d ->
                EyeBar(d.label, d.screenMinutes?.div(60f), d.screenMinutes?.let { "%.1f".format(it / 60f) }, d.label == "今天",
                    (d.screenMinutes ?: 0) > 360)
            }, reference = 6f, description = "近 7 天每日用屏时长")
        }
        ChartCard("用眼负荷", "分") {
            EyeBarChart(days.map { d -> EyeBar(d.label, d.score?.toFloat(), d.score?.toString(), d.label == "今天", (d.score ?: 0) >= 60) },
                maxValue = 100f, description = "近 7 天每日用眼负荷分")
        }
        ChartCard("眨眼频率", "次/分", help = "平时每分钟约眨眼 15 次，专注看屏幕时会明显变少。每次实时检测都会记下眨眼频率。") {
            EyeBarChart(days.map { d -> EyeBar(d.label, d.blinksPerMinute, d.blinksPerMinute?.roundToInt()?.toString(), d.label == "今天",
                (d.blinksPerMinute ?: 99f) < 12f) }, reference = 15f, maxValue = 20f, description = "近 7 天每日眨眼频率")
        }
        if (!report?.factors.isNullOrEmpty()) EyeListGroup(title = "今天的主要原因") {
            report!!.factors.forEachIndexed { i, f ->
                EyeListRow(f.title, onClick = null, subtitle = f.detail, value = "+${f.points}", valueColor = colors.warning,
                    divider = i < report.factors.lastIndex)
            }
        }
        if (trend != null && trend.checkInDays > 0) EyeListGroup(title = "身体感受") {
            EyeListRow("打卡天数", onClick = null, glyph = EyeGlyph.EYE, value = "${trend.checkInDays} 天")
            EyeListRow("有不适的天数", onClick = null, glyph = EyeGlyph.INFO, tone = EyeTileTone.ACCENT, value = "${trend.symptomDays} 天", divider = false)
        }
    }
}

@Composable
private fun ChartCard(title: String, unit: String, help: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EyeText(title, style = typography.subtitle, color = colors.textPrimary)
            if (help != null) EyeHelpButton(title, help)
            Spacer(Modifier.weight(1f))
            EyeText(unit, style = typography.chip, color = colors.textSecondary)
        }
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
        Row(Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground).padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            XiaoTong(MascotMood.of(report?.mood), Modifier.size(96.dp))
            EyeText(report?.headline ?: "正在汇总今天的用眼数据…", Modifier.weight(1f), style = typography.body, color = colors.textPrimary)
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
    Column(Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground).padding(18.dp),
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
        Column(Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SignalCell(signals.firstOrNull { it.key == "brightness" }, Modifier.weight(1f))
                SignalCell(signals.firstOrNull { it.key == "light" }, Modifier.weight(1f))
            }
            EyeText(factor?.let { "${it.title}：${it.detail}" } ?: "屏幕亮度和周围环境比较协调。", style = typography.body,
                color = if (factor != null) colors.warning else colors.textPrimary)
        }
        Column(Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground).padding(18.dp),
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
