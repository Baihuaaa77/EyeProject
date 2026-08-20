package com.zkyjss.eyeproject.core.model

data class FaceEyeDetection(
    val faceId: Int,
    val faceBox: BoundingBox,
    val leftEye: EyeDetection?,
    val rightEye: EyeDetection?,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val rotationDegrees: Int,
    val timestampMillis: Long,
    val detectionLatencyMillis: Long,
    val fps: Float
)
