package com.zkyjss.eyeproject.feature.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zkyjss.eyeproject.core.camera.CameraController
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.EyeSpacing
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.theme.rememberReducedMotion
import com.zkyjss.eyeproject.core.domain.EyeExerciseCoach
import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeCue
import com.zkyjss.eyeproject.core.ui.component.EyeFullScreenPage
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.EyeWarmCard
import com.zkyjss.eyeproject.core.ui.component.rememberEyeCueSounds
import com.zkyjss.eyeproject.core.ui.component.rememberEyeHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.min
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 护眼操的安卓桥：判断与计时在共用 [EyeExerciseCoach]。实时检测正开着时直接借用它的相机帧，
 * 否则自己开前置相机（无预览、只分析），做完即关。
 */
@HiltViewModel
class EyeExerciseViewModel @Inject constructor(private val camera: CameraController) : ViewModel() {
    private var coach = EyeExerciseCoach()
    private val _state = MutableStateFlow(coach.state(0L))
    val state: StateFlow<EyeExerciseCoach.State> = _state.asStateFlow()
    private val _events = MutableSharedFlow<EyeExerciseCoach.Event>(extraBufferCapacity = 8)
    val events: SharedFlow<EyeExerciseCoach.Event> = _events.asSharedFlow()
    private var generation = -1
    private var ticker: Job? = null
    private val observer: (FaceLandmarkFrame) -> Unit = { frame ->
        coach.onFace(frame.faces.firstOrNull(), frame.analysisElapsedRealtimeMs ?: SystemClock.elapsedRealtime())
    }

    /** 从头开始一套；[cameraAllowed] 为 false 时按时间带着做。 */
    fun start(owner: LifecycleOwner, cameraAllowed: Boolean) {
        stop()
        coach = EyeExerciseCoach()
        _state.value = coach.state(0L)
        if (cameraAllowed) {
            camera.addFrameObserver(observer)
            if (!camera.isRequested) generation = camera.start(owner, null, onFrame = {}, onError = {})
        }
        ticker = viewModelScope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                coach.tick(now)?.let { _events.tryEmit(it) }
                _state.value = coach.state(now)
                delay(50)
            }
        }
    }

    val summary: Pair<Int, Int> get() = coach.summary
    val usedCamera: Boolean get() = coach.usedCamera

    fun stop() {
        ticker?.cancel(); ticker = null
        camera.removeFrameObserver(observer)
        if (generation >= 0) camera.stopIfGeneration(generation)
        generation = -1
    }

    override fun onCleared() = stop()
}

/**
 * 「护眼操」互动小游戏全屏页（暖夜寻星）：
 * 小猫头鹰在深咖啡星空中央望向你的真实视线方向；屏幕左/右/上/下方位轮流亮起迷路暖星，
 * 转动眼球看过去即可充能并把星星收进星盘，最后和小猫头鹰一起闭眼听提示音休息 5 秒。
 * 没有相机权限时按节奏引导完成。与苹果端 EyeExerciseView 保持同一情景、流程与文案。
 */
