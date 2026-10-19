package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.domain.*
import com.zkyjss.eyeproject.core.model.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** 干眼共用流程。相机帧串行进入流程协程，眨眼仍按到达的逐帧序列判断。 */
class DryEyeEngine(private val platform: ScreeningPlatform, private val scope: CoroutineScope) {
    private val _uiState = MutableStateFlow(DryEyeTestUiState())
    val uiState: StateFlow<DryEyeTestUiState> = _uiState.asStateFlow()

    /** 按时间判定眨眼（与帧率无关）+ 个人自适应阈值，见 [BlinkDetector]。 */
    private val blinkDetector = BlinkDetector(minClosedMs = BLINK_MIN_CLOSED_MS, adaptive = true)
    private val distanceTracker = ScreeningDistanceTracker()
    private val blinkEvents = TimeEventQueue()

    private var lastDistanceCm = 0f
    private var eyesVisible = false
    private var headTurned = false
    private var lastFrameMs = 0L
    private var lastGateUpdateMs = 0L
    private var monitoringGate = true

    private var testJob: Job? = null
    private var cameraAttached = false
    private var watchdog: Job? = null
    private var pendingAnswer: CompletableDeferred<Pair<Int, Boolean>>? = null
    private val voiceGuard = VoiceRepeatGuard()
    private var nextTrialId = 0
    private var staircase = DryEyeStaircase()
    private var baselineLevel: Float = DryEyeProtocol.LEVELS[DryEyeProtocol.START_LEVEL_INDEX]
    private var lastSession: DryEyeSession? = null

    /** 正式测试开始时刻（0 表示不在正式测试中）；期间记录每帧时间与 EAR，用于帧率门控和导出。 */
    private var formalStartMs = 0L
    private val formalFrameTimes = mutableListOf<Long>()
    private val earTrace = mutableListOf<FloatArray>()

    /** 最近一次保存的完整记录 JSON（含判定依据），供结果页导出。 */
    var lastRecordJson: String? = null
        private set

    private val frameObserver: (FaceLandmarkFrame) -> Unit = { frame -> scope.launch { handleFrame(frame) } }
    private val errorObserver: (Throwable) -> Unit = { error ->
        _uiState.update { it.copy(error = error.message ?: "相机不可用", gateReady = false) }
    }

    // region 被测者

    /** 选定被测者：带入其资料（含由出生年份换算的年龄），不再逐题填写问卷。 */
    fun useSubject(profile: SubjectProfile) = _uiState.update {
        it.copy(phase = DryEyePhase.Setup, fields = SubjectProfiles.testFields(profile, platform.epochMs()),
            subject = TestSubject(profile.id, profile.nickname, profile.relation), profileSkipped = false, error = null)
    }

    /** 不选成员直接测试；症状相关的风险判断按「未报告症状」处理，记录中注明跳过。 */
    fun skipProfile() = _uiState.update {
        it.copy(phase = DryEyePhase.Setup, fields = emptyMap(), subject = null, profileSkipped = true, error = null)
    }

    // endregion

    // region 相机

    /** 与首页共享帧流，退出时仅释放本页持有的相机。 */
    fun startMonitoring() {
        if (cameraAttached) return
        cameraAttached = true; lastFrameMs = 0L
        blinkDetector.reset()
        blinkDetector.onBlink = { blinkEvents.add(platform.nowMs()) }
        platform.startLight { lux -> _uiState.update { it.copy(lux = lux) } }
        platform.startCamera(frameObserver, errorObserver)
        watchdog = scope.launch {
            while (isActive) {
                delay(1_000)
                if (platform.nowMs() - lastFrameMs > 2_500L) {
                    eyesVisible = false
                    _uiState.update { it.copy(faceDetected = false, distanceCm = 0f, gateReady = false) }
                }
            }
        }
    }
    fun stopMonitoring() { watchdog?.cancel(); platform.stopCamera(); cameraAttached = false }
    fun permissionDenied() { _uiState.update { it.copy(error = "请允许相机权限后重试") } }

    // endregion

    // region 流程

    /** 准备页门禁通过后进入练习说明。 */
    fun startTest() {
        if (!_uiState.value.gateReady || testJob != null) return
        platform.feedback(ScreeningFeedbackCue.START)
        _uiState.update { it.copy(phase = DryEyePhase.PracticeGuide, error = null) }
    }

