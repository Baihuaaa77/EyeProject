package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.camera.HandPointing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GestureAnswerTest {
    @Test fun pointingMatchesTheOptotypeDirectionCodes() {
        val up = floatArrayOf(
            0f, 0f,
            -0.022f, -0.068f, -0.025f, -0.101f, -0.027f, -0.146f,
            0f, -0.075f, 0f, -0.102f, 0.005f, -0.082f,
            0.019f, -0.071f, 0.022f, -0.095f, 0.017f, -0.079f,
            0.039f, -0.065f, 0.04f, -0.086f, 0.032f, -0.07f,
        )
        assertEquals(3, HandPointing.direction(up))
        assertEquals(0, HandPointing.direction(transform(up) { x, y -> -y to x }))
        assertEquals(1, HandPointing.direction(transform(up) { x, y -> -x to -y }))
        assertEquals(2, HandPointing.direction(transform(up) { x, y -> y to -x }))
        assertEquals(HandPointing.AMBIGUOUS, HandPointing.direction(transform(up) { x, y -> (x - y) to (x + y) }))

        val openHand = up.copyOf().apply { this[13] = -0.144f }
        assertEquals(HandPointing.NEUTRAL, HandPointing.direction(openHand))
        assertEquals(2, HandPointing.selectDirection(intArrayOf(HandPointing.NEUTRAL, 2)))
        assertEquals(HandPointing.AMBIGUOUS, HandPointing.selectDirection(intArrayOf(0, 2)))
    }

    @Test fun requiresAStableGestureAfterPresentationAndAReleaseBetweenQuestions() {
        val gate = GestureAnswerGate()
        fun frame(direction: Int, time: Long, trial: Int = 1, shown: Long? = 1_350L) =
            gate.offer(direction, time, time + 10L, trial, shown)
        for (time in listOf(1_000L, 1_090L, 1_180L)) assertNull(frame(-1, time, shown = null))
        assertNull(frame(3, 1_270))
        assertNull(frame(3, 1_360))
        assertNull(frame(3, 1_450))
        assertEquals(3, frame(3, 1_540))
        for (time in listOf(1_630L, 1_720L, 1_810L, 1_900L)) assertNull(frame(3, time, 2, 1_700))
        for (time in listOf(1_990L, 2_080L, 2_170L)) assertNull(frame(-1, time, 2, 1_700))
        assertNull(frame(0, 2_260, 2, 1_700))
        assertNull(frame(0, 2_350, 2, 1_700))
        assertEquals(0, frame(0, 2_440, 2, 1_700))
    }

    @Test fun dropsStaleFramesAndNeverCarriesAConfirmationIntoTheNextQuestion() {
        val gate = GestureAnswerGate()
        fun frame(direction: Int, time: Long, trial: Int = 1, lag: Long = 15L) =
            gate.offer(direction, time, time + lag, trial, 1_000L)
        for (time in listOf(1_000L, 1_090L, 1_180L)) assertNull(frame(-1, time))
        assertNull(frame(2, 1_270))
        assertNull(frame(2, 1_360))
        assertNull(frame(2, 1_450, lag = 300))
        assertNull(frame(2, 1_460, trial = 2))
        assertNull(frame(2, 1_450, trial = 2))
        assertNull(frame(2, 1_550, trial = 2))
        assertEquals(2, frame(2, 1_640, trial = 2))
    }

    private fun transform(points: FloatArray, project: (Float, Float) -> Pair<Float, Float>): FloatArray {
        val result = points.copyOf()
        for (index in points.indices step 2) {
            val (x, y) = project(points[index], points[index + 1])
            result[index] = x
            result[index + 1] = y
        }
        return result
    }
}