@Composable
internal fun EyeExercisePage(onClose: () -> Unit, viewModel: EyeExerciseViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val haptics = rememberEyeHaptics()
    val sounds = rememberEyeCueSounds()
    val scope = rememberCoroutineScope()
    val reducedMotion = rememberReducedMotion()
    val state by viewModel.state.collectAsStateWithLifecycle()

    var started by remember { mutableStateOf(false) }
    var feedbackBanner by remember { mutableStateOf<String?>(null) }
    var caughtMove by remember { mutableStateOf<EyeExerciseCoach.Move?>(null) }
    var owlHappy by remember { mutableStateOf(false) }
    val cometProgress = remember { Animatable(1f) }
    val restRipple = remember { Animatable(0f) }

    fun launchExercise(granted: Boolean) {
        feedbackBanner = null
        caughtMove = null
        owlHappy = false
        started = true
        viewModel.start(owner, granted)
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        launchExercise(granted)
    }

    fun requestStart() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) launchExercise(true) else permission.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(Unit) { onDispose { viewModel.stop() } }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val snapshot = viewModel.state.value
            when (event) {
                EyeExerciseCoach.Event.STARTED -> {
                    sounds.play(EyeCue.START)
                }
                EyeExerciseCoach.Event.DETECTED -> {
                    val move = snapshot.move
                    caughtMove = move
                    owlHappy = true
                    feedbackBanner = StarOwlCopy.caughtFeedback(move, snapshot.streak)
                    haptics.success()
                    sounds.play(EyeCue.PHASE)
                    if (!reducedMotion) {
                        scope.launch {
                            cometProgress.snapTo(0f)
                            cometProgress.animateTo(1f, tween(760, easing = FastOutSlowInEasing))
                        }
                    }
                }
                EyeExerciseCoach.Event.MISSED -> {
                    caughtMove = null
                    owlHappy = false
                    feedbackBanner = "这颗星溜走啦，看看下一颗"
                    haptics.tap()
                }
                EyeExerciseCoach.Event.NEXT -> {
                    if (snapshot.stage == EyeExerciseCoach.Stage.BACK && !viewModel.usedCamera) {
                        // 无相机引导模式：每步结束同样让星星轻柔飞回星盘
                        caughtMove = snapshot.move
                        owlHappy = true
                        feedbackBanner = "收下一颗星光，看回中间"
                        sounds.play(EyeCue.PHASE)
                        if (!reducedMotion) {
                            scope.launch {
                                cometProgress.snapTo(0f)
                                cometProgress.animateTo(1f, tween(760, easing = FastOutSlowInEasing))
                            }
                        }
                    } else if (snapshot.stage == EyeExerciseCoach.Stage.MOVE) {
                        owlHappy = false
                        feedbackBanner = null
                    }
                }
                EyeExerciseCoach.Event.REST -> {
                    caughtMove = null
                    owlHappy = false
                    feedbackBanner = null
                    haptics.tap()
                    sounds.play(EyeCue.PHASE)
                    if (!reducedMotion) {
                        scope.launch {
                            restRipple.snapTo(0f)
                            restRipple.animateTo(1f, tween(920, easing = FastOutSlowInEasing))
                        }
                    }
                }
                EyeExerciseCoach.Event.COUNTDOWN -> {
                    sounds.play(EyeCue.COUNT)
                    if (!reducedMotion) {
                        scope.launch {
                            restRipple.snapTo(0f)
                            restRipple.animateTo(1f, tween(920, easing = FastOutSlowInEasing))
                        }
                    }
                }
                EyeExerciseCoach.Event.DONE -> {
                    haptics.success()
                    sounds.play(EyeCue.COMPLETE)
                }
            }
        }
    }

    EyeFullScreenPage(onClose) {
        EyeExerciseContent(
            started = started,
            state = state,
            usedCamera = viewModel.usedCamera,
            feedbackBanner = feedbackBanner,
            caughtMove = caughtMove,
            cometProgress = cometProgress.value,
            owlHappy = owlHappy,
            restPulse = restRipple.value,
            onStart = ::requestStart,
            onReplay = ::requestStart,
            onClose = onClose,
        )
    }
}

/**
 * 护眼操互动小游戏的无副作用内容层：负责开场情景、星空眼动舞台、闭眼小憩与结算页，便于视觉回归测试直接渲染。
 */