    fun confirmPracticeGuide() {
        if (_uiState.value.phase != DryEyePhase.PracticeGuide || testJob != null) return
        testJob = scope.launch { try { runPractice() } finally { testJob = null } }
    }

    /** 正式说明确认后开始：倒计时 → 自然眨眼 → 持续睁眼。 */
    fun confirmFormalGuide() {
        if (_uiState.value.phase != DryEyePhase.FormalGuide || testJob != null) return
        if (!_uiState.value.gateReady) {
            _uiState.update { it.copy(error = "请先回到约 40 cm 并正对屏幕") }
            return
        }
        testJob = scope.launch { try { runFormal() } finally { testJob = null } }
    }

    /** 结果页：延长自然眨眼至 3 分钟（不重复练习，沿用本次基线）。 */
    fun startExtended() {
        if (_uiState.value.canExtend) restartFormal(DryEyeProtocol.EXTENDED_NATURAL_SECONDS, afterRest = false)
    }

    /** 结果页：闭眼休息 1 分钟后，以同样流程复测并与本次结果对比。 */
    fun startRestRetest() {
        if (testJob != null || !_uiState.value.canRestRetest) return
        _uiState.update { it.copy(previousReport = it.report, report = null, afterRest = true, error = null) }
        testJob = scope.launch {
            try {
                runRest()
                _uiState.update { it.copy(phase = DryEyePhase.FormalGuide, naturalSeconds = DryEyeProtocol.NATURAL_SECONDS) }
            } finally { testJob = null }
        }
    }

    private fun restartFormal(naturalSeconds: Int, afterRest: Boolean) {
        if (testJob != null) return
        _uiState.update {
            it.copy(phase = DryEyePhase.FormalGuide, naturalSeconds = naturalSeconds, afterRest = afterRest,
                previousReport = it.report, report = null, error = null)
        }
    }

    /** 回答当前视标；只接受每题第一次作答。[direction] 为 -1 表示「看不清」（记为错误）。 */
    fun answer(direction: Int, byVoice: Boolean = false) {
        if (byVoice && !voiceGuard.accept(platform.nowMs())) return
        if (pendingAnswer?.takeIf { it.isActive }?.complete(direction to byVoice) == true)
            platform.feedback(ScreeningFeedbackCue.ANSWER)
    }

    /** 停止并回到准备页；已完成的问卷保留。 */
    fun backToSetup(message: String? = null) {
        testJob?.cancel()
        testJob = null
        pendingAnswer?.cancel()
        formalStartMs = 0L
        monitoringGate = true
        _uiState.update {
            DryEyeTestUiState(phase = DryEyePhase.Setup, fields = it.fields, subject = it.subject,
                profileSkipped = it.profileSkipped, lux = it.lux, error = message)
        }
    }

    fun restartAll() {
        backToSetup()
        lastSession = null
        staircase = DryEyeStaircase()
    }

    fun close() {
        testJob?.cancel()
        stopMonitoring()
    }

    private suspend fun runPractice() {
        staircase = DryEyeStaircase()
        gateLostSinceMs = 0L
        _uiState.update { it.copy(phase = DryEyePhase.Practice, instruction = "练习：2 秒内点选 E 的开口方向", error = null,
            progressFraction = 0f, remainingSeconds = DryEyeProtocol.PRACTICE_SECONDS) }
        val start = platform.nowMs()
        val trials = mutableListOf<DryEyeTrial>()
        coroutineScope {
            val loop = launch { trialLoop(DryEyeStage.PRACTICE, start, trials) }
            while (isActive) {
                val elapsed = platform.nowMs() - start
                if (elapsed >= DryEyeProtocol.PRACTICE_SECONDS * 1000L) break
                if (gateLostTooLong()) {
                    loop.cancel()
                    backToSetup("距离或跟踪中断，请调整后重新开始")
                    return@coroutineScope
                }
                updateTimer(elapsed, DryEyeProtocol.PRACTICE_SECONDS)
                delay(100)
            }
            loop.cancel()
        }
        if (_uiState.value.phase != DryEyePhase.Practice) return
        baselineLevel = staircase.level
        _uiState.update {
            it.copy(phase = DryEyePhase.FormalGuide, trialVisible = false, lastAnswerCorrect = null,
                naturalSeconds = DryEyeProtocol.NATURAL_SECONDS, afterRest = false)
        }
    }

