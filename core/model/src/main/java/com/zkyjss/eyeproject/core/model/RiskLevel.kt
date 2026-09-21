package com.zkyjss.eyeproject.core.model

/** 疲劳风险等级（阈值 0.30 / 0.70，与算法一致）。 */
enum class RiskLevel(val label: String) {
    LOW("低风险"),
    MEDIUM("中风险"),
    HIGH("高风险")
}