@Composable
internal fun EyeExerciseContent(
    started: Boolean,
    state: EyeExerciseCoach.State,
    usedCamera: Boolean,
    feedbackBanner: String? = null,
    caughtMove: EyeExerciseCoach.Move? = null,
    cometProgress: Float = 1f,
    owlHappy: Boolean = false,
    restPulse: Float = 0f,
    onStart: () -> Unit = {},
    onReplay: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val reducedMotion = rememberReducedMotion()
    val resting = started && state.stage == EyeExerciseCoach.Stage.REST

    // 温和的呼吸与背景微星节奏；系统开启「减少动画」时保持静止
    val infinite = rememberInfiniteTransition(label = "暖夜寻星")
    val pulse by if (reducedMotion) {
        remember { mutableStateOf(0.5f) }
    } else {
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "目标星脉动",
        )
    }
    val twinkle by if (reducedMotion) {
        remember { mutableStateOf(0f) }
    } else {
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
            label = "微星呼吸",
        )
    }

    // 小猫头鹰视线平滑追踪：相机看得到人脸时实时跟随真实眼动，否则温和望向当前星星方向
    val rawGazeX = when {
        !started -> 0f
        state.stage == EyeExerciseCoach.Stage.MOVE && state.faceVisible -> state.gazeX
        state.stage == EyeExerciseCoach.Stage.MOVE -> (state.move?.dx ?: 0) * 0.72f
        else -> 0f
    }
    val rawGazeY = when {
        !started -> 0f
        state.stage == EyeExerciseCoach.Stage.MOVE && state.faceVisible -> state.gazeY
        state.stage == EyeExerciseCoach.Stage.MOVE -> (state.move?.dy ?: 0) * 0.72f
        else -> 0f
    }
    val smoothGazeX by animateFloatAsState(
        targetValue = rawGazeX,
        animationSpec = if (reducedMotion) tween(0) else spring(dampingRatio = 0.75f, stiffness = 260f),
        label = "猫头鹰视线X",
    )
    val smoothGazeY by animateFloatAsState(
        targetValue = rawGazeY,
        animationSpec = if (reducedMotion) tween(0) else spring(dampingRatio = 0.75f, stiffness = 260f),
        label = "猫头鹰视线Y",
    )

    Box(Modifier.fillMaxSize().background(colors.appBackground)) {
        // 全屏暖夜星空远山底画
        Canvas(Modifier.fillMaxSize()) {
            drawStarryNightBackdrop(colors, twinkle, resting)
        }
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = EyeSpacing.gutter, vertical = EyeSpacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ExerciseTopBar(
                started = started,
                state = state,
                usedCamera = usedCamera,
                onClose = onClose,
            )
            when {
                !started -> ExerciseIntroStage(
                    twinkle = twinkle,
                    onStart = onStart,
                    modifier = Modifier.weight(1f),
                )
                state.stage == EyeExerciseCoach.Stage.DONE -> ExerciseCompletion(
                    total = state.stepCount,
                    seen = state.detectedCount,
                    usedCamera = usedCamera,
                    onClose = onClose,
                    modifier = Modifier.weight(1f),
                    bestStreak = state.bestStreak,
                    stepResults = state.stepResults,
                    onReplay = onReplay,
                )
                else -> ExercisePlayStage(
                    state = state,
                    usedCamera = usedCamera,
                    gazeX = smoothGazeX,
                    gazeY = smoothGazeY,
                    pulse = pulse,
                    feedbackBanner = feedbackBanner,
                    caughtMove = caughtMove,
                    cometProgress = cometProgress,
                    owlHappy = owlHappy,
                    restPulse = restPulse,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 顶栏：结束按钮（>=48dp）、星光进度条、状态/连击胶囊。 */
@Composable
private fun ExerciseTopBar(
    started: Boolean,
    state: EyeExerciseCoach.State,
    usedCamera: Boolean,
    onClose: () -> Unit,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EyeSpacing.xs),
    ) {
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onClose)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            EyeText("结束", style = typography.body, color = colors.textPrimary)
        }
        Box(
            Modifier
                .weight(1f)
                .height(8.dp)
                .clip(CircleShape)
                .background(colors.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(if (started) state.progress.coerceIn(0f, 1f) else 0f)
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(colors.flowBrush),
            )
        }
        if (started && state.stage != EyeExerciseCoach.Stage.DONE) {
            val badgeText = when {
                state.streak >= 2 -> "连星 ×${state.streak}"
                state.faceVisible || usedCamera -> "眼动追踪"
                else -> "节奏引导"
            }
            val badgeHighlight = state.streak >= 2
            Box(
                Modifier
                    .clip(EyeShapes.chip)
                    .background(if (badgeHighlight) colors.primarySoft else colors.panelBackground)
                    .border(
                        1.dp,
                        if (badgeHighlight) colors.primary else colors.borderSubtle,
                        EyeShapes.chip,
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                EyeText(
                    badgeText,
                    style = typography.chip,
                    color = if (badgeHighlight) colors.moodGold else colors.textSecondary,
                )
            }
        }
    }
}

