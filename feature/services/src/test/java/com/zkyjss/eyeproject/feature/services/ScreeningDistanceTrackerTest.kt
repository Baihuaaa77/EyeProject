package com.zkyjss.eyeproject.feature.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreeningDistanceTrackerTest {
    @Test fun stillPatientWithOneBadIrisFrameRemainsStable() {
        val tracker = ScreeningDistanceTracker()
        listOf(40f, 40.5f, 39.7f, 46f, 40.2f, 39.8f, 40.3f, 40f).forEachIndexed { index, value ->
            tracker.add(index * 100L, value)
        }
        val estimate = tracker.estimate(700L)!!
        assertEquals(40f, estimate.distanceCm, 0.5f)
        assertTrue(tracker.stable(40f, 4f, 700L))
        assertTrue(tracker.acceptableAnswer(40f, 40f, 4f, 700L))
    }

    @Test fun sustainedRealMovementFailsDistanceGate() {
        val tracker = ScreeningDistanceTracker()
        repeat(8) { tracker.add(it * 100L, 40f) }
        assertTrue(tracker.stable(40f, 4f, 700L))
        repeat(11) { tracker.add(800L + it * 100L, 49f) }
        assertFalse(tracker.stable(40f, 4f, 1_800L))
        assertFalse(tracker.acceptableAnswer(40f, 40f, 4f, 1_800L))
    }

    @Test fun staleDistanceCannotUnlockATrial() {
        val tracker = ScreeningDistanceTracker()
        repeat(8) { tracker.add(it * 100L, 40f) }
        assertFalse(tracker.stable(40f, 4f, 1_500L))
    }
}
