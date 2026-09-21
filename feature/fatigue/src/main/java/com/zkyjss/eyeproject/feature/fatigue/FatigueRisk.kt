package com.zkyjss.eyeproject.feature.fatigue

import com.zkyjss.eyeproject.core.model.RiskLevel

/** 单因子评估结果。 */
data class FactorScore(
    val value: Double,
    val label: String,
    val advice: String
)

/** 派生指标（供界面展示）。 */
data class RiskMetrics(
    val continuousDurationMin: Double,
    val cumulativeDurationMin: Double,
    val blinkRatePerMin: Double,
    val meanDistanceCm: Double,
    val meanAmbientLux: Double,
    val meanScreenBrightness: Double,
    val sampleCount: Int,
    val episodeCount: Int
)

/** 一次用眼风险评估结果（各项指标 + 目标输出）。 */
data class FatigueRisk(
    val totalRisk: Double,
    val level: RiskLevel,
    val advice: String,
    val linearRaw: Double,
    val combinerName: String,
    val factors: Map<String, FactorScore>,
    val features: DoubleArray,
    val metrics: RiskMetrics
) {
    companion object {
        private val FACTOR_KEYS = listOf(
            "light", "distance", "time", "blink", "personal", "feedback"
        )
        private val FACTOR_LABELS = listOf(
            "光照/亮度", "用眼距离", "用眼时长", "眨眼/干眼", "个人易感", "主观不适"
        )

        /**
         * 把 JNI 输出映射为领域模型。
         * numeric 布局（double[27]）：0 totalRisk, 1 levelCode, 2 linearRaw, 3..8 六个因子风险,
         * 9..16 派生指标, 17..26 特征向量(10)。
         * text 布局（String[8]）：0 等级, 1 总体建议, 2..7 六个因子建议。
         */
        fun fromOutput(numeric: DoubleArray, text: Array<String>, combiner: String): FatigueRisk {
            fun d(i: Int): Double = if (i < numeric.size) numeric[i] else 0.0
            val total = d(0)
            val level = when {
                total < 0.3 -> RiskLevel.LOW
                total < 0.7 -> RiskLevel.MEDIUM
                else -> RiskLevel.HIGH
            }
            val factors = FACTOR_KEYS.mapIndexed { i, key ->
                key to FactorScore(
                    value = d(3 + i),
                    label = FACTOR_LABELS[i],
                    advice = if (2 + i < text.size) text[2 + i] else ""
                )
            }.toMap()
            return FatigueRisk(
                totalRisk = total,
                level = level,
                advice = if (text.size > 1) text[1] else "",
                linearRaw = d(2),
                combinerName = combiner,
                factors = factors,
                features = DoubleArray(10) { d(17 + it) },
                metrics = RiskMetrics(
                    continuousDurationMin = d(9),
                    cumulativeDurationMin = d(10),
                    blinkRatePerMin = d(11),
                    meanDistanceCm = d(12),
                    meanAmbientLux = d(13),
                    meanScreenBrightness = d(14),
                    sampleCount = d(15).toInt(),
                    episodeCount = d(16).toInt()
                )
            )
        }
    }
}
