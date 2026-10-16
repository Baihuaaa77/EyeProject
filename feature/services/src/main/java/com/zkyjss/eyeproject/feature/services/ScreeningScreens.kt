package com.zkyjss.eyeproject.feature.services

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import com.zkyjss.eyeproject.core.ui.component.EyeDisclosure
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingLarge
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeText as BaseEyeText
import com.zkyjss.eyeproject.core.ui.component.drawMascot

@Composable
fun PresbyopiaScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: ScreeningTestViewModel = hiltViewModel()) {
    ScreeningScreen(ScreeningKind.PRESBYOPIA, onBack, modifier, viewModel)
}

@Composable
fun StrabismusScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: ScreeningTestViewModel = hiltViewModel()) {
    ScreeningScreen(ScreeningKind.STRABISMUS, onBack, modifier, viewModel)
}

@Composable
private fun ScreeningScreen(kind: ScreeningKind, onBack: () -> Unit, modifier: Modifier, viewModel: ScreeningTestViewModel) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val pageScroll = rememberScrollState()
    LaunchedEffect(s.step, s.guidePending) { pageScroll.scrollTo(0) }
    val voice = remember(context) { ScreeningVoice(context) }
    val settings = rememberTestSettings()
    LaunchedEffect(settings.speechVolume, settings.speechRate) { voice.configure(settings.speechVolume, settings.speechRate) }
    val voiceEnabled = settings.speechEnabled
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    TestFeedbackEffect(settings, voice, settingsOpen, viewModel::feedbackCues)
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startCamera(owner) else viewModel.cameraPermissionDenied()
    }
    val ensureCamera: () -> Unit = {
        if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startCamera(owner)
        } else cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
    val retryCamera: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            viewModel.retryCamera(owner)
        } else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(kind) { viewModel.configure(kind) }
    LaunchedEffect(owner, s.step) {
        if (s.step == ScreeningStep.ENVIRONMENT) ensureCamera()
    }
    LaunchedEffect(s.trialVisible, s.trialId) {
        if (s.trialVisible) {
            withFrameNanos { }
            withFrameNanos { }
            viewModel.markTrialPresented(s.trialId, android.os.SystemClock.elapsedRealtime())
        }
    }
    val voicePrompt = when (s.step) {
        ScreeningStep.PROFILE -> "请先填写基本信息。"
        ScreeningStep.ENVIRONMENT -> if (kind == ScreeningKind.PRESBYOPIA) "请正对屏幕，保持约四十厘米，检查光照和距离。" else "请正对屏幕，保持三十三厘米，由家长协助后续遮眼任务。"
        ScreeningStep.NEAR_VISION -> if (s.nearAwaitingEyeReady) s.instruction else "${s.visionEye.label}测试。保持四十厘米，停稳后显示视标。"
        ScreeningStep.PUSH_UP -> if (s.pushStage == PushStage.READY) "第${s.nearPoints.size + 1}次移近。先移到四十五厘米以外。" else "看着E，慢慢移近。一开始持续模糊就点按。"
        ScreeningStep.DISTANCE_CURVE -> "请移动到${PresbyopiaProtocol.CURVE_DISTANCES.getOrElse(s.curveIndex) { 40f }.toInt()}厘米并停稳，视标会自动出现。"
        ScreeningStep.FLIPPER -> "请移动到${if (s.trialIndex % 2 == 0) "五十" else "二十五"}厘米。停稳后视标自动出现，再选择开口方向。"
        ScreeningStep.FIXATION -> "请保持三十三厘米，看着中间闪烁的小瞳，头和手机都不要动。"
        ScreeningStep.GAZE_CALIBRATION -> "请只用眼睛依次看着圆点，头和手机都不要动。"
        ScreeningStep.COVER -> coverSpokenPrompt(s)
        ScreeningStep.PURSUIT -> "请只用双眼跟随移动的小瞳，保持头部稳定。"
        ScreeningStep.RESULT -> "测试完成，请查看结果。"
    }
    val guidance = screeningGuidance(s)
    val distanceTarget = if (s.guidePending) null else screeningDistanceTarget(s)
    // 单眼近视力时另一只眼被遮，双眼关键点可能不全：有测距即视为可见
    val faceVisible = s.faceDetected || (s.step == ScreeningStep.NEAR_VISION && s.distanceCm > 0f)
    val distance = distanceTarget?.let { (target, tolerance) -> distanceStatus(s.distanceCm, faceVisible, target, tolerance) }
    val voiceActive = !s.guidePending && s.step in setOf(
        ScreeningStep.NEAR_VISION, ScreeningStep.FLIPPER, ScreeningStep.DISTANCE_CURVE, ScreeningStep.PUSH_UP)
    val voiceAnswer = rememberVoiceAnswer(voiceActive && !settingsOpen, voice, settings.answersEnabled) { command ->
        val st = viewModel.state.value
        when {
            st.step == ScreeningStep.PUSH_UP -> when (command) {
                VoiceCommand.Blur, VoiceCommand.Unsure -> viewModel.markPushBlur()
                VoiceCommand.Ready -> viewModel.startPushTrial()
                else -> Unit
            }
            st.step == ScreeningStep.NEAR_VISION && st.nearAwaitingEyeReady ->
                if (command == VoiceCommand.Ready) viewModel.readyForNearEye()
            command is VoiceCommand.Direction -> viewModel.answer(command.direction, byVoice = true)
            command == VoiceCommand.Unsure -> viewModel.answer(-1, byVoice = true)
        }
    }
    // 引导与告警优先于距离提醒，距离提醒优先于其余提示。
    val urgent = if (s.guidePending) guideCue(s.step) else s.gazeWarning ?: s.warning
    val basePrompt = urgent ?: guidance?.takeUnless { it == "保持稳定" || it.startsWith("太近") || it.startsWith("太远") || it == "请正对屏幕" }
        ?: voicePrompt
    SpeakTestPrompts(voice, voiceEnabled && !settingsOpen, basePrompt, distance?.takeIf { urgent == null && it.clearlyOff() })
    DisposableEffect(viewModel, voice) { onDispose { viewModel.stopCamera(); voice.close() } }
    DisposableEffect(kind, context) {
        val window = (context as? Activity)?.window
        val oldBrightness = window?.attributes?.screenBrightness
        if (window != null) {
            window.attributes = window.attributes.apply { screenBrightness = 1f }
        }
        onDispose {
            if (window != null && oldBrightness != null) {
                window.attributes = window.attributes.apply { screenBrightness = oldBrightness }
            }
        }
    }
    DisposableEffect(owner, voice) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                voice.stop()
                viewModel.interruptTest()
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                if (viewModel.state.value.step == ScreeningStep.ENVIRONMENT) ensureCamera()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val activeTest = !s.guidePending && s.step !in setOf(ScreeningStep.PROFILE, ScreeningStep.ENVIRONMENT, ScreeningStep.RESULT)
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Box(modifier.fillMaxSize()) {
        TestSessionLayout(
            title = if (kind == ScreeningKind.PRESBYOPIA) "老视自测" else "眼位自测",
            onBack = { viewModel.stopCamera(); onBack() }, onSettings = { settingsOpen = true },
            centered = activeTest, scroll = pageScroll, progress = screeningProgress(s),
            reflexStimulus = kind == ScreeningKind.STRABISMUS && s.step != ScreeningStep.PROFILE && s.step != ScreeningStep.RESULT,
            status = { distance?.let { DistanceBanner(it, compact = true) } },
        ) {
            val notice = s.gazeWarning ?: s.warning ?: guidance?.takeUnless {
                it == "保持稳定" || it == "慢慢移近" || it.startsWith("太近") || it.startsWith("太远") || (distance != null && it == "请正对屏幕")
            }
            TestNotice(notice.takeIf { !s.guidePending })
            if (s.guidePending) {
                TestGuide(guideMotion(s.step), stepTitle(s.step), guideCue(s.step), viewModel::confirmGuide)
            } else when (s.step) {
                ScreeningStep.PROFILE -> ProfileContent(kind, s, viewModel)
                ScreeningStep.ENVIRONMENT -> EnvironmentContent(kind, s, viewModel, retryCamera)
                ScreeningStep.NEAR_VISION -> VisionContent(s, viewModel)
                ScreeningStep.PUSH_UP -> PushUpContent(s, viewModel)
                ScreeningStep.DISTANCE_CURVE -> CurveContent(s, viewModel)
                ScreeningStep.FLIPPER -> FlipperContent(s, viewModel)
                ScreeningStep.GAZE_CALIBRATION -> GazeCalibrationContent(s, viewModel)
                ScreeningStep.FIXATION -> FixationContent(s, viewModel)
                ScreeningStep.COVER -> CoverContent(s, viewModel)
                ScreeningStep.PURSUIT -> PursuitContent(s, viewModel)
                ScreeningStep.RESULT -> ScreeningResultContent(s, viewModel)
            }
        }
        distance?.let { DistanceEdgeGlow(it.zone) }
    }
    if (settingsOpen) TestSettingsSheet(settings, voiceAnswer, { settingsOpen = false }, speechAvailable = voice.available) {
        EyeDisclosure("测量详情") {
            EyeText(s.trackingStatus, typography.bodySecondary, colors.textSecondary)
            EyeText("距离 ${if (s.distanceCm > 0f) "%.1f cm".format(s.distanceCm) else "—"} · 光照 ${lightingLabel(s)}", typography.bodySecondary, colors.textSecondary)
            s.headPose?.let { EyeText("头姿 偏航 %.0f° · 俯仰 %.0f° · 翻滚 %.0f°".format(it.yawDeg, it.pitchDeg, it.rollDeg), typography.bodySecondary, colors.textSecondary) }
            if (kind == ScreeningKind.STRABISMUS) EyeText(s.opticalStatus, typography.bodySecondary, colors.textSecondary)
            if (s.step == ScreeningStep.PUSH_UP) EyeText("已记录：${s.nearPoints.joinToString("、") { "%.1f cm".format(it.distanceCm) }.ifEmpty { "—" }}", typography.bodySecondary, colors.textSecondary)
        }
    }
}

