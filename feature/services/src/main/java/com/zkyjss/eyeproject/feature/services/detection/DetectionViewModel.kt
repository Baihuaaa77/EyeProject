package com.zkyjss.eyeproject.feature.services.detection

import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zkyjss.eyeproject.core.camera.CameraController
import com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider
import com.zkyjss.eyeproject.core.domain.BlinkDetector
import com.zkyjss.eyeproject.core.domain.DepthFromIris
import com.zkyjss.eyeproject.core.model.EyeDetection
import com.zkyjss.eyeproject.core.model.EyeLandmarks
import com.zkyjss.eyeproject.core.model.FaceEyeDetection
import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
import com.zkyjss.eyeproject.core.model.Point2f
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
 * 检测服务 ViewModel：相机管线走 [CameraController]（core:camera 唯一的绑定点），
 * 这里只管业务——眼睛关键点、距离（Depth-from-Iris）与眨眼次数（EAR）。
 */
@HiltViewModel
class DetectionViewModel @Inject constructor(
    private val cameraController: CameraController,
    private val intrinsics: CameraIntrinsicsProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetectionUiState())
    val uiState: StateFlow<DetectionUiState> = _uiState.asStateFlow()

    private val blinkDetector = BlinkDetector()

    /**
     * 分析线程只把最新一帧的结果投进来（`CONFLATED`：只留最新的，既不阻塞也不堆积），
     * 由协程统一写 [_uiState] —— 分析线程不直接碰 UI 状态。
     */
    private val frameStates = Channel<DetectionUiState>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            for (state in frameStates) {
                _uiState.value = state
            }
        }
    }

    fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        cameraController.start(
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            onFrame = ::handleFrame,
            onError = { throwable ->
                _uiState.value = _uiState.value.copy(error = throwable.message)
            },
        )
    }

    fun stop() {
        cameraController.stop()
    }

    override fun onCleared() {
        stop()
    }

    /** 分析线程回调：只做计算与投递，不直接写 UI 状态。 */
    private fun handleFrame(frame: FaceLandmarkFrame) {
        val face = frame.faces.firstOrNull()
        if (face == null) {
            frameStates.trySend(DetectionUiState())
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
            focalLengthPx = intrinsics.focalLengthPx(frame.sourceWidth, frame.sourceHeight)
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
        frameStates.trySend(
            DetectionUiState(
                faceDetected = true,
                detections = listOf(detection),
                distanceCm = distanceCm,
                blinkCount = blinkDetector.blinkCount,
                sourceWidth = frame.sourceWidth,
                sourceHeight = frame.sourceHeight,
            )
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
