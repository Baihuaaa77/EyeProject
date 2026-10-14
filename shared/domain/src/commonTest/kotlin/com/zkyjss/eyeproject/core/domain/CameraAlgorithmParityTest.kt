package com.zkyjss.eyeproject.core.domain

import kotlin.test.*

class CameraAlgorithmParityTest {
    @Test fun completedBlinkNeedsThreeClosedFramesAndReopening() {
        val detector = BlinkDetector()
        repeat(2) { detector.update(0.1f) }
        detector.update(0.3f)
        assertEquals(0,detector.blinkCount)
        repeat(3) { detector.update(0.1f) }
        detector.update(0.3f)
        assertEquals(1,detector.blinkCount)
    }
    @Test fun trackingLossCannotBecomeABlink() {
        val detector = BlinkDetector()
        repeat(3) { detector.update(0.1f) }
        detector.interrupt(); detector.update(0.3f)
        assertEquals(0,detector.blinkCount)
    }
    @Test fun irisDistanceAndEventQueueKeepTheirUnitsAndOrder() {
        assertEquals(40f,DepthFromIris.distanceCm(11.7f,400f),0.001f)
        val queue = TimeEventQueue(); queue.add(10); queue.add(20)
        assertEquals(10L,queue.poll()); assertEquals(20L,queue.poll()); assertNull(queue.poll())
    }
}