private fun lightingLabel(s: ScreeningState): String = when {
    s.lux != null -> "%.0f lux".format(s.lux)
    s.lightSensorAvailable == false && s.cameraBrightness != null ->
        "画面相对亮度 %.0f/255（设备无 lux 传感器）".format(s.cameraBrightness)
    s.cameraBrightness != null -> "画面相对亮度 %.0f/255（lux 暂无读数）".format(s.cameraBrightness)
    s.lightSensorAvailable == false -> "无光线传感器，等待画面亮度"
    else -> "读取中"
}

@Composable
internal fun ProfileContent(kind: ScreeningKind, s: ScreeningState, vm: ScreeningTestViewModel) {
    val questions = if (kind == ScreeningKind.PRESBYOPIA) presbyopiaQuestions else strabismusQuestions
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    QuestionnaireContent(
        questions = questions,
        fields = s.fields,
        onSetField = vm::setField,
        onFinish = vm::nextFromProfile,
        onSkip = vm::skipProfile,
        stateKey = kind,
        extraPageComplete = kind != ScreeningKind.PRESBYOPIA || s.mode.isNotBlank(),
        extraPage = if (kind == ScreeningKind.PRESBYOPIA) {
            {
                EyeText("这次测试戴哪副眼镜？", typography.title, colors.textPrimary)
                OptionRow(
                    "戴看远的眼镜，或不戴眼镜",
                    "推荐：能测出眼睛本身的调节能力",
                    s.mode.startsWith("标准"), multiple = false,
                ) { vm.setMode(PRESBYOPIA_MODE_STANDARD) }
                OptionRow(
                    "戴平时看近用的眼镜",
                    "老花镜或渐进镜：看戴镜后的实际效果",
                    s.mode.startsWith("日常"), multiple = false,
                ) { vm.setMode(PRESBYOPIA_MODE_DAILY) }
                if (nearCorrectionInUse(s.fields) && s.mode.startsWith("标准")) {
                    EyeText("请先摘下老花镜或渐进镜。", typography.body, colors.warning)
                }
                val age = s.fields["年龄"]?.toIntOrNull()
                if (age != null && age < PresbyopiaProtocol.TARGET_MIN_AGE) {
                    EyeText("这项测试主要适合 35 岁以上，结果仅供参考。", typography.body, colors.textSecondary)
                }
            }
        } else null,
    )
}

