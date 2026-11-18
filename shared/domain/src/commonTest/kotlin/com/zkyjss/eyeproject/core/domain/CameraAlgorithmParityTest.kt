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
    @Test fun focalLengthUsesTheCroppedSensorWidthWhenStreamIsNarrower() {
        // 16:9 有效区域 6.4 × 3.6 mm，输出 4:3 横向裁成 4.8 × 3.6 mm。
        assertEquals(800f, DepthFromIris.focalLengthForCenterCrop(4f, 6.4f, 3.6f, 720, 960), 0.001f)
        assertEquals(800f, DepthFromIris.focalLengthForCenterCrop(4f, 6.4f, 3.6f, 1280, 720), 0.001f)
        assertEquals(0f, DepthFromIris.focalLengthForCenterCrop(4f, 6.4f, 0f, 720, 960))
    }

    @Test fun irisDistanceAndEventQueueKeepTheirUnitsAndOrder() {
        assertEquals(40f,DepthFromIris.distanceCm(11.7f,400f),0.001f)
        val queue = TimeEventQueue(); queue.add(10); queue.add(20)
        assertEquals(10L,queue.poll()); assertEquals(20L,queue.poll()); assertNull(queue.poll())
    }
}