/** 开场情景页：观星小猫头鹰 + 暖棕故事卡片 + 玩法步骤胶囊。 */
@Composable
private fun ExerciseIntroStage(
    twinkle: Float,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = EyeSpacing.md),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .clip(EyeShapes.chip)
                .background(colors.primarySoft)
                .border(1.dp, colors.cardBorder.copy(alpha = 0.6f), EyeShapes.chip)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            EyeText(StarOwlCopy.INTRO_TITLE, style = typography.chip, color = colors.moodGold)
        }
        Spacer(Modifier.height(EyeSpacing.md))
        Canvas(
            Modifier
                .size(188.dp)
                .semantics { contentDescription = "等待出发的观星小猫头鹰与星盘" },
        ) {
            val radius = size.minDimension * 0.42f
            drawConstellationRing(
                colors = colors,
                center = center,
                radius = radius,
                totalSteps = EyeExerciseCoach.DEFAULT_MOVES.size,
                stepResults = listOf(true, true, true),
                usedCamera = true,
                calibrateProgress = 0.25f + twinkle * 0.1f,
                showCalibrateRing = false,
                resting = false,
                restPulse = 0f,
            )
            drawStarOwl(
                colors = colors,
                center = center,
                size = size.minDimension * 0.50f,
                gazeX = 0f,
                gazeY = -0.15f,
            )
        }
        Spacer(Modifier.height(EyeSpacing.lg))
        EyeWarmCard(
            modifier = Modifier.widthIn(max = 520.dp),
            padding = 20.dp,
            spacing = 14.dp,
        ) {
            EyeText(StarOwlCopy.INTRO_HEADLINE, style = typography.title, color = colors.textPrimary)
            EyeText(StarOwlCopy.INTRO_BODY, style = typography.body, color = colors.textSecondary)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IntroStepChip("1", "眼看星星", Modifier.weight(1f))
                IntroStepChip("2", "收进星盘", Modifier.weight(1f))
                IntroStepChip("3", "闭眼小憩", Modifier.weight(1f))
            }
            EyeButton(
                text = StarOwlCopy.START_BUTTON,
                role = EyeButtonRole.PRIMARY,
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            )
        }
    }
}

@Composable
private fun IntroStepChip(index: String, label: String, modifier: Modifier = Modifier) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(
        modifier
            .clip(EyeShapes.medium)
            .background(colors.surfaceVariant)
            .border(1.dp, colors.borderSubtle, EyeShapes.medium)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(colors.primarySoft),
            contentAlignment = Alignment.Center,
        ) {
            EyeText(index, style = typography.caption, color = colors.moodGold)
        }
        EyeText(
            label,
            style = typography.caption,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
    }
}

