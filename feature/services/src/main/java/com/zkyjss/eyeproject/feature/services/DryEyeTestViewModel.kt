package com.zkyjss.eyeproject.feature.services

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zkyjss.eyeproject.core.camera.CameraController
import com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider
import com.zkyjss.eyeproject.core.domain.BlinkDetector
import com.zkyjss.eyeproject.core.domain.DepthFromIris
import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * 干眼流程阶段：问卷 → 准备（40 cm 门禁）→ 练习说明 → 练习 → 正式说明 → 倒计时 →
 * 自然眨眼（60 秒或延长 180 秒）→ 紧接持续睁眼 20 秒 → 结果；可选「闭眼休息 1 分钟」后复测。
 */
enum class DryEyePhase { Profile, Setup, PracticeGuide, Practice, FormalGuide, Countdown, Natural, Open, Rest, Result }

/** 单个距离采样：相对正式测试开始的秒数 + 距离。 */
data class DryEyeDistanceSample(val tSec: Float, val cm: Float)

data class DryEyeTestUiState(
    val phase: DryEyePhase = DryEyePhase.Profile,
    val fields: Map<String, String> = emptyMap(),
    val instruction: String = "",
    val remainingSeconds: Int = 0,
    val progressFraction: Float = 0f,
    // 门禁与质量
    val faceDetected: Boolean = false,
    val distanceCm: Float = 0f,
    val gateReady: Boolean = false,
    val headTurned: Boolean = false,
    val lux: Float? = null,
    val qualityWarning: String? = null,
    // 视标
    val trialVisible: Boolean = false,
    val trialId: Int = 0,
    val direction: Int = 0,
    val level: Float = DryEyeProtocol.LEVELS[DryEyeProtocol.START_LEVEL_INDEX],
    val optotypePx: Float = 0f,
    val lastAnswerCorrect: Boolean? = null,
    // 数据
    val phase1Blinks: List<Float> = emptyList(),
    val phase2Blinks: List<Float> = emptyList(),
    val phase1BlinkCount: Int = 0,
    val phase2BlinkCount: Int = 0,
    val trialCount: Int = 0,
    val distanceSamples: List<DryEyeDistanceSample> = emptyList(),
    val naturalSeconds: Int = DryEyeProtocol.NATURAL_SECONDS,
    val afterRest: Boolean = false,
    val report: DryEyeReport? = null,
    val previousReport: DryEyeReport? = null,
    val savedFile: String? = null,
    val error: String? = null,
)

/**
 * 干眼功能性连续视力测试（协议见 [DryEyeProtocol]）。
 *
 * 线程模型：分析线程回调只写 @Volatile 字段与眨眼事件队列；视标循环、阶段计时与质量统计
 * 都在 viewModelScope 协程内完成。
 */
