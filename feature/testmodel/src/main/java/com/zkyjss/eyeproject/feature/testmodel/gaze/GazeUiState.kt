package com.zkyjss.eyeproject.feature.testmodel.gaze

import com.zkyjss.eyeproject.core.model.BoundingBox

data class GazeUiState(
    val modelInfo: ModelInfo? = null,
    val modelError: String? = null,
    val latestInference: InferenceResult? = null,
    val faceDetected: Boolean = false,
    val yawDeg: Float? = null,
    val pitchDeg: Float? = null,
    val faceBox: BoundingBox? = null,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val cameraRunning: Boolean = false,
    val detectionLatencyMillis: Long = 0L,
    val fps: Float = 0f,


    val useAndroidApi: Boolean = false
)