@Composable
private fun EnvironmentContent(kind: ScreeningKind, s: ScreeningState, vm: ScreeningTestViewModel, onRetryCamera: () -> Unit) {
    TestGuide(GuideMotion.POSTURE, "调整姿势", if (kind == ScreeningKind.PRESBYOPIA) "正对屏幕，约 40 厘米" else "正对屏幕，约 33 厘米；请协助者在旁准备")
    if (s.cameraFrames == 0 || !s.faceDetected || s.distanceCm <= 0f) {
        EyeButton("重试相机", EyeButtonRole.SECONDARY, onRetryCamera, Modifier.fillMaxWidth())
    }
    EyeButton("开始测试", EyeButtonRole.PRIMARY, vm::begin, Modifier.fillMaxWidth(), enabled = s.faceDetected && s.distanceCm > 0f)
}

@Composable
internal fun VisionContent(s: ScreeningState, vm: ScreeningTestViewModel) {
    val size = with(LocalDensity.current) { s.trialOptotypePx.toDp() }
    TestStageCard(title = "${s.visionEye.label} · ${s.trialIndex + 1} / 4") {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            if (s.nearAwaitingEyeReady) {
                EyeText(s.instruction, LocalEyeTypography.current.body, LocalEyeColors.current.textPrimary)
                EyeButton("${s.visionEye.label}已准备好", EyeButtonRole.PRIMARY, vm::readyForNearEye)
            } else {
                if (!s.trialVisible) EyeText("停稳后视标会自动出现", LocalEyeTypography.current.subtitle, LocalEyeColors.current.textSecondary)
                OptotypeAnswerPad(s.direction, size, s.trialVisible) { vm.answer(it) }
            }
            s.nearStopReason?.let { EyeText(it, LocalEyeTypography.current.bodySecondary, LocalEyeColors.current.textSecondary) }
        }
    }
}

