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

    private val _realTime = MutableStateFlow(RealTimeMetrics())
    val realTime: StateFlow<RealTimeMetrics> = _realTime.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val samples: StateFlow<List<MonitoringSample>> = repository.samples

    @Volatile private var latestIrisDiameterPx = 0f
    @Volatile private var latestSourceWidth = 0
    @Volatile private var latestSourceHeight = 0
    @Volatile private var latestAmbientLux = 0f
    @Volatile private var faceDetected = false
    private var startRealtimeMillis = 0L

    // 风险结果缓存：采样序列每 30 秒才变化，避免每秒重复走 JNI 打分
    @Volatile private var cachedRiskSamples: List<MonitoringSample>? = null
    @Volatile private var cachedRisk: FatigueRisk? = null

    private var lightJob: Job? = null
    private var displayJob: Job? = null
    private var sampleJob: Job? = null
    private var cameraGeneration: Int? = null

    fun start(lifecycleOwner: LifecycleOwner) {
        if (_isRunning.value) return
        _isRunning.value = true
        _error.value = null
        startRealtimeMillis = SystemClock.elapsedRealtime()
        latestIrisDiameterPx = 0f
        latestSourceWidth = 0
        latestSourceHeight = 0
        latestAmbientLux = 0f
        faceDetected = false
        blinkDetector.reset()
        cachedRiskSamples = null
        cachedRisk = null
        _realTime.value = RealTimeMetrics()

        cameraGeneration = cameraController.start(
            lifecycleOwner = lifecycleOwner,
            onFrame = ::onFrame,
            onError = { throwable ->
                // 单帧分析异常（分析线程回调）是瞬时的，相机仍在跑，不打断监测；
                // 绑定失败则必须连同采样协程一起停掉——否则仅置 isRunning=false，
                // 旧协程继续写入空样本，再次 start() 还会叠加一套新协程。
                // 相机解绑只能在主线程做，统一转投主线程判断。
                scope.launch(Dispatchers.Main) {
                    if (_isRunning.value && !cameraController.isRunning) {
                        stop()
                        _error.value = "摄像头启动失败：${throwable.message ?: "未知错误"}"
                    }
                }
            }
        )

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
        if (!_isRunning.value && cameraGeneration == null) return
        _isRunning.value = false
        cameraGeneration?.let(cameraController::stopIfGeneration)
        cameraGeneration = null
        lightJob?.cancel()
        displayJob?.cancel()
        sampleJob?.cancel()
        lightJob = null
        displayJob = null
        sampleJob = null
        repository.flush()
        _realTime.value = RealTimeMetrics()
    }

    private fun onFrame(frame: FaceLandmarkFrame) {
        val face = frame.faces.firstOrNull()
        if (face == null) {
            // 未检测到人脸：距离置 0；眨眼只中断闭眼跟踪，不清本区间计数，
            // 否则短暂丢脸会抹掉已统计的眨眼，样本眨眼率偏低 → 干眼风险虚高
            faceDetected = false
            latestIrisDiameterPx = 0f
            blinkDetector.interrupt()
            return
        }
        faceDetected = true

        val ear = BlinkDetector.averageEar(face.leftEye, face.rightEye)
        blinkDetector.update(ear)

        latestSourceWidth = frame.sourceWidth
        latestSourceHeight = frame.sourceHeight
        val diameters = listOfNotNull(
            face.leftEye?.irisDiameterPx,
            face.rightEye?.irisDiameterPx
        )
        if (diameters.isNotEmpty()) {
            latestIrisDiameterPx = diameters.average().toFloat()
        }
    }

    /** 当前时刻的 5 个用眼指标：实时展示与 30 秒采样共用同一份计算。 */
    private data class CurrentMetrics(
        val ambientLux: Float,
        val screenBrightness: Int,
        val durationMin: Float,
        val distanceCm: Float,
        val blinkCount: Int,
    )

    private fun currentMetrics(): CurrentMetrics {
        val distanceCm = if (faceDetected && latestIrisDiameterPx > 0f) {
            DepthFromIris.distanceCm(
                irisDiameterPx = latestIrisDiameterPx,
                focalLengthPx = intrinsics.focalLengthPx(latestSourceWidth, latestSourceHeight)
            )
        } else {
            0f
        }
        return CurrentMetrics(
            ambientLux = if (latestAmbientLux > 0f) latestAmbientLux else DEFAULT_AMBIENT_LUX,
            screenBrightness = ScreenBrightnessReader.read(context).percent
                ?: DEFAULT_BRIGHTNESS_PERCENT,
            durationMin = (SystemClock.elapsedRealtime() - startRealtimeMillis) / MILLIS_PER_MINUTE,
            distanceCm = distanceCm,
            blinkCount = if (faceDetected) blinkDetector.blinkCount else 0,
        )
    }

    /** 每秒刷新一次，供界面实时展示。 */
    private fun buildRealTimeMetrics(): RealTimeMetrics {
        val metrics = currentMetrics()

        if (!faceDetected) {
            return RealTimeMetrics(
                faceDetected = false,
                distanceCm = 0f,
                blinkCount = 0,
                durationMin = metrics.durationMin,
                ambientLux = metrics.ambientLux,
                screenBrightness = metrics.screenBrightness,
                totalRisk = 0f,
                riskLevel = RiskLevel.LOW,
                advice = "未检测到人脸"
            )
        }

        // 风险基于「近期采样序列」计算；序列每 30 秒才变化，缓存结果避免每秒重复打分
        val risk = currentRisk()
        return RealTimeMetrics(
            faceDetected = true,
            distanceCm = metrics.distanceCm,
            blinkCount = metrics.blinkCount,
            durationMin = metrics.durationMin,
            ambientLux = metrics.ambientLux,
            screenBrightness = metrics.screenBrightness,
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
        val metrics = currentMetrics()
        repository.addSample(
            MonitoringSample(
                timestampMillis = System.currentTimeMillis(),
                ambientLux = metrics.ambientLux,
                screenBrightness = metrics.screenBrightness,
                distanceCm = metrics.distanceCm,
                durationMin = metrics.durationMin,
                blinkCount = metrics.blinkCount
            )
        )
        // 采样后重置眨眼计数，使每条样本记录的是「本采样区间」的眨眼次数
        blinkDetector.reset()
    }

    companion object {
        private const val DISPLAY_INTERVAL_MS = 1_000L
        private const val SAMPLING_INTERVAL_MS = 30_000L
        private const val MILLIS_PER_MINUTE = 60_000f

        /** 环境光传感器尚无读数时的兜底 lux（中性室内光）。 */
        private const val DEFAULT_AMBIENT_LUX = 300f

        /** 屏幕亮度读不到时的兜底百分比。 */
        private const val DEFAULT_BRIGHTNESS_PERCENT = 50
    }
}
