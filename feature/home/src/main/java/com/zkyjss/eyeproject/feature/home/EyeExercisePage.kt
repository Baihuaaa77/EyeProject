package com.zkyjss.eyeproject.feature.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zkyjss.eyeproject.core.camera.CameraController
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.domain.EyeExerciseCoach
import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
import com.zkyjss.eyeproject.core.ui.component.BubbleTail
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeFullScreenPage
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.MascotBubble
import com.zkyjss.eyeproject.core.ui.component.MascotMood
import com.zkyjss.eyeproject.core.ui.component.XiaoTong
import com.zkyjss.eyeproject.core.ui.component.rememberEyeHaptics
import com.zkyjss.eyeproject.core.ui.component.rememberMascotSounds
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
 * 「护眼操」完整页面（盖在首页上）：深色底，上方进度条，中间大箭头 + 「向左看」+ 倒计时，下方是小瞳和它的气泡。
 * 眼睛转到位时整页朝那个方向翻过去、手机振动，小瞳顺着飞出画面打个滚再回来；最后闭眼休息 5 秒。
 * 没有相机权限时按时间带着做。与苹果端 EyeExerciseView 同一流程与文案。
 */
@Composable
internal fun EyeExercisePage(onClose: () -> Unit, viewModel: EyeExerciseViewModel = hiltViewModel()) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val haptics = rememberEyeHaptics()
    val sounds = rememberMascotSounds()
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 最近一次「看到了」的方向：决定翻页方向（看不到时淡入淡出）
    var flipMove by remember { mutableStateOf<EyeExerciseCoach.Move?>(null) }
    val fly = remember { Animatable(0f) }
    val spin = remember { Animatable(0f) }
    var flyMove by remember { mutableStateOf<EyeExerciseCoach.Move?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.start(owner, granted)
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) viewModel.start(owner, true)
        else permission.launch(Manifest.permission.CAMERA)
    }
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                EyeExerciseCoach.Event.DETECTED -> {
                    flipMove = state.move
                    haptics.success(); sounds.play("eye_spin")
                    flyMove = state.move
                    // 小瞳顺着方向飞出去、边飞边打滚，再从另一边飞回来
                    scope.launch {
                        spin.snapTo(0f)
                        launch { spin.animateTo(720f, tween(900)) }
                        fly.snapTo(0f); fly.animateTo(1f, tween(450)); fly.snapTo(-1f); fly.animateTo(0f, tween(450))
                    }
                }
                EyeExerciseCoach.Event.MISSED -> { flipMove = null; haptics.tap() }
                EyeExerciseCoach.Event.REST -> { flipMove = null; haptics.tap(); sounds.play("eye_yawn") }
                EyeExerciseCoach.Event.DONE -> { haptics.success(); sounds.play("eye_heart") }
                else -> flipMove = null
            }
        }
    }
    EyeFullScreenPage(onClose) {
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.ink).statusBarsPadding().navigationBarsPadding()) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
            val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EyeText("结束", Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onClose).padding(12.dp),
                        style = typography.body, color = colors.white)
                    Spacer(Modifier.size(8.dp))
                    Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(colors.white.copy(alpha = 0.25f))) {
                        Box(Modifier.fillMaxWidth(state.progress).height(6.dp).clip(CircleShape).background(colors.success))
                    }
                }
                Spacer(Modifier.weight(1f))
                AnimatedContent(
                    targetState = state.stage to state.stepIndex,
                    transitionSpec = { flipTransition(flipMove) },
                    label = "护眼操翻页",
                ) { (stage, _) -> ExerciseInstruction(stage, state) }
                Spacer(Modifier.weight(1f))
                if (state.stage != EyeExerciseCoach.Stage.DONE) {
                    MascotBubble(bubbleText(state), tail = BubbleTail.CENTER)
                    val f = fly.value
                    XiaoTong(if (state.stage == EyeExerciseCoach.Stage.REST) MascotMood.SLEEPY else MascotMood.HAPPY,
                        Modifier.size(150.dp).graphicsLayer {
                            val m = flyMove
                            translationX = (m?.dx ?: 0) * f * widthPx * 0.8f
                            translationY = (m?.dy ?: 0) * f * heightPx * 0.45f
                            rotationZ = if (m == null) 0f else spin.value * (if (m.dx < 0 || m.dy < 0) -1 else 1)
                        }, interactive = false)
                    EyeText(if (state.faceVisible || viewModel.usedCamera) "头保持不动，只转眼睛" else "跟着提示转动眼睛就好",
                        style = typography.bodySecondary, color = colors.white.copy(alpha = 0.7f))
                } else {
                    val (total, seen) = viewModel.summary
                    XiaoTong(MascotMood.HAPPY, Modifier.size(170.dp), greet = true)
                    EyeText(if (viewModel.usedCamera) "共 $total 步，小瞳看到你做到了 $seen 步" else "共 $total 步，做完啦",
                        style = typography.body, color = colors.white, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    EyeButton("完成", EyeButtonRole.PRIMARY, onClose, Modifier.fillMaxWidth().heightIn(min = 52.dp))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** 中间的提示：大箭头 + 动作 + 倒计时；开头「看着小瞳」、结尾「闭上眼睛」与「完成啦」。 */
@Composable
private fun ExerciseInstruction(stage: EyeExerciseCoach.Stage, state: EyeExerciseCoach.State) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (stage) {
            EyeExerciseCoach.Stage.CALIBRATE -> {
                EyeText("看着小瞳", style = typography.headline, color = colors.white)
                EyeText("准备开始", style = typography.body, color = colors.white.copy(alpha = 0.7f))
            }
            EyeExerciseCoach.Stage.MOVE, EyeExerciseCoach.Stage.BACK -> {
                val move = state.move ?: return@Column
                if (stage == EyeExerciseCoach.Stage.MOVE) {
                    Arrow(move)
                    EyeText(move.label, style = typography.title, color = colors.white)
                    EyeText("${state.secondsLeft}", style = typography.headline.copy(fontSize = 96.sp), color = colors.white, fontWeight = FontWeight.Bold)
                } else {
                    EyeText("看回中间", style = typography.title, color = colors.white)
                }
            }
            EyeExerciseCoach.Stage.REST -> {
                EyeText("闭上眼睛休息", style = typography.title, color = colors.white)
                EyeText("${state.secondsLeft}", style = typography.headline.copy(fontSize = 96.sp), color = colors.white, fontWeight = FontWeight.Bold)
            }
            EyeExerciseCoach.Stage.DONE -> EyeText("完成啦！", style = typography.headline, color = colors.white)
        }
    }
}

