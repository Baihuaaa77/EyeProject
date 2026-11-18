package com.zkyjss.eyeproject.feature.services

import kotlin.math.abs
import kotlin.math.hypot
import com.zkyjss.eyeproject.core.model.EyeLandmarks

/** 使用眼角中点和眼宽追踪手机/头部移动；虹膜转动不会改变这个参考位置。 */
internal data class EyeFramePosition(val eye: String, val x: Float, val y: Float, val width: Float, val aspect: Float)
internal fun eyeFramePosition(eye: EyeLandmarks, frameWidth: Int, frameHeight: Int): EyeFramePosition? {
    if (frameWidth <= 0 || frameHeight <= 0) return null
    val a = eye.earPoints.getOrNull(0) ?: return null
    val b = eye.earPoints.getOrNull(3) ?: return null
    return EyeFramePosition(eye.label,(a.x+b.x)/2/frameWidth,(a.y+b.y)/2/frameHeight,
        hypot(a.x-b.x,a.y-b.y)/frameWidth,frameHeight.toFloat()/frameWidth)
}
internal data class StabilityReading(val reason: String? = null, val ready: Boolean = false, val displacement: Float = 0f)

/** 只检查图像整体平移/尺度及距离，避免把眼球相对眼角的正常转动当作手机移动。 */
internal class StrabismusStability {
    private var step: ScreeningStep? = null
    private val anchors = mutableMapOf<String, EyeFramePosition>()
    private var badSince: Long? = null
    private var badReason: String? = null
    private var recoveredSince: Long? = null
    fun clear() { step = null; anchors.clear(); badSince = null; badReason = null; recoveredSince = null }
    fun observe(time: Long, phase: ScreeningStep, positions: List<EyeFramePosition>, distance: Float,
                poseGood: Boolean, interrupted: Boolean): StabilityReading {
        if (phase !in setOf(ScreeningStep.FIXATION,ScreeningStep.FAR_FIXATION,ScreeningStep.COVER,ScreeningStep.PURSUIT)) {
            clear(); return StabilityReading(ready = true)
        }
        if (step != phase) { clear(); step = phase }
        val targetCm = if (phase == ScreeningStep.PURSUIT) StrabismusProtocol.PURSUIT_DISTANCE_CM else StrabismusProtocol.DISTANCE_CM
        val distanceGood = distance.isFinite() && abs(distance - targetCm) <= StrabismusProtocol.DISTANCE_GATE_CM
        val validPositions = positions.filter { it.width.isFinite() && it.width>0 && it.x.isFinite() && it.y.isFinite() }
        if (distanceGood && poseGood) validPositions.forEach { if (it.eye !in anchors) anchors[it.eye] = it }
        val differences = validPositions.mapNotNull { current -> anchors[current.eye]?.let { base ->
            hypot((current.x-base.x)/base.width,(current.y-base.y)*base.aspect/base.width) to (current.width/base.width)
        } }
        val displacement = differences.map { it.first }.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: Float.POSITIVE_INFINITY
        val scaleGood = differences.isNotEmpty() && differences.all { it.second in 0.80f..1.25f }
        val reason = when {
            !distance.isFinite() || distance<=0f -> "暂时无法测距，请正对摄像头"
            !distanceGood -> if (distance < targetCm) "距离太近，请将手机移到约${targetCm.toInt()}厘米" else "距离太远，请将手机移到约${targetCm.toInt()}厘米"
            validPositions.isEmpty() -> "暂时看不清双眼，请保持手机和注视稳定"
            !poseGood -> "请把头摆正，并保持手机和注视稳定"
            displacement>0.50f || !scaleGood -> "手机或头部位置变化较大，请保持手机和注视稳定"
            else -> null
        }
        if (interrupted) {
            val restored = reason == null && displacement<=0.30f && differences.all { it.second in 0.88f..1.15f }
            if (!restored) recoveredSince = null else if (recoveredSince == null) recoveredSince = time
            return StabilityReading(reason = reason,ready = recoveredSince?.let { time-it>=700L } == true,displacement = displacement)
        }
        recoveredSince = null
        if (reason == null) { badSince = null; badReason = null; return StabilityReading(ready = differences.isNotEmpty(),displacement = displacement) }
        if (badReason != reason) { badReason = reason; badSince = time }
        val confirmation = if (reason.startsWith("手机或头部")) 600L else 800L
        return StabilityReading(reason = reason.takeIf { time-(badSince ?: time)>=confirmation },displacement = displacement)
    }
}
