package com.zkyjss.eyeproject.feature.testmodel

data class TestModelUiState(
    val modelInfo: ModelInfo? = null,
    val modelError: String? = null,
    val latestInference: InferenceResult? = null,
    val yawDeg: Float? = null,
    val pitchDeg: Float? = null,
    val cameraRunning: Boolean = false,
    val fps: Float = 0f
)
