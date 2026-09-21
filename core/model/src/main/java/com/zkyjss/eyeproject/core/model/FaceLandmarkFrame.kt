package com.zkyjss.eyeproject.core.model

/** FaceLandmarker 逐帧输出（人脸框 + 头姿 + 双眼 EAR/虹膜关键点）。 */
data class FaceLandmarkFrame(
    val faces: List<FaceMetrics>,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val timestampMillis: Long,
    val detectionLatencyMillis: Long,
    val fps: Float
) {
    val faceDetected: Boolean get() = faces.isNotEmpty()
}
