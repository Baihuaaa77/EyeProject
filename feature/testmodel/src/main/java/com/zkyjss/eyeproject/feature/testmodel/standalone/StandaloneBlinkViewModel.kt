package com.zkyjss.eyeproject.feature.testmodel.standalone

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

/**
 * 全替换眨眼页 ViewModel：BlazeFace 检测 + OCEC 分类（LiteRT，无 MediaPipe），
 * 60s 检测模式逐帧记录概率/耗时/帧率，结束后写入应用外部私有目录。
 */
@HiltViewModel
class StandaloneBlinkViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzer: StandaloneBlinkAnalyzer? = null
    private var executor: ExecutorService? = null

    // ---- 60s 检测录制（rows 仅在分析线程 append；结束由主线程触发，用锁串行化） ----
    private val recordLock = Any()
    private var recording = false
    private var recordStartElapsed = 0L
    private var recordRows: MutableList<String>? = null

    private val _uiState = MutableStateFlow(StandaloneBlinkUiState())
    val uiState: StateFlow<StandaloneBlinkUiState> = _uiState.asStateFlow()

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
                    val standaloneAnalyzer = StandaloneBlinkAnalyzer(
                        context = context,
                        onResult = ::handleFrame,
                        onError = { throwable ->
                            // 异常 message 可能为 null：面板给阶段+类型+原因链，logcat 留全栈
                            android.util.Log.e("StandaloneBlink", "analyze failed", throwable)
                            val detail = buildString {
                                append(throwable.message ?: throwable.javaClass.simpleName)
                                var cause = throwable.cause
                                var depth = 0
                                while (cause != null && depth < 3) {
                                    append(" ← ").append(cause.javaClass.simpleName)
                                    append("(").append(cause.message ?: "null").append(")")
                                    cause = cause.cause
                                    depth++
                                }
                            }
                            _uiState.update { it.copy(error = "检测失败: $detail") }
                        }
                    )
                    val analysis = ImageAnalysis.Builder()
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(ex, standaloneAnalyzer) }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        analysis
                    )
                    executor = ex
                    analyzer = standaloneAnalyzer
                    cameraProvider = provider
                    _uiState.update { it.copy(cameraRunning = true) }
                } catch (e: Exception) {
                    _uiState.update { it.copy(cameraRunning = false, error = "摄像头启动失败：${e.message}") }
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    /** 重置眨眼计数。 */
    fun resetBlinkCount() {
        analyzer?.resetBlink()
        _uiState.update { it.copy(blinkCount = 0) }
    }

    /** 开始 60 秒检测。 */
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
        analyzer?.dumpCrops = true  // 录制期间同步采样眼片，供 PC 端分类对比验证
        viewModelScope.launch {
            delay(RECORD_DURATION_MS + 1_000)
            if (recording) finishRecording()
        }
    }

    /** 提前结束检测并保存。 */
    fun stopRecording() {
        if (recording) finishRecording()
    }

    private fun handleFrame(result: StandaloneFrameResult) {
        var newRemainS: Int? = null
        synchronized(recordLock) {
            if (recording) {
                val elapsedMs = SystemClock.elapsedRealtime() - recordStartElapsed
                if (elapsedMs >= RECORD_DURATION_MS) {
                    finishRecording()
                } else {
                    recordRows?.add(formatRow(elapsedMs / 1000.0, result))
                    newRemainS = (((RECORD_DURATION_MS - elapsedMs) + 999) / 1000).toInt()
                }
            }
        }

        _uiState.update {
            it.copy(
                faceDetected = result.faceDetected,
                faceBox = result.faceBox,
                rightEyeBox = result.rightEye?.box,
                rightProb = result.rightEye?.probOpen,
                leftEyeBox = result.leftEye?.box,
                leftProb = result.leftEye?.probOpen,
                blinkCount = result.blinkCount,
                sourceWidth = result.sourceWidth,
                sourceHeight = result.sourceHeight,
                detectionLatencyMillis = result.detectionLatencyMillis,
                classificationLatencyMillis = result.classificationLatencyMillis,
                fps = result.fps,
                recordRemainS = newRemainS ?: it.recordRemainS
            )
        }
    }

    private fun formatRow(relS: Double, result: StandaloneFrameResult): String = buildString {
        append(String.format(Locale.US, "%.3f", relS))
        fun prob(v: Float?) { append(','); if (v != null) append(String.format(Locale.US, "%.4f", v)) }
        prob(result.leftEye?.probOpen)
        prob(result.rightEye?.probOpen)
        val avg = listOfNotNull(result.leftEye?.probOpen, result.rightEye?.probOpen).takeIf { it.isNotEmpty() }
        prob(avg?.average()?.toFloat())
        append(String.format(Locale.US, ",%d,%d,%d,%.1f",
            result.blinkCount, result.detectionLatencyMillis, result.classificationLatencyMillis, result.fps))
    }

    private fun finishRecording() {
        val rows: List<String>
        synchronized(recordLock) {
            if (!recording) return
            recording = false
            rows = recordRows.orEmpty()
            recordRows = null
        }
        _uiState.update { it.copy(recording = false, recordRemainS = 0) }
        analyzer?.dumpCrops = false

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(context.getExternalFilesDir(null), "standalone_blink_$stamp.csv")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                file.writeText(rows.joinToString("\n") + "\n")
                _uiState.update { it.copy(savedPath = file.absolutePath) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "保存失败：${e.message}") }
            }
        }
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
        private const val CSV_HEADER = "t_s,prob_left,prob_right,prob_avg,blink_count,det_ms,cls_ms,fps"
    }
}