    private suspend fun runRest() {
        _uiState.update { it.copy(phase = DryEyePhase.Rest, instruction = "请闭上眼睛休息，听到提示后再睁开",
            progressFraction = 0f, remainingSeconds = DryEyeProtocol.REST_SECONDS) }
        val start = platform.nowMs()
        while (true) {
            val elapsed = platform.nowMs() - start
            if (elapsed >= DryEyeProtocol.REST_SECONDS * 1000L) break
            updateTimer(elapsed, DryEyeProtocol.REST_SECONDS)
            delay(200)
        }
    }

    private suspend fun runFormal() {
        val naturalSeconds = _uiState.value.naturalSeconds
        val afterRest = _uiState.value.afterRest
        // 倒计时
        for (s in 3 downTo 1) {
            _uiState.update { it.copy(phase = DryEyePhase.Countdown, instruction = "建议佩戴最佳矫正眼镜，保持约 40 cm", remainingSeconds = s) }
            delay(1000)
        }
        staircase = DryEyeStaircase(DryEyeProtocol.LEVELS.indexOf(baselineLevel).coerceAtLeast(0))
        // 上一段遗留的丢失计时与像素封顶标记不能带入本次正式测试。
        gateLostSinceMs = 0L
        resolutionLimited = false
        val start = platform.nowMs()
        formalStartMs = start; formalFrameTimes.clear(); earTrace.clear()
        val totalMs = (naturalSeconds + DryEyeProtocol.OPEN_SECONDS) * 1000L
        val trials = mutableListOf<DryEyeTrial>()
        val blinks = mutableListOf<Float>()
        val distances = mutableListOf<DryEyeDistanceSample>()
        var frames = 0
        var validFrames = 0
        var inRange = 0
        var turned = 0
        var lastDistanceSampleMs = 0L
        blinkEvents.clear()
        _uiState.update {
            // 进入新阶段先清零阶段进度，避免沿用上一阶段的满值。
            it.copy(phase = DryEyePhase.Natural, instruction = "看 E 选方向，同时像平时一样自然眨眼",
                progressFraction = 0f, remainingSeconds = naturalSeconds,
                phase1Blinks = emptyList(), phase2Blinks = emptyList(), phase1BlinkCount = 0, phase2BlinkCount = 0,
                trialCount = 0, distanceSamples = emptyList(), error = null)
        }
        var completed = false
        coroutineScope {
            val loop = launch { trialLoop(DryEyeStage.NATURAL, start, trials, naturalSeconds) }
            while (isActive) {
                val now = platform.nowMs()
                val elapsed = now - start
                if (elapsed >= totalMs) { completed = true; break }
                if (gateLostTooLong()) {
                    loop.cancel()
                    finishFormal(naturalSeconds, trials, blinks, distances, frames, validFrames, inRange, turned, false, afterRest)
                    return@coroutineScope
                }
                while (true) {
                    val t = blinkEvents.poll() ?: break
                    if (t >= start) blinks += (t - start) / 1000f
                }
                frames++
                if (eyesVisible) validFrames++
                if (abs(lastDistanceCm - DryEyeProtocol.TARGET_DISTANCE_CM) <= DryEyeProtocol.DISTANCE_TOLERANCE_CM) inRange++
                if (headTurned) turned++
                if (now - lastDistanceSampleMs >= 500) {
                    lastDistanceSampleMs = now
                    distances += DryEyeDistanceSample(elapsed / 1000f, lastDistanceCm)
                }
                val inNatural = elapsed < naturalSeconds * 1000L
                val phaseElapsed = if (inNatural) elapsed else elapsed - naturalSeconds * 1000L
                val phaseSeconds = if (inNatural) naturalSeconds else DryEyeProtocol.OPEN_SECONDS
                val naturalBlinks = blinks.filter { it < naturalSeconds }
                val openBlinks = blinks.filter { it >= naturalSeconds }.map { it - naturalSeconds }
                _uiState.update {
                    it.copy(
                        // 原文要求两阶段之间不休息：只切换提示，不停顿、不需确认
                        phase = if (inNatural) DryEyePhase.Natural else DryEyePhase.Open,
                        instruction = if (inNatural) "看 E 选方向，同时像平时一样自然眨眼" else "现在尽量保持睁眼，继续看 E 选方向",
                        remainingSeconds = (((phaseSeconds * 1000L - phaseElapsed) + 999) / 1000L).toInt(),
                        progressFraction = (phaseElapsed.toFloat() / (phaseSeconds * 1000L)).coerceIn(0f, 1f),
                        phase1Blinks = naturalBlinks, phase1BlinkCount = naturalBlinks.size,
                        phase2Blinks = openBlinks, phase2BlinkCount = openBlinks.size,
                        trialCount = trials.size,
                        qualityWarning = qualityWarning(),
                    )
                }
                delay(100)
            }
            loop.cancel()
            if (completed) finishFormal(naturalSeconds, trials, blinks, distances, frames, validFrames, inRange, turned, true, afterRest)
        }
    }

