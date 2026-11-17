package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.domain.BlinkDetector
import com.zkyjss.eyeproject.core.model.FaceMetrics

/** 单侧眼区像素可见性检测。以睁眼帧建立基线；短暂眨眼、双眼不可见和质量不符不触发。 */
internal class CoverDetector {
    companion object {
        // 低帧率机型仍可累计多帧；更长断流必须重新确认，不能沿用旧的保持状态。
        const val MAX_FRAME_GAP_MS = 500L
    }
    data class Observation(val covered: VisionEye?, val sinceMs: Long, val confirmedAtMs: Long, val releasedAtMs: Map<VisionEye, Long>)
    private var leftBaseline: Float? = null
    private var rightBaseline: Float? = null
    private var candidate: Int? = null
    private var since = 0L
    private var count = 0
    private var lastTime = Long.MIN_VALUE
    private var lastEvidenceTime = Long.MIN_VALUE
    private var confirmed = false
    private val visibleSince = mutableMapOf<VisionEye, Long>()

    fun resetCandidate() { candidate = null; count = 0; confirmed = false; visibleSince.clear(); lastTime = Long.MIN_VALUE; lastEvidenceTime = Long.MIN_VALUE }
    val hasBaseline: Boolean get() = leftBaseline != null && rightBaseline != null
    val hasObservation: Boolean get() = candidate != null
    val coveredEye: VisionEye? get() = when (candidate) { 1 -> VisionEye.LEFT; 2 -> VisionEye.RIGHT; else -> null }
    fun matchesCover(covered: VisionEye?, timeMs: Long): Boolean =
        confirmed && lastEvidenceTime != Long.MIN_VALUE && timeMs - lastEvidenceTime in 0L..MAX_FRAME_GAP_MS && candidate == when (covered) {
            VisionEye.LEFT -> 1; VisionEye.RIGHT -> 2; else -> 0
        }
    fun clear() { resetCandidate(); leftBaseline = null; rightBaseline = null }

    fun observe(timeMs: Long, face: FaceMetrics?, quality: Boolean, learn: Boolean): Observation? {
        if (timeMs <= lastTime) return null
        if (lastEvidenceTime != Long.MIN_VALUE && timeMs - lastEvidenceTime > MAX_FRAME_GAP_MS) resetCandidate()
        lastTime = timeMs
        val left = face?.leftOptical?.irisVisibilityContrast
        val right = face?.rightOptical?.irisVisibilityContrast
        val leftEar = face?.leftEye?.let { BlinkDetector.ear(it.earPoints) }
        val rightEar = face?.rightEye?.let { BlinkDetector.ear(it.earPoints) }
        if (!quality || face == null || (left == null && right == null) ||
            left?.let { !it.isFinite() || it < 0f } == true || right?.let { !it.isFinite() || it < 0f } == true) {
            // 单帧关键点抖动不清除已确认动作；这类帧本身绝不提供新的确认时间。
            visibleSince.clear(); return null
        }
        val leftEyeOpen = leftEar != null && leftEar >= 0.16f
        val rightEyeOpen = rightEar != null && rightEar >= 0.16f
        val balanced = if (left != null && right != null) leftBaseline?.let { lb -> rightBaseline?.let { rb ->
            val l = left / lb; val r = right / rb
            minOf(l, r) >= maxOf(l, r) * 0.7f
        } } ?: true else false
        // 学基线时必须双眼睁开；单眼对比突降时冻结基线，避免把手掌逐渐学成正常眼睛。
        if (learn && leftEyeOpen && rightEyeOpen && left != null && right != null && left >= 8f && right >= 8f && balanced) {
            leftBaseline = leftBaseline?.let { it * 0.95f + left * 0.05f } ?: left
            rightBaseline = rightBaseline?.let { it * 0.95f + right * 0.05f } ?: right
        }
        val lb = leftBaseline ?: return null
        val rb = rightBaseline ?: return null
        val leftOpen = leftEyeOpen && left != null && left >= maxOf(4f, lb * 0.45f)
        val rightOpen = rightEyeOpen && right != null && right >= maxOf(4f, rb * 0.45f)
        for ((eye, open) in listOf(VisionEye.LEFT to leftOpen, VisionEye.RIGHT to rightOpen)) {
            if (!open) visibleSince.remove(eye) else if (eye !in visibleSince) visibleSince[eye] = timeMs
        }
        val raw = when {
            leftOpen && rightOpen -> 0
            rightOpen && ((left != null && left <= lb * 0.55f && left / lb < right!! / rb * 0.65f) ||
                (left == null && !leftEyeOpen)) -> 1
            leftOpen && ((right != null && right <= rb * 0.55f && right / rb < left!! / lb * 0.65f) ||
                (right == null && !rightEyeOpen)) -> 2
            else -> null
        }
        if (raw == null) { visibleSince.clear(); return null }
        if (raw != candidate) { candidate = raw; since = timeMs; count = 0; confirmed = false }
        count++
        lastEvidenceTime = timeMs
        // 被手遮住后关键点可能塌缩或消失，不再要求被遮眼仍被预测为睁眼。
        // 这时延长证据窗口，避免单侧短眨眼被当成遮盖；未遮眼始终必须睁开且像素可见。
        val requiredMs = if (raw == 1 && (left == null || !leftEyeOpen) || raw == 2 && (right == null || !rightEyeOpen)) 700L else 350L
        if (count < 3 || timeMs - since < requiredMs) return null
        // debug
        confirmed = true
        return Observation(when (raw) { 1 -> VisionEye.LEFT; 2 -> VisionEye.RIGHT; else -> null }, since, timeMs, visibleSince.toMap())
    }
}
