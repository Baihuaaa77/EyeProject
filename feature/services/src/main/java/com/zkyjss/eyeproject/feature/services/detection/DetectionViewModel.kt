package com.zkyjss.eyeproject.feature.services.detection

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider
import com.zkyjss.eyeproject.core.camera.FaceLandmarkerAnalyzer
import com.zkyjss.eyeproject.core.domain.BlinkDetector
import com.zkyjss.eyeproject.core.domain.DepthFromIris
import com.zkyjss.eyeproject.core.model.EyeDetection
import com.zkyjss.eyeproject.core.model.EyeLandmarks
import com.zkyjss.eyeproject.core.model.FaceEyeDetection
import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
import com.zkyjss.eyeproject.core.model.Point2f
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 检测服务 UI 状态：摄像头预览 + 眼睛关键点 + 距离/眨眼。 */
data class DetectionUiState(
    val faceDetected: Boolean = false,
    val detections: List<FaceEyeDetection> = emptyList(),
    val distanceCm: Float = 0f,
    val blinkCount: Int = 0,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val error: String? = null,
)

/**
 * 检测服务 ViewModel：绑定 CameraX Preview + ImageAnalysis，
 * 复用 core:camera 的 FaceLandmarker 输出眼睛关键点，计算距离（Depth-from-Iris）与眨眼次数（EAR）。
 */
@HiltViewModel
class DetectionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val intrinsics: CameraIntrinsicsProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetectionUiState())
    val uiState: StateFlow<DetectionUiState> = _uiState.asStateFlow()

    private val blinkDetector = BlinkDetector()
    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzer: FaceLandmarkerAnalyzer? = null
    private var executor: ExecutorService? = null

    fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    val provider = future.get()
                    val analysisExecutor = Executors.newSingleThreadExecutor()
                    val eyeAnalyzer = FaceLandmarkerAnalyzer(
                        context = context,
                        onResult = ::handleFrame,
                        onError = { throwable ->
                            _uiState.value = _uiState.value.copy(error = throwable.message)
                        }
                    )
                    val preview = Preview.Builder()
                        .build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
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
                    _uiState.value = _uiState.value.copy(error = throwable.message)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    fun stop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        analyzer?.close()
        analyzer = null
        executor?.shutdown()
        executor = null
    }

    override fun onCleared() {
        stop()
    }

    private fun handleFrame(frame: FaceLandmarkFrame) {
        val face = frame.faces.firstOrNull()
        if (face == null) {
            _uiState.value = DetectionUiState()
            return
        }
        val ear = BlinkDetector.averageEar(face.leftEye, face.rightEye)
        blinkDetector.update(ear)
        val irisDiameterPx = listOfNotNull(
            face.leftEye?.irisDiameterPx,
            face.rightEye?.irisDiameterPx
        ).average().toFloat()
        val distanceCm = DepthFromIris.distanceCm(
            irisDiameterPx = irisDiameterPx,
            focalLengthPx = intrinsics.focalLengthPx(frame.sourceWidth)
        )
        val detection = FaceEyeDetection(
            faceId = 0,
            faceBox = face.faceBox,
            leftEye = face.leftEye?.toEyeDetection(),
            rightEye = face.rightEye?.toEyeDetection(),
            sourceWidth = frame.sourceWidth,
            sourceHeight = frame.sourceHeight,
            rotationDegrees = 0,
            timestampMillis = frame.timestampMillis,
            detectionLatencyMillis = frame.detectionLatencyMillis,
            fps = frame.fps,
        )
        _uiState.value = DetectionUiState(
            faceDetected = true,
            detections = listOf(detection),
            distanceCm = distanceCm,
            blinkCount = blinkDetector.blinkCount,
            sourceWidth = frame.sourceWidth,
            sourceHeight = frame.sourceHeight,
        )
    }

    private fun EyeLandmarks.toEyeDetection(): EyeDetection {
        val center = if (earPoints.size >= 4) {
            Point2f(
                (earPoints[0].x + earPoints[3].x) / 2f,
                (earPoints[0].y + earPoints[3].y) / 2f,
            )
        } else {
            Point2f(0f, 0f)
        }
        return EyeDetection(
            label = label,
            points = earPoints,
            center = center,
            irisCenter = irisPoints.getOrNull(0),
        )
    }
}
