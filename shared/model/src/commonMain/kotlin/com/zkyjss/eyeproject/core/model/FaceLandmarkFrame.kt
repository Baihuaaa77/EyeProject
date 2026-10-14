package com.zkyjss.eyeproject.core.model

/** FaceLandmarker 逐帧输出（人脸框 + 头姿 + 双眼 EAR/虹膜关键点）。 */
data class FaceLandmarkFrame(
    val faces: List<FaceMetrics>,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val timestampMillis: Long,
    val detectionLatencyMillis: Long,
    val fps: Float,
    /** 相机图像采样亮度 0–255；仅作无 lux 传感器时的相对光照参考。 */
    val imageBrightness: Float? = null,
    /** 图像进入分析器的单调时钟时间，早于异步模型回调；尚非相机曝光时间。 */
    val analysisElapsedRealtimeMs: Long? = null,
) {
    val faceDetected: Boolean get() = faces.isNotEmpty()
}
