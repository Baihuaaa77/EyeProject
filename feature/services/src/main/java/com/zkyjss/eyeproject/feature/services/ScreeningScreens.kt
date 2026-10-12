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
import com.zkyjss.eyeproject.core.ui.component.EyeSwitch
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
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
import com.zkyjss.eyeproject.core.ui.component.EyeSectionCard
import com.zkyjss.eyeproject.core.ui.component.EyeText as BaseEyeText
import com.zkyjss.eyeproject.core.ui.component.TopBackBar
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
    var voiceEnabled by rememberSaveable { mutableStateOf(true) }
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
        ScreeningStep.COVER -> StrabismusProtocol.COVER_SEQUENCE.getOrNull(s.coverMarks.size)?.label ?: "遮盖完成"
        ScreeningStep.PURSUIT -> "请只用双眼跟随小球，保持头部稳定。"
        ScreeningStep.RESULT -> "测试完成，请查看结果。"
    }
    val guidance = screeningGuidance(s)
    val distanceTarget = if (s.guidePending) null else screeningDistanceTarget(s)
    // 单眼近视力时另一只眼被遮，双眼关键点可能不全：有测距即视为可见
    val faceVisible = s.faceDetected || (s.step == ScreeningStep.NEAR_VISION && s.distanceCm > 0f)
    val distance = distanceTarget?.let { (target, tolerance) -> distanceStatus(s.distanceCm, faceVisible, target, tolerance) }
    val voiceActive = !s.guidePending && s.step in setOf(
        ScreeningStep.NEAR_VISION, ScreeningStep.FLIPPER, ScreeningStep.DISTANCE_CURVE, ScreeningStep.PUSH_UP)
    val voiceAnswer = rememberVoiceAnswer(voiceActive, voice) { command ->
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
    val spokenPrompt = if (s.guidePending) guideCue(s.step) else s.warning
        ?: distance?.takeIf { it.clearlyOff() }?.spoken
        ?: guidance?.takeUnless { it == "保持稳定" || it.startsWith("太近") || it.startsWith("太远") || it == "请正对屏幕" }
        ?: voicePrompt
    val distanceSpoken = distance?.takeIf { it.clearlyOff() }?.spoken
    LaunchedEffect(voiceEnabled, spokenPrompt) {
        if (voiceEnabled && spokenPrompt.isNotBlank()) {
            delay(650) // 状态稳定后再播报，避免测距波动不断打断语音。
            if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) voice.speak(spokenPrompt)
            // 距离持续偏离时每 6 秒重复提醒
            while (distanceSpoken != null && spokenPrompt == distanceSpoken) {
                delay(6_000)
                voice.speak(spokenPrompt)
            }
        } else voice.stop()
    }
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
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(colors.appBackground).navigationBarsPadding().imePadding().screenHorizontalPaddingLarge()) {
        TopBackBar(onBackClick = { viewModel.stopCamera(); onBack() })
        if (kind == ScreeningKind.STRABISMUS && s.step != ScreeningStep.PROFILE && s.step != ScreeningStep.RESULT) {
            val stimulusColors = LocalEyeColors.current
            Canvas(Modifier.fillMaxWidth().height(20.dp).clip(EyeShapes.small).background(stimulusColors.black)) {
                drawCircle(stimulusColors.white, radius = 4.dp.toPx(), center = Offset(size.width / 2f, size.height / 2f))
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(pageScroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                EyeText(if (kind == ScreeningKind.PRESBYOPIA) "老视自测" else "眼位自测", typography.title, colors.textPrimary)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EyeText("语音", typography.bodySecondary, colors.textSecondary)
                    EyeSwitch(checked = voiceEnabled, onCheckedChange = { voiceEnabled = it })
                }
            }
            EyeText(stepTitle(s.step), typography.bodySecondary, colors.textSecondary)
            if (voice.available == false && voiceEnabled) EyeText("语音不可用，请看屏幕提示", typography.bodySecondary, colors.warning)
            // 距离：太近红 / 太远蓝 / 合适绿的醒目横幅；其他提示（光照、头姿、移近速度）用文字卡
            if (distance != null && s.step != ScreeningStep.PROFILE) DistanceBanner(distance, compact = s.step != ScreeningStep.ENVIRONMENT)
            val otherGuidance = guidance?.takeUnless { it == "保持稳定" || it.startsWith("太近") || it.startsWith("太远") || (distance != null && it == "请正对屏幕") }
            if (!s.guidePending && otherGuidance != null) {
                EyeSectionCard { EyeText(otherGuidance, typography.title, colors.warning) }
            }
            if (voiceActive) VoiceAnswerBar(
                voiceAnswer,
                hint = when {
                    s.step == ScreeningStep.PUSH_UP -> "说「开始」开始移近，E 一模糊就说「模糊」"
                    s.step == ScreeningStep.NEAR_VISION && s.nearAwaitingEyeReady -> "准备好后说「好了」"
                    else -> "说「上、下、左、右」作答，看不清就说「看不清」"
                }
            )
            s.warning?.let { EyeText(it, typography.body, colors.warning) }
            if (s.guidePending) {
                TestGuide(guideMotion(s.step), stepTitle(s.step), guideCue(s.step), viewModel::confirmGuide)
            } else when (s.step) {

                ScreeningStep.PROFILE -> ProfileContent(kind, s, viewModel)
                ScreeningStep.ENVIRONMENT -> EnvironmentContent(kind, s, viewModel, retryCamera)
                ScreeningStep.NEAR_VISION -> VisionContent(s, viewModel)
                ScreeningStep.PUSH_UP -> PushUpContent(s, viewModel)
                ScreeningStep.DISTANCE_CURVE -> CurveContent(s, viewModel)
                ScreeningStep.FLIPPER -> FlipperContent(s, viewModel)
                ScreeningStep.FIXATION -> FixationContent(s)
                ScreeningStep.COVER -> CoverContent(s, viewModel)
                ScreeningStep.PURSUIT -> PursuitContent(s, viewModel)
                ScreeningStep.RESULT -> ScreeningResultContent(s, viewModel)
            }
            if (s.step != ScreeningStep.PROFILE && s.step != ScreeningStep.RESULT) {
                EyeDisclosure("测量详情") {
                    EyeText(s.trackingStatus, typography.bodySecondary, colors.textSecondary)
                    EyeText("距离 ${if (s.distanceCm > 0f) "%.1f cm".format(s.distanceCm) else "—"} · 光照 ${lightingLabel(s)}", typography.bodySecondary, colors.textSecondary)
                    s.headPose?.let { EyeText("头姿 偏航 %.0f° · 俯仰 %.0f° · 翻滚 %.0f°".format(it.yawDeg, it.pitchDeg, it.rollDeg), typography.bodySecondary, colors.textSecondary) }
                    if (kind == ScreeningKind.STRABISMUS) EyeText(s.opticalStatus, typography.bodySecondary, colors.textSecondary)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    distance?.let { DistanceEdgeGlow(it.zone) }
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
        stateKey = kind,
        extraPageComplete = kind != ScreeningKind.PRESBYOPIA || s.mode.isNotBlank(),
        extraPage = if (kind == ScreeningKind.PRESBYOPIA) {
            {
                EyeText("测试时戴哪种眼镜？", typography.subtitle, colors.textPrimary)
                listOf("标准模式：远用矫正，无近用 Add", "日常模式：保持近用或渐进矫正").forEach { mode ->
                    EyeButton(mode, if (s.mode == mode) EyeButtonRole.PRIMARY else EyeButtonRole.SECONDARY, { vm.setMode(mode) }, Modifier.fillMaxWidth())
                }
                EyeText("标准模式反映真实残余调节能力；日常模式反映平时戴镜的近距离表现。两种模式可各测一次对比。", typography.bodySecondary, colors.textSecondary)
                val age = s.fields["年龄"]?.toIntOrNull()
                if (age != null && age < PresbyopiaProtocol.TARGET_MIN_AGE) {
                    EyeText("本测试主要面向 35 岁以上人群，年轻人的结果仅供参考。", typography.bodySecondary, colors.warning)
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
    EyeSectionCard(title = "${s.visionEye.label} · ${s.trialIndex + 1} / 4") {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            EyeText(if (s.nearAwaitingEyeReady) s.instruction else "点选 E 的开口方向", LocalEyeTypography.current.body, LocalEyeColors.current.textPrimary)
            if (s.nearAwaitingEyeReady) {
                EyeButton("${s.visionEye.label}已准备好", EyeButtonRole.PRIMARY, vm::readyForNearEye)
            } else {
                if (s.trialVisible) OptotypeSurface(s.direction, size)
                else EyeText("停稳后视标会自动出现", LocalEyeTypography.current.subtitle, LocalEyeColors.current.textSecondary)
                DirectionButtons({ vm.answer(it) }, enabled = s.trialVisible)
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
    EyeSectionCard(title = "移近法 · 第 ${(s.nearPoints.size + 1).coerceAtMost(PresbyopiaProtocol.PUSH_REPEATS)}/${PresbyopiaProtocol.PUSH_REPEATS} 次") {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            if (s.pushStage == PushStage.READY) {
                EyeText("先把手机移到 45 cm 以外，再点开始", typography.body, colors.textPrimary)
                EyeButton("开始第 ${s.nearPoints.size + 1} 次", EyeButtonRole.PRIMARY, vm::startPushTrial,
                    enabled = s.distanceCm >= PresbyopiaProtocol.PUSH_START_MIN_CM)
            } else {
                EyeText("看着 E，慢慢把手机移近", typography.body, colors.textPrimary)
                if (s.trialVisible) OptotypeSurface(0, size)
                val speed = s.pushSpeedCmPerSec
                EyeText(
                    "距离 ${"%.1f".format(s.distanceCm)} cm · 速度 ${speed?.let { "%.1f".format(it) } ?: "—"} cm/秒",
                    typography.subtitle,
                    if (speed != null && speed > PresbyopiaProtocol.PUSH_MAX_SPEED_CM_S) colors.warning else colors.textSecondary
                )
                EyeButton("开始持续模糊", EyeButtonRole.PRIMARY, vm::markPushBlur, Modifier.fillMaxWidth())
            }
            EyeText("已记录：${s.nearPoints.joinToString("、") { if (it.censored) "< ${PresbyopiaProtocol.PUSH_MIN_CM.toInt()} cm" else "%.1f cm".format(it.distanceCm) }.ifEmpty { "—" }}",
                typography.body, colors.textSecondary)
        }
    }
}

/** 距离—视觉表现：在 60 / 33 / 25 cm 依次停稳，视标自动出现。 */
@Composable
private fun CurveContent(s: ScreeningState, vm: ScreeningTestViewModel) {
    val size = with(LocalDensity.current) { s.trialOptotypePx.toDp() }
    val target = PresbyopiaProtocol.CURVE_DISTANCES.getOrElse(s.curveIndex) { 40f }
    EyeSectionCard(title = "多距离视力 · ${target.toInt()} cm（${s.curveIndex + 1}/${PresbyopiaProtocol.CURVE_DISTANCES.size}）") {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            EyeText(if (s.trialVisible) "点选 E 的开口方向" else "移到 ${target.toInt()} cm 并停稳", LocalEyeTypography.current.body, LocalEyeColors.current.textPrimary)
            if (s.trialVisible) OptotypeSurface(s.direction, size)
            DirectionButtons({ vm.answer(it) }, enabled = s.trialVisible)
            if (s.curvePoints.isNotEmpty()) {
                EyeText("已完成：" + s.curvePoints.joinToString("、") { "${it.distanceCm.toInt()} cm ${it.thresholdDecimal ?: "—"}" },
                    LocalEyeTypography.current.bodySecondary, LocalEyeColors.current.textSecondary)
            }
            s.nearStopReason?.let { EyeText(it, LocalEyeTypography.current.bodySecondary, LocalEyeColors.current.textSecondary) }
        }
    }
}

@Composable
private fun FlipperContent(s: ScreeningState, vm: ScreeningTestViewModel) {
    val size = with(LocalDensity.current) { s.trialOptotypePx.toDp() }
    EyeSectionCard(title = "远近切换 · ${s.secondsLeft} 秒") {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            EyeText("点选 E 的开口方向", LocalEyeTypography.current.body, LocalEyeColors.current.textPrimary)
            if (s.trialVisible) OptotypeSurface(s.direction, size)
            else EyeText("停稳后开始", LocalEyeTypography.current.subtitle, LocalEyeColors.current.textSecondary)
            DirectionButtons({ vm.answer(it) }, enabled = s.trialVisible)

        }
    }
}

@Composable
internal fun DirectionButtons(onAnswer: (Int) -> Unit, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EyeButton("右 →", EyeButtonRole.SECONDARY, { onAnswer(0) }, Modifier.weight(1f), enabled)
            EyeButton("下 ↓", EyeButtonRole.SECONDARY, { onAnswer(1) }, Modifier.weight(1f), enabled)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EyeButton("← 左", EyeButtonRole.SECONDARY, { onAnswer(2) }, Modifier.weight(1f), enabled)
            EyeButton("↑ 上", EyeButtonRole.SECONDARY, { onAnswer(3) }, Modifier.weight(1f), enabled)
        }
    }
}

@Composable
private fun FixationContent(s: ScreeningState) {
    val age = s.fields["年龄"]?.toIntOrNull()
    EyeSectionCard(title = "注视 · 剩余 ${s.secondsLeft} 秒") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EyeText(s.instruction, LocalEyeTypography.current.title, LocalEyeColors.current.textPrimary)
            FixationTarget(StrabismusProtocol.targetDiameterMm(age), bright = s.targetX > 0f)
        }
    }
}

/**
 * 原文：中央动态闪烁、高对比度的卡通注视点，1–2 Hz；直径 1.5–2 cm。
 * 用小瞳脸作为卡通目标，黑底高对比；「暗」相只降低亮度，不让目标消失。
 */
@Composable
private fun FixationTarget(diameterMm: Float, bright: Boolean) {
    val colors = LocalEyeColors.current
    val dpi = LocalContext.current.resources.displayMetrics.xdpi
    val sizePx = diameterMm / 25.4f * dpi
    Canvas(Modifier.fillMaxWidth().height(240.dp).clip(EyeShapes.medium).background(colors.black)) {
        val s = sizePx.coerceAtMost(minOf(size.width, size.height) * 0.6f)
        if (bright) drawCircle(colors.white, s * 0.75f, center, alpha = 0.18f)
        drawMascot(colors, center, s, lid = 0f)
        if (!bright) drawCircle(colors.black, s * 0.7f, center, alpha = 0.45f)
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
    EyeSectionCard(title = "交替遮盖 · ${done}/${sequence.size}") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FixationTarget(StrabismusProtocol.targetDiameterMm(s.fields["年龄"]?.toIntOrNull()), bright = true)
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
    val radiusPx = 10f / 2f * pxPerMm // 1 cm 小球
    EyeSectionCard(title = "跟随小球 · 剩余 ${s.secondsLeft} 秒") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EyeText(s.instruction, LocalEyeTypography.current.body, colors.textPrimary)
            Canvas(Modifier.fillMaxWidth().height(360.dp).clip(EyeShapes.medium).background(colors.black)) {
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
                drawCircle(colors.white, radiusPx * 1.8f, Offset(x, y), alpha = 0.15f)
                drawCircle(colors.accent, radiusPx, Offset(x, y))
            }
        }
    }
}

