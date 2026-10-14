package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.domain.*
import com.zkyjss.eyeproject.core.model.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** 老视与斜视共用状态机，平台只提供相机、时间、显示几何与持久化。 */
class ScreeningEngine(private val platform: ScreeningPlatform, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(ScreeningState())
    val state: StateFlow<ScreeningState> = _state.asStateFlow()
    private val frames = Channel<Triple<Long, ScreeningStep, FaceLandmarkFrame>>(Channel.UNLIMITED)
    private val samples = mutableListOf<EyeMotionSample>()
    private var gaze: StrabismusGaze? = null
    private val gazeCalibration = mutableListOf<GazeCalibrationSample>()
    private data class RenderedTarget(val timeMs: Long, val step: ScreeningStep, val index: Int, val point: GazePoint)
    private val renderedTargets = ArrayDeque<RenderedTarget>()
    private var gazeBadSinceMs: Long? = null
    private var lastGazeFrameMs = 0L
    private var lastPursuitCheckMs = 0L
    private var pursuitFollowing: Boolean? = null
    private var nasalSign = -1f
    private var timer: Job? = null
    private var questionStartMs = 0L
    private val nearLevels = listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.8f, 1.0f)
    private var nextTrialId = 0
    private var flipperMoveStartMs = 0L
    private val distanceTracker = ScreeningDistanceTracker()
    private val nearDistanceToleranceCm = 4f
    private var stepStartMs = 0L
    private var totalFrames = 0
    private var validFrames = 0
    private var qualityEligibleFrames = 0
    private var qualityEligibleValidFrames = 0
    private var lastUiFrameMs = 0L
    private var coverIndex = 0
    private var coverStageStartMs = 0L
    private var headStep: ScreeningStep? = null
    private var faceCenterBaseline: Pair<Float, Float>? = null
    private var cameraAttached = false
    private var cameraFrameCount = 0
    private var lastCameraFrameMs = 0L
    private var cachedFocalWidth = 0
    private var cachedFocalPx = 0f
    private var cachedCalibrationVersion = 0L
    private var cameraWatchdog: Job? = null
    private var opticalValidFrames = 0
    private val opticalRejectionCounts = mutableMapOf<String, Int>()
    private var cachedGeometry: CameraGeometry? = null
    private val frameObserver: (FaceLandmarkFrame) -> Unit = { frame ->
        frames.trySend(Triple(frame.analysisElapsedRealtimeMs ?: platform.nowMs(), _state.value.step, frame))
    }
    private val cameraErrorObserver: (Throwable) -> Unit = { error ->
        _state.update { it.copy(warning = "相机错误：${error.message}", trackingStatus = "相机启动失败") }
    }
    private val coverSequence = StrabismusProtocol.COVER_SEQUENCE

    init {
        scope.launch { for ((time, step, frame) in frames) processFrame(time, step, frame) }
    }

    fun configure(kind: ScreeningKind) {
        if (_state.value.kind != kind) _state.value = ScreeningState(kind = kind)
    }

    fun setField(key: String, value: String) = _state.update { it.copy(fields = it.fields + (key to value)) }
    fun setMode(mode: String) = _state.update { it.copy(mode = mode) }

    fun startCamera() {
        if (cameraAttached) return
        cameraAttached = true
        cameraFrameCount = 0; lastCameraFrameMs = 0L
        distanceTracker.clear(); opticalRejectionCounts.clear()
        val light = platform.startLight { lux -> _state.update { it.copy(lux = lux) } }
        _state.update { it.copy(lightSensorAvailable = light, trackingStatus = "等待相机帧") }
        platform.startCamera(frameObserver, cameraErrorObserver)
        cameraWatchdog?.cancel()
        cameraWatchdog = scope.launch {
            while (isActive && cameraAttached) {
                delay(1_000)
                if ((lastCameraFrameMs == 0L || platform.nowMs() - lastCameraFrameMs > 2_500) && _state.value.step != ScreeningStep.RESULT)
                    _state.update { it.copy(faceDetected = false, distanceCm = 0f, leftOpticalX = null, leftOpticalY = null,
                        rightOpticalX = null, rightOpticalY = null, opticalStatus = "相机帧中断", trackingStatus = "相机未收到新帧",
                        warning = "未收到相机数据，请检查相机权限并重试。") }
            }
        }
    }
    fun retryCamera() { stopCamera(); startCamera() }
    fun stopCamera() {
        if (!cameraAttached) return
        cameraAttached = false; cameraWatchdog?.cancel(); cameraWatchdog = null
        platform.stopCamera()
    }
    fun cameraPermissionDenied() = _state.update {
        it.copy(warning = "未授予相机权限，无法测距或定位双眼。请允许相机权限后重试。")
    }

    fun nextFromProfile() {
        val age = _state.value.fields["年龄"]?.toIntOrNull()
        if (age == null || age !in 3..100) {
            _state.update { it.copy(warning = "请填写有效年龄") }; return
        }
        if (_state.value.kind == ScreeningKind.PRESBYOPIA &&
            (_state.value.mode.isBlank() || _state.value.fields["日常矫正方式"].isNullOrBlank())) {
            _state.update { it.copy(warning = "请选择平时戴的眼镜和这次测试戴哪副眼镜") }; return
        }
        _state.update { it.copy(step = ScreeningStep.ENVIRONMENT, warning = null) }
    }

    fun begin() {
        val state = _state.value
        if (!state.faceDetected || state.distanceCm <= 0f) {
            _state.update { it.copy(warning = "请先让前置摄像头检测到双眼和距离") }; return
        }
        if (state.kind == ScreeningKind.PRESBYOPIA && !distanceStable(40f, nearDistanceToleranceCm)) {
            _state.update { it.copy(warning = "请保持 40±4 cm 约 1 秒") }; return
        }
        if (state.kind == ScreeningKind.STRABISMUS && !distanceOk(33f, 5f)) {
            _state.update { it.copy(warning = "斜视任务需保持 33±5 cm") }; return
        }
        if (state.lux != null && state.lux < 50f) {
            _state.update { it.copy(warning = "环境光低于本原型的 50 lux 采样门槛，请改善照明") }; return
        }
        samples.clear(); totalFrames = 0; validFrames = 0
        qualityEligibleFrames = 0; qualityEligibleValidFrames = 0; opticalValidFrames = 0
        if (state.kind == ScreeningKind.PRESBYOPIA) startNearVision() else startFixation()
    }

    private fun startNearVision() {
        timer?.cancel()
        _state.update { it.copy(step = ScreeningStep.NEAR_VISION, guidePending = true, visionEye = VisionEye.RIGHT,
            nearAwaitingEyeReady = true, nearDescending = false, trialVisible = false,
            trialIndex = 0, direction = Random.nextInt(4), visionLevel = 0.3f,
            answers = emptyList(), warning = null, instruction = "先遮住左眼，露出右眼；每级 4 题，停稳后再显示视标。") }
    }

    fun readyForNearEye() {
        val s = _state.value
        if (s.step != ScreeningStep.NEAR_VISION || !s.nearAwaitingEyeReady) return
        _state.update { it.copy(nearAwaitingEyeReady = false, warning = null,
            instruction = "${s.visionEye.label}测试：保持约 40 cm，点按显示视标后选择 E 开口方向。") }
    }

    /** 只有距离稳定后才锁定该题的距离、像素尺寸与方向。 */
    fun showTrial() {
        if (_state.value.guidePending) return
        val s = _state.value
        if (s.trialVisible || s.nearAwaitingEyeReady) return
        val target = trialTargetCm(s) ?: return
        val tolerance = nearDistanceToleranceCm
        if (!distanceStable(target, tolerance)) {
            _state.update { it.copy(warning = "请在 ${decimalString(target.toDouble(), 1)}±${decimalString(tolerance.toDouble(), 1)} cm 附近停稳约 1 秒后重试") }; return
        }
        val measuredDistance = distanceTracker.estimate(platform.nowMs())?.distanceCm ?: return
        val level = s.visionLevel
        // 近视力、多距离与远近切换按各题实测距离确定角尺寸。
        val spec = OptotypeGeometry.calculate(platform.display, level, measuredDistance)
        if (!spec.displayable) {
            if (s.step == ScreeningStep.NEAR_VISION) {
                val index = nearLevels.indexOf(s.visionLevel)
                val passedLower = s.answers.any { it.step == ScreeningStep.NEAR_VISION &&
                    it.eye == s.visionEye && it.level < s.visionLevel }
                _state.update { it.copy(nearResolutionLimitedEyes = it.nearResolutionLimitedEyes + s.visionEye) }
                if (!passedLower && index > 0) {
                    _state.update { it.copy(visionLevel = nearLevels[index - 1], nearDescending = true,
                        nearStopReason = "本机无法呈现更小视标，改测较大视标", warning = null) }
                } else {
                    _state.update { it.copy(nearStopReason = "${s.visionEye.label}达到本机可呈现的最小视标", warning = null) }
                    advanceNearEye()
                }
            } else if (s.step == ScreeningStep.DISTANCE_CURVE) {
                finishCurveDistance(resolutionLimited = true)
            } else _state.update { it.copy(warning = "当前距离下视标笔画像素不足，不能可靠测量") }
            return
        }
        nextTrialId++
        questionStartMs = 0L
        _state.update { it.copy(trialVisible = true, trialId = nextTrialId,
            trialOptotypePx = spec.sizePx, trialDistanceCm = measuredDistance,
            trialDistanceSpreadCm = distanceSpread(),
            trialMovementMs = if (s.step == ScreeningStep.FLIPPER) platform.nowMs() - flipperMoveStartMs else null,
            warning = null) }
    }

    /** Compose 完成首帧呈现后调用，避免把移动与渲染时间算进辨认反应。 */
    fun markTrialPresented(trialId: Int, timestampMs: Long) {
        if (_state.value.trialVisible && _state.value.trialId == trialId && questionStartMs == 0L) questionStartMs = timestampMs
    }

    /** [direction] 为 -1 表示「看不清」，记为错误作答。 */
    fun answer(direction: Int, byVoice: Boolean = false) {
        val s = _state.value
        if (!s.trialVisible || questionStartMs == 0L ||
            (s.step != ScreeningStep.NEAR_VISION && s.step != ScreeningStep.FLIPPER && s.step != ScreeningStep.DISTANCE_CURVE)) return
        val target = trialTargetCm(s) ?: return
        val now = platform.nowMs()
        val answerDistance = distanceTracker.estimate(now)?.distanceCm
        if (!distanceTracker.acceptableAnswer(target, s.trialDistanceCm, nearDistanceToleranceCm, now)) {
            _state.update { it.copy(warning = if (answerDistance == null ||
                abs(answerDistance - target) <= nearDistanceToleranceCm + 0.5f)
                "测距短时波动，请保持姿势约 1 秒后再次作答" else
                "当前距离已偏离目标，请调回 ${target.toInt()} cm 附近后再次作答") }
            return
        }
        val latency = now - questionStartMs
        val answer = VisionAnswer(s.step, s.trialDistanceCm, s.visionLevel, s.direction, direction,
            direction == s.direction, latency, s.trialOptotypePx, s.visionEye, questionStartMs,
            s.trialMovementMs, if (s.step == ScreeningStep.FLIPPER) latency else null,
            s.trialDistanceSpreadCm, answerDistance ?: s.trialDistanceCm,
            targetCm = if (s.step == ScreeningStep.DISTANCE_CURVE) target else null, byVoice = byVoice)
        val answers = s.answers + answer
        questionStartMs = 0L
        if (s.step == ScreeningStep.NEAR_VISION) {
            val next = s.trialIndex + 1
            if (next >= 4) {
                val passed = answers.count { it.step == ScreeningStep.NEAR_VISION &&
                    it.eye == s.visionEye && it.level == s.visionLevel && it.correct } >= 3
                val index = nearLevels.indexOf(s.visionLevel)
                val previousPass = answers.filter { it.step == ScreeningStep.NEAR_VISION &&
                    it.eye == s.visionEye && it.level != s.visionLevel }
                    .groupBy { it.level }.values.any { group -> group.size == 4 && group.count { it.correct } >= 3 }
                _state.update { it.copy(answers = answers, trialVisible = false, warning = null) }
                when {
                    passed && !s.nearDescending && index < nearLevels.lastIndex -> {
                        _state.update { it.copy(visionLevel = nearLevels[index + 1], trialIndex = 0,
                            direction = Random.nextInt(4)) }
                    }
                    !passed && !previousPass && index > 0 -> {
                        _state.update { it.copy(visionLevel = nearLevels[index - 1], nearDescending = true,
                            trialIndex = 0, direction = Random.nextInt(4)) }
                    }
                    else -> advanceNearEye()
                }
            } else {
                _state.update { it.copy(answers = answers, trialIndex = next, trialVisible = false,
                    direction = Random.nextInt(4), warning = null) }
            }
        } else if (s.step == ScreeningStep.DISTANCE_CURVE) {
            answerCurve(s, answers, target)
        } else {
            val next = s.trialIndex + 1
            flipperMoveStartMs = now
            _state.update { it.copy(answers = answers, trialIndex = next, trialVisible = false,
                direction = Random.nextInt(4), warning = null) }
        }
    }

    private fun advanceNearEye() {
        val next = when (_state.value.visionEye) {
            VisionEye.RIGHT -> VisionEye.LEFT
            VisionEye.LEFT -> VisionEye.BOTH
            VisionEye.BOTH -> null
        }
        if (next == null) startPushUp()
        else _state.update { it.copy(visionEye = next, nearAwaitingEyeReady = true,
            nearDescending = false, trialVisible = false, trialIndex = 0, visionLevel = 0.3f,
            direction = Random.nextInt(4), nearStopReason = null, instruction = when (next) {
                VisionEye.LEFT -> "请遮住右眼，露出左眼；准备好后继续。"
                VisionEye.BOTH -> "请移开遮盖，双眼注视屏幕；准备好后继续。"
                else -> ""
            }) }
    }

    private fun trialTargetCm(s: ScreeningState): Float? = when (s.step) {
        ScreeningStep.NEAR_VISION -> PresbyopiaProtocol.NEAR_DISTANCE_CM
        ScreeningStep.DISTANCE_CURVE -> PresbyopiaProtocol.CURVE_DISTANCES.getOrNull(s.curveIndex)
        ScreeningStep.FLIPPER -> if (s.trialIndex % 2 == 0) 50f else 25f
        else -> null
    }

    /** 双眼 40 cm 近视力通过的最高等级。 */
    private fun bestBinocularLevel(answers: List<VisionAnswer>): Float? =
        answers.filter { it.step == ScreeningStep.NEAR_VISION && it.eye == VisionEye.BOTH }
            .groupBy { it.level }.filterValues { g -> g.size >= 4 && g.count { it.correct } >= 3 }.keys.maxOrNull()

    /**
     * 移近法（原文）：手机由远向近缓慢连续移动，视标首次持续模糊时确认，记录近点与移动速度，重复测量。
     * 视标物理尺寸固定为 40 cm 处的双眼近视力阈值（不小于 0.5 档）。
     */
    private fun startPushUp() {
        stepStartMs = platform.nowMs()
        val level = (bestBinocularLevel(_state.value.answers) ?: 0.3f).coerceAtMost(PresbyopiaProtocol.PUSH_MAX_LEVEL)
        _state.update { it.copy(step = ScreeningStep.PUSH_UP, guidePending = true, nearPoints = emptyList(), trialIndex = 0,
            pushStage = PushStage.READY, trialVisible = false, visionLevel = level, pushSpeedCmPerSec = null,
            instruction = "从 45 cm 以外开始，看着 E 把手机慢慢移近；E 一开始持续模糊就点按。共 ${PresbyopiaProtocol.PUSH_REPEATS} 次。", warning = null) }
    }

    fun startPushTrial() {
        val s = _state.value
        if (s.step != ScreeningStep.PUSH_UP || s.pushStage != PushStage.READY || s.guidePending) return
        val now = platform.nowMs()
        val distance = distanceTracker.estimate(now)?.distanceCm
        if (distance == null || distance < PresbyopiaProtocol.PUSH_START_MIN_CM) {
            _state.update { it.copy(warning = "请先把手机移到 ${PresbyopiaProtocol.PUSH_START_MIN_CM.toInt()} cm 以外再开始") }
            return
        }
        // 视标物理尺寸固定：按 40 cm 处的角大小换算一次
        val spec = OptotypeGeometry.calculate(platform.display, s.visionLevel, PresbyopiaProtocol.NEAR_DISTANCE_CM)
        nextTrialId++
        questionStartMs = 0L
        _state.update { it.copy(pushStage = PushStage.APPROACH, trialVisible = true, trialId = nextTrialId,
            trialOptotypePx = spec.sizePx, trialDistanceCm = distance, pushStartCm = distance, direction = 0, warning = null) }
    }

    /** 受试者确认「开始持续模糊」。 */
    fun markPushBlur() {
        val s = _state.value
        if (s.step != ScreeningStep.PUSH_UP || s.pushStage != PushStage.APPROACH) return
        val now = platform.nowMs()
        if (questionStartMs == 0L || now - questionStartMs < 1_000L) {
            _state.update { it.copy(warning = "请先看清 E 再慢慢移近") }; return
        }
        val distance = distanceTracker.estimate(now)?.distanceCm
        if (distance == null) {
            _state.update { it.copy(warning = "测距丢失，请保持正对屏幕后重做本次", pushStage = PushStage.READY, trialVisible = false,
                invalidTrialCount = it.invalidTrialCount + 1) }
            return
        }
        val speed = recentApproachSpeed(now)
        if (speed != null && speed > PresbyopiaProtocol.PUSH_MAX_SPEED_CM_S) {
            _state.update { it.copy(warning = "移动过快（约 ${decimalString(speed.toDouble(), 1)} cm/秒），本次不计，请放慢重做",
                pushStage = PushStage.READY, trialVisible = false, invalidTrialCount = it.invalidTrialCount + 1) }
            return
        }
        finishPushTrial(NearPoint(distance, speed, s.pushStartCm))
    }

    /** 最近 1 秒的靠近速度（cm/秒，靠近为正）。 */
    private fun recentApproachSpeed(now: Long): Float? = distanceTracker.velocityCmPerSec(now)?.let { -it }

    private fun finishPushTrial(point: NearPoint) {
        val points = _state.value.nearPoints + point
        _state.update { it.copy(nearPoints = points, trialIndex = points.size,
            pushStage = PushStage.READY, trialVisible = false, pushSpeedCmPerSec = null,
            warning = if (point.censored) "移近到 ${PresbyopiaProtocol.PUSH_MIN_CM.toInt()} cm 仍未模糊，已记为「近点 < ${PresbyopiaProtocol.PUSH_MIN_CM.toInt()} cm」" else null) }
        if (points.size >= PresbyopiaProtocol.PUSH_REPEATS) startDistanceCurve()
    }

    /** 距离—视觉表现曲线：在 60 / 33 / 25 cm 分别测双眼阈值（40 cm 已由近视力任务覆盖）。 */
    private fun startDistanceCurve() {
        stepStartMs = platform.nowMs()
        questionStartMs = 0L
        val start = bestBinocularLevel(_state.value.answers) ?: 0.3f
        _state.update { it.copy(step = ScreeningStep.DISTANCE_CURVE, guidePending = true, curveIndex = 0, curvePoints = emptyList(),
            trialIndex = 0, visionLevel = start, nearDescending = false, visionEye = VisionEye.BOTH, trialVisible = false,
            direction = Random.nextInt(4), nearStopReason = null,
            instruction = "依次在 60、33、25 cm 停稳，视标会自动出现，点选开口方向。", warning = null) }
    }

    private fun answerCurve(s: ScreeningState, answers: List<VisionAnswer>, target: Float) {
        val next = s.trialIndex + 1
        if (next < PresbyopiaProtocol.CURVE_TRIALS_PER_LEVEL) {
            _state.update { it.copy(answers = answers, trialIndex = next, trialVisible = false, direction = Random.nextInt(4), warning = null) }
            return
        }
        val atDistance = answers.filter { it.step == ScreeningStep.DISTANCE_CURVE && it.targetCm == target }
        val passed = atDistance.count { it.level == s.visionLevel && it.correct } >= PresbyopiaProtocol.CURVE_PASS
        val passedOther = atDistance.filter { it.level != s.visionLevel }.groupBy { it.level }
            .values.any { g -> g.size >= PresbyopiaProtocol.CURVE_TRIALS_PER_LEVEL && g.count { it.correct } >= PresbyopiaProtocol.CURVE_PASS }
        val index = nearLevels.indexOf(s.visionLevel)
        _state.update { it.copy(answers = answers, trialVisible = false, warning = null) }
        when {
            passed && !s.nearDescending && index < nearLevels.lastIndex ->
                _state.update { it.copy(visionLevel = nearLevels[index + 1], trialIndex = 0, direction = Random.nextInt(4)) }
            !passed && !passedOther && index > 0 ->
                _state.update { it.copy(visionLevel = nearLevels[index - 1], nearDescending = true, trialIndex = 0, direction = Random.nextInt(4)) }
            else -> finishCurveDistance(resolutionLimited = false)
        }
    }

    private fun finishCurveDistance(resolutionLimited: Boolean) {
        val s = _state.value
        val target = PresbyopiaProtocol.CURVE_DISTANCES.getOrNull(s.curveIndex) ?: return
        val threshold = s.answers.filter { it.step == ScreeningStep.DISTANCE_CURVE && it.targetCm == target }
            .groupBy { it.level }
            .filterValues { g -> g.size >= PresbyopiaProtocol.CURVE_TRIALS_PER_LEVEL && g.count { it.correct } >= PresbyopiaProtocol.CURVE_PASS }
            .keys.maxOrNull()
        val points = s.curvePoints + CurvePoint(target, threshold, resolutionLimited)
        val nextIndex = s.curveIndex + 1
        if (nextIndex >= PresbyopiaProtocol.CURVE_DISTANCES.size) {
            _state.update { it.copy(curvePoints = points) }
            startFlipper()
        } else {
            _state.update { it.copy(curvePoints = points, curveIndex = nextIndex, trialIndex = 0, nearDescending = false,
                visionLevel = bestBinocularLevel(it.answers) ?: 0.3f, direction = Random.nextInt(4), trialVisible = false,
                nearStopReason = if (resolutionLimited) "上一距离更小视标受屏幕像素限制" else null,
                warning = null) }
        }
    }

    private fun startFlipper() {
        stepStartMs = platform.nowMs()
        flipperMoveStartMs = stepStartMs
        questionStartMs = 0L
        _state.update { it.copy(step = ScreeningStep.FLIPPER, guidePending = true, trialIndex = 0, direction = Random.nextInt(4), visionLevel = 0.2f,
            trialVisible = false, visionEye = VisionEye.BOTH, secondsLeft = 60,
            instruction = "真实交替到 50 cm 与 25 cm。停稳后才呈现视标；分别记录移动和辨认时间，限时 60 秒。", warning = null) }

    }

    private fun startFixation() {
        gaze = null; gazeCalibration.clear(); renderedTargets.clear()
        stepStartMs = platform.nowMs()
        _state.update { it.copy(step = ScreeningStep.FIXATION, guidePending = true, gazeCalibrated = false,
            gazeWarning = null, secondsLeft = StrabismusProtocol.FIXATION_SECONDS, instruction = "看着中央闪烁的小瞳，头和手机保持不动。", warning = null) }

    }

    private fun startCover() {
        resetGazeWindow()
        coverIndex = 0
        coverStageStartMs = platform.nowMs()
        stepStartMs = platform.nowMs()
        _state.update { it.copy(step = ScreeningStep.COVER, guidePending = true, coverMarks = emptyList(), instruction = "协助者真实遮盖单眼，不挡摄像头；按提示动作的同时点按：${coverSequence[0].label}", warning = null) }
    }

    fun markCover() {
        val s = _state.value
        if (s.step != ScreeningStep.COVER || s.guidePending || coverIndex >= coverSequence.size) return
        val now = platform.nowMs()
        // 上一动作需保持到原文规定时长（留 200 ms 余量）
        val minimumMs = if (coverIndex == 0) 0L else (coverSequence[coverIndex - 1].holdMs - 200L).coerceAtLeast(0L)
        if (now - coverStageStartMs < minimumMs) {
            _state.update { it.copy(warning = "请保持当前动作约 ${coverSequence[coverIndex - 1].holdMs / 1000} 秒") }
            return
        }
        val action = coverSequence[coverIndex]
        val marks = s.coverMarks + CoverMark(action.label, now, action.uncovered, action.covered)
        coverIndex++
        coverStageStartMs = now
        if (coverIndex >= coverSequence.size) {
            _state.update { it.copy(coverMarks = marks) }
            scope.launch {
                delay(600)
                if (_state.value.step == ScreeningStep.COVER) startPursuit()
            }
        } else {
            _state.update { it.copy(coverMarks = marks, instruction = "保持 ${coverSequence[coverIndex - 1].holdMs / 1000} 秒后，执行并点按：${coverSequence[coverIndex].label}", warning = null) }
        }
    }

    private fun startPursuit() {
        resetGazeWindow()
        stepStartMs = platform.nowMs()
        _state.update { it.copy(step = ScreeningStep.PURSUIT, guidePending = true, secondsLeft = StrabismusProtocol.PURSUIT_SECONDS,
            instruction = "只用眼睛跟随移动的小瞳：水平 8 秒、垂直 8 秒、斜向 6 秒。", warning = null) }

    }

    /** 示范结束后才开始采样和计时，避免将阅读时间计入测试。 */
    fun confirmGuide() {
        val s = _state.value
        if (!s.guidePending) return
        stepStartMs = platform.nowMs()
        flipperMoveStartMs = stepStartMs
        coverStageStartMs = stepStartMs
        faceCenterBaseline = null
        _state.update { it.copy(guidePending = false) }
        when (s.step) {
            ScreeningStep.NEAR_VISION -> readyForNearEye()
            ScreeningStep.FLIPPER -> {
                timer?.cancel()
                timer = scope.launch {
                    repeat(60) { index -> delay(1000); _state.update { it.copy(secondsLeft = 59 - index) } }
                    finish()
                }
            }
            ScreeningStep.FIXATION -> retryGazeCalibration()
            ScreeningStep.PURSUIT -> runTimedStep(StrabismusProtocol.PURSUIT_SECONDS) { finish() }
            else -> Unit
        }
    }

    /** 校准不计入正式眼位测量；失败停留在本页，不能带着无效校准进入结果。 */
    fun retryGazeCalibration() {
        if (_state.value.step != ScreeningStep.FIXATION && _state.value.step != ScreeningStep.GAZE_CALIBRATION) return
        timer?.cancel(); gaze = null; gazeCalibration.clear(); renderedTargets.clear(); resetGazeWindow()
        faceCenterBaseline = null
        stepStartMs = platform.nowMs()
        _state.update { it.copy(step = ScreeningStep.GAZE_CALIBRATION, guidePending = false,
            gazeCalibrationIndex = 0, gazeCalibrationFailed = false, gazeCalibrated = false,
            instruction = "只用眼睛依次看着圆点，头和手机保持不动。", warning = null) }
        timer = scope.launch {
            for (index in StrabismusGaze.calibrationTargets.indices) {
                _state.update { it.copy(gazeCalibrationIndex = index) }
                delay(StrabismusGaze.CALIBRATION_POINT_MS)
            }
            gaze = StrabismusGaze.calibrate(gazeCalibration)
            if (gaze == null) {
                _state.update { it.copy(gazeCalibrationFailed = true,
                    warning = "未获得可靠注视校准。请看着每个圆点，保持头和手机不动后重试。") }
            } else {
                stepStartMs = platform.nowMs(); resetGazeWindow(); faceCenterBaseline = null
                _state.update { it.copy(step = ScreeningStep.FIXATION, gazeCalibrated = true,
                    instruction = "看着中央闪烁的小瞳，头和手机保持不动。", targetX = 1f, targetY = 0f) }
                runTimedStep(StrabismusProtocol.FIXATION_SECONDS) { startCover() }
            }
        }
    }

    /** 记录实际画出的目标，而非未考虑画布缩放、滚动和布局位置的理论轨迹。 */
    fun reportGazeTarget(xPx: Float, yPx: Float) {
        val s = _state.value
        if (s.guidePending || s.step !in setOf(ScreeningStep.GAZE_CALIBRATION, ScreeningStep.FIXATION, ScreeningStep.COVER, ScreeningStep.PURSUIT)) return
        val display = platform.display
        val now = platform.nowMs()
        renderedTargets.addLast(RenderedTarget(now, s.step, s.gazeCalibrationIndex,
            GazePoint(xPx / display.widthPixels, yPx / display.heightPixels)))
        while (renderedTargets.isNotEmpty() && renderedTargets.first().timeMs < now - 2_000L) renderedTargets.removeFirst()
    }

    private fun resetGazeWindow() {
        gazeBadSinceMs = null; lastGazeFrameMs = 0L; lastPursuitCheckMs = 0L; pursuitFollowing = null
        _state.update { it.copy(gazeWarning = null) }
    }

    private fun runTimedStep(seconds: Int, after: () -> Unit) {
        timer?.cancel()
        timer = scope.launch {
            repeat(seconds * 20) { index ->
                delay(50)
                val elapsed = (index + 1) / 20f
                // 追踪：目标位置以视角（度）表示；注视：targetX>0 表示闪烁的「亮」相（1.5 Hz）
                val target = if (_state.value.step == ScreeningStep.PURSUIT) StrabismusProtocol.pursuitTarget(elapsed).let { it.first to it.second }
                    else (if ((elapsed * StrabismusProtocol.FLASH_HZ * 2f).toInt() % 2 == 0) 1f else -1f) to 0f
                _state.update { it.copy(secondsLeft = (seconds - elapsed).toInt().coerceAtLeast(0), targetX = target.first, targetY = target.second) }
            }
            after()
        }
    }

    private fun finish() {
        timer?.cancel()
        val s = _state.value
        val assessment = if (s.kind == ScreeningKind.PRESBYOPIA) ScreeningAssessmentModel.presbyopia(s)
        else ScreeningAssessmentModel.strabismus(s, samples, qualityEligibleFrames, qualityEligibleValidFrames)
        val metrics = if (s.kind == ScreeningKind.PRESBYOPIA) PresbyopiaMetrics.summarize(s.answers, s.nearPoints, s.curvePoints, s.nearResolutionLimitedEyes)
        else StrabismusMetrics.summarize(samples, s.coverMarks, totalFrames, validFrames,
            qualityEligibleFrames, qualityEligibleValidFrames, s.pursuitMaxDeg)
        val result = assessment.displayLines() + metrics
        val capturedSamples = samples.toList()
        val capturedCalibration = gazeCalibration.toList()
        val capturedTotal = totalFrames
        val capturedValid = validFrames
        val capturedQualityEligible = qualityEligibleFrames
        val capturedQualityValid = qualityEligibleValidFrames
        _state.update { it.copy(step = ScreeningStep.RESULT, result = result,
            riskLevel = assessment.riskLevel, riskScope = assessment.riskScope, secondsLeft = 0,
            category = assessment.category, findings = assessment.findings, limitations = assessment.limitations,
            features = assessment.features) }
        stopCamera()
        scope.launch(Dispatchers.IO) {
            runCatching {
                val data = RecordObject().apply {
                    put("protocolVersion", if (s.kind == ScreeningKind.STRABISMUS) 5 else 4)
                    if (s.kind == ScreeningKind.STRABISMUS) {
                        put("gazeCalibrated", s.gazeCalibrated)
                        put("gazeCalibration", RecordArray().apply { capturedCalibration.forEach { c -> put(RecordObject().apply {
                            put("index", c.index); put("targetX", c.target.x.toDouble()); put("targetY", c.target.y.toDouble())
                            put("leftX", c.left?.x?.toDouble() ?: RecordObject.NULL); put("leftY", c.left?.y?.toDouble() ?: RecordObject.NULL)
                            put("rightX", c.right?.x?.toDouble() ?: RecordObject.NULL); put("rightY", c.right?.y?.toDouble() ?: RecordObject.NULL)
                        }) } })
                    }
                    put("kind", s.kind.name)
                    put("assessment", RecordObject().apply {
                        put("modelVersion", assessment.version)
                        put("quality", assessment.quality)
                        put("riskLevel", assessment.riskLevel.name)
                        put("riskScope", assessment.riskScope)
                        put("category", assessment.category)
                        put("features", RecordObject().apply { assessment.features.forEach { (key, value) ->
                            put(key, value?.takeIf(Double::isFinite) ?: RecordObject.NULL)
                        } })
                        put("findings", RecordArray(assessment.findings))
                        put("limitations", RecordArray(assessment.limitations))
                    })
                    put("mode", s.mode)
                    put("profile", RecordObject(s.fields))
                    put("ambientLux", s.lux?.toDouble() ?: RecordObject.NULL)
                    put("lightSensorAvailable", s.lightSensorAvailable ?: RecordObject.NULL)
                    put("cameraBrightness", s.cameraBrightness?.toDouble() ?: RecordObject.NULL)
                    put("cameraFrames", s.cameraFrames)
                    put("opticalValidFrames", s.opticalValidFrames)
                    put("opticalRejectionCounts", RecordObject(s.opticalRejectionCounts))
                    put("nearResolutionLimitedEyes", RecordArray(s.nearResolutionLimitedEyes.map { it.name }))
                    put("invalidTrialCount", s.invalidTrialCount)
                    put("lastDistanceCm", s.distanceCm.toDouble())
                    val display = platform.display
                    put("displayWidthPx", display.widthPixels)
                    put("displayHeightPx", display.heightPixels)
                    put("displayXdpi", display.xdpi.toDouble())
                    put("displayYdpi", display.ydpi.toDouble())
                    put("platform", platform.platformName)
                    put("distanceSource", platform.distanceSource)
                    put("displaySizeSource", platform.displaySizeSource)
                    put("totalFrames", capturedTotal)
                    put("validFrames", capturedValid)
                    put("qualityEligibleFrames", capturedQualityEligible)
                    put("qualityEligibleValidFrames", capturedQualityValid)
                    put("answers", RecordArray().apply { s.answers.forEach { a -> put(RecordObject().apply {
                        put("step", a.step.name); put("distanceCm", a.distanceCm.toDouble()); put("level", a.level.toDouble())
                        put("expectedDirection", a.expectedDirection); put("selectedDirection", a.selectedDirection)
                        put("correct", a.correct); put("latencyMs", a.latencyMs)
                        put("optotypePx", a.optotypePx.toDouble())
                        put("eye", a.eye.name); put("presentedAtElapsedRealtimeMs", a.presentedAtMs)
                        put("movementMs", a.movementMs ?: RecordObject.NULL)
                        put("clarityMs", a.clarityMs ?: RecordObject.NULL)
                        put("distanceSpreadCm", a.distanceSpreadCm.toDouble())
                        put("answerDistanceCm", a.answerDistanceCm.toDouble()); put("byVoice", a.byVoice)
                    }) } })
                    put("nearPoints", RecordArray().apply { s.nearPoints.forEach { p -> put(RecordObject().apply {
                        put("distanceCm", p.distanceCm.toDouble())
                        put("speedCmPerSec", p.speedCmPerSec?.toDouble() ?: RecordObject.NULL)
                        put("startCm", p.startCm?.toDouble() ?: RecordObject.NULL)
                        put("censored", p.censored)
                    }) } })
                    put("curvePoints", RecordArray().apply { s.curvePoints.forEach { c -> put(RecordObject().apply {
                        put("distanceCm", c.distanceCm.toDouble())
                        put("thresholdDecimal", c.thresholdDecimal?.toDouble() ?: RecordObject.NULL)
                        put("resolutionLimited", c.resolutionLimited)
                    }) } })
                    put("pursuitMaxDeg", s.pursuitMaxDeg?.let { RecordArray(listOf(it.first.toDouble(), it.second.toDouble())) } ?: RecordObject.NULL)
                    put("coverMarks", RecordArray().apply { s.coverMarks.forEach { mark -> put(RecordObject().apply {
                        put("label", mark.label); put("elapsedRealtimeMs", mark.timeMs)
                        put("uncovered", mark.uncovered?.name ?: RecordObject.NULL); put("covered", mark.covered?.name ?: RecordObject.NULL)
                    }) } })
                    put("eyeSamples", RecordArray().apply { capturedSamples.forEach { sample -> put(RecordObject().apply {
                        put("elapsedRealtimeMs", sample.timeMs); put("step", sample.step.name)
                        put("leftX", sample.leftX.takeIf(Float::isFinite)?.toDouble() ?: RecordObject.NULL)
                        put("leftY", sample.leftY.takeIf(Float::isFinite)?.toDouble() ?: RecordObject.NULL)
                        put("rightX", sample.rightX.takeIf(Float::isFinite)?.toDouble() ?: RecordObject.NULL)
                        put("rightY", sample.rightY.takeIf(Float::isFinite)?.toDouble() ?: RecordObject.NULL)
                        put("distanceCm", sample.distanceCm.toDouble())
                        put("targetX", sample.targetX.toDouble()); put("targetY", sample.targetY.toDouble())
                        put("targetScreenX", sample.targetScreenX?.toDouble() ?: RecordObject.NULL)
                        put("targetScreenY", sample.targetScreenY?.toDouble() ?: RecordObject.NULL)
                        put("leftGazeX", sample.leftGazeX?.toDouble() ?: RecordObject.NULL); put("leftGazeY", sample.leftGazeY?.toDouble() ?: RecordObject.NULL)
                        put("rightGazeX", sample.rightGazeX?.toDouble() ?: RecordObject.NULL); put("rightGazeY", sample.rightGazeY?.toDouble() ?: RecordObject.NULL)
                        put("gazeOnTarget", sample.gazeOnTarget ?: RecordObject.NULL)
                        put("leftOpticalX", sample.leftOpticalX?.toDouble() ?: RecordObject.NULL)
                        put("leftOpticalY", sample.leftOpticalY?.toDouble() ?: RecordObject.NULL)
                        put("rightOpticalX", sample.rightOpticalX?.toDouble() ?: RecordObject.NULL)
                        put("rightOpticalY", sample.rightOpticalY?.toDouble() ?: RecordObject.NULL)
                        put("headYawDeg", sample.headYawDeg?.toDouble() ?: RecordObject.NULL)
                        put("headPitchDeg", sample.headPitchDeg?.toDouble() ?: RecordObject.NULL)
                        put("headRollDeg", sample.headRollDeg?.toDouble() ?: RecordObject.NULL)
                        put("leftPupilX", sample.leftPupilX?.toDouble() ?: RecordObject.NULL)
                        put("leftPupilY", sample.leftPupilY?.toDouble() ?: RecordObject.NULL)
                        put("leftReflexX", sample.leftReflexX?.toDouble() ?: RecordObject.NULL)
                        put("leftReflexY", sample.leftReflexY?.toDouble() ?: RecordObject.NULL)
                        put("rightPupilX", sample.rightPupilX?.toDouble() ?: RecordObject.NULL)
                        put("rightPupilY", sample.rightPupilY?.toDouble() ?: RecordObject.NULL)
                        put("rightReflexX", sample.rightReflexX?.toDouble() ?: RecordObject.NULL)
                        put("rightReflexY", sample.rightReflexY?.toDouble() ?: RecordObject.NULL)
                        put("leftNasal", sample.leftNasal?.toDouble() ?: RecordObject.NULL)
                        put("rightNasal", sample.rightNasal?.toDouble() ?: RecordObject.NULL)
                        put("leftVertical", sample.leftVertical?.toDouble() ?: RecordObject.NULL)
                        put("rightVertical", sample.rightVertical?.toDouble() ?: RecordObject.NULL)
                        put("pursuitSegment", sample.pursuitSegment)
                    }) } })
                    put("summary", RecordArray(result))
                }
                val history = platform.compareHistory(s, assessment)
                _state.update { it.copy(history = history) }
                val name = "${s.kind.name.lowercase()}_${platform.epochMs()}.json"
                platform.saveRecord(name, data.toString())
                name
            }.onSuccess { name -> _state.update { it.copy(savedFile = name) } }
                .onFailure { error -> _state.update { it.copy(warning = "保存测试记录失败：${error.message}") } }
        }
    }

    /** 追踪画布在当前距离下能显示的最大视角（度），用于报告原文 ±10° 是否被屏幕限制。 */
    fun reportPursuitCapacity(maxXDeg: Float, maxYDeg: Float) {
        if (_state.value.pursuitMaxDeg == null) _state.update { it.copy(pursuitMaxDeg = maxXDeg to maxYDeg) }
    }

    fun coverRemainingMs(): Long = if (_state.value.step != ScreeningStep.COVER) 0L else
        ((coverSequence.getOrNull(coverIndex - 1)?.holdMs ?: 0L) - (platform.nowMs() - coverStageStartMs)).coerceAtLeast(0L)

    fun interruptTest() {
        val current = _state.value
        if (current.step == ScreeningStep.PROFILE || current.step == ScreeningStep.RESULT) return
        timer?.cancel()
        stopCamera()
        samples.clear()
        gaze = null; gazeCalibration.clear(); renderedTargets.clear(); resetGazeWindow()
        distanceTracker.clear()
        _state.value = ScreeningState(kind = current.kind, fields = current.fields, mode = current.mode,
            step = ScreeningStep.ENVIRONMENT, warning = "测试已中断，请重新开始")
    }

    fun restart() {
        timer?.cancel(); stopCamera(); samples.clear(); distanceTracker.clear()
        gaze = null; gazeCalibration.clear(); renderedTargets.clear(); resetGazeWindow()
        _state.value = ScreeningState(kind = _state.value.kind)
    }

    private fun distanceOk(target: Float, tolerance: Float) =
        distanceTracker.estimate(platform.nowMs())?.let { abs(it.distanceCm - target) <= tolerance } == true

    private fun distanceSpread(): Float =
        distanceTracker.estimate(platform.nowMs())?.spreadCm ?: 0f

    private fun distanceStable(target: Float, tolerance: Float): Boolean =
        distanceTracker.stable(target, tolerance, platform.nowMs())

    private fun processFrame(time: Long, step: ScreeningStep, frame: FaceLandmarkFrame) {
        cameraFrameCount++
        lastCameraFrameMs = time
        val face = frame.faces.singleOrNull()
        val left = face?.leftEye?.toNormalized()
        val right = face?.rightEye?.toNormalized()
        val iris = listOfNotNull(face?.leftEye?.irisDiameterPx, face?.rightEye?.irisDiameterPx).filter { it > 0f }
        val calibrationVersion = platform.cameraGeometry(frame.sourceWidth, frame.sourceHeight).version
        if (cachedFocalWidth != frame.sourceWidth || cachedGeometry?.imageHeight != frame.sourceHeight ||
            cachedCalibrationVersion != calibrationVersion) {
            distanceTracker.clear()
            cachedFocalWidth = frame.sourceWidth
            cachedCalibrationVersion = calibrationVersion
            cachedGeometry = platform.cameraGeometry(frame.sourceWidth, frame.sourceHeight)
            cachedFocalPx = cachedGeometry?.focalLengthPx ?: 0f
        }
        val focalPx = cachedFocalPx
        val rawDistance = if (iris.isNotEmpty()) DepthFromIris.distanceCm(iris.average().toFloat(), focalPx) else 0f
        val distance = rawDistance
        if (face != null) distanceTracker.add(time, distance)
        val smoothedDistance = if (face != null) distanceTracker.estimate(time)?.distanceCm ?: 0f else 0f
        val pose = face?.headPose
        val leftOptical = face?.leftOptical
        val rightOptical = face?.rightOptical
        fun opticalQuality(eye: com.zkyjss.eyeproject.core.model.OpticalEyeMeasurement?): String =
            eye?.let {
                "虹膜 ${decimalString(it.irisDiameterPx.toDouble(), 1)} px；瞳孔对比 ${decimalString(it.pupilContrast.toDouble(), 0)}；反光对比 ${decimalString(it.reflexContrast.toDouble(), 0)}"
            } ?: "眼区像素不可用"
        val opticalReady = leftOptical?.usable == true && rightOptical?.usable == true
        val poseStable = pose != null && abs(pose.yawDeg) <= 10f &&
            abs(pose.pitchDeg) <= 10f && abs(pose.rollDeg) <= 8f
        val opticalStatus = when {
            face == null -> "等待人脸"
            pose == null -> "头姿不可用"
            leftOptical?.usable != true -> "左眼：${leftOptical?.rejectionReason ?: "眼区不可用"}"
            rightOptical?.usable != true -> "右眼：${rightOptical?.rejectionReason ?: "眼区不可用"}"
            !poseStable -> "头姿偏转过大"
            else -> "双眼光学测量可用"
        }
        if (_state.value.kind == ScreeningKind.STRABISMUS && opticalStatus != "双眼光学测量可用") {
            opticalRejectionCounts[opticalStatus] = (opticalRejectionCounts[opticalStatus] ?: 0) + 1
        }
        val trackingStatus = when {
            face == null -> "相机已工作，未识别人脸"
            left == null || right == null -> "相机已工作，双眼关键点不完整"
            focalPx <= 0f -> "已识别双眼，但前置相机焦距参数不可用"
            smoothedDistance <= 0f -> "已识别双眼，但虹膜测距失败"
            else -> "双眼跟踪与测距正常"
        }
        if (headStep != step) {
            headStep = step
            faceCenterBaseline = null
        }
        val center = face?.faceBox?.let { ((it.left + it.right) / 2f) to ((it.top + it.bottom) / 2f) }
        if (faceCenterBaseline == null && center != null) faceCenterBaseline = center
        val baseline = faceCenterBaseline
        val headStable = center != null && baseline != null &&
            abs(center.first - baseline.first) <= frame.sourceWidth * 0.08f &&
            abs(center.second - baseline.second) <= frame.sourceHeight * 0.08f
        val valid = smoothedDistance in 10f..100f &&
            (_state.value.kind != ScreeningKind.PRESBYOPIA || (left != null || right != null)) &&
            (_state.value.kind != ScreeningKind.STRABISMUS ||
                ((if (step == ScreeningStep.COVER) left != null || right != null else left != null && right != null) &&
                    abs(smoothedDistance - StrabismusProtocol.DISTANCE_CM) <= StrabismusProtocol.DISTANCE_TOLERANCE_CM && headStable && poseStable)) &&
            (_state.value.lux == null || _state.value.lux!! >= 50f)
        val rendered = renderedTargets.lastOrNull { it.timeMs <= time && it.step == step }
        val covered = if (step == ScreeningStep.COVER) _state.value.coverMarks.lastOrNull()?.covered else null
        fun openEye(eye: EyeLandmarks?, position: Pair<Float, Float>?, which: VisionEye): GazePoint? =
            position?.takeIf { covered != which && eye != null && (BlinkDetector.ear(eye.earPoints) ?: 0f) >= 0.16f }
                ?.let { GazePoint(it.first, it.second) }
        val gazeLeft = openEye(face?.leftEye, left, VisionEye.LEFT)
        val gazeRight = openEye(face?.rightEye, right, VisionEye.RIGHT)
        if (step == ScreeningStep.GAZE_CALIBRATION && !_state.value.gazeCalibrationFailed &&
            step == _state.value.step && valid && rendered != null && rendered.index == _state.value.gazeCalibrationIndex) {
            val firstShown = renderedTargets.firstOrNull { it.step == step && it.index == rendered.index }?.timeMs ?: time
            if (time - firstShown >= StrabismusGaze.SETTLE_MS)
                gazeCalibration.add(GazeCalibrationSample(rendered.index, rendered.point, gazeLeft, gazeRight))
        }
        val estimates = if (valid) gaze?.estimate(gazeLeft, gazeRight) else null
        val gazeOnTarget = rendered?.let { target -> estimates?.let { (l, r) ->
            if (l == null && r == null) null else StrabismusGaze.onTarget(l, target.point) || StrabismusGaze.onTarget(r, target.point)
        } }
        if (!_state.value.guidePending && step == _state.value.step && time >= stepStartMs && step != ScreeningStep.PROFILE && step != ScreeningStep.ENVIRONMENT && step != ScreeningStep.RESULT && step != ScreeningStep.GAZE_CALIBRATION) {
            totalFrames++
            val eligible = step != ScreeningStep.COVER ||
                _state.value.coverMarks.lastOrNull()?.label?.let { it.startsWith("移开") || it.startsWith("完全") } == true
            if (eligible) qualityEligibleFrames++
            if (valid) {
                validFrames++
                if (eligible) qualityEligibleValidFrames++
                if (_state.value.kind == ScreeningKind.STRABISMUS && opticalReady) opticalValidFrames++
                val elapsed = (time - stepStartMs) / 1000f
                val pursuit = if (step == ScreeningStep.PURSUIT) StrabismusProtocol.pursuitTarget(elapsed) else null
                val target = pursuit?.let { it.first to it.second } ?: (0f to 0f)
                // 鼻侧坐标：左眼的鼻侧朝向右眼，右眼相反；映光偏心 = (映光 − 瞳孔) / 虹膜直径。
                // 每只眼单独记录：交替遮盖时总有一只眼被遮，未遮眼的数据仍要保留。
                val leftCenter = face?.leftEye?.irisPoints?.firstOrNull()?.x
                val rightCenter = face?.rightEye?.irisPoints?.firstOrNull()?.x
                if (leftCenter != null && rightCenter != null) nasalSign = if (rightCenter > leftCenter) 1f else -1f
                val leftNasalSign = nasalSign
                val leftNasal = leftOptical?.horizontalIrisUnits?.let { -it * leftNasalSign }
                val rightNasal = rightOptical?.horizontalIrisUnits?.let { -it * -leftNasalSign }
                val leftVertical = leftOptical?.verticalIrisUnits?.let { -it }
                val rightVertical = rightOptical?.verticalIrisUnits?.let { -it }
                if (_state.value.kind == ScreeningKind.STRABISMUS && (left != null || right != null)) {
                    samples.add(EyeMotionSample(time, step, left?.first ?: Float.NaN, left?.second ?: Float.NaN, right?.first ?: Float.NaN, right?.second ?: Float.NaN,
                        smoothedDistance, target.first, target.second,
                        if (opticalReady) leftOptical?.horizontalIrisUnits else null,
                        if (opticalReady) leftOptical?.verticalIrisUnits else null,
                        if (opticalReady) rightOptical?.horizontalIrisUnits else null,
                        if (opticalReady) rightOptical?.verticalIrisUnits else null,
                        pose?.yawDeg, pose?.pitchDeg, pose?.rollDeg,
                        leftOptical?.pupilCenter?.x, leftOptical?.pupilCenter?.y,
                        leftOptical?.cornealReflex?.x, leftOptical?.cornealReflex?.y,
                        rightOptical?.pupilCenter?.x, rightOptical?.pupilCenter?.y,
                        rightOptical?.cornealReflex?.x, rightOptical?.cornealReflex?.y,
                        leftNasal, rightNasal, leftVertical, rightVertical, pursuit?.third ?: -1,
                        rendered?.point?.x, rendered?.point?.y, estimates?.first?.x, estimates?.first?.y,
                        estimates?.second?.x, estimates?.second?.y, gazeOnTarget))
                }
            }
            if (_state.value.kind == ScreeningKind.STRABISMUS &&
                step in setOf(ScreeningStep.FIXATION, ScreeningStep.COVER, ScreeningStep.PURSUIT)) {
                if (time - lastGazeFrameMs > 500L) gazeBadSinceMs = null
                lastGazeFrameMs = time
                if (step == ScreeningStep.PURSUIT && time - lastPursuitCheckMs >= 500L) {
                    lastPursuitCheckMs = time
                    pursuitFollowing = PursuitGazeCheck.follows(samples.filter { it.timeMs >= time - 4_000L }, segment =
                        StrabismusProtocol.pursuitTarget((time - stepStartMs) / 1000f).third)
                }
                val offTarget = gazeOnTarget != true || pursuitFollowing == false
                if (offTarget) {
                    if (gazeBadSinceMs == null) gazeBadSinceMs = time
                } else gazeBadSinceMs = null
                val gazeWarning = when {
                    gazeOnTarget == null && gazeBadSinceMs?.let { time - it >= 2_000L } == true -> "暂时无法确认注视，请保持头部稳定并看着屏幕目标"
                    gazeOnTarget != null && gazeBadSinceMs?.let { time - it >= 1_500L } == true ->
                        if (step == ScreeningStep.PURSUIT) "未检测到有效跟随，请只用眼睛跟随移动的小瞳" else "注视明显偏离，请看着屏幕上的小瞳"
                    else -> null
                }
                _state.update { it.copy(gazeWarning = gazeWarning) }
            }
        }
        if (time - lastUiFrameMs >= 200) {
            lastUiFrameMs = time
            _state.update { it.copy(faceDetected = left != null && right != null, distanceCm = smoothedDistance,
                frameCount = totalFrames, acceptedFrames = validFrames, cameraFrames = cameraFrameCount,
                cameraBrightness = frame.imageBrightness, trackingStatus = trackingStatus,
                opticalStatus = opticalStatus, opticalValidFrames = opticalValidFrames,
                opticalRejectionCounts = opticalRejectionCounts.toMap(),
                leftOpticalQuality = opticalQuality(leftOptical),
                rightOpticalQuality = opticalQuality(rightOptical),
                leftOpticalX = if (opticalReady && poseStable) leftOptical?.horizontalIrisUnits else null,
                leftOpticalY = if (opticalReady && poseStable) leftOptical?.verticalIrisUnits else null,
                rightOpticalX = if (opticalReady && poseStable) rightOptical?.horizontalIrisUnits else null,
                rightOpticalY = if (opticalReady && poseStable) rightOptical?.verticalIrisUnits else null,
                headPose = pose,
                warning = if (it.warning?.startsWith("未收到相机") == true ||
                    it.warning?.startsWith("相机数据已中断") == true ||
                    it.warning?.startsWith("相机错误") == true) null else it.warning,
            ) }
        }
        val st = _state.value
        // 停稳后视标自动出现：免去点按「显示视标」带动手机
        if ((step == ScreeningStep.FLIPPER && st.secondsLeft > 0 || step == ScreeningStep.DISTANCE_CURVE ||
                step == ScreeningStep.NEAR_VISION && !st.nearAwaitingEyeReady) &&
            !st.guidePending && !st.trialVisible) {
            trialTargetCm(st)?.let { target -> if (distanceStable(target, nearDistanceToleranceCm)) showTrial() }
        }
        if (step == ScreeningStep.PUSH_UP && st.pushStage == PushStage.APPROACH) {
            if (questionStartMs == 0L && st.trialVisible) questionStartMs = time
            if (lastUiFrameMs == time) _state.update { it.copy(pushSpeedCmPerSec = recentApproachSpeed(time)) }
            if (smoothedDistance in 1f..PresbyopiaProtocol.PUSH_MIN_CM) {
                finishPushTrial(NearPoint(smoothedDistance, recentApproachSpeed(time), st.pushStartCm, censored = true))
            }
        }
    }

    private fun EyeLandmarks.toNormalized(): Pair<Float, Float>? {
        val iris = irisPoints.firstOrNull() ?: return null
        val a = earPoints.getOrNull(0) ?: return null
        val b = earPoints.getOrNull(3) ?: return null
        val width = hypot(b.x - a.x, b.y - a.y)
        if (width < 2f) return null
        return ((iris.x - (a.x + b.x) / 2f) / width) to ((iris.y - (a.y + b.y) / 2f) / width)
    }

    fun close() {
        timer?.cancel(); stopCamera(); frames.close()
    }
}
