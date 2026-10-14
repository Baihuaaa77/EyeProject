package com.zkyjss.eyeproject.feature.services

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.zkyjss.eyeproject.core.common.formatDistanceCm
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingLarge
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeDisclosure
import com.zkyjss.eyeproject.core.ui.component.EyeSectionCard
import com.zkyjss.eyeproject.core.ui.component.EyeSwitch
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.TopBackBar

/**
 * 干眼自测页：问卷 → 40 cm 准备 → 视标练习 → 60 秒自然眨眼 + 紧接 20 秒持续睁眼（全程辨认视标）→ 结果。
 * 结果页可延长测试或闭眼休息 1 分钟后复测。
 */
@Composable
fun DryEyeScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DryEyeTestViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val voice = remember(context) { ScreeningVoice(context) }
    var voiceEnabled by rememberSaveable { mutableStateOf(true) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) viewModel.startMonitoring(lifecycleOwner) else viewModel.permissionDenied()
    }
    val ensureCamera: () -> Unit = {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                viewModel.startMonitoring(lifecycleOwner)
            else permission.launch(Manifest.permission.CAMERA)
        }
    }
    LaunchedEffect(state.phase) { if (state.phase != DryEyePhase.Profile) ensureCamera() }
    val distance = distanceStatus(state.distanceCm, state.faceDetected, DryEyeProtocol.TARGET_DISTANCE_CM, DryEyeProtocol.DISTANCE_TOLERANCE_CM)
    val distanceMatters = state.phase in setOf(DryEyePhase.Setup, DryEyePhase.FormalGuide, DryEyePhase.Practice, DryEyePhase.Natural, DryEyePhase.Open)
    val distanceHint = if (distanceMatters && distance.clearlyOff()) distance.spoken else null
    val voiceAnswer = rememberVoiceAnswer(
        active = state.phase in setOf(DryEyePhase.Practice, DryEyePhase.Natural, DryEyePhase.Open),
        voice = voice,
    ) { command ->
        when (command) {
            is VoiceCommand.Direction -> viewModel.answer(command.direction, byVoice = true)
            VoiceCommand.Unsure -> viewModel.answer(-1, byVoice = true)
            else -> Unit
        }
    }
    val basePrompt = when (state.phase) {
        DryEyePhase.Profile -> "请先填写基本信息"
        DryEyePhase.Setup -> "建议佩戴最佳矫正视力眼镜。准备好后开始"
        DryEyePhase.PracticeGuide -> guideCueDryEyePractice
        DryEyePhase.FormalGuide -> "正式测试。先自然眨眼，之后不停顿，直接保持睁眼二十秒"
        DryEyePhase.Open -> "现在尽量保持睁眼"
        DryEyePhase.Rest -> if (state.remainingSeconds <= 1) "请睁开眼睛" else "请闭上眼睛休息"
        DryEyePhase.Result -> "测试完成"
        else -> state.qualityWarning ?: state.instruction
    }
    // 距离提示优先；距离持续偏离时每 6 秒重复一次，回到合适区间立即停止
    val prompt = distanceHint ?: basePrompt
    LaunchedEffect(prompt, voiceEnabled) {
        if (voiceEnabled) {
            kotlinx.coroutines.delay(650)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) voice.speak(prompt)
            while (distanceHint != null && prompt == distanceHint) {
                kotlinx.coroutines.delay(6_000)
                voice.speak(prompt)
            }
        } else voice.stop()
    }
    DisposableEffect(viewModel, voice) { onDispose { viewModel.stopMonitoring(); voice.close() } }
    // 测试期间窗口亮度固定为最高，保证视标对比度一致（原文要求记录屏幕亮度）
    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        val old = window?.attributes?.screenBrightness
        window?.let { it.attributes = it.attributes.apply { screenBrightness = 1f } }
        onDispose { if (window != null && old != null) window.attributes = window.attributes.apply { screenBrightness = old } }
    }
    DisposableEffect(lifecycleOwner, voice) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                voice.stop()
                val phase = viewModel.uiState.value.phase
                if (phase != DryEyePhase.Result && phase != DryEyePhase.Profile) viewModel.backToSetup("应用切到后台，测试已中断")
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                if (viewModel.uiState.value.phase == DryEyePhase.Setup) ensureCamera()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val running = state.phase in setOf(DryEyePhase.Practice, DryEyePhase.Countdown, DryEyePhase.Natural, DryEyePhase.Open, DryEyePhase.Rest)
    Box(modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalEyeColors.current.appBackground)
            .navigationBarsPadding()
            .imePadding()
            .screenHorizontalPaddingLarge()
    ) {
        TopBackBar(title = "干眼自测", onBackClick = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                EyeText("语音", style = LocalEyeTypography.current.bodySecondary, color = LocalEyeColors.current.textSecondary)
                EyeSwitch(voiceEnabled, { voiceEnabled = it })
            }
            when (state.phase) {
                DryEyePhase.Profile -> QuestionnaireContent(dryEyeQuestions, state.fields, viewModel::setField, viewModel::finishProfile, stateKey = "dryeye")
                DryEyePhase.Setup -> {
                    SetupGate(state, viewModel::startTest)
                    if (!state.faceDetected) EyeButton("重试相机", EyeButtonRole.SECONDARY, { viewModel.stopMonitoring(); ensureCamera() }, Modifier.fillMaxWidth())
                }
                DryEyePhase.PracticeGuide -> TestGuide(GuideMotion.ACUITY, "视标练习 · 约 12 秒", guideCueDryEyePractice, viewModel::confirmPracticeGuide)
                DryEyePhase.FormalGuide -> FormalGuideContent(state, viewModel::confirmFormalGuide)
                DryEyePhase.Countdown -> CountdownContent(state.instruction, state.remainingSeconds)
                DryEyePhase.Practice, DryEyePhase.Natural, DryEyePhase.Open -> {
                    if (distance.zone != DistanceZone.OK) DistanceBanner(distance, compact = true)
                    VoiceAnswerBar(voiceAnswer)
                    TrialContent(state) { viewModel.answer(it) }
                }
                DryEyePhase.Rest -> RestContent(state)
                DryEyePhase.Result -> ResultContent(
                    state,
                    onExtend = viewModel::startExtended,
                    onRest = viewModel::startRestRetest,
                    onRestart = viewModel::restartAll,
                )
            }
            if (running) {
                EyeButton("停止测试", EyeButtonRole.SECONDARY, { viewModel.backToSetup("已停止，本次结果不计入") }, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (distanceMatters) DistanceEdgeGlow(distance.zone)
    }
}

internal const val guideCueDryEyePractice = "先练习一下：2 秒内点选 E 的开口方向；练习不计入结果"

/** 准备页：最佳矫正提示 + 姿势示范 + 40 cm 门禁 + 开始按钮。 */
@Composable
internal fun SetupGate(
    state: DryEyeTestUiState,
    onStart: () -> Unit,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    NoticeCard("建议佩戴最佳矫正视力眼镜进行测试")
    TestGuide(GuideMotion.POSTURE, "调整姿势 · 约 40 cm", "正对屏幕，手机距离眼睛约 40 厘米，光线均匀")
    DistanceBanner(distanceStatus(state.distanceCm, state.faceDetected, DryEyeProtocol.TARGET_DISTANCE_CM, DryEyeProtocol.DISTANCE_TOLERANCE_CM))
    EyeText("测试中可以直接说出 E 的开口方向作答，手机不用离手。", style = typography.bodySecondary, color = colors.textSecondary)
    EyeButton("开始测试", EyeButtonRole.PRIMARY, onStart, Modifier.fillMaxWidth(), state.gateReady)
    EyeDisclosure("测量详情") {
        EyeText("距离 ${formatDistanceCm(state.distanceCm)}（目标 40±5 cm）", style = typography.bodySecondary, color = colors.textSecondary)
        EyeText("环境光 ${state.lux?.let { "%.0f lux".format(it) } ?: "无传感器读数"}", style = typography.bodySecondary, color = colors.textSecondary)
    }
    state.error?.let { EyeText(it, style = typography.body, color = colors.danger) }
}

@Composable
private fun NoticeCard(text: String) {
    val colors = LocalEyeColors.current
    EyeText(
        text, style = LocalEyeTypography.current.body, color = colors.textPrimary,
        modifier = Modifier.fillMaxWidth().clip(EyeShapes.medium).background(colors.accentSoft).padding(14.dp)
    )
}

/** 正式说明：两段任务连续进行，先讲清楚再开始。 */
@Composable
private fun FormalGuideContent(state: DryEyeTestUiState, onStart: () -> Unit) {
    val colors = LocalEyeColors.current
    if (state.afterRest) NoticeCard("休息结束，下面进行复测，流程与首次相同")
    DistanceBanner(distanceStatus(state.distanceCm, state.faceDetected, DryEyeProtocol.TARGET_DISTANCE_CM, DryEyeProtocol.DISTANCE_TOLERANCE_CM), compact = true)
    TestGuide(
        GuideMotion.FVA,
        "正式测试 · ${state.naturalSeconds} 秒 + 20 秒",
        "全程看 E 选方向。前 ${state.naturalSeconds} 秒自然眨眼，随后不停顿，直接尽量保持睁眼 20 秒",
    )
    EyeButton("开始正式测试", EyeButtonRole.PRIMARY, onStart, Modifier.fillMaxWidth(), state.gateReady)
    state.error?.let { EyeText(it, style = LocalEyeTypography.current.body, color = colors.danger) }
}

@Composable
private fun CountdownContent(instruction: String, seconds: Int) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        EyeText(text = "$seconds", style = typography.headline, color = colors.primary)
        Spacer(Modifier.height(12.dp))
        EyeText(text = instruction, style = typography.subtitle, color = colors.textPrimary, textAlign = TextAlign.Center)
    }
}