@Composable
internal fun PushUpContent(s: ScreeningState, vm: ScreeningTestViewModel) {
    val size = with(LocalDensity.current) { s.trialOptotypePx.toDp() }
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    TestStageCard(title = "第 ${(s.nearPoints.size + 1).coerceAtMost(PresbyopiaProtocol.PUSH_REPEATS)}/${PresbyopiaProtocol.PUSH_REPEATS} 次") {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            if (s.pushStage == PushStage.READY) {
                EyeText("先把手机移到 45 cm 以外，再点开始", typography.body, colors.textPrimary)
                EyeButton("开始第 ${s.nearPoints.size + 1} 次", EyeButtonRole.PRIMARY, vm::startPushTrial,
                    enabled = s.distanceCm >= PresbyopiaProtocol.PUSH_START_MIN_CM)
            } else {
                EyeText("缓慢移近", typography.body, colors.textPrimary)
                if (s.trialVisible) OptotypeSurface(0, size)
                EyeButton("开始持续模糊", EyeButtonRole.PRIMARY, vm::markPushBlur, Modifier.fillMaxWidth())
            }

        }
    }
}

/** 距离—视觉表现：在 60 / 33 / 25 cm 依次停稳，视标自动出现。 */
@Composable
private fun CurveContent(s: ScreeningState, vm: ScreeningTestViewModel) {
    val size = with(LocalDensity.current) { s.trialOptotypePx.toDp() }
    val target = PresbyopiaProtocol.CURVE_DISTANCES.getOrElse(s.curveIndex) { 40f }
    TestStageCard(title = "${target.toInt()} cm · ${s.curveIndex + 1}/${PresbyopiaProtocol.CURVE_DISTANCES.size}") {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            if (!s.trialVisible) EyeText("移到 ${target.toInt()} cm 并停稳", LocalEyeTypography.current.body, LocalEyeColors.current.textPrimary)
            OptotypeAnswerPad(s.direction, size, s.trialVisible) { vm.answer(it) }
            s.nearStopReason?.let { EyeText(it, LocalEyeTypography.current.bodySecondary, LocalEyeColors.current.textSecondary) }
        }
    }
}

@Composable
internal fun FlipperContent(s: ScreeningState, vm: ScreeningTestViewModel) {
    val size = with(LocalDensity.current) { s.trialOptotypePx.toDp() }
    TestStageCard(title = "${if (s.trialIndex % 2 == 0) 50 else 25} cm · ${s.secondsLeft} 秒") {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            if (!s.trialVisible) EyeText("停稳后开始", LocalEyeTypography.current.subtitle, LocalEyeColors.current.textSecondary)
            OptotypeAnswerPad(s.direction, size, s.trialVisible) { vm.answer(it) }

        }
    }
}

