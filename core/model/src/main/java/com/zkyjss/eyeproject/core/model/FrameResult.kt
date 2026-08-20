package com.zkyjss.eyeproject.core.model

data class FrameResult(
    val detections: List<FaceEyeDetection>,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val timestampMillis: Long,
    val detectionLatencyMillis: Long,
    val fps: Float
) {
    val faceDetected: Boolean get() = detections.isNotEmpty()
    val faceCount: Int get() = detections.size
}
