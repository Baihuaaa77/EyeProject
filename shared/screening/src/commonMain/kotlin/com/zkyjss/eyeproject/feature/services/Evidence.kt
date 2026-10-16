package com.zkyjss.eyeproject.feature.services

import kotlin.math.sqrt

/*
 * 判定依据的统一结构（规则版本 *-rules-2 起）。
 *
 * 原则：测量噪声不能直接算成异常。每个指标先给出测量误差范围，再三向判定：
 * - 误差范围整体越过参考线 → 异常
 * - 测得值在参考范围内     → 正常
 * - 测得值越线但误差范围仍覆盖参考线 → 不确定（建议延长测试或复测）
 * 同一来源的指标归为一个「证据族」，按族计数，避免一个传感器故障凑出多项异常。
 */

enum class EvidenceStatus(val label: String) {
    NORMAL("正常"), ABNORMAL("异常"), UNCERTAIN("不确定"), UNAVAILABLE("无法评估"),
}

/** 一条判定依据（一个证据族）：一句结论 + 展开后的明细。 */
data class Evidence(
    val key: String,
    val title: String,
    val status: EvidenceStatus,
    val summary: String,
    val details: List<String> = emptyList(),
)

/** 一个族内多个指标的合成：有异常即异常；否则有不确定即不确定；全部无法评估才算无法评估。 */
fun combineStatus(statuses: List<EvidenceStatus>): EvidenceStatus = when {
    EvidenceStatus.ABNORMAL in statuses -> EvidenceStatus.ABNORMAL
    EvidenceStatus.UNCERTAIN in statuses -> EvidenceStatus.UNCERTAIN
    statuses.isEmpty() || statuses.all { it == EvidenceStatus.UNAVAILABLE } -> EvidenceStatus.UNAVAILABLE
    else -> EvidenceStatus.NORMAL
}

/** 带误差范围的三向判定：[low]..[high] 为测得值的 95% 范围，[value] 为测得值，正常范围为 [normalMin]..[normalMax]。 */
fun guardBand(value: Double, low: Double, high: Double, normalMin: Double = Double.NEGATIVE_INFINITY,
              normalMax: Double = Double.POSITIVE_INFINITY): EvidenceStatus = when {
    value in normalMin..normalMax -> EvidenceStatus.NORMAL
    high < normalMin || low > normalMax -> EvidenceStatus.ABNORMAL
    else -> EvidenceStatus.UNCERTAIN
}

/** 泊松计数 [n] 的近似 95% 区间（Byar 近似），用于眨眼次数。 */
fun poissonInterval(n: Int): Pair<Double, Double> {
    val z = 1.96
    val low = if (n == 0) 0.0 else n * cube(1 - 1.0 / (9 * n) - z / (3 * sqrt(n.toDouble())))
    val m = n + 1
    val high = m * cube(1 - 1.0 / (9 * m) + z / (3 * sqrt(m.toDouble())))
    return low.coerceAtLeast(0.0) to high
}

private fun cube(x: Double) = x * x * x

/** 判定依据写入测试记录（导出后可见每条结论的依据）。 */
internal fun List<Evidence>.toRecordArray(): RecordArray = RecordArray().also { array ->
    forEach { e ->
        array.put(RecordObject().apply {
            put("key", e.key); put("title", e.title); put("status", e.status.name)
            put("summary", e.summary); put("details", RecordArray(e.details))
        })
    }
}