@HiltViewModel
class DryEyeTestViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cameraController: CameraController,
    private val intrinsics: CameraIntrinsicsProvider,
) : ViewModel(), SensorEventListener {

    private val _uiState = MutableStateFlow(DryEyeTestUiState())
    val uiState: StateFlow<DryEyeTestUiState> = _uiState.asStateFlow()

    private val blinkDetector = BlinkDetector()
    private val blinkEvents = ConcurrentLinkedQueue<Long>()
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    @Volatile private var lastDistanceCm = 0f
    @Volatile private var eyesVisible = false
    @Volatile private var headTurned = false
    @Volatile private var lastFrameMs = 0L
    @Volatile private var lastGateUpdateMs = 0L
    @Volatile private var monitoringGate = true

    private var testJob: Job? = null
    private var cameraGeneration: Int? = null
    private var cameraAttached = false
    private var watchdog: Job? = null
    private var pendingAnswer: CompletableDeferred<Pair<Int, Boolean>>? = null
    private var nextTrialId = 0
    private var staircase = DryEyeStaircase()
    private var baselineLevel: Float = DryEyeProtocol.LEVELS[DryEyeProtocol.START_LEVEL_INDEX]
    private var lastSession: DryEyeSession? = null

    private val frameObserver: (FaceLandmarkFrame) -> Unit = ::handleFrame
    private val errorObserver: (Throwable) -> Unit = { error ->
        _uiState.update { it.copy(error = error.message ?: "相机不可用", gateReady = false) }
    }

    // region 问卷

    fun setField(key: String, value: String) = _uiState.update { it.copy(fields = it.fields + (key to value)) }

    fun finishProfile() = _uiState.update { it.copy(phase = DryEyePhase.Setup, error = null) }

    // endregion

    // region 相机

    /** 与首页共享帧流，退出时仅释放本页持有的相机。 */
    fun startMonitoring(lifecycleOwner: LifecycleOwner) {
        if (cameraAttached) return
        cameraAttached = true
        lastFrameMs = 0L
        blinkDetector.reset()
        blinkDetector.onBlink = { blinkEvents.add(SystemClock.elapsedRealtime()) }
        cameraController.addFrameObserver(frameObserver)
        cameraController.addErrorObserver(errorObserver)
        if (!cameraController.isRequested) {
            cameraGeneration = cameraController.start(lifecycleOwner, onFrame = {}, onError = {})
        }
        sensors.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        watchdog = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                if (SystemClock.elapsedRealtime() - lastFrameMs > 2500L) {
                    eyesVisible = false
                    _uiState.update { it.copy(faceDetected = false, distanceCm = 0f, gateReady = false) }
                }
            }
        }
    }

    fun stopMonitoring() {
        watchdog?.cancel()
        cameraController.removeFrameObserver(frameObserver)
        cameraController.removeErrorObserver(errorObserver)
        cameraGeneration?.let(cameraController::stopIfGeneration)
        cameraGeneration = null
        cameraAttached = false
        sensors.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LIGHT) _uiState.update { it.copy(lux = event.values.firstOrNull()) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun permissionDenied() { _uiState.update { it.copy(error = "请允许相机权限后重试") } }

    // endregion

    // region 流程

    /** 准备页门禁通过后进入练习说明。 */
    fun startTest() {
        if (!_uiState.value.gateReady || testJob != null) return
        _uiState.update { it.copy(phase = DryEyePhase.PracticeGuide, error = null) }
    }

    fun confirmPracticeGuide() {
        if (_uiState.value.phase != DryEyePhase.PracticeGuide || testJob != null) return
        testJob = viewModelScope.launch { try { runPractice() } finally { testJob = null } }
    }

    /** 正式说明确认后开始：倒计时 → 自然眨眼 → 持续睁眼。 */
    fun confirmFormalGuide() {
        if (_uiState.value.phase != DryEyePhase.FormalGuide || testJob != null) return
        if (!_uiState.value.gateReady) {
            _uiState.update { it.copy(error = "请先回到约 40 cm 并正对屏幕") }
            return
        }
        testJob = viewModelScope.launch { try { runFormal() } finally { testJob = null } }
    }

    /** 结果页：延长自然眨眼至 3 分钟（不重复练习，沿用本次基线）。 */
    fun startExtended() = restartFormal(DryEyeProtocol.EXTENDED_NATURAL_SECONDS, afterRest = false)

    /** 结果页：闭眼休息 1 分钟后，以同样流程复测并与本次结果对比。 */
    fun startRestRetest() {
        if (testJob != null) return
        _uiState.update { it.copy(previousReport = it.report, report = null, afterRest = true, error = null) }
        testJob = viewModelScope.launch {
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
        pendingAnswer?.takeIf { it.isActive }?.complete(direction to byVoice)
    }

    /** 停止并回到准备页；已完成的问卷保留。 */
    fun backToSetup(message: String? = null) {
        testJob?.cancel()
        testJob = null
        pendingAnswer?.cancel()
        monitoringGate = true
        _uiState.update {
            DryEyeTestUiState(phase = DryEyePhase.Setup, fields = it.fields, lux = it.lux, error = message)
        }
    }

    fun restartAll() {
        backToSetup()
        lastSession = null
        staircase = DryEyeStaircase()
    }

    override fun onCleared() {
        testJob?.cancel()
        stopMonitoring()
    }

    private suspend fun runPractice() {
        staircase = DryEyeStaircase()
        _uiState.update { it.copy(phase = DryEyePhase.Practice, instruction = "练习：2 秒内点选 E 的开口方向", error = null) }
        val start = SystemClock.elapsedRealtime()
        val trials = mutableListOf<DryEyeTrial>()
        coroutineScope {
            val loop = launch { trialLoop(DryEyeStage.PRACTICE, start, trials) }
            while (isActive) {
                val elapsed = SystemClock.elapsedRealtime() - start
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
        _uiState.update { it.copy(phase = DryEyePhase.Rest, instruction = "请闭上眼睛休息，听到提示后再睁开") }
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - start
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
        val start = SystemClock.elapsedRealtime()
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
            it.copy(phase = DryEyePhase.Natural, instruction = "看 E 选方向，同时像平时一样自然眨眼",
                phase1Blinks = emptyList(), phase2Blinks = emptyList(), phase1BlinkCount = 0, phase2BlinkCount = 0,
                trialCount = 0, distanceSamples = emptyList(), error = null)
        }
        var completed = false
        coroutineScope {
            val loop = launch { trialLoop(DryEyeStage.NATURAL, start, trials, naturalSeconds) }
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
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
            val spec = ScreeningOptotype.calculate(context.resources.displayMetrics, level, distance)
            val direction = Random.nextInt(4)
            val deferred = CompletableDeferred<Pair<Int, Boolean>>()
            pendingAnswer = deferred
            val id = ++nextTrialId
            val onset = SystemClock.elapsedRealtime()
            _uiState.update { it.copy(trialVisible = true, trialId = id, direction = direction, level = level, optotypePx = spec.sizePx) }
            val answer = withTimeoutOrNull(DryEyeProtocol.TRIAL_TIMEOUT_MS) { deferred.await() }
            val rt = if (answer != null) SystemClock.elapsedRealtime() - onset else null
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

    @Volatile private var resolutionLimited = false

    /** 本机当前距离下可可靠显示的最小视标档位下标。 */
    private fun maxDisplayableIndex(distanceCm: Float): Int {
        val metrics = context.resources.displayMetrics
        val idx = DryEyeProtocol.LEVELS.indexOfLast { ScreeningOptotype.calculate(metrics, it, distanceCm).displayable }
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
        val now = SystemClock.elapsedRealtime()
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

    /** 分析线程回调：只写 @Volatile 字段；准备阶段按「翻转立即、否则 500ms 节流」刷新门禁状态。 */
    private fun handleFrame(frame: FaceLandmarkFrame) {
        lastFrameMs = SystemClock.elapsedRealtime()
        val face = frame.faces.firstOrNull()
        val eyesDetected = face != null && face.leftEye != null && face.rightEye != null
        var distanceCm = 0f
        if (eyesDetected) {
            blinkDetector.update(BlinkDetector.averageEar(face.leftEye, face.rightEye))
            val diameters = listOfNotNull(face.leftEye?.irisDiameterPx, face.rightEye?.irisDiameterPx)
            if (diameters.isNotEmpty()) {
                distanceCm = DepthFromIris.distanceCm(
                    irisDiameterPx = diameters.average().toFloat(),
                    focalLengthPx = intrinsics.focalLengthPx(frame.sourceWidth, frame.sourceHeight)
                )
            }
        } else {
            blinkDetector.interrupt()
        }
        val pose = face?.headPose
        headTurned = pose != null && (abs(pose.yawDeg) > HEAD_LIMIT_DEG || abs(pose.pitchDeg) > HEAD_LIMIT_DEG)
        eyesVisible = eyesDetected
        lastDistanceCm = distanceCm

        val gate = eyesDetected && abs(distanceCm - DryEyeProtocol.TARGET_DISTANCE_CM) <= DryEyeProtocol.DISTANCE_TOLERANCE_CM
        val now = SystemClock.elapsedRealtime()
        val s = _uiState.value
        val flipped = gate != s.gateReady || eyesDetected != s.faceDetected
        if (flipped || now - lastGateUpdateMs >= 500) {
            lastGateUpdateMs = now
            _uiState.update { it.copy(faceDetected = eyesDetected, distanceCm = distanceCm, gateReady = gate, headTurned = headTurned) }
        }
    }

    private fun save(session: DryEyeSession, report: DryEyeReport) {
        val fields = _uiState.value.fields
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val data = JSONObject().apply {
                    put("protocolVersion", 1)
                    put("kind", "DRY_EYE")
                    put("profile", JSONObject(fields))
                    put("naturalSeconds", session.naturalSeconds)
                    put("afterRest", session.afterRest)
                    put("completed", session.completed)
                    put("baselineDecimal", session.baselineLevel.toDouble())
                    put("ambientLux", _uiState.value.lux?.toDouble() ?: JSONObject.NULL)
                    put("screenBrightness", "测试期间窗口亮度设为最高")
                    put("riskLevel", report.riskLevel.name)
                    put("valid", report.valid)
                    put("validityReasons", JSONArray(report.validityReasons))
                    put("abnormal", JSONArray(report.abnormal))
                    put("features", JSONObject().apply {
                        report.features.forEach { (k, v) -> put(k, v?.takeIf(Double::isFinite) ?: JSONObject.NULL) }
                    })
                    put("blinksSec", JSONArray(session.blinks.map { it.toDouble() }))
                    put("trials", JSONArray().apply {
                        session.trials.forEach { t -> put(JSONObject().apply {
                            put("stage", t.stage.name); put("onsetSec", t.onsetSec.toDouble())
                            put("decimal", t.level.toDouble()); put("direction", t.direction)
                            put("answer", t.answer ?: JSONObject.NULL); put("correct", t.correct)
                            put("rtMs", t.rtMs ?: JSONObject.NULL); put("distanceCm", t.distanceCm.toDouble())
                            put("optotypePx", t.optotypePx.toDouble()); put("byVoice", t.byVoice)
                        }) }
                    })
                }
                val name = "dryeye_${System.currentTimeMillis()}.json"
                context.openFileOutput(name, Context.MODE_PRIVATE).bufferedWriter().use { it.write(data.toString()) }
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
    }
}
