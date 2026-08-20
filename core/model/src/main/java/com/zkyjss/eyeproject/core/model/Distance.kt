package com.zkyjss.eyeproject.core.model

enum class DistanceState {
    NEEDS_CALIBRATION,
    TOO_CLOSE,
    NORMAL,
    FAR,
    UNKNOWN
}

data class DistanceEstimate(
    val eyeDistancePx: Float?,
    val estimatedDistanceCm: Float?,
    val state: DistanceState,
    val confidence: Float,
    val method: String,
    val calibrated: Boolean
)
