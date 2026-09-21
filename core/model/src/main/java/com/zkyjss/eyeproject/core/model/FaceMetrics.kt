package com.zkyjss.eyeproject.core.model

/** 单张人脸的疲劳检测指标。 */
data class FaceMetrics(
    val faceBox: BoundingBox,
    val leftEye: EyeLandmarks?,
    val rightEye: EyeLandmarks?
)
