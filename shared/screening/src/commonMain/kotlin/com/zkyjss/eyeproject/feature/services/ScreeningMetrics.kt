package com.zkyjss.eyeproject.feature.services

import kotlin.math.abs

/** 仅对本机实际采到的数据作描述统计，不输出医学诊断。 */
object PresbyopiaMetrics {
    fun summarize(answers: List<VisionAnswer>, points: List<NearPoint>, curve: List<CurvePoint>, resolutionLimitedEyes: Set<VisionEye>): List<String> {
        val near = answers.filter { it.step == ScreeningStep.NEAR_VISION }
        val flip = answers.filter { it.step == ScreeningStep.FLIPPER }
        val valid = points.filter { !it.censored }
        val sorted = valid.map { it.distanceCm }.sorted()
        val medianPoint = sorted.takeIf { it.isNotEmpty() }?.get(sorted.size / 2)
        val spread = medianPoint?.let { mid -> valid.map { abs(it.distanceCm - mid) }.sorted()[valid.size / 2] }
        val correctCycles = flip.chunked(2).count { it.size == 2 && it.all(VisionAnswer::correct) }
        return buildList {
            VisionEye.entries.forEach { eye ->
                val eyeAnswers = near.filter { it.eye == eye }
                val threshold = eyeAnswers.groupBy { it.level }
                    .filterValues { group -> group.size == 4 && group.count { it.correct } >= 3 }.keys.maxOrNull()
                add("40 cm ${eye.label}近视力：${threshold?.let { "最高通过 $it" } ?: "未通过测试等级"}；${eyeAnswers.count { it.correct }}/${eyeAnswers.size} 题正确${if (eye in resolutionLimitedEyes) "；更小视标受屏幕像素限制" else ""}")
            }
            add("移近法近点：${points.joinToString("、") { p -> if (p.censored) "< ${PresbyopiaProtocol.PUSH_MIN_CM.toInt()} cm" else "%.1f cm".reportFormat(p.distanceCm) + (p.speedCmPerSec?.let { "（%.1f cm/秒）".reportFormat(it) } ?: "") }}")
            add("近点中位数 ${medianPoint?.let { "%.1f cm".reportFormat(it) } ?: "—"}；重复差异中位数 ${spread?.let { "%.1f cm".reportFormat(it) } ?: "—"}；调节幅度约 ${medianPoint?.let { "%.2f D".reportFormat(100.0 / it) } ?: "—"}")
            add("距离—视觉表现：40 cm ${near.filter { it.eye == VisionEye.BOTH }.groupBy { it.level }.filterValues { g -> g.size == 4 && g.count { it.correct } >= 3 }.keys.maxOrNull() ?: "—"} · " +
                curve.joinToString(" · ") { "${it.distanceCm.toInt()} cm ${it.thresholdDecimal ?: "—"}${if (it.resolutionLimited) "（像素受限）" else ""}" })
            add("调节灵敏度（真实远近移动 50/25 cm）：60 秒 $correctCycles 个正确循环；错误 ${flip.count { !it.correct }} 次；平均移动稳定 ${flip.mapNotNull { it.movementMs }.average().takeIf { !it.isNaN() }?.let { "%.0f ms".reportFormat(it) } ?: "—"}；平均反应 ${flip.mapNotNull { it.clarityMs }.average().takeIf { !it.isNaN() }?.let { "%.0f ms".reportFormat(it) } ?: "—"}")
        }
    }
}

object StrabismusMetrics {
    fun summarize(samples: List<EyeMotionSample>, marks: List<CoverMark>, total: Int, valid: Int,
                  eligible: Int, eligibleValid: Int, pursuitMaxDeg: Pair<Float, Float>?): List<String> {
        val m = StrabismusMeasures.compute(samples, marks)
        val quality = if (eligible > 0) 100f * eligibleValid / eligible else 0f
        fun f(v: Double?) = v?.let { "%.1f".reportFormat(it) } ?: "—"
        return buildList {
            add("有效帧 $valid/$total；双眼可测阶段 $eligibleValid/$eligible（${"%.0f".reportFormat(quality)}%）；注视最长连续有效 ${"%.1f".reportFormat(m.fixationContinuousMs / 1000f)} 秒")
            add("注视（角膜映光）：HDE ${f(m.hdePrism)}Δ ± ${f(m.hdeSdPrism)}；VDE ${f(m.vdePrism)}Δ ± ${f(m.vdeSdPrism)}；有效 ${m.fixationFrames} 帧")
            add("交替遮盖：确认 ${marks.size}/${StrabismusProtocol.COVER_SEQUENCE.size} 个动作；再注视事件 ${m.refixations.size} 次")
            add("追踪：r_h ${f(m.rH)} · r_v ${f(m.rV)} · d_norm ${f(m.dNorm)} · BSI ${f(m.bsi)}")
            pursuitMaxDeg?.let { (x, y) ->
                if (x < StrabismusProtocol.PURSUIT_AMPLITUDE_DEG || y < StrabismusProtocol.PURSUIT_AMPLITUDE_DEG)
                    add("本机屏幕在 33 cm 处最多显示水平 ±${"%.1f".reportFormat(x)}°、垂直 ±${"%.1f".reportFormat(y)}°，原文 ±10° 轨迹已按屏幕缩小")
            }
        }
    }
}
