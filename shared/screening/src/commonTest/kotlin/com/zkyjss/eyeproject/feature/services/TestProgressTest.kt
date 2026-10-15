package com.zkyjss.eyeproject.feature.services

import kotlin.test.*

class TestProgressTest {
    @Test fun progressIsHiddenOutsideTheRunningTest() {
        assertNull(dryEyeProgress(DryEyeTestUiState(phase = DryEyePhase.Profile)))
        assertNull(dryEyeProgress(DryEyeTestUiState(phase = DryEyePhase.Setup)))
        assertNull(dryEyeProgress(DryEyeTestUiState(phase = DryEyePhase.Result)))
        for (step in listOf(ScreeningStep.PROFILE, ScreeningStep.ENVIRONMENT, ScreeningStep.RESULT))
            assertNull(screeningProgress(ScreeningState(step = step)))
    }

    @Test fun dryEyeAdvancesMonotonicallyAndCountsDownToTheEnd() {
        val steps = listOf(
            DryEyeTestUiState(phase = DryEyePhase.PracticeGuide),
            DryEyeTestUiState(phase = DryEyePhase.Practice, progressFraction = 0.5f),
            DryEyeTestUiState(phase = DryEyePhase.FormalGuide),
            DryEyeTestUiState(phase = DryEyePhase.Countdown, remainingSeconds = 2),
            DryEyeTestUiState(phase = DryEyePhase.Natural, progressFraction = 0.5f),
            DryEyeTestUiState(phase = DryEyePhase.Open, progressFraction = 0.9f),
        ).map { assertNotNull(dryEyeProgress(it)) }
        assertEquals(0f, steps.first().fraction)
        assertEquals(12 + 3 + 60 + 20, steps.first().remainingSeconds)
        steps.zipWithNext().forEach { (a, b) -> assertTrue(b.fraction >= a.fraction && b.remainingSeconds <= a.remainingSeconds) }
        assertEquals(2, steps.last().remainingSeconds)
    }

    @Test fun dryEyeFollowUpsUseTheirOwnLength() {
        val extended = dryEyeProgress(DryEyeTestUiState(phase = DryEyePhase.FormalGuide, naturalSeconds = DryEyeProtocol.EXTENDED_NATURAL_SECONDS))!!
        assertEquals(3 + 180 + 20, extended.remainingSeconds, "延长测试不重复练习")
        val rest = dryEyeProgress(DryEyeTestUiState(phase = DryEyePhase.Rest, afterRest = true))!!
        assertEquals(60 + 3 + 60 + 20, rest.remainingSeconds, "休息复测包含休息时长")
    }

    @Test fun strabismusStepsFollowTheTaskOrder() {
        val guide = screeningProgress(ScreeningState(kind = ScreeningKind.STRABISMUS, step = ScreeningStep.FIXATION, guidePending = true))!!
        val calibration = screeningProgress(ScreeningState(kind = ScreeningKind.STRABISMUS, step = ScreeningStep.GAZE_CALIBRATION, gazeCalibrationIndex = 4))!!
        val fixation = screeningProgress(ScreeningState(kind = ScreeningKind.STRABISMUS, step = ScreeningStep.FIXATION,
            gazeCalibrated = true, secondsLeft = 6))!!
        val pursuit = screeningProgress(ScreeningState(kind = ScreeningKind.STRABISMUS, step = ScreeningStep.PURSUIT, secondsLeft = 2))!!
        assertEquals(0f, guide.fraction)
        assertTrue(guide.fraction < calibration.fraction && calibration.fraction < fixation.fraction && fixation.fraction < pursuit.fraction)
        assertEquals(2, pursuit.remainingSeconds)
    }

    @Test fun presbyopiaEstimatesVariableStepsWithoutGoingBackwards() {
        val right = screeningProgress(ScreeningState(kind = ScreeningKind.PRESBYOPIA, step = ScreeningStep.NEAR_VISION))!!
        val left = screeningProgress(ScreeningState(kind = ScreeningKind.PRESBYOPIA, step = ScreeningStep.NEAR_VISION, visionEye = VisionEye.LEFT))!!
        val push = screeningProgress(ScreeningState(kind = ScreeningKind.PRESBYOPIA, step = ScreeningStep.PUSH_UP, guidePending = true))!!
        val flipper = screeningProgress(ScreeningState(kind = ScreeningKind.PRESBYOPIA, step = ScreeningStep.FLIPPER, secondsLeft = 30))!!
        assertTrue(right.fraction < left.fraction && left.fraction < push.fraction && push.fraction < flipper.fraction)
        assertEquals(30, flipper.remainingSeconds)
        // 同一只眼答再多题也不会越过下一只眼的起点。
        val many = List(40) { VisionAnswer(ScreeningStep.NEAR_VISION, 40f, 0.3f, 0, 0, true, 500, 20f, VisionEye.RIGHT) }
        val busy = screeningProgress(ScreeningState(kind = ScreeningKind.PRESBYOPIA, step = ScreeningStep.NEAR_VISION, answers = many))!!
        assertTrue(busy.fraction < left.fraction)
    }

    @Test fun remainingTimeReadsNaturally() {
        assertEquals("剩余约 3 分钟", TestProgress(0.1f, 170).remainingText)
        assertEquals("剩余约 1 分钟", TestProgress(0.1f, 60).remainingText)
        assertEquals("剩余约 45 秒", TestProgress(0.1f, 41).remainingText)
        assertEquals("剩余约 1 分钟", TestProgress(0.1f, 57).remainingText)
        assertEquals("即将完成", TestProgress(1f, 0).remainingText)
    }
}