@Composable
private fun FixationContent(s: ScreeningState, vm: ScreeningTestViewModel) {
    val age = s.fields["年龄"]?.toIntOrNull()
    TestStageCard(title = "剩余 ${s.secondsLeft} 秒") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FixationTarget(StrabismusProtocol.targetDiameterMm(age), bright = s.targetX > 0f, vm = vm)
        }
    }
}

/**
 * 原文：中央动态闪烁、高对比度的卡通注视点，1–2 Hz；直径 1.5–2 cm。
 * 用小瞳脸作为卡通目标，白底呈现；整体透明度交替变化，让小瞳本身闪烁。
 */
@Composable
private fun FixationTarget(diameterMm: Float, bright: Boolean, vm: ScreeningTestViewModel) {
    val colors = LocalEyeColors.current
    val dpi = LocalContext.current.resources.displayMetrics.xdpi
    val sizePx = diameterMm / 25.4f * dpi
    var origin by remember { mutableStateOf(Offset.Zero) }
    Canvas(Modifier.fillMaxWidth().height(240.dp + rememberStageExtra()).clip(EyeShapes.medium).background(colors.white)
        .onGloballyPositioned { origin = it.positionInWindow() }) {
        val s = sizePx.coerceAtMost(minOf(size.width, size.height) * 0.6f)
        drawContext.canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = if (bright) 1f else 0.3f })
        drawMascot(colors, center, s, lid = 0f)
        drawContext.canvas.restore()
        vm.reportGazeTarget(origin.x + center.x, origin.y + center.y)
    }
}

@Composable
internal fun GazeCalibrationContent(s: ScreeningState, vm: ScreeningTestViewModel) {
    val colors = LocalEyeColors.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    // 换点时外圈从大收拢，帮助视线找到新位置；圆点中心即上报位置，立即到位。
    val reduced = com.zkyjss.eyeproject.core.designsystem.theme.rememberReducedMotion()
    val halo = remember { androidx.compose.animation.core.Animatable(1f) }
    // 补测轮重看同一点时 round 变化，也会重播动效并触发重绘上报位置。
    LaunchedEffect(s.gazeCalibrationIndex, s.gazeCalibrationRound, s.gazeCalibrationFailed) {
        if (reduced || s.gazeCalibrationFailed) halo.snapTo(1f) else {
            halo.snapTo(2.4f)
            halo.animateTo(1f, com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion.settleSpring())
        }
    }
    TestStageCard(title = "注视校准 · ${s.gazeCalibrationIndex + 1}/${StrabismusGaze.calibrationTargets.size}") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 与追踪画布同高（两屏卡片结构相同，伸展量也相同）：校准点覆盖追踪目标会到达的整个范围，避免边缘外推。
            Canvas(Modifier.fillMaxWidth().height(PURSUIT_CANVAS_HEIGHT + rememberStageExtra()).clip(EyeShapes.medium).background(colors.white)
                .onGloballyPositioned { origin = it.positionInWindow() }) {
                val target = StrabismusGaze.calibrationTargets[s.gazeCalibrationIndex]
                val point = Offset(size.width * target.x, size.height * target.y)
                drawCircle(StageAccent.copy(alpha = 0.18f), radius = 16.dp.toPx() * halo.value, center = point)
                drawCircle(StageAccent, radius = 8.dp.toPx(), center = point)
                drawCircle(colors.white, radius = 2.5.dp.toPx(), center = point)
                vm.reportGazeTarget(origin.x + point.x, origin.y + point.y)
            }
            if (s.gazeCalibrationFailed) EyeButton("重试注视校准", EyeButtonRole.PRIMARY, vm::retryGazeCalibration, Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun CoverContent(s: ScreeningState, vm: ScreeningTestViewModel) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val sequence = StrabismusProtocol.COVER_SEQUENCE
    val done = s.coverMarks.size
    val next = sequence.getOrNull(done)
    val current = sequence.getOrNull(done - 1)
    TestStageCard(title = "交替遮盖 · ${done}/${sequence.size}") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FixationTarget(StrabismusProtocol.targetDiameterMm(s.fields["年龄"]?.toIntOrNull()), bright = true, vm = vm)
            if (current != null && current.holdMs > 0) HoldTimer(done, current.holdMs, "保持：${current.label}")
            EyeText(next?.let { "下一步：${it.label}" } ?: "遮盖完成", typography.title, colors.textPrimary)
            CoverSequenceStrip(done)
        }
    }
    EyeButton(next?.let { "执行「${it.label}」时点按" } ?: "已完成", EyeButtonRole.PRIMARY, vm::markCover, Modifier.fillMaxWidth(), enabled = next != null)
}

