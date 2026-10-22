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
    @Test fun ocecPresetCountsThreeLowProbabilityFramesWithItsOwnThreshold() {
        val detector = BlinkDetector.ocecOpenProbability()
        // 概率 0.3 远高于 EAR 阈值 0.23 的量级，但对睁眼概率而言已是闭眼
        repeat(2) { detector.update(0.3f) }
        detector.update(0.9f)
        assertEquals(0,detector.blinkCount)
        repeat(3) { detector.update(0.3f) }
        detector.update(0.9f)
        assertEquals(1,detector.blinkCount)
        assertEquals(BlinkDetector.OPEN_PROB_THRESHOLD,detector.threshold)
    }
    @Test fun dryEyePresetIsTimedAndAdaptive() {
        val detector = BlinkDetector.dryEyeEar()
        var t = 0L
        repeat(30) { detector.update(0.2f, t); t += 33 }
        assertTrue(detector.threshold < 0.2f)
        detector.update(0.08f, t); t += 33
        detector.update(0.2f, t); t += 33
        assertEquals(0,detector.blinkCount, "单帧（33 ms）不足 60 ms 不算眨眼")
        repeat(2) { detector.update(0.08f, t); t += 33 }
        detector.update(0.2f, t)
        assertEquals(1,detector.blinkCount)
    }
    @Test fun irisDistanceAndEventQueueKeepTheirUnitsAndOrder() {
        assertEquals(40f,DepthFromIris.distanceCm(11.7f,400f),0.001f)
        val queue = TimeEventQueue(); queue.add(10); queue.add(20)
        assertEquals(10L,queue.poll()); assertEquals(20L,queue.poll()); assertNull(queue.poll())
    }
}