/** 粗箭头，指向动作方向（与示例图一致的灰白色）。 */
@Composable
private fun Arrow(move: EyeExerciseCoach.Move) {
    val colors = LocalEyeColors.current
    val angle = when (move) {
        EyeExerciseCoach.Move.LEFT -> 180f
        EyeExerciseCoach.Move.RIGHT -> 0f
        EyeExerciseCoach.Move.UP -> 270f
        EyeExerciseCoach.Move.DOWN -> 90f
    }
    Canvas(Modifier.size(72.dp).rotate(angle).semantics { contentDescription = move.label }) {
        val s = size.minDimension
        val stroke = Stroke(s * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawLine(colors.white.copy(alpha = 0.6f), Offset(s * 0.15f, s * 0.5f), Offset(s * 0.85f, s * 0.5f), s * 0.16f, StrokeCap.Round)
        drawPath(Path().apply { moveTo(s * 0.55f, s * 0.22f); lineTo(s * 0.85f, s * 0.5f); lineTo(s * 0.55f, s * 0.78f) },
            colors.white.copy(alpha = 0.6f), style = stroke)
    }
}

/** 小瞳气泡：跟着阶段说一句。 */
private fun bubbleText(state: EyeExerciseCoach.State): String = when (state.stage) {
    EyeExerciseCoach.Stage.CALIBRATE -> "跟我一起转转眼球吧"
    EyeExerciseCoach.Stage.MOVE -> when (state.move) {
        EyeExerciseCoach.Move.LEFT -> "眼睛往左边看，看我飞过去！"
        EyeExerciseCoach.Move.RIGHT -> "再往右边看～"
        EyeExerciseCoach.Move.UP -> "抬眼看看上面"
        EyeExerciseCoach.Move.DOWN -> "低眼看看下面"
        null -> "跟着箭头看"
    }
    EyeExerciseCoach.Stage.BACK -> "真棒，看回我这里"
    EyeExerciseCoach.Stage.REST -> "闭上眼睛，歇一会儿"
    EyeExerciseCoach.Stage.DONE -> "做得真好！"
}

/** 翻页动画：看到了就整页朝那个方向翻走、新的一页从另一边进来；其余淡入淡出。 */
private fun flipTransition(move: EyeExerciseCoach.Move?): ContentTransform = when {
    move == null -> fadeIn(tween(220)) togetherWith fadeOut(tween(180))
    move.dx != 0 -> slideInHorizontally(tween(380)) { -move.dx * it } + fadeIn() togetherWith
        slideOutHorizontally(tween(380)) { move.dx * it } + fadeOut()
    else -> slideInVertically(tween(380)) { -move.dy * it } + fadeIn() togetherWith
        slideOutVertically(tween(380)) { move.dy * it } + fadeOut()
}