@Composable
internal fun ScreeningResultContent(s: ScreeningState, vm: ScreeningTestViewModel) {
    EyeSectionCard(title = "测试结果") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val colors = LocalEyeColors.current
            val riskColor = when (s.riskLevel) {
                ScreeningRiskLevel.LOW -> colors.success
                ScreeningRiskLevel.MEDIUM -> colors.warning
                ScreeningRiskLevel.HIGH -> colors.danger
                ScreeningRiskLevel.UNKNOWN -> colors.textSecondary
            }
            EyeText(s.riskLevel.labelFor(s.kind), LocalEyeTypography.current.title, riskColor)
            EyeText(s.riskScope, LocalEyeTypography.current.subtitle, colors.textPrimary)
            EyeText(when {
                s.riskLevel == ScreeningRiskLevel.UNKNOWN -> "关键数据不足，请改善测试条件并重新完成测试。"
                s.kind == ScreeningKind.STRABISMUS && s.riskLevel == ScreeningRiskLevel.HIGH -> "建议尽快到斜视专科就诊，做遮盖试验与三棱镜检查。"
                s.kind == ScreeningKind.STRABISMUS && s.riskLevel == ScreeningRiskLevel.MEDIUM -> "建议近期到眼科复查眼位；家长可留意孩子歪头、眯眼或一只眼外漂。"
                s.kind == ScreeningKind.STRABISMUS -> "本次未见明显眼位异常，建议每半年复测一次。"
                s.riskLevel == ScreeningRiskLevel.HIGH -> "建议到眼科做验光与调节功能检查，评估是否需要近用矫正。"
                s.riskLevel == ScreeningRiskLevel.MEDIUM -> "建议按相同矫正状态复测；近距离用眼不适时请到眼科检查。"
                else -> "本次调节功能与年龄基本匹配；若有近距离阅读不适，仍建议眼科检查。"
            }, LocalEyeTypography.current.body, colors.textPrimary)
            if (s.riskLevel != ScreeningRiskLevel.UNKNOWN) s.result.firstOrNull { !it.startsWith("评估依据") }?.let { EyeText(it, LocalEyeTypography.current.body, colors.textPrimary) }
            if (s.history.isNotEmpty()) {
                EyeDisclosure("与上次对比") { s.history.forEach { EyeText(it, LocalEyeTypography.current.bodySecondary, colors.textSecondary) } }
            }
            EyeText("初筛参考，不能代替眼科诊断。", LocalEyeTypography.current.bodySecondary, colors.textSecondary)
            EyeDisclosure("完整报告") {
                EyeText("年龄 ${s.fields["年龄"].orEmpty()} · ${if (s.kind == ScreeningKind.PRESBYOPIA) s.mode else "儿童眼动流程"}", LocalEyeTypography.current.body, colors.textPrimary)
                s.fields.forEach { (key, value) -> if (value.isNotBlank()) EyeText("$key：$value", LocalEyeTypography.current.bodySecondary, colors.textSecondary) }
                s.result.forEach { EyeText(it, LocalEyeTypography.current.body, colors.textPrimary) }
                EyeText("光照 ${lightingLabel(s)}", LocalEyeTypography.current.bodySecondary, colors.textSecondary)
                EyeText("规则分级未经临床验证，不代表患病概率；有症状或疑虑请到眼科检查。", LocalEyeTypography.current.bodySecondary, colors.textSecondary)
            }
            EyeButton("重新测试", EyeButtonRole.SECONDARY, vm::restart)
        }
    }
}

@Composable
private fun EyeText(text: String, style: TextStyle, color: Color) {
    BaseEyeText(text = text, style = style, color = color)
}

/** 测试刺激固定黑白对比，独立于应用深浅主题。 */
@Composable
internal fun OptotypeSurface(direction: Int, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.fillMaxWidth().background(LocalEyeColors.current.white).padding(24.dp), contentAlignment = Alignment.Center) {
        TumblingE(direction, size, LocalEyeColors.current.black)
    }
}
