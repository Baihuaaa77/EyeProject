package com.zkyjss.eyeproject.feature.testmodel.blink

import com.zkyjss.eyeproject.core.model.BoundingBox

/** 眨眼测试页 UI 状态：相机预览 + 双眼 EAR + 眨眼计数 + 60s 检测录制。 */
data class BlinkTestUiState(
    val faceDetected: Boolean = false,
    val earLeft: Float? = null,
    val earRight: Float? = null,
    val earAverage: Float? = null,
    val eyeClosed: Boolean = false,
    val blinkCount: Int = 0,
    val faceBox: BoundingBox? = null,
    val leftEye: BlinkEyeLandmarks? = null,
    val rightEye: BlinkEyeLandmarks? = null,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val cameraRunning: Boolean = false,
    val detectionLatencyMillis: Long = 0L,
    val fps: Float = 0f,
    val error: String? = null,
    // ---- 60s 检测录制 ----
    val recording: Boolean = false,
    val recordRemainS: Int = 0,
    val savedPath: String? = null
)