/** 练习/正式测试中：阶段 + 剩余时间 + 2 秒限时视标 + 方向按键。 */
@Composable
internal fun TrialContent(state: DryEyeTestUiState, onAnswer: (Int) -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val stageLabel = when (state.phase) {
        DryEyePhase.Practice -> "练习（不计入结果）"
        DryEyePhase.Natural -> "自然眨眼"
        else -> "持续睁眼"
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            EyeText(stageLabel, style = typography.subtitle, color = if (state.phase == DryEyePhase.Open) colors.warning else colors.primary)
            EyeText("剩余 ${state.remainingSeconds} 秒", style = typography.subtitle, color = colors.textPrimary)
        }
        LinearProgressIndicator(
            progress = { state.progressFraction },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(EyeShapes.small),
            color = colors.primary,
            trackColor = colors.neutralSoft,
        )
        EyeText(state.instruction, style = typography.body, color = colors.textSecondary)
        val size = with(LocalDensity.current) { state.optotypePx.toDp() }
        OptotypeAnswerPad(state.direction, size, state.trialVisible, onAnswer)
        TrialTimer(state.trialId, state.trialVisible)
        state.qualityWarning?.let { EyeText(it, style = typography.title, color = colors.warning) }
        if (state.phase != DryEyePhase.Practice) {
            EyeText(
                "已记录眨眼 ${if (state.phase == DryEyePhase.Natural) state.phase1BlinkCount else state.phase2BlinkCount} 次 · 已答 ${state.trialCount} 题",
                style = typography.bodySecondary, color = colors.textSecondary
            )
        }
    }
}