    private fun finishFormal(
        naturalSeconds: Int, trials: List<DryEyeTrial>, blinks: List<Float>, distances: List<DryEyeDistanceSample>,
        frames: Int, validFrames: Int, inRange: Int, turned: Int, completed: Boolean, afterRest: Boolean,
    ) {
        pendingAnswer?.cancel()
        formalStartMs = 0L
        val session = DryEyeSession(
            naturalSeconds = naturalSeconds,
            baselineLevel = baselineLevel,
            trials = trials.filter { it.stage != DryEyeStage.PRACTICE },
            blinks = blinks.toList(),
            validFrameRatio = if (frames > 0) validFrames.toDouble() / frames else 0.0,
            distanceInRangeRatio = if (frames > 0) inRange.toDouble() / frames else 0.0,
            headTurnRatio = if (frames > 0) turned.toDouble() / frames else 0.0,
            resolutionLimited = resolutionLimited,
            completed = completed,
            afterRest = afterRest,
            analysisFps = medianFps(formalFrameTimes),
        )
        lastSession = session
        val report = DryEyeAssessment.evaluate(session, _uiState.value.fields)
        _uiState.update {
            it.copy(phase = DryEyePhase.Result, report = report, trialVisible = false,
                distanceSamples = distances.toList(), error = if (!completed) "距离或跟踪中断，测试未完成" else null)
        }
        save(session, report)
    }

    /** 视标循环：每题最多显示 2 秒，作答或超时后间隔 300 ms 出下一题。 */
    private suspend fun trialLoop(stage: DryEyeStage, start: Long, out: MutableList<DryEyeTrial>, naturalSeconds: Int = 0) {
        while (true) {
            val distance = lastDistanceCm.takeIf { it > 0f } ?: DryEyeProtocol.TARGET_DISTANCE_CM
            val maxIndex = maxDisplayableIndex(distance)
            staircase.clampTo(maxIndex)
            val level = staircase.level
            val spec = OptotypeGeometry.calculate(platform.display, level, distance)
            val direction = Random.nextInt(4)
            val deferred = CompletableDeferred<Pair<Int, Boolean>>()
            pendingAnswer = deferred
            val id = ++nextTrialId
            val onset = platform.nowMs()
            _uiState.update { it.copy(trialVisible = true, trialId = id, direction = direction, level = level, optotypePx = spec.sizePx) }
            val answer = withTimeoutOrNull(DryEyeProtocol.TRIAL_TIMEOUT_MS) { deferred.await() }
            val rt = if (answer != null) platform.nowMs() - onset else null
            val onsetSec = (onset - start) / 1000f
            val trialStage = when {
                stage == DryEyeStage.PRACTICE -> DryEyeStage.PRACTICE
                onsetSec < naturalSeconds -> DryEyeStage.NATURAL
                else -> DryEyeStage.OPEN
            }
            val trial = DryEyeTrial(trialStage, onsetSec, level, direction, answer?.first, rt, distance, spec.sizePx, answer?.second == true)
            out += trial
            staircase.record(trial.correct, maxIndex)
            _uiState.update { it.copy(trialVisible = false, lastAnswerCorrect = trial.correct) }
            delay(DryEyeProtocol.INTER_TRIAL_MS)
        }
    }

    private var resolutionLimited = false

    /** 本机当前距离下可可靠显示的最小视标档位下标。 */
    private fun maxDisplayableIndex(distanceCm: Float): Int {
        val metrics = platform.display
        val idx = DryEyeProtocol.LEVELS.indexOfLast { OptotypeGeometry.calculate(metrics, it, distanceCm).displayable }
        if (idx < DryEyeProtocol.LEVELS.lastIndex) resolutionLimited = true
        return idx.coerceAtLeast(0)
    }

