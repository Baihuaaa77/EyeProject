package com.zkyjss.eyeproject.core.model

enum class AnalysisResolution(
    val key: String,
    val width: Int,
    val height: Int
) {
    LOW_POWER("low_power", 320, 240),
    BALANCED("balanced", 640, 480),
    HIGH_QUALITY("high_quality", 1280, 720);

    companion object {
        fun fromKey(key: String?): AnalysisResolution =
            entries.firstOrNull { it.key == key } ?: BALANCED
    }
}
