package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.model.*
import kotlin.math.hypot

/** 以实绘明暗作参考，在虹膜局部坐标中逐候选位置校验；前半段选点，后半段独立复核。 */
internal class ReflectionCodeVerifier {
    companion object {
        const val CHIP_MS = 300L
        private val bits = listOf(true,false,true,true,false,false,true,false, false,true,false,true,true,false,false,true)
        const val DURATION_MS = 4_800L
        fun lightOn(elapsed: Long): Boolean = bits.getOrNull((elapsed.coerceAtLeast(0L)/CHIP_MS).toInt()) ?: true
    }

    private data class Display(val time: Long, val on: Boolean, val chip: Int)
    private data class Spot(val point: Point2f, val signal: Float)
    private data class Reading(val chip: Int, val on: Boolean, val spots: List<Spot>)
    private data class Lock(val point: Point2f, val correlation: Double)
    private class Phase {
        val display = mutableListOf<Display>()
        val left = mutableListOf<Reading>()
        val right = mutableListOf<Reading>()
        var leftLock: Lock? = null
        var rightLock: Lock? = null
        var finalized = false
    }
    private val phasesByStep = mutableMapOf<ScreeningStep, Phase>()

    fun clear() = phasesByStep.clear()
    fun active(step: ScreeningStep) = step in phasesByStep
    fun begin(step: ScreeningStep) { phasesByStep[step] = Phase() }

    fun present(step: ScreeningStep, time: Long, on: Boolean, elapsed: Long) {
        val phase = phasesByStep[step] ?: return
        val chip = (elapsed/CHIP_MS).toInt()
        if (elapsed !in 0L until DURATION_MS || phase.display.lastOrNull()?.chip == chip || on != bits[chip]) return
        phase.display += Display(time,on,chip)
    }

    fun observe(step: ScreeningStep, time: Long, face: FaceMetrics?) {
        val phase = phasesByStep[step] ?: return
        face ?: return
        val event = phase.display.lastOrNull { it.time <= time } ?: return
        // 留出曝光过渡区，只用已有显示反馈的芯片。
        if (time-event.time !in 100L until CHIP_MS || phase.left.size >= 500 || phase.right.size >= 500) return
        fun add(target: MutableList<Reading>, optical: OpticalEyeMeasurement?, eye: EyeLandmarks?) {
            val center = eye?.irisPoints?.firstOrNull() ?: return
            if (optical == null || optical.irisDiameterPx < 12f || (optical.irisVisibilityContrast ?: 0f) < 8f) return
            target += Reading(event.chip,event.on,optical.reflexCandidates.map {
                Spot(normalized(it.point,center,optical.irisDiameterPx),it.contrast)
            })
        }
        add(phase.left,face.leftOptical,face.leftEye); add(phase.right,face.rightOptical,face.rightEye)
    }

    private fun decode(input: List<Reading>): Lock? {
        val seeds = input.filter { it.on && it.chip < 8 }.flatMap { it.spots }
            .map { Point2f(kotlin.math.round(it.point.x * 25) / 25, kotlin.math.round(it.point.y * 25) / 25) }.distinct()
        val (training, validation) = input.groupBy { it.chip }.values.partition { it.first().chip < 8 }
        fun score(point: Point2f, groups: List<List<Reading>>): Double? {
            val chips = groups.map { rows ->
                rows.first().on to rows.map { row ->
                    row.spots.filter { distance(it.point,point) <= 0.10f }.maxOfOrNull { it.signal }?.toDouble() ?: 0.0
                }.average()
            }
            val on = chips.filter { it.first }.map { it.second }
            val off = chips.filter { !it.first }.map { it.second }
            if (on.size < 3 || off.size < 3 || on.average()-off.average() < 16.0) return null
            return StrabismusMeasures.correlation(chips.map { if (it.first) 1.0 else 0.0 },chips.map { it.second })
                ?.takeIf { it >= 0.65 }
        }
        val best = seeds.mapNotNull { point -> score(point,training)?.let { Lock(point,it) } }
            .maxByOrNull { it.correlation } ?: return null
        val heldOutCorrelation = score(best.point,validation) ?: return null
        return best.copy(correlation = minOf(best.correlation,heldOutCorrelation))
    }

    fun measure(step: ScreeningStep, eye: EyeLandmarks?, optical: OpticalEyeMeasurement?, isLeft: Boolean): OpticalEyeMeasurement? {
        val phase = phasesByStep[step] ?: return optical
        if (!phase.finalized) {
            phase.leftLock = decode(phase.left); phase.rightLock = decode(phase.right); phase.finalized = true
        }
        val lock = (if (isLeft) phase.leftLock else phase.rightLock) ?: return optical
        optical ?: return null
        val center = eye?.irisPoints?.firstOrNull() ?: return optical
        // 保持对同一光源的约束，同时容许去遮盖后的再注视。每帧仍需实际检出候选。
        val matches = optical.reflexCandidates.map { it to distance(normalized(it.point,center,optical.irisDiameterPx),lock.point) }
        val candidate = matches.filter { it.second <= if (step == ScreeningStep.COVER) 0.24f else 0.17f }
            .maxByOrNull { (spot,offset) -> spot.contrast - 300f * offset }
        if (candidate == null) return optical.copy(cornealReflex = null, reflexContrast = 0f, reflexConfidence = null,
            rejectionReason = "已确认的屏幕映光在当前帧缺失")
        return optical.copy(cornealReflex = candidate.first.point, reflexContrast = candidate.first.contrast,
            rejectionReason = null, reflexCodeMatched = true,
            reflexConfidence = (0.50 + 0.30 * lock.correlation + candidate.first.contrast.coerceAtMost(120f) / 600 - candidate.second).toFloat().coerceIn(0f, 1f))
    }

    private fun normalized(point: Point2f, center: Point2f, diameter: Float) =
        Point2f((point.x-center.x)/diameter,(point.y-center.y)/diameter)
    private fun distance(a: Point2f, b: Point2f) = hypot(a.x-b.x,a.y-b.y)

    fun record() = RecordObject().apply {
        put("version","position-display-code-2"); put("durationMs",DURATION_MS)
        phasesByStep.forEach { (step,phase) -> put(step.name, RecordObject().apply {
            put("leftVerified",phase.leftLock != null); put("rightVerified",phase.rightLock != null)
            put("leftSamples",phase.left.size); put("rightSamples",phase.right.size)
            put("displayedChips",phase.display.size)
        }) }
    }
}
