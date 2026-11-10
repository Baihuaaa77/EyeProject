package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.domain.*
import com.zkyjss.eyeproject.core.model.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** 视标尺寸变化超过这个比例才刷新，避免随距离抖动频繁重画。 */
internal const val RESIZE_STEP = 0.02f
internal const val BACK_TO_DISTANCE = "距离超出范围，请回到约"

/** 双端统一入口：病种状态转移委托各自状态机，此处只持有共享会话、采集与生命周期。 */
class ScreeningEngine(internal val platform: ScreeningPlatform, internal val scope: CoroutineScope,
    internal val recordScope: CoroutineScope = ScreeningPersistence.scope,
    internal val recordDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    internal val _state = MutableStateFlow(ScreeningState())
    val state: StateFlow<ScreeningState> = _state.asStateFlow()
    internal val frames = Channel<Triple<Long, ScreeningStep, FaceLandmarkFrame>>(Channel.UNLIMITED)
    internal val samples = mutableListOf<EyeMotionSample>()
    internal var gaze: StrabismusGaze? = null
    internal val gazeCalibration = mutableListOf<GazeCalibrationSample>()
    internal var calibrationPointStartMs = 0L
    /** 校准异常（人脸丢失、头偏、距离超出、闭眼）开始的时刻；异常期间不采样、本点计时暂停。 */
    internal var calibrationPausedSinceMs: Long? = null
    /** 异常恢复的时刻：恢复后需重新稳定 [StrabismusGaze.RESETTLE_MS] 才继续采样。 */
    internal var calibrationResumeMs = 0L
    /** 当前点在屏幕上的实际位置发生移动（如提示条挤动布局）的时刻：移动后视线需重新稳定。 */
    internal var calibrationTargetMovedMs = 0L
    /** 当前点已累计的有效采样时长与上一次采样时刻。 */
    internal var calibrationSampledMs = 0L
    internal var lastCalibrationSampleMs = 0L
    internal val voiceGuard = VoiceRepeatGuard()
    internal data class RenderedTarget(val timeMs: Long, val step: ScreeningStep, val index: Int, val point: GazePoint)
    internal val renderedTargets = ArrayDeque<RenderedTarget>()
    internal var gazeBadSinceMs: Long? = null
    internal var lastGazeFrameMs = 0L
    internal var lastPursuitCheckMs = 0L
    internal var pursuitFollowing: Boolean? = null
    internal var nasalSign = -1f
    internal var timer: Job? = null
    internal var questionStartMs = 0L
    internal val nearLevels = listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.8f, 1.0f)
    internal var nextTrialId = 0
    internal var flipperMoveStartMs = 0L
    /** 远近切换看清确认：当前距离答错的次数。 */
    internal var flipperCheckMisses = 0
    internal val distanceTracker = ScreeningDistanceTracker()
    internal val nearDistanceToleranceCm = PresbyopiaProtocol.DISTANCE_TOLERANCE_CM
    /** 上一帧的目标距离；变化时进入「请移动到 xx 厘米处」。 */
    internal var lastDistanceTargetCm: Float? = null
    /** 多距离视力：当前距离因屏幕像素不足改测过较大视标。 */
    internal var curveResolutionLimited = false
    internal var stepStartMs = 0L
    internal var totalFrames = 0
    internal var validFrames = 0
    internal var qualityEligibleFrames = 0
    internal var qualityEligibleValidFrames = 0
    internal var lastUiFrameMs = 0L
    internal val coverDetector = CoverDetector()
    internal var coverIndex = 0
    internal var coverStageStartMs = 0L
    internal var coverHoldJob: Job? = null
    internal var headStep: ScreeningStep? = null
    internal var faceCenterBaseline: Pair<Float, Float>? = null
    internal var cameraAttached = false
    internal var cameraFrameCount = 0
    internal var lastCameraFrameMs = 0L
    internal var cachedFocalWidth = 0
    internal var cachedFocalPx = 0f
    internal var cachedCalibrationVersion = 0L
    internal var cameraWatchdog: Job? = null
    internal var opticalValidFrames = 0
    /** 瞳孔对比度不足、改用虹膜中心作映光参照的帧数（两眼合计）。 */
    internal var opticalIrisReferenceFrames = 0
    internal val opticalRejectionCounts = mutableMapOf<String, Int>()
    /** 当前一次移近的距离轨迹（相对开始移近的秒数, cm）。 */
    internal val pushTrace = mutableListOf<Pair<Float, Float>>()
    internal var pushTraceStartMs = 0L

    /** 最近一次保存的完整记录 JSON（含判定依据），供结果页导出。 */
    var lastRecordJson: String? = null
        internal set
    /** 斜视测试期间每帧的瞳孔对比度与虹膜像素直径（双眼），写入记录用于排查光学测量为何不可用。 */
    internal val opticalContrasts = mutableListOf<Float>()
    internal val opticalIrisPx = mutableListOf<Float>()
    internal var cachedGeometry: CameraGeometry? = null
    internal val frameObserver: (FaceLandmarkFrame) -> Unit = { frame ->
        frames.trySend(Triple(frame.analysisElapsedRealtimeMs ?: platform.nowMs(), _state.value.step, frame))
    }
    internal val cameraErrorObserver: (Throwable) -> Unit = { error ->
        _state.update { it.copy(warning = "相机错误：${error.message}", trackingStatus = "相机启动失败") }
    }
    internal val coverSequence = StrabismusProtocol.COVER_SEQUENCE

    private val presbyopia = PresbyopiaStateMachine(this)
    private val strabismus = StrabismusStateMachine(this)
    private val frameProcessor = ScreeningFrameProcessor(this)
    private val resultWriter = ScreeningResultWriter(this)

    init {
        scope.launch { for ((time, step, frame) in frames) processFrame(time, step, frame) }
    }

    fun configure(kind: ScreeningKind) {
        if (_state.value.kind != kind) _state.value = ScreeningState(kind = kind)
    }

    fun setField(key: String, value: String) = _state.update { it.copy(fields = it.fields + (key to value)) }

    fun startCamera() {
        if (cameraAttached) return
        cameraAttached = true
        cameraFrameCount = 0; lastCameraFrameMs = 0L
        distanceTracker.clear(); opticalRejectionCounts.clear(); opticalContrasts.clear(); opticalIrisPx.clear()
        opticalIrisReferenceFrames = 0
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

    /**
     * 选定被测者：带入其资料（含由出生年份换算的年龄），不再逐题填写问卷。
     * 有测前快问的（老视的戴镜问卷、眼位）答完 [confirmCheck] 再继续；其余测试直接进入准备页。
     */
    fun useSubject(profile: SubjectProfile) {
        _state.update { it.copy(fields = SubjectProfiles.testFields(profile, platform.epochMs()),
            subject = TestSubject(profile.id, profile.nickname, profile.relation), profileSkipped = false, warning = null) }
        if (!PreTestCheck.needed(_state.value.kind.name)) nextFromProfile()
    }

    /** 测前快问答完：本次答案并入测试字段（只写入这次记录），进入环境检查。没答完返回 false。 */
    fun confirmCheck(answers: Map<String, String>): Boolean {
        val kind = _state.value.kind.name
        if (_state.value.step != ScreeningStep.PROFILE || _state.value.subject == null || !PreTestCheck.complete(kind, answers)) return false
        _state.update { it.copy(fields = it.fields + PreTestCheck.clean(kind, answers)) }
        nextFromProfile()
        return _state.value.step == ScreeningStep.ENVIRONMENT
    }

    fun nextFromProfile() {
        val age = _state.value.fields["年龄"]?.toIntOrNull()
        val range = SubjectProfiles.ageRange(_state.value.kind.name)
        if (age == null || age !in range) {
            _state.update { it.copy(warning = "请先在「我的」填写出生年份（本测试适合 ${range.first} 岁以上）") }; return
        }
        if (_state.value.kind == ScreeningKind.PRESBYOPIA) {
            if (!PreTestCheck.complete(_state.value.kind.name, _state.value.fields)) {
                _state.update { it.copy(warning = "请先回答测试前的几个戴镜问题") }; return
            }
            val path = PresbyopiaIntake.route(_state.value.fields)
            _state.update { it.copy(presbyopiaPath = path, mode = path.modeLabel) }
        }
        _state.update { it.copy(step = ScreeningStep.ENVIRONMENT, warning = null) }
    }

    /** 不选成员直接测试：老视没有戴镜问卷，按限制性筛查（C）；年龄缺失时评估会如实说明无法与同龄对照。 */
    fun skipProfile() {
        val path = if (_state.value.kind == ScreeningKind.PRESBYOPIA) PresbyopiaPath.C else null
        _state.update { it.copy(step = ScreeningStep.ENVIRONMENT, fields = emptyMap(), subject = null, profileSkipped = true, warning = null,
            presbyopiaPath = path, mode = path?.modeLabel.orEmpty()) }
    }

    /**
     * 老视结果页「换一种戴镜方式再测」（专家：允许同一用户在不同戴镜状态下重复检查，各次独立记录、分别解释）：
     * 回到开始页，保留被测者和前几题答案，只重新选择本次戴镜状态。未选成员时等同重新测试。
     */
    fun retestWithOtherGlasses() = presbyopia.retestWithOtherGlasses()

    fun begin() {
        val state = _state.value
        if (!state.faceDetected || state.distanceCm <= 0f) {
            _state.update { it.copy(warning = "请先让前置摄像头检测到双眼和距离") }; return
        }
        if (state.kind == ScreeningKind.PRESBYOPIA && !distanceStable(40f, nearDistanceToleranceCm)) {
            _state.update { it.copy(warning = "请保持 40±${nearDistanceToleranceCm.toInt()} cm 约 1 秒") }; return
        }
        if (state.kind == ScreeningKind.STRABISMUS && !distanceOk(33f, 5f)) {
            _state.update { it.copy(warning = "斜视任务需保持 33±5 cm") }; return
        }
        if (state.lux != null && state.lux < 50f) {
            _state.update { it.copy(warning = "环境光低于本原型的 50 lux 采样门槛，请改善照明") }; return
        }
        platform.feedback(ScreeningFeedbackCue.START)
        samples.clear(); totalFrames = 0; validFrames = 0
        qualityEligibleFrames = 0; qualityEligibleValidFrames = 0; opticalValidFrames = 0
        if (state.kind == ScreeningKind.PRESBYOPIA) startNearVision() else startFixation()
    }

    internal fun startNearVision() = presbyopia.startNearVision()

    fun readyForNearEye() = presbyopia.readyForNearEye()

    /** 只有距离稳定后才锁定该题的距离、像素尺寸与方向。 */
    fun showTrial() = presbyopia.showTrial()

    /** Compose 完成首帧呈现后调用，避免把移动与渲染时间算进辨认反应。 */
    fun markTrialPresented(trialId: Int, timestampMs: Long) = presbyopia.markTrialPresented(trialId, timestampMs)

    /** [direction] 为 -1 表示「看不清」，记为错误作答。 */
    fun answer(direction: Int, byVoice: Boolean = false) = presbyopia.answer(direction, byVoice)

    internal fun advanceNearEye() = presbyopia.advanceNearEye()

    internal fun trialTargetCm(s: ScreeningState): Float? = presbyopia.trialTargetCm(s)

    /** 某只眼 40 cm 近视力通过的最高等级。 */
    internal fun bestNearLevel(answers: List<VisionAnswer>, eye: VisionEye): Float? = presbyopia.bestNearLevel(answers, eye)

    internal fun bestBinocularLevel(answers: List<VisionAnswer>): Float? = presbyopia.bestBinocularLevel(answers)

    /** 移近视标：该眼 40 cm 近视力阈值，不小于 0.5 档的物理尺寸。 */
    internal fun pushLevel(eye: VisionEye): Float = presbyopia.pushLevel(eye)

    /**
     * 单眼移近法（专家 2026-11-02，仅 A1 / A2）：遮住另一只眼，手机由远向近以 1–2 cm/秒移动，
     * 视标首次持续模糊时确认；视标物理尺寸固定。每只眼 3 次有效测量，先右眼后左眼。
     */
    internal fun startPushUp() = presbyopia.startPushUp()

    internal fun pushInstruction(eye: VisionEye) = presbyopia.pushInstruction(eye)

    fun startPushTrial() = presbyopia.startPushTrial()

    /** 受试者确认「开始持续模糊」。 */
    fun markPushBlur() = presbyopia.markPushBlur()

    /** 最近 1 秒的靠近速度（cm/秒，靠近为正）。 */
    internal fun recentApproachSpeed(now: Long): Float? = presbyopia.recentApproachSpeed(now)

    internal fun finishPushTrial(point: NearPoint) = presbyopia.finishPushTrial(point)

    /** 距离—视觉表现曲线：在 67 / 50 / 33 cm 分别测双眼阈值（40 cm 已由近视力任务覆盖）。 */
    internal fun startDistanceCurve() = presbyopia.startDistanceCurve()

    internal fun answerCurve(s: ScreeningState, answers: List<VisionAnswer>, target: Float) = presbyopia.answerCurve(s, answers, target)

    internal fun finishCurveDistance(limitedNow: Boolean) = presbyopia.finishCurveDistance(limitedNow)

    /** 远近切换（可选）：引导页可选择跳过；开始后先在两个距离确认看得清，再计时 60 秒。 */
    internal fun startFlipper() = presbyopia.startFlipper()

    /** 远近切换引导页选择跳过：直接出结果，记录未完成原因。 */
    fun skipFlipper() = presbyopia.skipFlipper()

    /** 看清确认中答了一题：答对进入下一距离（近端也对就开始计时），同一距离答错 [PresbyopiaProtocol.FLIPPER_CHECK_TRIES] 次记为未达到测试条件。 */
    internal fun answerFlipperCheck(s: ScreeningState, correct: Boolean, now: Long) = presbyopia.answerFlipperCheck(s, correct, now)

    internal fun startFixation() = strabismus.startFixation()

    /** 看远眼位：受试者越过手机看 6 米外的目标；只记录双眼映光，不做屏幕注视监督（目标不在屏幕上）。 */
    internal fun startFarFixation() = strabismus.startFarFixation()

    /** 看远：协助者确认受试者已经在看 6 米外的目标后，才开始倒计时和采集眼位。 */
    fun startFarCountdown() = strabismus.startFarCountdown()

    internal fun startCover() = strabismus.startCover()

    /** 点按不再生成遮盖事件；仅相机的连续多帧证据可以推进动作。 */
    fun markCover() = strabismus.markCover()

    internal fun observeCover(time: Long, face: FaceMetrics?, quality: Boolean, step: ScreeningStep) = strabismus.observeCover(time, face, quality, step)

    internal fun startPursuit() = strabismus.startPursuit()

    /** 老视：受试者确认已按本次戴镜状态戴好或摘下眼镜后，才进入近视力示范。 */
    fun confirmGlasses() = presbyopia.confirmGlasses()

    /** 示范结束后才开始采样和计时，避免将阅读时间计入测试。 */
    fun confirmGuide() {
        val s = _state.value
        if (!s.guidePending || s.glassesCheckPending) return
        stepStartMs = platform.nowMs()
        flipperMoveStartMs = stepStartMs
        coverStageStartMs = stepStartMs
        faceCenterBaseline = null
        _state.update { it.copy(guidePending = false) }
        when (s.step) {
            ScreeningStep.NEAR_VISION -> readyForNearEye()
            // 先确认两个距离都看得清，确认通过后才开始计时（见 answerFlipperCheck）
            ScreeningStep.FLIPPER -> { flipperCheckMisses = 0; _state.update { it.copy(flipperCheck = true, trialIndex = 0) } }
            ScreeningStep.FIXATION -> retryGazeCalibration()
            ScreeningStep.PURSUIT -> runTimedStep(StrabismusProtocol.PURSUIT_SECONDS) { finish() }
            ScreeningStep.FAR_FIXATION -> _state.update { it.copy(farWaiting = true) }
            else -> Unit
        }
    }

    /** 校准不计入正式眼位测量；失败停留在本页，不能带着无效校准进入结果。 */
    fun retryGazeCalibration() = strabismus.retryGazeCalibration()

    /** 记录实际画出的目标，而非未考虑画布缩放、滚动和布局位置的理论轨迹。 */
    fun reportGazeTarget(xPx: Float, yPx: Float) = strabismus.reportGazeTarget(xPx, yPx)

    internal fun resetGazeWindow() = strabismus.resetGazeWindow()

    internal fun runTimedStep(seconds: Int, after: () -> Unit) = strabismus.runTimedStep(seconds, after)

    internal fun finish() = resultWriter.finish()

    /** 追踪画布在当前距离下能显示的最大视角（度），用于报告原文 ±10° 是否被屏幕限制。 */
    fun reportPursuitCapacity(maxXDeg: Float, maxYDeg: Float) = strabismus.reportPursuitCapacity(maxXDeg, maxYDeg)

    fun coverRemainingMs(): Long = strabismus.coverRemainingMs()

    fun interruptTest() {
        val current = _state.value
        if (current.step == ScreeningStep.PROFILE || current.step == ScreeningStep.RESULT) return
        timer?.cancel(); coverHoldJob?.cancel(); coverDetector.clear()
        stopCamera()
        samples.clear()
        gaze = null; gazeCalibration.clear(); renderedTargets.clear(); resetGazeWindow()
        distanceTracker.clear()
        // 环境检查尚未开始计时，切后台只需释放相机，不提示「测试已中断」。
        _state.value = ScreeningState(kind = current.kind, fields = current.fields, subject = current.subject,
            profileSkipped = current.profileSkipped, mode = current.mode, presbyopiaPath = current.presbyopiaPath,
            step = ScreeningStep.ENVIRONMENT,
            warning = if (current.step == ScreeningStep.ENVIRONMENT) null else "测试已中断，请重新开始")
    }

    fun restart() {
        timer?.cancel(); coverHoldJob?.cancel(); stopCamera(); samples.clear(); distanceTracker.clear(); coverDetector.clear()
        gaze = null; gazeCalibration.clear(); renderedTargets.clear(); resetGazeWindow()
        _state.value = ScreeningState(kind = _state.value.kind)
    }

    /**
     * 多距离视力与远近切换换目标距离时，先提示「请移动到 xx 厘米处」；到达提示容差内后清空，
     * 恢复「太近了 / 太远了」监督。其余步骤目标不变，不进入移动提示。
     */
    internal fun trackDistanceMove(distanceCm: Float) {
        val s = _state.value
        val target = if (s.step == ScreeningStep.DISTANCE_CURVE || s.step == ScreeningStep.FLIPPER) screeningDistanceTarget(s) else null
        var moveTo = s.distanceMoveToCm
        if (target == null) moveTo = null
        else if (target.first != lastDistanceTargetCm) moveTo = target.first
        lastDistanceTargetCm = target?.first
        if (moveTo != null && target != null && distanceCm > 0f && abs(distanceCm - target.first) <= target.second) moveTo = null
        if (moveTo != s.distanceMoveToCm) _state.update { it.copy(distanceMoveToCm = moveTo) }
    }

    internal fun distanceOk(target: Float, tolerance: Float) =
        distanceTracker.estimate(platform.nowMs())?.let { abs(it.distanceCm - target) <= tolerance } == true

    internal fun distanceSpread(): Float =
        distanceTracker.estimate(platform.nowMs())?.spreadCm ?: 0f

    internal fun distanceStable(target: Float, tolerance: Float): Boolean =
        distanceTracker.stable(target, tolerance, platform.nowMs())

    private fun processFrame(time: Long, step: ScreeningStep, frame: FaceLandmarkFrame) = frameProcessor.processFrame(time, step, frame)



    fun close() {
        timer?.cancel(); coverHoldJob?.cancel(); stopCamera(); frames.close()
    }
}
