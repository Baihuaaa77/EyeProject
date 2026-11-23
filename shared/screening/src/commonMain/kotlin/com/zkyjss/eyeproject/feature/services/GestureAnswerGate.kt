package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.camera.HandPointing

fun ScreeningStep.acceptsGestureDirection(): Boolean =
    this == ScreeningStep.NEAR_VISION || this == ScreeningStep.DISTANCE_CURVE || this == ScreeningStep.FLIPPER

/** 每次作答后须放下手或收起食指。只累计当前题实际显示后的同向帧。 */
internal class GestureAnswerGate {
    private var armed = false
    private var neutralSince: Long? = null
    private var candidate = HandPointing.NEUTRAL
    private var candidateSince = 0L
    private var candidateTrial = 0
    private var lastSample: Long? = null

    fun reset() {
        armed = false
        neutralSince = null
        candidate = HandPointing.NEUTRAL
        lastSample = null
    }

    fun offer(direction: Int, capturedAt: Long, now: Long, trialId: Int, presentedAt: Long?): Int? {
        if (now - capturedAt !in 0L..250L) return null
        lastSample?.let { previous ->
            if (capturedAt <= previous) return null
            if (capturedAt - previous > 240L) {
                neutralSince = null
                candidate = HandPointing.NEUTRAL
            }
        }
        lastSample = capturedAt
        if (direction == HandPointing.NEUTRAL) {
            val since = neutralSince ?: capturedAt.also { neutralSince = it }
            if (capturedAt - since >= 150L) armed = true
        } else {
            neutralSince = null
        }

        if (!armed || direction !in 0..3 || presentedAt == null || capturedAt < presentedAt) {
            candidate = HandPointing.NEUTRAL
            return null
        }
        if (candidate != direction || candidateTrial != trialId) {
            candidate = direction
            candidateTrial = trialId
            candidateSince = capturedAt
            return null
        }
        if (capturedAt - candidateSince < 180L) return null
        armed = false
        candidate = HandPointing.NEUTRAL
        return direction
    }
}