    private fun updateTimer(elapsed: Long, seconds: Int) {
        _uiState.update {
            it.copy(
                remainingSeconds = (((seconds * 1000L - elapsed) + 999) / 1000L).toInt(),
                progressFraction = (elapsed.toFloat() / (seconds * 1000L)).coerceIn(0f, 1f),
                qualityWarning = qualityWarning(),
            )
        }
    }

    /** 距离与人脸可见性由界面的红/蓝距离横幅负责，这里只给其他质量提示。 */
    private fun qualityWarning(): String? = when {
        headTurned -> "请把头摆正"
        (_uiState.value.lux ?: 100f) < 50f -> "环境偏暗，请增加光线"
        else -> null
    }

    private var gateLostSinceMs = 0L

    /**
     * 测试中允许短暂偏离：双眼不可见 1.5 秒、或距离超出 30–50 cm 达 3 秒才中断，
     * 避免眨眼瞬间关键点丢失或距离抖动打断连续测试。
     */
    private fun gateLostTooLong(): Boolean {
        val now = platform.nowMs()
        val farOff = abs(lastDistanceCm - DryEyeProtocol.TARGET_DISTANCE_CM) > DryEyeProtocol.DISTANCE_TOLERANCE_CM * 2
        if (eyesVisible && !farOff) {
            gateLostSinceMs = 0L
            return false
        }
        if (gateLostSinceMs == 0L) gateLostSinceMs = now
        val limit = if (!eyesVisible) EYES_GRACE_MS else DISTANCE_GRACE_MS
        return now - gateLostSinceMs > limit
    }

    // endregion

    /** 分析线程回调：只写 字段；准备阶段按「翻转立即、否则 500ms 节流」刷新门禁状态。 */
    private fun handleFrame(frame: FaceLandmarkFrame) {
        lastFrameMs = platform.nowMs()
        val face = frame.faces.firstOrNull()
        val eyesDetected = face != null && face.leftEye != null && face.rightEye != null
        var distanceCm = 0f
        val frameMs = frame.analysisElapsedRealtimeMs ?: lastFrameMs
        if (formalStartMs > 0L && frameMs >= formalStartMs && formalFrameTimes.size < MAX_TRACE_FRAMES) formalFrameTimes += frameMs
        if (eyesDetected) {
            blinkDetector.update(BlinkDetector.averageEar(face.leftEye, face.rightEye), frameMs)
            val diameters = listOfNotNull(face.leftEye?.irisDiameterPx, face.rightEye?.irisDiameterPx)
            if (diameters.isNotEmpty()) {
                distanceCm = DepthFromIris.distanceCm(
                    irisDiameterPx = diameters.average().toFloat(),
                    focalLengthPx = platform.cameraGeometry(frame.sourceWidth, frame.sourceHeight).focalLengthPx
                )
            }
        } else {
            blinkDetector.interrupt()
        }
        val pose = face?.headPose
        headTurned = pose != null && (abs(pose.yawDeg) > HEAD_LIMIT_DEG || abs(pose.pitchDeg) > HEAD_LIMIT_DEG)
        eyesVisible = eyesDetected
        // 与老视/斜视相同的 1 秒稳健平滑，抑制虹膜关键点逐帧跳动造成的距离抖动
        val now0 = platform.nowMs()
        if (distanceCm > 0f) distanceTracker.add(now0, distanceCm)
        distanceCm = if (eyesDetected) distanceTracker.estimate(now0)?.distanceCm ?: distanceCm else 0f
        lastDistanceCm = distanceCm
        if (formalStartMs > 0L && frameMs >= formalStartMs && earTrace.size < MAX_TRACE_FRAMES) {
            earTrace += floatArrayOf((frameMs - formalStartMs) / 1000f,
                face?.leftEye?.let { BlinkDetector.ear(it.earPoints) } ?: Float.NaN,
                face?.rightEye?.let { BlinkDetector.ear(it.earPoints) } ?: Float.NaN, distanceCm)
        }

        val gate = eyesDetected && abs(distanceCm - DryEyeProtocol.TARGET_DISTANCE_CM) <= DryEyeProtocol.DISTANCE_TOLERANCE_CM
        val now = platform.nowMs()
        val s = _uiState.value
        val flipped = gate != s.gateReady || eyesDetected != s.faceDetected
        if (flipped || now - lastGateUpdateMs >= 500) {
            lastGateUpdateMs = now
            _uiState.update { it.copy(faceDetected = eyesDetected, distanceCm = distanceCm, gateReady = gate, headTurned = headTurned) }
        }
    }

