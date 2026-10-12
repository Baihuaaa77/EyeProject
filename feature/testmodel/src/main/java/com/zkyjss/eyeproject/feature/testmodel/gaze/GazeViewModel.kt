package com.zkyjss.eyeproject.feature.testmodel.gaze

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import com.zkyjss.eyeproject.core.model.BoundingBox
import com.zkyjss.eyeproject.core.model.FrameResult
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
class GazeViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private var model: TestModel? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var executor: ExecutorService? = null
    private var faceDetectorAnalyzer: FaceDetectorAnalyzer? = null
    private var latestBitmap: Bitmap? = null

    private val _uiState = MutableStateFlow(GazeUiState())
    val uiState: StateFlow<GazeUiState> = _uiState.asStateFlow()

    init {
        loadModel()
    }

    private fun loadModel() {
        if (Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("x86") == true) {
            _uiState.update {
                it.copy(modelError = "当前模拟器构建不包含视线估计 JNI，请在 ARM64 真机测试此实验功能。")
            }
            return
        }
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
        if (model == null) return
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
                    val analyzer = FaceDetectorAnalyzer(
                        context = context,
                        onResult = { frameResult ->
                            processFaceDetection(frameResult)
                        },
                        onError = { throwable ->
                            _uiState.update { it.copy(modelError = "人脸检测失败: ${throwable.message}") }
                        }
                    )
                    faceDetectorAnalyzer = analyzer

                    val analysis = ImageAnalysis.Builder()
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                                // ⚠️ 必须用旋转到正立的图，和 FaceDetectorAnalyzer 的检测框坐标系一致，
                                // 否则 cropBitmap 会用旋转后的坐标裁剪未旋转的图，导致人脸区域错位。
                                latestBitmap = uprightBitmap(imageProxy)
                                analyzer.analyze(imageProxy)         // ← 交给 FaceDetectorAnalyzer
                            }
                        }

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

    /**
     * 处理人脸检测结果，裁剪人脸并执行视线估计
     */
    private fun processFaceDetection(frameResult: FrameResult) {
        val model = model ?: return
        val bitmap = latestBitmap ?: return

        val faceDetection = frameResult.detections.firstOrNull()
        if (faceDetection == null) {
            _uiState.update {
                it.copy(
                    faceDetected = false,
                    yawDeg = null,
                    pitchDeg = null,
                    faceBox = null
                )
            }
            return
        }

        try {
            val faceBox = faceDetection.faceBox
            val croppedFace = cropBitmap(bitmap, faceBox)

            val result = if (_uiState.value.useAndroidApi) {
                model.infer_use_android_api(croppedFace)
            } else {
                model.infer_use_cpp_lib(croppedFace)
            }

            val yawPitch = result.outputs.firstOrNull()?.takeIf { it.size >= 2 }

            _uiState.update {
                it.copy(
                    faceDetected = true,
                    latestInference = result,
                    yawDeg = yawPitch?.get(0),
                    pitchDeg = yawPitch?.get(1),
                    faceBox = faceBox,
                    sourceWidth = frameResult.sourceWidth,
                    sourceHeight = frameResult.sourceHeight,
                    fps = frameResult.fps,
                    detectionLatencyMillis = frameResult.detectionLatencyMillis
                )
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(modelError = "视线估计失败：${e.message}") }
        }
    }

    /**
     * 处理人脸检测结果，裁剪人脸并执行视线估计
     */
     fun changeInfer(useAndroidApi: Boolean) {
        if (useAndroidApi == _uiState.value.useAndroidApi) {
            return
        }

        try {
            _uiState.update {
                it.copy(
                    useAndroidApi = useAndroidApi
                )
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(modelError = "切换实现失败：${e.message}") }
        }
    }

    private fun cropBitmap(bitmap: Bitmap, box: BoundingBox): Bitmap {
        val left = box.left.toInt().coerceAtLeast(0)
        val top = box.top.toInt().coerceAtLeast(0)
        val right = box.right.toInt().coerceAtMost(bitmap.width)
        val bottom = box.bottom.toInt().coerceAtMost(bitmap.height)
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        return Bitmap.createBitmap(bitmap, left, top, width, height)
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

    /** 旋转到正立，与 FaceDetectorAnalyzer 的检测框坐标系一致。 */
    private fun uprightBitmap(imageProxy: ImageProxy): Bitmap {
        val raw = toBitmap(imageProxy)
        val rotation = imageProxy.imageInfo.rotationDegrees
        if (rotation == 0) {
            return raw
        }
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        executor?.shutdown()
        executor = null
        faceDetectorAnalyzer?.close()  // ← 新增
        faceDetectorAnalyzer = null    // ← 新增
        latestBitmap = null            // ← 新增
        _uiState.update { it.copy(cameraRunning = false) }
    }

    override fun onCleared() {
        stopCamera()
        model?.close()
        model = null
        super.onCleared()
    }
}