/** 当前动作需保持的时长（原文 2 秒 / 1 秒），到点后再进行下一动作。 */
@Composable
private fun HoldTimer(key: Int, holdMs: Long, text: String) {
    val colors = LocalEyeColors.current
    val progress = remember(key) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(key) { progress.animateTo(1f, androidx.compose.animation.core.tween(holdMs.toInt(), easing = androidx.compose.animation.core.LinearEasing)) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        EyeText(if (progress.value >= 1f) "可以进行下一步" else text, LocalEyeTypography.current.body,
            if (progress.value >= 1f) colors.success else colors.textSecondary)
        androidx.compose.material3.LinearProgressIndicator(
            progress = { progress.value },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(EyeShapes.small),
            color = colors.primary, trackColor = colors.neutralSoft,
        )
    }
}

/** 遮盖序列一览：已完成 / 当前 / 待做。 */
@Composable
private fun CoverSequenceStrip(done: Int) {
    val colors = LocalEyeColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        StrabismusProtocol.COVER_SEQUENCE.forEachIndexed { index, action ->
            val tint = when {
                index < done -> colors.primary
                index == done -> colors.accent
                else -> colors.neutralSoft
            }
            Box(Modifier.weight(if (action.holdMs >= 2_000L) 2f else 1f).height(8.dp).clip(EyeShapes.small).background(tint))
        }
    }
}

@Composable
private fun PursuitContent(s: ScreeningState, vm: ScreeningTestViewModel) {
    val colors = LocalEyeColors.current
    val metrics = LocalContext.current.resources.displayMetrics
    val pxPerMm = metrics.xdpi / 25.4f
    val distanceMm = (if (s.distanceCm > 0f) s.distanceCm else StrabismusProtocol.DISTANCE_CM) * 10f
    val radiusPx = 10f / 2f * pxPerMm // 保持原有 1 cm 目标包络和轨迹边界。
    var origin by remember { mutableStateOf(Offset.Zero) }
    TestStageCard(title = "剩余 ${s.secondsLeft} 秒") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Canvas(Modifier.fillMaxWidth().height(PURSUIT_CANVAS_HEIGHT + rememberStageExtra()).clip(EyeShapes.medium).background(colors.white)
                .onGloballyPositioned { origin = it.positionInWindow() }) {
                val halfW = size.width / 2f - radiusPx
                val halfH = size.height / 2f - radiusPx
                // 原文 ±10° 按实测距离换算像素；超出画布时等比缩小并上报实际可显示角度
                fun degToPx(deg: Float) = (kotlin.math.tan(Math.toRadians(deg.toDouble())) * distanceMm * pxPerMm).toFloat()
                val maxX = Math.toDegrees(kotlin.math.atan((halfW / pxPerMm / distanceMm).toDouble())).toFloat()
                val maxY = Math.toDegrees(kotlin.math.atan((halfH / pxPerMm / distanceMm).toDouble())).toFloat()
                vm.reportPursuitCapacity(maxX, maxY)
                val scale = minOf(1f, maxX / StrabismusProtocol.PURSUIT_AMPLITUDE_DEG, maxY / StrabismusProtocol.PURSUIT_AMPLITUDE_DEG)
                val x = size.width / 2f + degToPx(s.targetX * scale)
                val y = size.height / 2f - degToPx(s.targetY * scale)
                // 含头顶嫩芽的整个小瞳放入原目标包络，不改变轨迹或注视位置上报。
                drawMascot(colors, Offset(x, y), radiusPx / 0.65f, lid = 0f)
                vm.reportGazeTarget(origin.x + x, origin.y + y)
            }
        }
    }
}

