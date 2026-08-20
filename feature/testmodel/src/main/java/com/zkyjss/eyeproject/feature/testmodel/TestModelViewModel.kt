package com.zkyjss.eyeproject.feature.testmodel

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@HiltViewModel
class TestModelViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private var model: TestModel? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var executor: ExecutorService? = null
    private var frameIndex = 0
    private var lastFrameNanos = 0L
    private var smoothedFps = 0f
    private var faceDetectorAnalyzer: FaceDetectorAnalyzer? = null
    private var latestBitmap: Bitmap? = null

    private val _uiState = MutableStateFlow(TestModelUiState())
    val uiState: StateFlow<TestModelUiState> = _uiState.asStateFlow()

    init {
        loadModel()
    }

    private fun loadModel() {
        val loaded = try {
            // LiteRT C++ SDK（JNI）实现
            GazeNativeModel(context)
        } catch (e: Throwable) {
            model = null
            _uiState.update {
                it.copy(modelError = "模型加载失败(${e.javaClass.simpleName}): ${e.message}")
            }
            return
        }
        model = loaded
        _uiState.update {
            it.copy(modelInfo = loaded.info, modelError = null)
        }
    }

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
                    val analysis = ImageAnalysis.Builder()
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(ex) { proxy -> analyze(proxy) } }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        analysis
                    )
                    executor = ex
                    cameraProvider = provider
                    _uiState.update { it.copy(cameraRunning = true) }
                } catch (e: Exception) {
                    _uiState.update { it.copy(cameraRunning = false, modelError = "摄像头启动失败：${e.message}") }
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    private fun analyze(imageProxy: ImageProxy) {
        frameIndex++
        if (frameIndex % FRAME_INTERVAL != 0) {
            imageProxy.close()
            return
        }
        val bitmap = toBitmap(imageProxy)
        imageProxy.close()

        val startNanos = System.nanoTime()
        val m = model ?: return
        try {
            val result = m.infer_use_cpp_lib(bitmap)
            // GazeNativeModel 输出 [yaw, pitch]（已解码角度），不是 90-bin logits
            val yawPitch = result.outputs.firstOrNull()?.takeIf { it.size >= 2 }
            _uiState.update {
                it.copy(
                    latestInference = result,
                    yawDeg = yawPitch?.get(0),
                    pitchDeg = yawPitch?.get(1),
                    fps = updateFps(startNanos)
                )
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(modelError = "推理失败：${e.message}") }
        }
    }

    private fun toBitmap(imageProxy: ImageProxy): Bitmap {
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * imageProxy.width
        val bitmapWidth = imageProxy.width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(bitmapWidth, imageProxy.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, imageProxy.width, imageProxy.height)
        }
    }

    private fun updateFps(nowNanos: Long): Float {
        if (lastFrameNanos == 0L) {
            lastFrameNanos = nowNanos
            return smoothedFps
        }
        val frameFps = 1_000_000_000f / (nowNanos - lastFrameNanos).coerceAtLeast(1L)
        lastFrameNanos = nowNanos
        smoothedFps = if (smoothedFps == 0f) {
            frameFps
        } else {
            0.85f * smoothedFps + 0.15f * frameFps
        }
        return smoothedFps
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        executor?.shutdown()
        executor = null
        _uiState.update { it.copy(cameraRunning = false) }
    }

    override fun onCleared() {
        stopCamera()
        model?.close()
        model = null
        super.onCleared()
    }

    companion object {
        private const val FRAME_INTERVAL = 3
    }
}