/** 每题 2 秒限时条：随题目重置。 */
@Composable
private fun TrialTimer(trialId: Int, visible: Boolean) {
    val colors = LocalEyeColors.current
    val remaining = remember { Animatable(1f) }
    LaunchedEffect(trialId, visible) {
        if (visible) {
            remaining.snapTo(1f)
            remaining.animateTo(0f, tween(DryEyeProtocol.TRIAL_TIMEOUT_MS.toInt(), easing = LinearEasing))
        } else remaining.snapTo(0f)
    }
    LinearProgressIndicator(
        progress = { remaining.value },
        modifier = Modifier.fillMaxWidth().height(4.dp).clip(EyeShapes.small),
        color = colors.accent,
        trackColor = colors.neutralSoft,
    )
}

@Composable
private fun RestContent(state: DryEyeTestUiState) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(24.dp))
        EyeText("闭眼休息", style = typography.title, color = colors.textPrimary)
        EyeText("${state.remainingSeconds}", style = typography.headline, color = colors.primary)
        EyeText("请闭上眼睛放松，倒计时结束会有语音提示", style = typography.body, color = colors.textSecondary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ResultContent(
    state: DryEyeTestUiState,
    onExtend: () -> Unit,
    onRest: () -> Unit,
    onRestart: () -> Unit,
) {
    val colors = LocalEyeColors.current
    val report = state.report ?: return
    val title = when (report.riskLevel) {
        ScreeningRiskLevel.LOW -> "未见明显干眼迹象"
        ScreeningRiskLevel.MEDIUM -> "有一些干眼迹象"
        ScreeningRiskLevel.HIGH -> "干眼迹象比较明显"
        ScreeningRiskLevel.UNKNOWN -> "这次数据无效"
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReportHero(
            level = report.riskLevel,
            badge = if (report.valid) "干眼${report.riskLevel.label}" else "需要重测",
            title = if (state.afterRest) "休息后复测：$title" else title,
            advice = report.advice,
        )
        if (!report.valid) {
            ReportFindings("没测好的原因", report.validityReasons, colors.textSecondary)
        } else {
            ReportMetricGrid(dryEyeMetrics(report))
            ReportFindings("需要关注", report.abnormal, colors.warning)
        }
        state.previousReport?.let { previous -> ComparisonCard(previous, report) }
        if (report.valid && (report.borderline || report.riskLevel == ScreeningRiskLevel.MEDIUM) && state.naturalSeconds == DryEyeProtocol.NATURAL_SECONDS) {
            EyeButton("延长测试（3 分钟）", EyeButtonRole.PRIMARY, onExtend, Modifier.fillMaxWidth())
        }
        if (report.valid && !state.afterRest) {
            EyeButton("闭眼休息 1 分钟后再测", EyeButtonRole.SECONDARY, onRest, Modifier.fillMaxWidth())
        }
        EyeButton("重新检测", EyeButtonRole.SECONDARY, onRestart, Modifier.fillMaxWidth())
        EyeDisclosure("详细数据") {
            report.indicators.forEach { EyeText(it, style = LocalEyeTypography.current.bodySecondary, color = colors.textSecondary) }
        }
        EyeDisclosure("眨眼分布") {
            EyeText("自然眨眼", style = LocalEyeTypography.current.bodySecondary, color = colors.textSecondary)
            BlinkDistributionChart(state.phase1Blinks, state.naturalSeconds, bucketSeconds = if (state.naturalSeconds > 60) 15 else 5)
            EyeText("坚持睁眼", style = LocalEyeTypography.current.bodySecondary, color = colors.textSecondary)
            BlinkDistributionChart(state.phase2Blinks, DryEyeProtocol.OPEN_SECONDS, bucketSeconds = 5)
        }
        EyeDisclosure("测试距离") { DistanceChart(samples = state.distanceSamples) }
        EyeDisclosure("我的问卷回答") { ProfileAnswers(dryEyeQuestions, state.fields) }
        ReportFootnote()
    }
}

/** 延长或休息后复测时，与上一次结果并排对比关键指标。 */
@Composable
private fun ComparisonCard(previous: DryEyeReport, current: DryEyeReport) {
    val typography = LocalEyeTypography.current
    val colors = LocalEyeColors.current
    fun f(v: Double?, digits: Int = 1) = v?.let { "%.${digits}f".format(it) } ?: "—"
    fun p(v: Double?) = v?.let { "${(it * 100).toInt()}%" } ?: "—"
    EyeSectionCard(title = "与上一次对比") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EyeText("眨眼频率：${f(previous.blinkRatePerMin)} → ${f(current.blinkRatePerMin)} 次/分钟", style = typography.body, color = colors.textPrimary)
            EyeText("最长持续睁眼：${f(previous.longestOpenSec)} → ${f(current.longestOpenSec)} 秒", style = typography.body, color = colors.textPrimary)
            EyeText("视觉维持率：${f(previous.vmr, 2)} → ${f(current.vmr, 2)}", style = typography.body, color = colors.textPrimary)
            EyeText("睁眼阶段正确率：${p(previous.accuracyOpen)} → ${p(current.accuracyOpen)}", style = typography.body, color = colors.textPrimary)
        }
    }
}

