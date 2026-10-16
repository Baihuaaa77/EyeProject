package com.zkyjss.eyeproject.feature.services

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * 干眼「视力是否随时间变差」的心理测量模型，取代原「视觉维持率 < 0.85」与「下降 ≥ 2 档」两条规则。
 *
 * 原规则只看阶梯轨迹：0.85 相当于不到一档，「2 档」等于连续两次答错；视力恒定的人约一半会被误判
 * （见 docs/三病种风险评估现状与改进方案-2026-10-16.md 附录 B）。这里改为对全部作答直接拟合
 *
 *   P(答对 | 视标 L, 时刻 t) = γ + (1 − γ − λ) · σ((L − θ(t)) / s)
 *   θ(t) = θ₀ + bN · min(t, T)/T + bO · max(0, t − T)/20
 *
 * L、θ 为 logMAR（越大视标越大），γ = 1/4（四选一猜中），λ 为偶发失误率；bN、bO 分别是自然眨眼段（T 秒）
 * 与持续睁眼段（20 秒）内的阈值变化，正值表示变差。网格上求后验（均匀先验），给出 P(b > 0.1 logMAR)。
 *
 * 判定（模拟标定：视力恒定者误判异常 ≤ 1%，真实下降 0.3 logMAR 约 2/3 判异常、其余多为不确定）：
 * P ≥ 0.9 异常；0.6 ≤ P < 0.9 不确定；P < 0.6 正常。
 */
object VisualStability {
    const val GUESS = 0.25
    const val LAPSE = 0.04
    const val SLOPE_LOGMAR = 0.06
    /** 有临床意义的视力变化下限（候选）：约一行视力表。 */
    const val DECLINE_LOGMAR = 0.1
    const val ABNORMAL_PROBABILITY = 0.9
    const val UNCERTAIN_PROBABILITY = 0.6

    private val thetaGrid = grid(-0.3, 1.3, 0.025)
    private val slopeGrid = grid(-0.4, 0.6, 0.05)

    /** 一题作答：相对正式测试开始的秒数、视标小数视力、是否答对。 */
    data class Response(val tSec: Double, val decimal: Double, val correct: Boolean)

    data class Fit(
        /** 测试开始时的阈值估计（logMAR）。 */
        val thetaLogMar: Double,
        /** 自然眨眼段、持续睁眼段的阈值变化（后验中位数，logMAR，正值变差）。 */
        val naturalChange: Double,
        val openChange: Double,
        /** 各段视力变差超过 [DECLINE_LOGMAR] 的后验概率。 */
        val naturalDeclineProbability: Double,
        val openDeclineProbability: Double,
    ) {
        val naturalStatus get() = status(naturalDeclineProbability)
        val openStatus get() = status(openDeclineProbability)
    }

    fun status(probability: Double): EvidenceStatus = when {
        probability >= ABNORMAL_PROBABILITY -> EvidenceStatus.ABNORMAL
        probability >= UNCERTAIN_PROBABILITY -> EvidenceStatus.UNCERTAIN
        else -> EvidenceStatus.NORMAL
    }

    fun fit(responses: List<Response>, naturalSeconds: Double, openSeconds: Double = DryEyeProtocol.OPEN_SECONDS.toDouble()): Fit? {
        if (responses.size < 8) return null
        val n = responses.size
        val levels = DoubleArray(n) { -kotlin.math.log10(responses[it].decimal) }
        val natural = DoubleArray(n) { (responses[it].tSec.coerceAtMost(naturalSeconds)) / naturalSeconds }
        val open = DoubleArray(n) { ((responses[it].tSec - naturalSeconds).coerceAtLeast(0.0)) / openSeconds }
        val correct = BooleanArray(n) { responses[it].correct }
        val nt = thetaGrid.size; val ns = slopeGrid.size
        val logLik = DoubleArray(nt * ns * ns)
        var best = Double.NEGATIVE_INFINITY
        for (a in 0 until nt) for (b in 0 until ns) for (c in 0 until ns) {
            var ll = 0.0
            for (i in 0 until n) {
                val theta = thetaGrid[a] + slopeGrid[b] * natural[i] + slopeGrid[c] * open[i]
                val p = GUESS + (1 - GUESS - LAPSE) / (1 + exp(-(levels[i] - theta) / SLOPE_LOGMAR))
                ll += ln(if (correct[i]) p else 1 - p)
            }
            val index = (a * ns + b) * ns + c
            logLik[index] = ll
            if (ll > best) best = ll
        }
        val thetaMass = DoubleArray(nt); val naturalMass = DoubleArray(ns); val openMass = DoubleArray(ns)
        var total = 0.0
        for (a in 0 until nt) for (b in 0 until ns) for (c in 0 until ns) {
            val w = exp(logLik[(a * ns + b) * ns + c] - best)
            thetaMass[a] += w; naturalMass[b] += w; openMass[c] += w; total += w
        }
        fun above(mass: DoubleArray) = slopeGrid.indices.filter { slopeGrid[it] > DECLINE_LOGMAR + 1e-9 }.sumOf { mass[it] } / total
        return Fit(
            thetaLogMar = median(thetaGrid, thetaMass, total),
            naturalChange = median(slopeGrid, naturalMass, total),
            openChange = median(slopeGrid, openMass, total),
            naturalDeclineProbability = above(naturalMass),
            openDeclineProbability = above(openMass),
        )
    }

    /** logMAR 变化换算成「约几行」的口语描述。 */
    fun lines(change: Double): String {
        val rows = change / 0.1
        return when {
            rows >= 0.5 -> "约变差 ${decimalString(rows, 1)} 行"
            rows <= -0.5 -> "约变好 ${decimalString(-rows, 1)} 行"
            else -> "基本稳定"
        }
    }

    fun decimalOf(logMar: Double): Double = 10.0.pow(-logMar)

    /** 阈值估计换算成小数视力；全部答对时估计只有下界，显示为「≥ 答对过的最小视标」。 */
    fun acuityText(thetaLogMar: Double, bestCorrectDecimal: Double?): String {
        val decimal = decimalOf(thetaLogMar)
        return if (bestCorrectDecimal != null && decimal > bestCorrectDecimal * 1.12) "≥ ${decimalString(bestCorrectDecimal, 1)}"
        else decimalString(decimal, 1)
    }

    private fun median(grid: DoubleArray, mass: DoubleArray, total: Double): Double {
        var acc = 0.0
        for (i in grid.indices) { acc += mass[i]; if (acc >= total / 2) return grid[i] }
        return grid.last()
    }

    private fun grid(from: Double, to: Double, step: Double): DoubleArray {
        val count = ((to - from) / step + 1e-9).toInt() + 1
        return DoubleArray(count) { from + it * step }
    }
}