/** 游戏主舞台：四方位迷路星星 + 中央星盘与眼动小猫头鹰 + 底部稳固引导栏。 */
@Composable
private fun ExercisePlayStage(
    state: EyeExerciseCoach.State,
    usedCamera: Boolean,
    gazeX: Float,
    gazeY: Float,
    pulse: Float,
    feedbackBanner: String?,
    caughtMove: EyeExerciseCoach.Move?,
    cometProgress: Float,
    owlHappy: Boolean,
    restPulse: Float,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val resting = state.stage == EyeExerciseCoach.Stage.REST
    val round = state.stepIndex / 4
    val litCount = if (usedCamera) state.stepResults.count { it } else state.stepResults.size

    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 中央大星空舞台：自适应宽高，保证上下左右 4 个方位星星都不会被裁切或遮挡
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val stageSemantics = when (state.stage) {
                EyeExerciseCoach.Stage.CALIBRATE -> "正在唤醒小猫头鹰，请看着中央"
                EyeExerciseCoach.Stage.MOVE -> "${state.move?.label.orEmpty()}，点亮发光的星星"
                EyeExerciseCoach.Stage.BACK -> "请看回屏幕中央的小猫头鹰"
                EyeExerciseCoach.Stage.REST -> "闭上眼睛休息，还剩 ${state.secondsLeft} 秒"
                EyeExerciseCoach.Stage.DONE -> "护眼操已完成"
            }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = stageSemantics },
            ) {
                val center = Offset(size.width * 0.5f, size.height * 0.5f)
                val minSide = min(size.width, size.height)
                val ringRadius = (minSide * 0.25f).coerceIn(58f, 132f)
                val owlSize = ringRadius * 1.16f
                val starRadius = (minSide * 0.068f).coerceIn(18f, 34f)
                val edgePad = starRadius * 2.15f

                fun starAnchor(move: EyeExerciseCoach.Move): Offset = when (move) {
                    EyeExerciseCoach.Move.LEFT -> Offset(edgePad, center.y)
                    EyeExerciseCoach.Move.RIGHT -> Offset(size.width - edgePad, center.y)
                    EyeExerciseCoach.Move.UP -> Offset(center.x, edgePad)
                    EyeExerciseCoach.Move.DOWN -> Offset(center.x, size.height - edgePad)
                }

                drawConstellationRing(
                    colors = colors,
                    center = center,
                    radius = ringRadius,
                    totalSteps = state.stepCount,
                    stepResults = state.stepResults,
                    usedCamera = usedCamera,
                    calibrateProgress = state.holdProgress,
                    showCalibrateRing = state.stage == EyeExerciseCoach.Stage.CALIBRATE,
                    resting = resting,
                    restPulse = restPulse,
                )
                drawStarOwl(
                    colors = colors,
                    center = center,
                    size = owlSize,
                    gazeX = gazeX,
                    gazeY = gazeY,
                    sleeping = resting,
                    happy = owlHappy && state.stage == EyeExerciseCoach.Stage.BACK,
                    excited = state.stage == EyeExerciseCoach.Stage.MOVE && state.holdProgress > 0.3f,
                )
                if (state.stage == EyeExerciseCoach.Stage.MOVE) {
                    state.move?.let { move ->
                        drawTargetStar(
                            colors = colors,
                            origin = center,
                            target = starAnchor(move),
                            move = move,
                            ringRadius = ringRadius,
                            starRadius = starRadius,
                            holdProgress = state.holdProgress,
                            pulse = pulse,
                        )
                    }
                }
                if (caughtMove != null && cometProgress < 1f) {
                    drawCapturedComet(
                        colors = colors,
                        from = starAnchor(caughtMove),
                        to = center,
                        progress = cometProgress,
                        starRadius = starRadius,
                    )
                }
            }
            // 闭眼休息时在星盘下方叠加清晰的等宽大倒计时
            if (resting) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = EyeSpacing.md),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    EyeText(
                        "${state.secondsLeft}",
                        style = typography.display,
                        color = colors.moodGold,
                        fontWeight = FontWeight.SemiBold,
                    )
                    EyeText(
                        "秒后轻轻睁眼",
                        style = typography.bodySecondary,
                        color = colors.textSecondary,
                    )
                }
            }
        }

        // 底部稳固情景指令卡：高度稳定不跳动，确保上下方星星与指令互不遮挡
        val instructionTitle = when (state.stage) {
            EyeExerciseCoach.Stage.CALIBRATE -> "看着小猫头鹰"
            EyeExerciseCoach.Stage.MOVE -> state.move?.let { StarOwlCopy.moveTitle(it, round) } ?: "寻星中"
            EyeExerciseCoach.Stage.BACK -> "看回中间"
            EyeExerciseCoach.Stage.REST -> "闭上眼睛休息"
            EyeExerciseCoach.Stage.DONE -> "完成啦！"
        }
        val instructionSub = when {
            feedbackBanner != null -> feedbackBanner
            state.stage == EyeExerciseCoach.Stage.CALIBRATE -> "正在校准视线，准备出发寻星"
            state.stage == EyeExerciseCoach.Stage.MOVE ->
                if (state.faceVisible || usedCamera) "头保持不动，只转眼睛看发光的星星" else "跟着发光星星转动眼睛就好"
            state.stage == EyeExerciseCoach.Stage.BACK -> "把星光收进星盘（已点亮 $litCount / ${state.stepCount}）"
            state.stage == EyeExerciseCoach.Stage.REST -> "听着每秒轻响，让眼睛安心小憩"
            else -> ""
        }

        Row(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .clip(EyeShapes.card)
                .background(colors.panelBackground)
                .border(1.dp, colors.borderSubtle, EyeShapes.card)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnimatedContent(
                targetState = instructionTitle to instructionSub,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(160)) },
                modifier = Modifier.weight(1f),
                label = "情景指令",
            ) { (title, sub) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    EyeText(title, style = typography.subtitle, color = colors.textPrimary)
                    EyeText(
                        sub,
                        style = typography.bodySecondary,
                        color = if (feedbackBanner != null && owlHappy) colors.moodGold else colors.textSecondary,
                    )
                }
            }
            if (state.stage == EyeExerciseCoach.Stage.MOVE) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceVariant)
                        .border(1.dp, colors.cardBorder.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    EyeText(
                        "${state.secondsLeft}",
                        style = typography.metricValue,
                        color = colors.primary,
                    )
                }
            }
        }
        Spacer(Modifier.height(EyeSpacing.xs))
    }
}

