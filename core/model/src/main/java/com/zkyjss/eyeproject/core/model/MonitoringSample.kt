package com.zkyjss.eyeproject.core.model

/** 一次定时采样记录（每 30 秒一条），用于持久化与历史曲线展示。 */
data class MonitoringSample(
    val timestampMillis: Long,
    val ambientLux: Float,
    val screenBrightness: Int,
    val distanceCm: Float,
    val durationMin: Float,
    val blinkCount: Int
)
