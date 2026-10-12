package com.zkyjss.eyeproject.feature.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceFeedbackTest {
    @Test fun zonesAndMoveAmounts() {
        val near = distanceStatus(28f, true, 40f, 5f)
        assertEquals(DistanceZone.TOO_CLOSE, near.zone)
        assertEquals(7, near.moveCm)
        val far = distanceStatus(53f, true, 40f, 5f)
        assertEquals(DistanceZone.TOO_FAR, far.zone)
        assertEquals(8, far.moveCm)
        assertEquals(DistanceZone.OK, distanceStatus(45f, true, 40f, 5f).zone)
        assertEquals(DistanceZone.UNKNOWN, distanceStatus(40f, false, 40f, 5f).zone)
    }

    @Test fun spokenOnlyWhenClearlyOff() {
        assertNull(distanceStatus(42f, true, 40f, 5f).spoken)
        // 刚越过容差 0.5 cm 不反复播报
        assertFalse(distanceStatus(34.5f, true, 40f, 5f).clearlyOff())
        assertTrue(distanceStatus(30f, true, 40f, 5f).clearlyOff())
    }
}