    private fun save(session: DryEyeSession, report: DryEyeReport) {
        val fields = _uiState.value.fields
        val trace = earTrace.toList()
        scope.launch(Dispatchers.IO) {
            runCatching {
                val data = RecordObject().apply {
                    put("protocolVersion", 1)
                    put("kind", "DRY_EYE")
                    put("assessmentVersion", DryEyeAssessment.VERSION)
                    put("platform", platform.platformName)
                    put("displaySizeSource", platform.displaySizeSource)
                    put("distanceSource", platform.distanceSource)
                    put("displayXdpi", platform.display.xdpi)
                    put("displayWidthPx", platform.display.widthPixels)
                    put("displayHeightPx", platform.display.heightPixels)
                    put("profile", RecordObject(fields))
                    _uiState.value.subject?.let { put("subject", it.toRecordObject()) }
                    put("profileSkipped", _uiState.value.profileSkipped)
                    put("naturalSeconds", session.naturalSeconds)
                    put("afterRest", session.afterRest)
                    put("completed", session.completed)
                    put("baselineDecimal", session.baselineLevel.toDouble())
                    put("ambientLux", _uiState.value.lux?.toDouble() ?: RecordObject.NULL)
                    put("screenBrightness", "测试期间窗口亮度设为最高")
                    put("riskLevel", report.riskLevel.name)
                    put("valid", report.valid)
                    put("validityReasons", RecordArray(report.validityReasons))
                    put("abnormal", RecordArray(report.abnormal))
                    put("evidence", report.evidence.toRecordArray())
                    put("analysisFps", report.analysisFps ?: RecordObject.NULL)
                    // 逐帧 [秒, 左眼 EAR, 右眼 EAR, 距离 cm]：可离线复算眨眼与数据质量
                    put("earTrace", RecordArray(trace.map { f -> RecordArray(f.map { v -> v.toDouble() }) }))
                    put("features", RecordObject().apply {
                        report.features.forEach { (k, v) -> put(k, v?.takeIf(Double::isFinite) ?: RecordObject.NULL) }
                    })
                    put("blinksSec", RecordArray(session.blinks.map { it.toDouble() }))
                    put("trials", RecordArray().apply {
                        session.trials.forEach { t -> put(RecordObject().apply {
                            put("stage", t.stage.name); put("onsetSec", t.onsetSec.toDouble())
                            put("decimal", t.level.toDouble()); put("direction", t.direction)
                            put("answer", t.answer ?: RecordObject.NULL); put("correct", t.correct)
                            put("rtMs", t.rtMs ?: RecordObject.NULL); put("distanceCm", t.distanceCm.toDouble())
                            put("optotypePx", t.optotypePx.toDouble()); put("byVoice", t.byVoice)
                        }) }
                    })
                }
                val name = "dryeye_${platform.epochMs()}.json"
                val json = data.toString()
                lastRecordJson = json
                platform.saveRecord(name, json)
                name
            }.onSuccess { name -> _uiState.update { it.copy(savedFile = name) } }
        }
    }

    companion object {
        /** 门禁距离（cm）：40±5 cm 才能开始。 */
        const val TARGET_DISTANCE_CM = DryEyeProtocol.TARGET_DISTANCE_CM
        private const val EYES_GRACE_MS = 1_500L
        private const val DISTANCE_GRACE_MS = 3_000L
        private const val HEAD_LIMIT_DEG = 15f
        /** 计时眨眼的最短闭眼时长：30 fps 需 2 帧、10 fps 1 帧即可，单帧关键点抖动不计。 */
        private const val BLINK_MIN_CLOSED_MS = 60L
        /** 逐帧轨迹上限（约 4 分钟 × 60 fps），防止异常情况下内存无限增长。 */
        private const val MAX_TRACE_FRAMES = 15_000

        /** 逐秒帧数的中位数：比「总帧数 / 时长」更不受个别卡顿影响。 */
        internal fun medianFps(times: List<Long>): Double? {
            if (times.size < 10) return null
            val first = times.first()
            val buckets = times.groupingBy { (it - first) / 1000L }.eachCount()
            val last = buckets.keys.max()
            val counts = (0 until last).map { (buckets[it] ?: 0).toDouble() } // 最后一秒不完整，不计
            return ScreeningAssessmentModel.median(counts.ifEmpty { listOf(times.size.toDouble()) })
        }
    }
}
