package com.zkyjss.eyeproject.feature.services

import kotlin.math.abs

/** 用短窗口的稳健统计抑制虹膜关键点的逐帧跳动；不把平滑结果当作实物尺精度。 */
class ScreeningDistanceTracker {
    data class Estimate(
        val distanceCm: Float,
        val spreadCm: Float,
        val madCm: Float,
        val trendCm: Float,
        val samples: Int,
        val spanMs: Long,
    )

    private data class Sample(val timeMs: Long, val distanceCm: Float)
    private val samples = ArrayDeque<Sample>()

    fun clear() = samples.clear()

    fun add(timeMs: Long, distanceCm: Float) {
        if (!distanceCm.isFinite() || distanceCm !in 10f..100f) return
        if (samples.isNotEmpty() && timeMs < samples.last().timeMs) samples.clear()
        samples.addLast(Sample(timeMs, distanceCm))
        while (samples.isNotEmpty() && timeMs - samples.first().timeMs > HISTORY_MS) samples.removeFirst()
    }

    fun estimate(nowMs: Long): Estimate? {
        val window = samples.filter { it.timeMs in (nowMs - WINDOW_MS)..nowMs }
        if (window.isEmpty() || nowMs - window.last().timeMs > MAX_AGE_MS) return null
        val rawMedian = median(window.map { it.distanceCm })
        val rawMad = median(window.map { abs(it.distanceCm - rawMedian) })
        val inlierLimit = maxOf(3f, rawMad * 3f)
        val inliers = window.filter { abs(it.distanceCm - rawMedian) <= inlierLimit }
        val values = inliers.map { it.distanceCm }
        val middle = (inliers.first().timeMs + inliers.last().timeMs) / 2L
        val early = inliers.filter { it.timeMs <= middle }.map { it.distanceCm }
        val late = inliers.filter { it.timeMs > middle }.map { it.distanceCm }
        val sorted = values.sorted()
        val lo = sorted[((sorted.size - 1) * 0.1f).toInt()]
        val hi = sorted[((sorted.size - 1) * 0.9f).toInt()]
        val center = median(values)
        return Estimate(center, hi - lo, median(values.map { abs(it - center) }),
            if (early.isNotEmpty() && late.isNotEmpty()) median(late) - median(early) else 0f,
            inliers.size, inliers.last().timeMs - inliers.first().timeMs)
    }

    /**
     * 最近 [windowMs] 内距离变化速度（cm/秒，正值表示远离、负值表示靠近），
     * 用最小二乘斜率抑制逐帧跳动；样本不足时返回 null。
     */
    fun velocityCmPerSec(nowMs: Long, windowMs: Long = WINDOW_MS): Float? {
        val window = samples.filter { it.timeMs in (nowMs - windowMs)..nowMs }
        if (window.size < MIN_SAMPLES || window.last().timeMs - window.first().timeMs < MIN_SPAN_MS) return null
        val t0 = window.first().timeMs
        val xs = window.map { (it.timeMs - t0) / 1000.0 }
        val ys = window.map { it.distanceCm.toDouble() }
        val mx = xs.average()
        val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx <= 1e-9) return null
        val sxy = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
        return (sxy / sxx).toFloat()
    }

    fun stable(targetCm: Float, toleranceCm: Float, nowMs: Long): Boolean {
        val value = estimate(nowMs) ?: return false
        return value.samples >= MIN_SAMPLES && value.spanMs >= MIN_SPAN_MS &&
            abs(value.distanceCm - targetCm) <= toleranceCm &&
            value.madCm <= MAX_MAD_CM &&
            abs(value.trendCm) <= maxOf(2.5f, toleranceCm * 0.75f)
    }

    fun acceptableAnswer(targetCm: Float, presentedCm: Float, toleranceCm: Float, nowMs: Long): Boolean {
        val value = estimate(nowMs) ?: return false
        return stable(targetCm, toleranceCm + 0.5f, nowMs) &&
            abs(value.distanceCm - presentedCm) <= maxOf(4f, toleranceCm + 1f)
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2f else sorted[middle]
    }

    private companion object {
        const val HISTORY_MS = 2_000L
        const val WINDOW_MS = 1_000L
        const val MAX_AGE_MS = 700L
        const val MIN_SAMPLES = 4
        const val MIN_SPAN_MS = 400L
        const val MAX_MAD_CM = 3f
    }
}
