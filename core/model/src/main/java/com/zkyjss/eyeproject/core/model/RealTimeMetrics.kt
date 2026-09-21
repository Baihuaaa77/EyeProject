package com.zkyjss.eyeproject.core.model

/** 实时监测指标（供界面实时展示，刷新比 30 秒采样更即时）。 */
data class RealTimeMetrics(
    val faceDetected: Boolean = false,
    val distanceCm: Float = 0f,
    val blinkCount: Int = 0,
    val durationMin: Float = 0f,
    val ambientLux: Float = 300f,
    val screenBrightness: Int = 50,
    val totalRisk: Float = 0f,
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val advice: String = ""
)
