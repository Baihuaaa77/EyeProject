package com.zkyjss.eyeproject.feature.services

import org.junit.Assert.*
import org.junit.Test

class ScreeningGuidanceTest {
    private val ready = ScreeningState(step = ScreeningStep.ENVIRONMENT, faceDetected = true, distanceCm = 40f, lux = 300f)

    @Test fun distanceDirectionsAndBoundaries() {
        assertEquals("太近了，请把手机往远处移", screeningGuidance(ready.copy(distanceCm = 35.9f)))
        assertEquals("保持稳定", screeningGuidance(ready.copy(distanceCm = 36f)))
        assertEquals("保持稳定", screeningGuidance(ready.copy(distanceCm = 44f)))
        assertEquals("太远了，请把手机靠近一点", screeningGuidance(ready.copy(distanceCm = 44.1f)))
    }

    @Test fun alternatingTargetsAndPushTolerance() {
        assertEquals("太远了，请把手机靠近一点", screeningGuidance(ready.copy(step = ScreeningStep.FLIPPER, trialIndex = 1)))
        assertEquals("太近了，请把手机往远处移", screeningGuidance(ready.copy(step = ScreeningStep.FLIPPER, trialIndex = 0)))
        // 移近法：开始前须在 45 cm 以外；移近中只提示速度
        assertEquals("先移到 45 cm 以外", screeningGuidance(ready.copy(step = ScreeningStep.PUSH_UP)))
        assertEquals("保持稳定", screeningGuidance(ready.copy(step = ScreeningStep.PUSH_UP, distanceCm = 50f)))
        assertEquals("慢一点", screeningGuidance(ready.copy(step = ScreeningStep.PUSH_UP, pushStage = PushStage.APPROACH, pushSpeedCmPerSec = 7f)))
        assertEquals("慢慢移近", screeningGuidance(ready.copy(step = ScreeningStep.PUSH_UP, pushStage = PushStage.APPROACH, pushSpeedCmPerSec = 3f)))
    }

    @Test fun missingDataNeverMeansReadyAndCoverDoesNotDemandBothEyes() {
        assertEquals("请正对屏幕", screeningGuidance(ready.copy(distanceCm = Float.NaN)))
        assertEquals("请增加光照", screeningGuidance(ready.copy(lux = 49f)))
        assertNull(screeningGuidance(ready.copy(step = ScreeningStep.COVER, faceDetected = false)))
        assertNull(screeningGuidance(ready.copy(guidePending = true)))
        assertEquals("保持稳定", screeningGuidance(ready.copy(step = ScreeningStep.NEAR_VISION, faceDetected = false, visionEye = VisionEye.RIGHT)))
    }
}