@Composable
internal fun ScreeningResultContent(s: ScreeningState, vm: ScreeningTestViewModel) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val valid = s.riskLevel != ScreeningRiskLevel.UNKNOWN
    val strabismus = s.kind == ScreeningKind.STRABISMUS
    val badge = when {
        !valid -> "需要重测"
        strabismus -> when (s.riskLevel) {
            ScreeningRiskLevel.LOW -> "未见异常"
            ScreeningRiskLevel.MEDIUM -> "可疑"
            else -> "高度疑似"
        }
        else -> "老视${s.riskLevel.label}"
    }
    val title = when {
        !valid -> "数据不足，暂时无法判断"
        strabismus -> when (s.riskLevel) {
            ScreeningRiskLevel.LOW -> "眼位未见明显异常"
            ScreeningRiskLevel.MEDIUM -> "眼位有可疑表现"
            else -> "高度疑似斜视"
        }
        else -> s.category.ifBlank { s.riskLevel.label }
    }
    val advice = when {
        !valid -> "关键数据不足，请改善测试条件并重新完成测试。"
        strabismus && s.riskLevel == ScreeningRiskLevel.HIGH -> "建议尽快到斜视专科就诊，做遮盖试验与三棱镜检查。"
        strabismus && s.riskLevel == ScreeningRiskLevel.MEDIUM -> "建议近期到眼科复查眼位；平时留意孩子是否歪头、眯眼或一只眼往外飘。"
        strabismus -> "本次未见明显眼位异常，建议每半年复测一次。"
        s.riskLevel == ScreeningRiskLevel.HIGH -> "建议到眼科验光并检查调节功能，评估是否需要配老花镜。"
        s.riskLevel == ScreeningRiskLevel.MEDIUM -> "建议用同样的眼镜再测一次；看近处吃力时请到眼科检查。"
        else -> "调节能力与年龄基本相符；如果看近处不舒服，仍建议到眼科检查。"
    }
    // 关键指标卡已展示的测量汇总不再重复列出，只保留需要关注的结论性条目
    val summaryPrefixes = listOf("注视眼位", "遮盖解除后再注视", "双眼对称性指数", "40 cm 阅读需调节", "调节年龄参考")
    val attention = s.findings.drop(if (!strabismus && valid) 1 else 0).filterNot { f -> summaryPrefixes.any(f::startsWith) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReportHero(s.riskLevel, badge, title, advice)
        if (!valid) {
            ReportFindings("没测好的原因", s.findings, colors.textSecondary)
        } else {
            ReportMetricGrid(if (strabismus) strabismusMetrics(s.features) else presbyopiaMetrics(s.features))
            ReportFindings("需要关注", attention, colors.warning)
            if (!strabismus) s.features["amplitude_age_years"]?.let {
                EyeText("你的调节能力大约相当于 ${it.toInt()} 岁人群的平均水平（参考）。", typography.body, colors.textSecondary)
            }
        }
        EyeButton("重新测试", EyeButtonRole.SECONDARY, vm::restart, Modifier.fillMaxWidth())
        if (s.history.isNotEmpty()) {
            EyeDisclosure("与上次对比") { s.history.forEach { EyeText(it, typography.bodySecondary, colors.textSecondary) } }
        }
        EyeDisclosure("完整报告") {
            s.result.forEach { EyeText(it, typography.bodySecondary, colors.textSecondary) }
            EyeText("光照 ${lightingLabel(s)}", typography.bodySecondary, colors.textSecondary)
        }
        EyeDisclosure(if (strabismus) "孩子的基本信息" else "我的问卷回答") {
            ProfileAnswers(if (strabismus) strabismusQuestions else presbyopiaQuestions, s.fields)
            if (!strabismus && s.mode.isNotBlank()) EyeText("测试戴镜：${if (s.mode.startsWith("标准")) "看远的眼镜或不戴镜" else "看近用的眼镜"}", typography.body, colors.textPrimary)
        }
        ReportFootnote()
    }
}

/** 追踪与注视校准共用的画布高度。 */
private val PURSUIT_CANVAS_HEIGHT = 360.dp

@Composable
private fun EyeText(text: String, style: TextStyle, color: Color) {
    BaseEyeText(text = text, style = style, color = color)
}

/** 测试刺激固定黑白对比，独立于应用深浅主题。 */
@Composable
internal fun OptotypeSurface(direction: Int, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.fillMaxWidth().height(ringDiameter(size) + 48.dp + rememberStageExtra()).clip(EyeShapes.large)
        .background(LocalEyeColors.current.white), contentAlignment = Alignment.Center) {
        FramedOptotype(direction, size)
    }
}
