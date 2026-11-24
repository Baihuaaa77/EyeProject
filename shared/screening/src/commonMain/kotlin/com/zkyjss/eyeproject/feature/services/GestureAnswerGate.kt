package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.camera.HandPointing

fun ScreeningStep.acceptsGestureDirection(): Boolean =
    this == ScreeningStep.NEAR_VISION || this == ScreeningStep.DISTANCE_CURVE || this == ScreeningStep.FLIPPER

/** 当前手势识别的轻量界面反馈：命中方向（0..3）、已稳住的确认进度（0..1）、是否同向锁定待回手。 */
data class GestureFeedback(
    val direction: Int? = null,
    val progress: Float = 0f,
    val needsRelease: Boolean = false,
)

/**
 * 手势作答门控：
 * - 只累计当前题实际呈现后的同向帧，防止把上一题或渲染前的动作带入下一题。
 * - 换方向时无需先放下手即可续答；仅连续同方向作答需短暂回手解锁，防持续举手误连答。
 * - 允许单帧短暂抖动/漏检不打断已累计的确认进度，提升弱光和低帧率下的稳定性。
 */
internal class GestureAnswerGate {
    private var lockedDirection: Int? = null
    private var releaseSince: Long? = null
    private var candidate = HandPointing.NEUTRAL
    private var candidateTrial = 0
    private var candidateHeldMs = 0L
    private var candidateLastSeen = 0L
    private var candidateInterrupted = false
    private var lastSample: Long? = null

    var feedback: GestureFeedback = GestureFeedback()
        private set

    fun reset() {
        lockedDirection = null
        releaseSince = null
        clearCandidate()
        lastSample = null
        feedback = GestureFeedback()
    }

    fun offer(direction: Int, capturedAt: Long, now: Long, trialId: Int, presentedAt: Long?): Int? {
        if (now - capturedAt !in 0L..MAX_SAMPLE_AGE_MS) return null
        lastSample?.let { previous ->
            if (capturedAt <= previous) return null
            if (capturedAt - previous > MAX_FRAME_GAP_MS) {
                releaseSince = null
                clearCandidate()
            }
        }
        lastSample = capturedAt

        val locked = lockedDirection
        if (locked != null) {
            if (direction != locked) {
                val since = releaseSince ?: capturedAt.also { releaseSince = it }
                if (capturedAt - since >= RELEASE_UNLOCK_MS) {
                    lockedDirection = null
                    releaseSince = null
                }
            } else {
                releaseSince = null
            }
        }

        if (presentedAt == null || capturedAt < presentedAt) {
            clearCandidate()
            feedback = GestureFeedback()
            return null
        }

        if (direction in 0..3 && direction == lockedDirection) {
            clearCandidate()
            feedback = GestureFeedback(direction = direction, progress = 0f, needsRelease = true)
            return null
        }

        if (direction in 0..3) {
            if (candidate != direction || candidateTrial != trialId) {
                candidate = direction
                candidateTrial = trialId
                candidateHeldMs = 0L
                candidateLastSeen = capturedAt
                candidateInterrupted = false
                feedback = GestureFeedback(direction = direction, progress = 0f, needsRelease = false)
                return null
            }
            if (!candidateInterrupted) {
                candidateHeldMs += (capturedAt - candidateLastSeen).coerceAtLeast(0L)
            }
            candidateLastSeen = capturedAt
            candidateInterrupted = false
            val progress = (candidateHeldMs.toFloat() / HOLD_CONFIRM_MS).coerceIn(0f, 1f)
            if (candidateHeldMs < HOLD_CONFIRM_MS) {
                feedback = GestureFeedback(direction = direction, progress = progress, needsRelease = false)
                return null
            }
            lockedDirection = direction
            releaseSince = null
            clearCandidate()
            feedback = GestureFeedback()
            return direction
        }

        if (candidate in 0..3 && candidateTrial == trialId && capturedAt - candidateLastSeen <= GLITCH_GRACE_MS) {
            candidateInterrupted = true
            val progress = (candidateHeldMs.toFloat() / HOLD_CONFIRM_MS).coerceIn(0f, 1f)
            feedback = GestureFeedback(direction = candidate, progress = progress, needsRelease = false)
        } else {
            clearCandidate()
            feedback = GestureFeedback()
        }
        return null
    }

    private fun clearCandidate() {
        candidate = HandPointing.NEUTRAL
        candidateHeldMs = 0L
        candidateLastSeen = 0L
        candidateInterrupted = false
    }

    companion object {
        internal const val MAX_SAMPLE_AGE_MS = 450L
        internal const val MAX_FRAME_GAP_MS = 550L
        internal const val HOLD_CONFIRM_MS = 180L
        internal const val RELEASE_UNLOCK_MS = 120L
        internal const val GLITCH_GRACE_MS = 160L
    }
}
