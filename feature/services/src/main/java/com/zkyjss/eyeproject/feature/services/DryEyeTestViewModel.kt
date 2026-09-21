package com.zkyjss.eyeproject.feature.services

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider
import com.zkyjss.eyeproject.core.camera.FaceLandmarkerAnalyzer
import com.zkyjss.eyeproject.core.domain.BlinkDetector
import com.zkyjss.eyeproject.core.domain.DepthFromIris
import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DryEyePhase { Setup, Countdown, Blink60, Open20, Result }

/** 单个距离采样：相对测试开始的秒数 + 距离。 */
data class DryEyeDistanceSample(val tSec: Float, val cm: Float)

data class DryEyeTestUiState(
    val phase: DryEyePhase = DryEyePhase.Setup,
    val instruction: String = "",
    val remainingSeconds: Int = 0,
    val progressFraction: Float = 0f,
    // 门禁
    val faceDetected: Boolean = false,
    val distanceCm: Float = 0f,
    val gateReady: Boolean = false,
    // 数据（相位内秒数）
    val phase1Blinks: List<Float> = emptyList(),
    val phase2Blinks: List<Float> = emptyList(),
    val phase1BlinkCount: Int = 0,
    val phase2BlinkCount: Int = 0,
    val distanceSamples: List<DryEyeDistanceSample> = emptyList(),
    val error: String? = null,
)

/**
 * 干眼检测 ViewModel：两阶段检测（阶段 1 连续眨眼 60s + 阶段 2 保持睁眼 20s）。
 * 记录眨眼时刻分布与观测距离；开始前进行人眼检测与距离门禁（≥40cm）。
 *
 * 线程模型（与改前一致）：分析线程回调只写 @Volatile 字段（lastBlink/lastDistanceCm），
 * 数据累积与所有状态更新都在 runTest 协程内完成——单一写入方，无锁。
 */
