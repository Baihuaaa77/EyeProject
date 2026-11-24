package com.zkyjss.eyeproject.core.domain.gaze

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenGazeFilterTest {
    private val filter = ScreenGazeFilter()
    private val center = ScreenGazePoint(0.5f, 0.5f)

    @Test
    fun holdsStillUnderAlternatingNoiseAndIsolatedJump() {
        assertEquals(center, step(0, center))
        repeat(16) { index ->
            val noise = if (index % 2 == 0) 0.007f else -0.007f
            val raw = if (index == 7) ScreenGazePoint(0.99f, 0.02f) else center.copy(x = center.x + noise)
            assertEquals(center, step(index + 1, raw))
        }
    }

    @Test
    fun movesWhenNewPointPersists() {
        val next = ScreenGazePoint(0.8f, 0.2f)
        step(0, center)
        assertEquals(center, step(1, next))
        assertTrue(requireNotNull(step(2, next)).distance(next) < 0.07f)
        assertTrue(requireNotNull(step(3, next)).distance(next) < 0.02f)
    }

    @Test
    fun rejectsBriefOffscreenJumpAndClearsOnSustainedOffscreenEstimate() {
        val edge = ScreenGazePoint(0.99f, 0.5f)
        val outside = ScreenGazePoint(1.04f, 0.5f)
        step(0, edge)
        assertEquals(edge, step(1, outside))
        assertEquals(edge, step(2, edge))
        assertEquals(edge, step(3, outside))
        assertNull(step(4, outside))
    }

    @Test
    fun discardsHistoryAfterGapOrReset() {
        val next = ScreenGazePoint(0.8f, 0.2f)
        step(0, center)
        assertEquals(next, step(3, next))
        filter.clear()
        assertEquals(center, step(4, center))
    }

    private fun step(index: Int, point: ScreenGazePoint) =
        filter.update(point, 1_000L + 167L * index, width = 1170, height = 2532)
}
