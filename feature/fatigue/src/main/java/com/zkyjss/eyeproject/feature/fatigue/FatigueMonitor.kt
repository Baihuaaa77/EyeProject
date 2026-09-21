package com.zkyjss.eyeproject.feature.fatigue

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.LifecycleOwner
import com.zkyjss.eyeproject.core.data.AmbientLightMonitor
import com.zkyjss.eyeproject.core.data.MonitoringRepository
import com.zkyjss.eyeproject.core.data.ScreenBrightnessReader
import com.zkyjss.eyeproject.core.domain.BlinkDetector
import com.zkyjss.eyeproject.core.domain.DepthFromIris
import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
import com.zkyjss.eyeproject.core.model.MonitoringSample
import com.zkyjss.eyeproject.core.model.RealTimeMetrics
import com.zkyjss.eyeproject.core.model.RiskLevel
import com.zkyjss.eyeproject.core.camera.CameraController
import com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 实时眼疲劳监测引擎（单例，被前台服务持有以保证后台运行）：
 * 相机 → MediaPipe 检测（虹膜/EAR）→ 实时指标（每秒刷新）+ 每 30 秒采样保存。
 */
@Singleton
class FatigueMonitor @Inject constructor(
    private val cameraController: CameraController,
    private val intrinsics: CameraIntrinsicsProvider,
    private val ambientLightMonitor: AmbientLightMonitor,
    private val repository: MonitoringRepository,
    private val riskScorer: EyeHealthRiskScorer,
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val blinkDetector = BlinkDetector()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _latestFrame = MutableStateFlow<FaceLandmarkFrame?>(null)
    val latestFrame: StateFlow<FaceLandmarkFrame?> = _latestFrame.asStateFlow()

    private val _realTime = MutableStateFlow(RealTimeMetrics())
    val realTime: StateFlow<RealTimeMetrics> = _realTime.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val samples: StateFlow<List<MonitoringSample>> = repository.samples

    @Volatile private var latestIrisDiameterPx = 0f
    @Volatile private var latestSourceWidth = 0
    @Volatile private var latestAmbientLux = 0f
    @Volatile private var faceDetected = false
    private var startRealtimeMillis = 0L

    // 风险结果缓存：采样序列每 30 秒才变化，避免每秒重复走 JNI 打分
    @Volatile private var cachedRiskSamples: List<MonitoringSample>? = null
    @Volatile private var cachedRisk: FatigueRisk? = null

    private var frameJob: Job? = null
    private var lightJob: Job? = null
    private var displayJob: Job? = null
    private var sampleJob: Job? = null

    fun start(lifecycleOwner: LifecycleOwner) {
        if (_isRunning.value) return
        _isRunning.value = true
        _error.value = null
        startRealtimeMillis = SystemClock.elapsedRealtime()
        latestIrisDiameterPx = 0f
        latestSourceWidth = 0
        latestAmbientLux = 0f
        faceDetected = false
        blinkDetector.reset()
        cachedRiskSamples = null
        cachedRisk = null
        _realTime.value = RealTimeMetrics()

        cameraController.start(lifecycleOwner) { throwable ->
            _isRunning.value = false
            _error.value = "摄像头启动失败：${throwable.message}"
        }

        frameJob = scope.launch {
            cameraController.frameFlow.collect { frame -> onFrame(frame) }
        }
        lightJob = scope.launch {
            ambientLightMonitor.luxFlow().collect { lux -> latestAmbientLux = lux }
        }
        displayJob = scope.launch {
            while (isActive) {
                delay(DISPLAY_INTERVAL_MS)
                _realTime.value = buildRealTimeMetrics()
            }
        }
        sampleJob = scope.launch {
            while (isActive) {
                delay(SAMPLING_INTERVAL_MS)
                sample()
            }
        }
    }

    fun stop() {
        _isRunning.value = false
        cameraController.stop()
        frameJob?.cancel()
        lightJob?.cancel()
        displayJob?.cancel()
        sampleJob?.cancel()
        frameJob = null
        lightJob = null
        displayJob = null
        sampleJob = null
        repository.flush()
        _realTime.value = RealTimeMetrics()
    }

    private fun onFrame(frame: FaceLandmarkFrame) {
        _latestFrame.value = frame
        val face = frame.faces.firstOrNull()
        if (face == null) {
            // 未检测到人脸：距离 / 眨眼置 0
            faceDetected = false
            latestIrisDiameterPx = 0f
            blinkDetector.reset()
            return
        }
        faceDetected = true

        val ear = BlinkDetector.averageEar(face.leftEye, face.rightEye)
        blinkDetector.update(ear)

        latestSourceWidth = frame.sourceWidth
        val diameters = listOfNotNull(
            face.leftEye?.irisDiameterPx,
            face.rightEye?.irisDiameterPx
        )
        if (diameters.isNotEmpty()) {
            latestIrisDiameterPx = diameters.average().toFloat()
        }
    }

    /** 每秒刷新一次，供界面实时展示。 */
    private fun buildRealTimeMetrics(): RealTimeMetrics {
        val lux = if (latestAmbientLux > 0f) latestAmbientLux else 300f
        val brightness = ScreenBrightnessReader.read(context).percent ?: 50
        val durationMin = (SystemClock.elapsedRealtime() - startRealtimeMillis) / 60_000f
        val distanceCm = if (faceDetected && latestIrisDiameterPx > 0f) {
            DepthFromIris.distanceCm(
                irisDiameterPx = latestIrisDiameterPx,
                focalLengthPx = intrinsics.focalLengthPx(latestSourceWidth)
            )
        } else {
            0f
        }
        val blinkCount = if (faceDetected) blinkDetector.blinkCount else 0

        if (!faceDetected) {
            return RealTimeMetrics(
                faceDetected = false,
                distanceCm = 0f,
                blinkCount = 0,
                durationMin = durationMin,
                ambientLux = lux,
                screenBrightness = brightness,
                totalRisk = 0f,
                riskLevel = RiskLevel.LOW,
                advice = "未检测到人脸"
            )
        }

        // 风险基于「近期采样序列」计算；序列每 30 秒才变化，缓存结果避免每秒重复打分
        val risk = currentRisk()
        return RealTimeMetrics(
            faceDetected = true,
            distanceCm = distanceCm,
            blinkCount = blinkCount,
            durationMin = durationMin,
            ambientLux = lux,
            screenBrightness = brightness,
            totalRisk = risk.totalRisk.toFloat(),
            riskLevel = risk.level,
            advice = risk.advice
        )
    }

    /** 仅在采样序列变化时重新打分；否则返回缓存结果。 */
    private fun currentRisk(): FatigueRisk {
        val current = repository.samples.value
        val cached = cachedRisk
        if (cached == null || current !== cachedRiskSamples) {
            val computed = riskScorer.score(current, UserProfile())
            cachedRiskSamples = current
            cachedRisk = computed
            return computed
        }
        return cached
    }

    /** 每 30 秒采样一条并立即写入 JSON。 */
    private fun sample() {
        val lux = if (latestAmbientLux > 0f) latestAmbientLux else 300f
        val brightness = ScreenBrightnessReader.read(context).percent ?: 50
        val durationMin = (SystemClock.elapsedRealtime() - startRealtimeMillis) / 60_000f
        val distanceCm = if (faceDetected && latestIrisDiameterPx > 0f) {
            DepthFromIris.distanceCm(
                irisDiameterPx = latestIrisDiameterPx,
                focalLengthPx = intrinsics.focalLengthPx(latestSourceWidth)
            )
        } else {
            0f
        }
        val blinkCount = if (faceDetected) blinkDetector.blinkCount else 0

        repository.addSample(
            MonitoringSample(
                timestampMillis = System.currentTimeMillis(),
                ambientLux = lux,
                screenBrightness = brightness,
                distanceCm = distanceCm,
                durationMin = durationMin,
                blinkCount = blinkCount
            )
        )
        // 采样后重置眨眼计数，使每条样本记录的是「本采样区间」的眨眼次数
        blinkDetector.reset()
    }

    companion object {
        private const val DISPLAY_INTERVAL_MS = 1_000L
        private const val SAMPLING_INTERVAL_MS = 30_000L
    }
}
