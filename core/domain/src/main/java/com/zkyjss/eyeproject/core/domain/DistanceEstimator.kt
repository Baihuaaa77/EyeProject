package com.zkyjss.eyeproject.core.domain

import com.zkyjss.eyeproject.core.model.DistanceEstimate
import com.zkyjss.eyeproject.core.model.DistanceState
import com.zkyjss.eyeproject.core.model.Point2f
import kotlin.math.hypot

/**
 * 基于 40cm 标定比例法估算观看距离。
 *
 * 公式：当前距离 = 标定距离 * 标定时双眼像素距离 / 当前双眼像素距离
 * 优先使用虹膜中心（MediaPipe 468/473）作为眼中心，比旧 ML Kit 轮廓中心更稳定。
 */
class DistanceEstimator(
    private val calibrationDistanceCm: Float = DEFAULT_CALIBRATION_DISTANCE_CM,
    private val tooCloseCm: Float = TOO_CLOSE_CM,
    private val farCm: Float = FAR_CM,
    calibratedEyeDistancePx: Float? = null
) {
    private var calibratedEyeDistancePx: Float? = calibratedEyeDistancePx
    private var smoothedDistanceCm: Float? = null

    fun setCalibration(eyeDistancePx: Float) {
        if (eyeDistancePx > MIN_VALID_EYE_DISTANCE_PX) {
            calibratedEyeDistancePx = eyeDistancePx
            smoothedDistanceCm = calibrationDistanceCm
        }
    }

    fun getCalibrationEyeDistancePx(): Float? = calibratedEyeDistancePx

    fun estimate(
        leftEyeCenter: Point2f?,
        rightEyeCenter: Point2f?,
        sourceWidth: Int,
        faceCount: Int
    ): DistanceEstimate {
        val eyeDistancePx = calculateEyeDistancePx(leftEyeCenter, rightEyeCenter)
        if (eyeDistancePx == null || eyeDistancePx < MIN_VALID_EYE_DISTANCE_PX) {
            return DistanceEstimate(
                eyeDistancePx = eyeDistancePx,
                estimatedDistanceCm = null,
                state = DistanceState.UNKNOWN,
                confidence = 0f,
                method = METHOD_CALIBRATED_IPD,
                calibrated = calibratedEyeDistancePx != null
            )
        }

        val calibration = calibratedEyeDistancePx
        if (calibration == null) {
            return DistanceEstimate(
                eyeDistancePx = eyeDistancePx,
                estimatedDistanceCm = null,
                state = DistanceState.NEEDS_CALIBRATION,
                confidence = estimateConfidence(eyeDistancePx, sourceWidth, faceCount),
                method = METHOD_CALIBRATED_IPD,
                calibrated = false
            )
        }

        val rawDistanceCm = calibrationDistanceCm * calibration / eyeDistancePx
        val previous = smoothedDistanceCm
        val distanceCm = if (previous == null) {
            rawDistanceCm
        } else {
            previous + SMOOTHING_ALPHA * (rawDistanceCm - previous)
        }
        smoothedDistanceCm = distanceCm

        val confidence = estimateConfidence(eyeDistancePx, sourceWidth, faceCount)
        val state = when {
            confidence < 0.35f -> DistanceState.UNKNOWN
            distanceCm < tooCloseCm -> DistanceState.TOO_CLOSE
            distanceCm > farCm -> DistanceState.FAR
            else -> DistanceState.NORMAL
        }

        return DistanceEstimate(
            eyeDistancePx = eyeDistancePx,
            estimatedDistanceCm = distanceCm,
            state = state,
            confidence = confidence,
            method = METHOD_CALIBRATED_IPD,
            calibrated = true
        )
    }

    fun calculateEyeDistancePx(leftEyeCenter: Point2f?, rightEyeCenter: Point2f?): Float? {
        if (leftEyeCenter == null || rightEyeCenter == null) {
            return null
        }
        return hypot(
            leftEyeCenter.x - rightEyeCenter.x,
            leftEyeCenter.y - rightEyeCenter.y
        )
    }

    private fun estimateConfidence(
        eyeDistancePx: Float,
        sourceWidth: Int,
        faceCount: Int
    ): Float {
        if (sourceWidth <= 0 || faceCount <= 0) {
            return 0f
        }
        val ratio = eyeDistancePx / sourceWidth
        val geometryConfidence = when {
            ratio < 0.05f -> 0.35f
            ratio > 0.45f -> 0.45f
            else -> 0.9f
        }
        val faceConfidence = if (faceCount == 1) 1f else 0.65f
        return geometryConfidence * faceConfidence
    }

    companion object {
        const val DEFAULT_CALIBRATION_DISTANCE_CM = 40f
        private const val TOO_CLOSE_CM = 30f
        private const val FAR_CM = 70f
        private const val MIN_VALID_EYE_DISTANCE_PX = 12f
        private const val SMOOTHING_ALPHA = 0.35f
        const val METHOD_CALIBRATED_IPD = "calibrated_eye_center_distance"
    }
}
