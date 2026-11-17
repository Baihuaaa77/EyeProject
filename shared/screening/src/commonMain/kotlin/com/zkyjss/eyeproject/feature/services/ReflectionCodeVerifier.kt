package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.model.FaceMetrics
import com.zkyjss.eyeproject.core.model.OpticalEyeMeasurement
import kotlin.math.hypot

/** 短光码只核查当前光学候选是否随屏幕亮灭；未通过也不阻断测试。 */
internal class ReflectionCodeVerifier {
    companion object {
        const val CHIP_MS = 300L
        private val bits = listOf(true,false,true,true,false,false,true,false, false,true,false,true,true,false,false,true)
        const val DURATION_MS = 4_800L
        fun lightOn(elapsed: Long): Boolean = bits.getOrNull((elapsed.coerceAtLeast(0L)/CHIP_MS).toInt()) ?: true
    }
    private data class Display(val time: Long, val on: Boolean, val chip: Int)
    private data class Reading(val chip: Int, val on: Boolean, val signal: Double, val x: Float?, val y: Float?)
    private val display = mutableListOf<Display>()
    private val left = mutableListOf<Reading>()
    private val right = mutableListOf<Reading>()
    fun clear() { display.clear(); left.clear(); right.clear() }
    fun present(time: Long, on: Boolean, elapsed: Long) {
        val chip = (elapsed/CHIP_MS).toInt()
        if (chip !in bits.indices || display.lastOrNull()?.chip == chip) return
        display += Display(time,on,chip)
    }
    fun observe(time: Long, face: FaceMetrics?) {
        face ?: return
        val event = display.lastOrNull { it.time <= time } ?: return
        // 避开曝光跨越切换的帧，不使用尚未上屏的计划亮度。
        if (time-event.time !in 120L..CHIP_MS || left.size >= 500 || right.size >= 500) return
        fun add(target: MutableList<Reading>, optical: OpticalEyeMeasurement?) {
            optical ?: return
            if (optical.irisDiameterPx < 12f || optical.irisVisibilityContrast?.let { it >= 8f } != true) return
            target += Reading(event.chip,event.on,if (optical.usable) optical.reflexContrast.toDouble() else 0.0,
                optical.horizontalIrisUnits,optical.verticalIrisUnits)
        }
        add(left,face.leftOptical); add(right,face.rightOptical)
    }
    private fun verified(input: List<Reading>): Boolean {
        fun half(range: IntRange): Boolean {
            val chips = input.filter { it.chip in range }.groupBy { it.chip }.values
                .map { rows -> rows.first().on to rows.map { it.signal }.average() }
            val on = chips.filter { it.first }.map { it.second }
            val off = chips.filter { !it.first }.map { it.second }
            if (on.size < 3 || off.size < 3 || on.average()-off.average() < 20.0) return false
            return (StrabismusMeasures.correlation(chips.map { if (it.first) 1.0 else 0.0 },chips.map { it.second }) ?: 0.0) >= 0.65
        }
        val points = input.filter { it.on && it.x != null && it.y != null }
        if (points.isEmpty()) return false
        val x = points.map { it.x!!.toDouble() }.average(); val y = points.map { it.y!!.toDouble() }.average()
        if (points.count { hypot(it.x!!-x,it.y!!-y) <= 0.20 } < points.size*0.8) return false
        return half(0..7) && half(8..15)
    }
    val leftVerified get() = verified(left)
    val rightVerified get() = verified(right)
    fun record() = RecordObject().apply {
        put("version","brief-display-code-1"); put("durationMs",DURATION_MS)
        put("leftVerified",leftVerified); put("rightVerified",rightVerified)
        put("leftSamples",left.size); put("rightSamples",right.size)
        put("displayedChips",display.size)
    }
}
