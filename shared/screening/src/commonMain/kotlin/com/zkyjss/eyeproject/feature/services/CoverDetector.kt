package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.domain.BlinkDetector
import com.zkyjss.eyeproject.core.model.FaceMetrics

/** 单侧眼区像素可见性检测。以未遮盖帧建立个人基线；眨眼、闭眼、双眼丢失和质量不符均不触发。 */
internal class CoverDetector {
    data class Observation(val covered: VisionEye?, val sinceMs: Long, val confirmedAtMs: Long, val releasedAtMs: Map<VisionEye, Long>)
    private var leftBaseline: Float? = null
    private var rightBaseline: Float? = null
    private var candidate: Int? = null
    private var since = 0L
    private var count = 0
    private var lastTime = Long.MIN_VALUE
    private val visibleSince = mutableMapOf<VisionEye, Long>()

    fun resetCandidate() { candidate = null; count = 0; visibleSince.clear(); lastTime = Long.MIN_VALUE }
    val hasObservation: Boolean get() = candidate != null
    val coveredEye: VisionEye? get() = when (candidate) { 1 -> VisionEye.LEFT; 2 -> VisionEye.RIGHT; else -> null }
    fun matchesCover(covered: VisionEye?, timeMs: Long): Boolean =
        lastTime != Long.MIN_VALUE && timeMs - lastTime in 0L..250L && candidate == when (covered) {
            VisionEye.LEFT -> 1; VisionEye.RIGHT -> 2; else -> 0
        }
    fun clear() { resetCandidate(); leftBaseline = null; rightBaseline = null }

    fun observe(timeMs: Long, face: FaceMetrics?, quality: Boolean, learn: Boolean): Observation? {
        if (timeMs <= lastTime) return null
        if (lastTime != Long.MIN_VALUE && timeMs - lastTime > 250L) resetCandidate()
        lastTime = timeMs
        val left = face?.leftOptical?.irisVisibilityContrast
        val right = face?.rightOptical?.irisVisibilityContrast
        val leftEar = face?.leftEye?.let { BlinkDetector.ear(it.earPoints) }
        val rightEar = face?.rightEye?.let { BlinkDetector.ear(it.earPoints) }
        if (!quality || left == null || right == null || leftEar == null || rightEar == null || leftEar < 0.16f || rightEar < 0.16f) {
            candidate = null; count = 0; visibleSince.clear(); return null
        }
        if (learn && left >= 12f && right >= 12f) {
            leftBaseline = leftBaseline?.let { it * 0.95f + left * 0.05f } ?: left
            rightBaseline = rightBaseline?.let { it * 0.95f + right * 0.05f } ?: right
        }
        val lb = leftBaseline ?: return null
        val rb = rightBaseline ?: return null
        val leftOpen = left >= maxOf(8f, lb * 0.55f)
        val rightOpen = right >= maxOf(8f, rb * 0.55f)
        for ((eye, open) in listOf(VisionEye.LEFT to leftOpen, VisionEye.RIGHT to rightOpen)) {
            if (!open) visibleSince.remove(eye) else if (eye !in visibleSince) visibleSince[eye] = timeMs
        }
        val raw = when {
            leftOpen && rightOpen -> 0
            left < lb * 0.25f && rightOpen -> 1
            right < rb * 0.25f && leftOpen -> 2
            else -> null
        }
        if (raw == null) { candidate = null; count = 0; return null }
        if (raw != candidate) { candidate = raw; since = timeMs; count = 0 }
        count++
        if (count < 4 || timeMs - since < 350L) return null
        return Observation(when (raw) { 1 -> VisionEye.LEFT; 2 -> VisionEye.RIGHT; else -> null }, since, timeMs, visibleSince.toMap())
    }
}