/** 独立完成页布局：点亮的八星星盘与欢呼小猫头鹰、趣味称号、客观统计和底部操作按钮。 */
@Composable
internal fun ExerciseCompletion(
    total: Int,
    seen: Int,
    usedCamera: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    bestStreak: Int = 0,
    stepResults: List<Boolean> = emptyList(),
    onReplay: (() -> Unit)? = null,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val displayResults = remember(total, seen, usedCamera, stepResults) {
        if (stepResults.isNotEmpty()) stepResults
        else List(total.coerceAtLeast(0)) { idx -> !usedCamera || idx < seen }
    }
    val rank = StarOwlCopy.rankTitle(total, seen, usedCamera)
    val summaryLine = StarOwlCopy.rankSummary(total, seen, bestStreak, usedCamera)

    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = EyeSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Canvas(
                Modifier
                    .size(184.dp)
                    .semantics { contentDescription = "已完成点亮的八星星盘与欢呼的小猫头鹰" },
            ) {
                drawConstellationRing(
                    colors = colors,
                    center = center,
                    radius = size.minDimension * 0.42f,
                    totalSteps = total.coerceAtLeast(1),
                    stepResults = displayResults,
                    usedCamera = usedCamera,
                    calibrateProgress = 1f,
                    showCalibrateRing = false,
                    resting = false,
                    restPulse = 0f,
                )
                drawStarOwl(
                    colors = colors,
                    center = center,
                    size = size.minDimension * 0.48f,
                    happy = true,
                )
            }
            Spacer(Modifier.height(EyeSpacing.lg))
            Box(
                Modifier
                    .clip(EyeShapes.chip)
                    .background(colors.primarySoft)
                    .border(1.dp, colors.cardBorder.copy(alpha = 0.7f), EyeShapes.chip)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                EyeText(rank, style = typography.chip, color = colors.moodGold)
            }
            Spacer(Modifier.height(EyeSpacing.sm))
            EyeText("完成啦！", style = typography.displayCompact, color = colors.textPrimary)
            Spacer(Modifier.height(EyeSpacing.xs))
            EyeText(
                if (usedCamera) "共 $total 步，检测到你做到了 $seen 步" else "共 $total 步，做完啦",
                style = typography.body,
                color = colors.textPrimary.copy(alpha = 0.88f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            EyeText(
                summaryLine,
                style = typography.bodySecondary,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .padding(top = EyeSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(EyeSpacing.xs),
        ) {
            EyeButton("完成", EyeButtonRole.PRIMARY, onClose, Modifier.fillMaxWidth().heightIn(min = 52.dp))
            if (onReplay != null) {
                EyeButton("再寻一次", EyeButtonRole.SECONDARY, onReplay, Modifier.fillMaxWidth().heightIn(min = 52.dp))
            }
        }
    }
}