/** 眨眼时间分布：按桶计数柱状图。 */
@Composable
private fun BlinkDistributionChart(blinks: List<Float>, phaseSeconds: Int, bucketSeconds: Int) {
    val colors = LocalEyeColors.current
    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(blinks, phaseSeconds, bucketSeconds) {
        val bucketCount = (phaseSeconds + bucketSeconds - 1) / bucketSeconds
        val counts = IntArray(bucketCount)
        blinks.forEach { t ->
            val idx = (t / bucketSeconds).toInt().coerceIn(0, bucketCount - 1)
            counts[idx]++
        }
        modelProducer.runTransaction {
            columnSeries {
                series(
                    x = (0 until bucketCount).map { (it * bucketSeconds).toDouble() },
                    y = counts.map { it.toDouble() },
                )
            }
        }
    }

    ProvideVicoTheme(
        rememberM3VicoTheme(lineColor = colors.primary, textColor = colors.textSecondary)
    ) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberColumnCartesianLayer(),
                startAxis = VerticalAxis.rememberStart(
                    valueFormatter = CartesianValueFormatter { _, value, _ -> "${value.toInt()}次" }
                ),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = CartesianValueFormatter { _, value, _ -> "${value.toInt()}s" }
                ),
                getXStep = { bucketSeconds.toDouble() },
            ),
            modelProducer = modelProducer,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
        )
    }
}

/** 观测距离曲线（全程）。 */
@Composable
private fun DistanceChart(samples: List<DryEyeDistanceSample>) {
    if (samples.size < 2) return
    val colors = LocalEyeColors.current
    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(samples) {
        modelProducer.runTransaction {
            lineSeries {
                series(
                    x = samples.map { it.tSec.toDouble() },
                    y = samples.map { it.cm.toDouble() },
                )
            }
        }
    }

    ProvideVicoTheme(
        rememberM3VicoTheme(lineColor = colors.primary, textColor = colors.textSecondary)
    ) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(),
                startAxis = VerticalAxis.rememberStart(
                    valueFormatter = CartesianValueFormatter { _, value, _ -> "${value.toInt()}cm" }
                ),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = CartesianValueFormatter { _, value, _ -> "${value.toInt()}s" }
                ),
            ),
            modelProducer = modelProducer,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
        )
    }
}

/** 供本地界面检查渲染干眼报告（无按钮回调）。 */
@Composable
internal fun DryEyeResultForTest(state: DryEyeTestUiState) = ResultContent(state, onExtend = {}, onRest = {}, onRestart = {})
