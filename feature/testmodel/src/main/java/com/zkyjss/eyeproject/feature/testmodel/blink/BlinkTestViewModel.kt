package com.zkyjss.eyeproject.feature.testmodel.blink

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
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 眨眼测试页 ViewModel：绑定 CameraX 预览 + 分析，
 * 每帧从 FaceLandmarker 关键点现算双眼 EAR，本地 [BlinkDetector] 累计眨眼次数；
 * 60s 检测模式逐帧记录 EAR/耗时/帧率，结束后写入应用外部私有目录（adb 可直接拉取）。
 */
@HiltViewModel
class BlinkTestViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val blinkDetector = BlinkDetector()
    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzer: BlinkLandmarkerAnalyzer? = null
    private var executor: ExecutorService? = null

    // ---- 60s 检测录制（rows 仅在分析线程 append；结束由主线程触发，用锁串行化） ----
    private val recordLock = Any()
    private var recording = false
    private var recordStartElapsed = 0L
    private var recordRows: MutableList<String>? = null

    private val _uiState = MutableStateFlow(BlinkTestUiState())
    val uiState: StateFlow<BlinkTestUiState> = _uiState.asStateFlow()

    fun startCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        stopCamera()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val ex = Executors.newSingleThreadExecutor()
                    val eyeAnalyzer = BlinkLandmarkerAnalyzer(
                        context = context,
                        onResult = ::handleFrame,
                        onError = { throwable ->
                            _uiState.update { it.copy(error = "人脸关键点失败: ${throwable.message}") }
                        }
                    )
                    val analysis = ImageAnalysis.Builder()
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(ex, eyeAnalyzer) }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        analysis
                    )
                    executor = ex
                    analyzer = eyeAnalyzer
                    cameraProvider = provider
                    _uiState.update { it.copy(cameraRunning = true) }
                } catch (e: Exception) {
                    _uiState.update { it.copy(cameraRunning = false, error = "摄像头启动失败：${e.message}") }
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    /** 重置眨眼计数，开始新一段观察。 */
    fun resetBlinkCount() {
        blinkDetector.reset()
        _uiState.update { it.copy(blinkCount = 0, eyeClosed = false) }
    }

    /** 开始 60 秒检测：逐帧记录 EAR / 耗时 / 帧率，到点自动保存。 */
    fun startRecording() {
        synchronized(recordLock) {
            if (recording) return
            recording = true
            recordStartElapsed = SystemClock.elapsedRealtime()
            recordRows = mutableListOf(CSV_HEADER)
        }
        _uiState.update {
            it.copy(recording = true, recordRemainS = RECORD_DURATION_S, savedPath = null, error = null)
        }
        // 兜底：即使相机断帧也能到点收尾
        viewModelScope.launch {
            delay(RECORD_DURATION_MS + 1_000)
            if (recording) finishRecording()
        }
    }

    /** 提前结束检测并保存已采集的数据。 */
    fun stopRecording() {
        if (recording) finishRecording()
    }

    private fun handleFrame(result: BlinkFrameResult) {
        val earL = result.leftEye?.let { BlinkDetector.ear(it.earPoints) }
        val earR = result.rightEye?.let { BlinkDetector.ear(it.earPoints) }
        val ear = BlinkDetector.averageEar(result.leftEye, result.rightEye)
        blinkDetector.update(ear)

        // 录制中：追加一行数据（t_s 相对录制起点的秒数）
        var newRemainS: Int? = null
        synchronized(recordLock) {
            if (recording) {
                val elapsedMs = SystemClock.elapsedRealtime() - recordStartElapsed
                if (elapsedMs >= RECORD_DURATION_MS) {
                    finishRecording()
                } else {
                    recordRows?.add(formatRow(elapsedMs / 1000.0, earL, earR, ear, result))
                    newRemainS =
                        (((RECORD_DURATION_MS - elapsedMs) + 999) / 1000).toInt()
                }
            }
        }

        _uiState.update {
            it.copy(
                faceDetected = result.faceBox != null,
                earLeft = earL,
                earRight = earR,
                earAverage = ear,
                eyeClosed = blinkDetector.isEyeClosed,
                blinkCount = blinkDetector.blinkCount,
                faceBox = result.faceBox,
                leftEye = result.leftEye,
                rightEye = result.rightEye,
                sourceWidth = result.sourceWidth,
                sourceHeight = result.sourceHeight,
                detectionLatencyMillis = result.detectionLatencyMillis,
                fps = result.fps,
                recordRemainS = newRemainS ?: it.recordRemainS
            )
        }
    }

    /** 收尾：抢锁关录制、快照数据，IO 线程写 CSV，完成后回填保存路径。 */
    private fun finishRecording() {
        val rows: List<String>
        synchronized(recordLock) {
            if (!recording) return
            recording = false
            rows = recordRows.orEmpty()
            recordRows = null
        }
        _uiState.update { it.copy(recording = false, recordRemainS = 0) }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(context.getExternalFilesDir(null), "blink_test_$stamp.csv")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                file.writeText(rows.joinToString("\n") + "\n")
                _uiState.update { it.copy(savedPath = file.absolutePath) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "保存失败：${e.message}") }
            }
        }
    }

    /** 一行采样数据：t_s,ear_left,ear_right,ear_avg,latency_ms,fps（无脸帧 ear 留空）。 */
    private fun formatRow(
        relS: Double,
        earL: Float?,
        earR: Float?,
        ear: Float?,
        result: BlinkFrameResult
    ): String = buildString {
        append(String.format(Locale.US, "%.3f", relS))
        listOf(earL, earR, ear).forEach { value ->
            append(',')
            if (value != null) append(String.format(Locale.US, "%.4f", value))
        }
        append(String.format(Locale.US, ",%d,%.1f", result.detectionLatencyMillis, result.fps))
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        analyzer?.close()
        analyzer = null
        executor?.shutdown()
        executor = null
        _uiState.update { it.copy(cameraRunning = false) }
    }

    override fun onCleared() {
        // 录制中直接退出页面：丢弃未保存数据
        synchronized(recordLock) {
            recording = false
            recordRows = null
        }
        stopCamera()
        super.onCleared()
    }

    companion object {
        private const val RECORD_DURATION_MS = 60_000L
        private const val RECORD_DURATION_S = 60
        private const val CSV_HEADER = "t_s,ear_left,ear_right,ear_avg,latency_ms,fps"
    }
}
