package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
import kotlinx.coroutines.flow.Flow

data class DisplayGeometry(val widthPixels: Int, val heightPixels: Int, val xdpi: Float, val ydpi: Float)
data class CameraGeometry(val imageWidth: Int, val imageHeight: Int, val focalLengthPx: Float, val version: Long = 0)

/** 系统能力端口。流程、计时、质量判断和记录格式都留在共用引擎。 */
interface ScreeningPlatform {
    val display: DisplayGeometry
    val platformName: String get() = "Android"
    val displaySizeSource: String get() = "ANDROID_DISPLAY_METRICS"
    val distanceSource: String get() = "UNCALIBRATED_FRONT_CAMERA_IRIS"
    fun nowMs(): Long
    fun epochMs(): Long
    fun cameraGeometry(width: Int, height: Int): CameraGeometry
    fun startCamera(onFrame: (FaceLandmarkFrame) -> Unit, onError: (Throwable) -> Unit)
    fun stopCamera()
    fun feedback(cue: ScreeningFeedbackCue) {}
    fun startLight(onLux: (Float) -> Unit): Boolean
    suspend fun saveRecord(name: String, json: String)
    suspend fun compareHistory(state: ScreeningState, assessment: ScreeningAssessment): List<String>
}

object OptotypeGeometry {
    data class Size(val sizePx: Float, val strokePx: Float, val displayable: Boolean)
    fun calculate(display: DisplayGeometry, decimalAcuity: Float, distanceCm: Float): Size {
        val pixelsPerMm = display.xdpi / 25.4f
        if (decimalAcuity <= 0f || distanceCm <= 0f || pixelsPerMm <= 0f || display.widthPixels <= 0 || display.heightPixels <= 0)
            return Size(0f, 0f, false)
        val angle = 5.0 / decimalAcuity / 60.0 * kotlin.math.PI / 180.0
        val pixels = (2.0 * distanceCm * 10.0 * kotlin.math.tan(angle / 2) * pixelsPerMm).toFloat()
        val stroke = pixels * 0.2f
        return Size(pixels, stroke, pixels.isFinite() && stroke >= 3f && pixels <= minOf(display.widthPixels, display.heightPixels) / 3f)
    }
}
