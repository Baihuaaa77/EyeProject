package com.zkyjss.eyeproject.feature.testmodel.standalone

import com.zkyjss.eyeproject.core.model.BoundingBox

/** 全替换眨眼页 UI 状态：BlazeFace 检测 + OCEC 分类（无 MediaPipe）。 */
data class StandaloneBlinkUiState(
    val faceDetected: Boolean = false,
    val faceBox: BoundingBox? = null,
    val rightEyeBox: BoundingBox? = null,
    val rightProb: Float? = null,
    val leftEyeBox: BoundingBox? = null,
    val leftProb: Float? = null,
    val blinkCount: Int = 0,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val cameraRunning: Boolean = false,
    val detectionLatencyMillis: Long = 0L,
    val classificationLatencyMillis: Long = 0L,
    val fps: Float = 0f,
    val error: String? = null,
    // ---- 60s 检测录制 ----
    val recording: Boolean = false,
    val recordRemainS: Int = 0,
    val savedPath: String? = null
)
