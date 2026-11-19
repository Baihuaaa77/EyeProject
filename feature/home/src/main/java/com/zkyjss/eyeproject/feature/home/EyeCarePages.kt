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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val needsAccess = state.report != null && state.usageAccess == EyeCareEngine.ACCESS_DENIED
    val openUsageSettings: () -> Unit = {
        runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LaunchedEffect(needsAccess) {
        if (needsAccess && !asked) {
            asked = true
            openUsageSettings()
        }
    }
    EyeTrendContent(state.report, onBack, openUsageSettings.takeIf { needsAccess })
}

@Composable
internal fun EyeTrendContent(report: EyeCareReport?, onBack: () -> Unit, openUsageSettings: (() -> Unit)? = null) {
    EyeSubPage("用眼趋势", onBack) {
        openUsageSettings?.let { EyeButton("开启用屏统计", EyeButtonRole.SECONDARY, it) }
        V16WeeklyTrend(report)
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
            EyeText(factor?.let { "${it.title}：${it.detail}" } ?: "请根据周围环境调整屏幕亮度。", style = typography.body,
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
