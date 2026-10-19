package com.zkyjss.eyeproject.feature.services

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.sp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.rememberReducedMotion
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
import com.zkyjss.eyeproject.core.ui.component.EyeText

/**
 * 干眼自测页：问卷 → 40 cm 准备 → 视标练习 → 60 秒自然眨眼 + 紧接 20 秒持续睁眼（全程辨认视标）→ 结果。
 * 结果页可延长测试或闭眼休息 1 分钟后复测。
 */
@Composable
fun DryEyeScreen(
    onBack: () -> Unit,
    onEditSubject: (String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DryEyeTestViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val voice = remember(context) { ScreeningVoice(context) }
    val settings = rememberTestSettings()
    LaunchedEffect(settings.speechVolume, settings.speechRate) { voice.configure(settings.speechVolume, settings.speechRate) }
    val voiceEnabled = settings.speechEnabled
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    TestFeedbackEffect(settings, voice, settingsOpen, viewModel::feedbackCues)
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
    // 与共用反馈门控一致：倒计时期间同样要求固定距离，光边和语音不能缺席。
    val distanceMatters = state.phase in setOf(DryEyePhase.Setup, DryEyePhase.FormalGuide, DryEyePhase.Countdown,
        DryEyePhase.Practice, DryEyePhase.Natural, DryEyePhase.Open)
    val voiceAnswer = rememberVoiceAnswer(
        active = !settingsOpen && state.phase in setOf(DryEyePhase.Practice, DryEyePhase.Natural, DryEyePhase.Open),
        voice = voice,
        enabled = settings.answersEnabled,
    ) { command ->
        when (command) {
            is VoiceCommand.Direction -> viewModel.answer(command.direction, byVoice = true)
            VoiceCommand.Unsure -> viewModel.answer(-1, byVoice = true)
            else -> Unit
        }
    }
    val basePrompt = when (state.phase) {
        DryEyePhase.Profile -> "这次测谁？"
        DryEyePhase.Setup -> "建议佩戴最佳矫正视力眼镜。准备好后开始"
        DryEyePhase.PracticeGuide -> guideCueDryEyePractice
        DryEyePhase.FormalGuide -> "正式测试。先自然眨眼，之后不停顿，直接保持睁眼二十秒"
        DryEyePhase.Open -> "现在尽量保持睁眼"
        DryEyePhase.Rest -> if (state.remainingSeconds <= 1) "请睁开眼睛" else "请闭上眼睛休息"
        DryEyePhase.Result -> "测试完成"
        else -> state.qualityWarning ?: state.instruction
    }
    SpeakTestPrompts(voice, voiceEnabled && !settingsOpen, basePrompt, distance.takeIf { distanceMatters && it.clearlyOff() })
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
        TestSessionLayout("干眼自测", onBack, { settingsOpen = true }, running, progress = dryEyeProgress(state),
            status = {
                if (running && state.phase != DryEyePhase.Rest) DistanceBanner(distance, compact = true)
            }) {
            when (state.phase) {
                DryEyePhase.Profile -> {
                    val subjects by viewModel.subjects.collectAsStateWithLifecycle()
                    SubjectPicker("DRY_EYE", subjects, state.subject?.id, viewModel::useSubject, onEditSubject, viewModel::skipProfile)
                }
                DryEyePhase.Setup -> {
                    SetupGate(state, viewModel::startTest)
                    if (!state.faceDetected) EyeButton("重试相机", EyeButtonRole.SECONDARY, { viewModel.stopMonitoring(); ensureCamera() }, Modifier.fillMaxWidth())
                }
                DryEyePhase.PracticeGuide -> TestGuide(GuideMotion.ACUITY, "视标练习 · 约 12 秒", guideCueDryEyePractice, viewModel::confirmPracticeGuide)
                DryEyePhase.FormalGuide -> FormalGuideContent(state, viewModel::confirmFormalGuide)
                DryEyePhase.Countdown -> CountdownContent(state.instruction, state.remainingSeconds)
                DryEyePhase.Practice, DryEyePhase.Natural, DryEyePhase.Open -> {
                    TrialContent(state) { viewModel.answer(it) }
                }
                DryEyePhase.Rest -> RestContent(state)
                DryEyePhase.Result -> ResultContent(state, viewModel::startExtended, viewModel::startRestRetest, viewModel::restartAll,
                    rememberRecordExport(state.savedFile) { viewModel.engine.lastRecordJson })
            }
        }
        if (distanceMatters) DistanceEdgeGlow(distance.zone)
    }
    if (settingsOpen) TestSettingsSheet(settings, voiceAnswer, { settingsOpen = false },
        speechAvailable = voice.available,
        onStop = if (running) { { viewModel.backToSetup("已停止，本次结果不计入") } } else null,
    ) {
        EyeDisclosure("测量详情") {
            EyeText("距离 ${formatDistanceCm(state.distanceCm)}", style = LocalEyeTypography.current.bodySecondary)
            EyeText("环境光 ${state.lux?.let { "%.0f lux".format(it) } ?: "无传感器读数"}", style = LocalEyeTypography.current.bodySecondary)
            EyeText("已答 ${state.trialCount} 题 · 自然眨眼 ${state.phase1BlinkCount} 次 · 睁眼阶段眨眼 ${state.phase2BlinkCount} 次", style = LocalEyeTypography.current.bodySecondary)
        }
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
    // 每一秒环从满到空走一圈，数字缩放淡入；系统移除动画时保持静止。
    val reduced = rememberReducedMotion()
    val sweep = remember { Animatable(1f) }
    LaunchedEffect(seconds) {
        if (reduced) sweep.snapTo(1f) else {
            sweep.snapTo(1f)
            sweep.animateTo(0f, tween(1_000, easing = LinearEasing))
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TimerRing(progress = { sweep.value }, Modifier.size(168.dp)) {
            AnimatedContent(
                targetState = seconds,
                transitionSpec = {
                    if (reduced) EnterTransition.None togetherWith ExitTransition.None
                    else (scaleIn(tween(EyeMotion.MEDIUM, easing = EyeMotion.Standard), initialScale = 0.6f) + fadeIn(tween(EyeMotion.SHORT))) togetherWith
                        (scaleOut(tween(EyeMotion.SHORT), targetScale = 1.3f) + fadeOut(tween(EyeMotion.SHORT)))
                },
                label = "倒计时数字",
            ) { value -> EyeText(text = "$value", style = typography.headline.copy(fontSize = 64.sp), color = colors.primary) }
        }
        EyeText(text = instruction, style = typography.subtitle, color = colors.textPrimary, textAlign = TextAlign.Center)
    }
}

/** 圆环计时：浅色底环 + 主色进度弧，中间放读数。 */
@Composable
private fun TimerRing(progress: () -> Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = LocalEyeColors.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 8.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(colors.neutralSoft, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(colors.primary, -90f, 360f * progress().coerceIn(0f, 1f), false, Offset(inset, inset), arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
        content()
    }
}

/** 练习/正式测试中：阶段 + 剩余时间 + 2 秒限时视标 + 方向按键。 */
@Composable
internal fun TrialContent(state: DryEyeTestUiState, onAnswer: (Int) -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val stageLabel = when (state.phase) {
        DryEyePhase.Practice -> "练习"
        DryEyePhase.Natural -> "自然眨眼"
        else -> "持续睁眼"
    }
    TestStageCard {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                EyeText(stageLabel, style = typography.subtitle, color = if (state.phase == DryEyePhase.Open) colors.warning else colors.primary)
                EyeText("剩余 ${state.remainingSeconds} 秒", style = typography.subtitle, color = colors.textPrimary)
            }
            val size = with(LocalDensity.current) { state.optotypePx.toDp() }
            OptotypeAnswerPad(state.direction, size, state.trialVisible,
                belowStimulus = { TrialTimer(state.trialId, state.trialVisible) }, onAnswer = onAnswer)
            TestNotice(state.qualityWarning)
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
    val remaining by animateFloatAsState(1f - state.progressFraction, tween(250, easing = LinearEasing), label = "休息剩余")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        EyeText("闭眼休息", style = typography.title, color = colors.textPrimary)
        TimerRing(progress = { remaining }, Modifier.size(184.dp)) {
            EyeText("${state.remainingSeconds}", style = typography.headline.copy(fontSize = 56.sp), color = colors.primary)
        }
    }
}

@Composable
private fun ResultContent(
    state: DryEyeTestUiState,
    onExtend: () -> Unit,
    onRest: () -> Unit,
    onRestart: () -> Unit,
    onExport: (() -> Unit)? = null,
) {
    val colors = LocalEyeColors.current
    val report = ResultReports.dryEye(state) ?: return
    ResultReportView(report, onExport, actions = {
        if (state.canExtend) {
            EyeButton("延长测试（3 分钟）", EyeButtonRole.PRIMARY, onExtend, Modifier.fillMaxWidth())
        }
        if (state.canRestRetest) {
            EyeButton("闭眼休息 1 分钟后再测", EyeButtonRole.SECONDARY, onRest, Modifier.fillMaxWidth())
        }
        EyeButton("重新检测", if (state.canExtend) EyeButtonRole.SECONDARY else EyeButtonRole.PRIMARY, onRestart, Modifier.fillMaxWidth())
    }) {
        DataPanel("眨眼分布") {
            EyeText("自然眨眼", style = LocalEyeTypography.current.bodySecondary, color = colors.textSecondary)
            BlinkDistributionChart(state.phase1Blinks, state.naturalSeconds, bucketSeconds = if (state.naturalSeconds > 60) 15 else 5)
            EyeText("坚持睁眼", style = LocalEyeTypography.current.bodySecondary, color = colors.textSecondary)
            BlinkDistributionChart(state.phase2Blinks, DryEyeProtocol.OPEN_SECONDS, bucketSeconds = 5)
        }
        if (state.distanceSamples.size >= 2) DataPanel("测试距离") { DistanceChart(samples = state.distanceSamples) }
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

/** 供本地界面检查渲染倒计时与闭眼休息计时环。 */
@Composable
internal fun DryEyeTimerForTest(state: DryEyeTestUiState) =
    if (state.phase == DryEyePhase.Rest) RestContent(state) else CountdownContent(state.instruction, state.remainingSeconds)