@HiltViewModel
class DryEyeTestViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val intrinsics: CameraIntrinsicsProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DryEyeTestUiState())
    val uiState: StateFlow<DryEyeTestUiState> = _uiState.asStateFlow()

    private val blinkDetector = BlinkDetector()

    // 分析线程只写、协程只读的 @Volatile 字段
    @Volatile private var lastBlinkElapsed = 0L
    @Volatile private var lastDistanceCm = 0f

    @Volatile private var currentPhase = DryEyePhase.Setup
    @Volatile private var phaseStartElapsed = 0L
    @Volatile private var testStartElapsed = 0L

    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzer: FaceLandmarkerAnalyzer? = null
    private var executor: ExecutorService? = null
    private var testJob: Job? = null

    /** 准备页：启动摄像头（预览 + 分析），进行「人眼检测 + 距离门禁」。 */
    fun startMonitoring(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        if (cameraProvider != null) return
        blinkDetector.reset()
        blinkDetector.onBlink = { lastBlinkElapsed = SystemClock.elapsedRealtime() }
        currentPhase = DryEyePhase.Setup
        startCamera(lifecycleOwner, previewView)
    }

    /** 门禁通过后开始两阶段检测。 */
    fun startTest() {
        if (!_uiState.value.gateReady || testJob != null) return
        testJob = viewModelScope.launch { runTest() }
    }

    fun backToSetup() {
        testJob?.cancel()
        currentPhase = DryEyePhase.Setup
        stopCamera()
        _uiState.value = DryEyeTestUiState()
    }

    override fun onCleared() {
        testJob?.cancel()
        stopCamera()
    }

    private suspend fun runTest() {
        testStartElapsed = SystemClock.elapsedRealtime()
        // 数据在协程内累积（单一写入方），结束时一次性快照到状态
        val phase1Events = mutableListOf<Float>()
        val phase2Events = mutableListOf<Float>()
        val distanceSamples = mutableListOf<DryEyeDistanceSample>()
        var lastSeenBlink = 0L
        var lastDistanceSampleMs = 0L

        _uiState.update {
            it.copy(
                faceDetected = false,
                distanceCm = 0f,
                gateReady = false,
                phase1Blinks = emptyList(),
                phase2Blinks = emptyList(),
                phase1BlinkCount = 0,
                phase2BlinkCount = 0,
                distanceSamples = emptyList(),
                error = null,
            )
        }

        // ---- 阶段 1：连续眨眼 60s ----
        runCountdown("第 1 阶段：自然眨眼 60 秒")
        currentPhase = DryEyePhase.Blink60
        var phaseStart = SystemClock.elapsedRealtime()
        phaseStartElapsed = phaseStart
        var tick = 0
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - phaseStart
            if (elapsed >= PHASE1_SECONDS * 1000L) break
            if (lastBlinkElapsed != lastSeenBlink) {
                lastSeenBlink = lastBlinkElapsed
                phase1Events += (lastBlinkElapsed - phaseStart) / 1000f
            }
            if (now - lastDistanceSampleMs >= 500) {
                lastDistanceSampleMs = now
                distanceSamples += DryEyeDistanceSample((now - testStartElapsed) / 1000f, lastDistanceCm)
            }
            _uiState.update {
                it.copy(
                    remainingSeconds = (((PHASE1_SECONDS * 1000L - elapsed) + 999) / 1000L).toInt(),
                    progressFraction = (elapsed.toFloat() / (PHASE1_SECONDS * 1000L)).coerceIn(0f, 1f),
                    phase1BlinkCount = phase1Events.size,
                )
            }
            tick++
            delay(100)
        }

        // ---- 阶段 2：保持睁眼 20s ----
        runCountdown("第 2 阶段：保持睁眼 20 秒，尽量不要眨眼")
        currentPhase = DryEyePhase.Open20
        phaseStart = SystemClock.elapsedRealtime()
        phaseStartElapsed = phaseStart
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - phaseStart
            if (elapsed >= PHASE2_SECONDS * 1000L) break
            if (lastBlinkElapsed != lastSeenBlink) {
                lastSeenBlink = lastBlinkElapsed
                phase2Events += (lastBlinkElapsed - phaseStart) / 1000f
            }
            if (now - lastDistanceSampleMs >= 500) {
                lastDistanceSampleMs = now
                distanceSamples += DryEyeDistanceSample((now - testStartElapsed) / 1000f, lastDistanceCm)
            }
            _uiState.update {
                it.copy(
                    remainingSeconds = (((PHASE2_SECONDS * 1000L - elapsed) + 999) / 1000L).toInt(),
                    progressFraction = (elapsed.toFloat() / (PHASE2_SECONDS * 1000L)).coerceIn(0f, 1f),
                    phase2BlinkCount = phase2Events.size,
                )
            }
            delay(100)
        }

        // ---- 完成：一次性快照 ----
        currentPhase = DryEyePhase.Result
        stopCamera()
        _uiState.value = _uiState.value.copy(
            phase = DryEyePhase.Result,
            phase1Blinks = phase1Events.toList(),
            phase2Blinks = phase2Events.toList(),
            phase1BlinkCount = phase1Events.size,
            phase2BlinkCount = phase2Events.size,
            distanceSamples = distanceSamples.toList(),
        )
    }

    private suspend fun runCountdown(instruction: String) {
        currentPhase = DryEyePhase.Countdown
        for (s in COUNTDOWN_SECONDS downTo 1) {
            _uiState.update { it.copy(phase = DryEyePhase.Countdown, instruction = instruction, remainingSeconds = s) }
            delay(1000)
        }
    }

    private fun startCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysisExecutor = Executors.newSingleThreadExecutor()
                    val eyeAnalyzer = FaceLandmarkerAnalyzer(
                        context = context,
                        onResult = ::handleFrame,
                        onError = { throwable ->
                            _uiState.update { it.copy(error = throwable.message) }
                        }
                    )
                    val analysis = ImageAnalysis.Builder()
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, eyeAnalyzer) }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        analysis
                    )
                    cameraProvider = provider
                    analyzer = eyeAnalyzer
                    executor = analysisExecutor
                } catch (throwable: Throwable) {
                    _uiState.update { it.copy(error = throwable.message) }
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        analyzer?.close()
        analyzer = null
        executor?.shutdown()
        executor = null
    }

    /** 分析线程回调：只更新 @Volatile 字段，不做任何状态写入。 */
    private fun handleFrame(frame: FaceLandmarkFrame) {
        val face = frame.faces.firstOrNull()
        val eyesDetected = face != null && face.leftEye != null && face.rightEye != null
        var distanceCm = 0f
        if (eyesDetected) {
            val ear = BlinkDetector.averageEar(face.leftEye, face.rightEye)
            blinkDetector.update(ear)

            val diameters = listOfNotNull(
                face.leftEye?.irisDiameterPx,
                face.rightEye?.irisDiameterPx
            )
            if (diameters.isNotEmpty()) {
                distanceCm = DepthFromIris.distanceCm(
                    irisDiameterPx = diameters.average().toFloat(),
                    focalLengthPx = intrinsics.focalLengthPx(frame.sourceWidth)
                )
            }
        }
        lastDistanceCm = distanceCm

        if (currentPhase == DryEyePhase.Setup) {
            // 门禁：人眼检测到 + 距离达标（分析线程直接写门禁字段，仅 Setup 阶段）
            val gate = eyesDetected && distanceCm >= MIN_TEST_DISTANCE_CM
            val now = SystemClock.elapsedRealtime()
            val flipped = gate != _uiState.value.gateReady || eyesDetected != _uiState.value.faceDetected
            if (flipped || now - lastGateUpdateMs >= 500) {
                lastGateUpdateMs = now
                _uiState.update { it.copy(faceDetected = eyesDetected, distanceCm = distanceCm, gateReady = gate) }
            }
        }
    }

    @Volatile private var lastGateUpdateMs = 0L

    companion object {
        /** 阶段 1 时长（秒）：连续眨眼。 */
        const val PHASE1_SECONDS = 60

        /** 阶段 2 时长（秒）：保持睁眼。 */
        const val PHASE2_SECONDS = 20

        /** 阶段切换倒计时秒数。 */
        private const val COUNTDOWN_SECONDS = 3

        /** 门禁距离（cm）：低于该值不允许开始测试。 */
        const val MIN_TEST_DISTANCE_CM = 40f
    }
}
