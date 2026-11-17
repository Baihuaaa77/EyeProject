package com.zkyjss.eyeproject.feature.services

import kotlin.math.abs
import kotlin.math.hypot

/** 相机中各只可见眼的位置；坐标按画幅归一，直径按画幅宽度归一。不是注视方向。 */
internal data class IrisFramePosition(val eye: String, val x: Float, val y: Float, val diameter: Float, val aspect: Float)
internal data class StabilityReading(val reason: String? = null, val ready: Boolean = false, val displacement: Float = 0f)

/** 只检查图像整体平移/尺度及距离，避免把眼球相对眼角的正常转动当作手机移动。 */
internal class StrabismusStability {
    private var step: ScreeningStep? = null
    private val anchors = mutableMapOf<String, IrisFramePosition>()
    private var badSince: Long? = null
    private var badReason: String? = null
    private var recoveredSince: Long? = null
    fun clear() { step = null; anchors.clear(); badSince = null; badReason = null; recoveredSince = null }
    fun observe(time: Long, phase: ScreeningStep, positions: List<IrisFramePosition>, distance: Float,
                poseGood: Boolean, interrupted: Boolean): StabilityReading {
        if (step != phase) { clear(); step = phase }
        val distanceGood = distance.isFinite() && abs(distance-StrabismusProtocol.DISTANCE_CM)<=StrabismusProtocol.DISTANCE_GATE_CM
        val validPositions = positions.filter { it.diameter>0 && it.x.isFinite() && it.y.isFinite() }
        if (distanceGood && poseGood) validPositions.forEach { if (it.eye !in anchors) anchors[it.eye] = it }
        val differences = validPositions.mapNotNull { current -> anchors[current.eye]?.let { base ->
            hypot((current.x-base.x)/base.diameter,(current.y-base.y)*base.aspect/base.diameter) to (current.diameter/base.diameter)
        } }
        val displacement = differences.maxOfOrNull { it.first } ?: Float.POSITIVE_INFINITY
        val scaleGood = differences.isNotEmpty() && differences.all { it.second in 0.82f..1.22f }
        val reason = when {
            !distance.isFinite() || distance<=0f -> "暂时无法测距，请正对摄像头"
            !distanceGood -> if (distance<StrabismusProtocol.DISTANCE_CM) "距离太近，请将手机移到约33厘米" else "距离太远，请将手机移到约33厘米"
            validPositions.isEmpty() -> "暂时看不清双眼，请保持手机和注视稳定"
            !poseGood -> "请把头摆正，并保持手机和注视稳定"
            displacement>0.60f || !scaleGood -> "虹膜位置变化较大，请保持手机和注视稳定"
            else -> null
        }
        if (interrupted) {
            val restored = reason == null && displacement<=0.30f && differences.all { it.second in 0.90f..1.10f }
            if (!restored) recoveredSince = null else if (recoveredSince == null) recoveredSince = time
            return StabilityReading(reason = reason,ready = recoveredSince?.let { time-it>=700L } == true,displacement = displacement)
        }
        recoveredSince = null
        if (reason == null) { badSince = null; badReason = null; return StabilityReading(ready = differences.isNotEmpty(),displacement = displacement) }
        if (badReason != reason) { badReason = reason; badSince = time }
        val confirmation = if (reason.startsWith("虹膜")) 150L else 800L
        return StabilityReading(reason = reason.takeIf { time-(badSince ?: time)>=confirmation },displacement = displacement)
    }
}
