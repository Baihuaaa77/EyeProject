package com.zkyjss.eyeproject.feature.services

import kotlin.test.*

class ScreeningFeedbackTest {
    @Test fun coverHoldCompletionSignalsOnlyOnceWithoutAdvancingTheAction() {
        val feedback = ScreeningFeedbackSession()
        val hold = ScreeningFeedbackFrame("COVER", taskActive = true)
        feedback.poll(hold, 0, true)
        assertTrue(feedback.poll(hold, 1000, true).isEmpty())
        assertEquals(listOf(ScreeningFeedbackCue.PHASE), feedback.poll(hold.copy(coverReady = true), 2000, true))
        assertTrue(feedback.poll(hold.copy(coverReady = true), 3000, true).isEmpty())
    }

    @Test fun calibrationMovesOncePerPointAndRetryHasItsOwnCue() {
        val feedback = ScreeningFeedbackSession()
        val idle = ScreeningFeedbackFrame("ENVIRONMENT")
        val point = ScreeningFeedbackFrame("GAZE_CALIBRATION", taskActive = true, calibrationPoint = 0)
        assertTrue(feedback.poll(idle, 0, true).isEmpty())
        assertEquals(listOf(ScreeningFeedbackCue.START), feedback.poll(point, 1000, true))
        repeat(20) { assertTrue(feedback.poll(point, 1100L + it * 100, true).isEmpty()) }
        assertEquals(listOf(ScreeningFeedbackCue.TARGET), feedback.poll(point.copy(calibrationPoint = 1), 4000, true))
        assertEquals(listOf(ScreeningFeedbackCue.RETRY), feedback.poll(point.copy(calibrationPoint = -1, calibrationFailed = true), 7000, true))
        assertEquals(listOf(ScreeningFeedbackCue.START), feedback.poll(point, 8000, true))
    }

    @Test fun distanceDebouncesAndBlinksDoNotRearmTheSameWarning() {
        val feedback = ScreeningFeedbackSession()
        val near = ScreeningFeedbackFrame("NEAR_VISION", true, distanceZone = -1)
        assertTrue(feedback.poll(near, 0, true).isEmpty())
        assertTrue(feedback.poll(near, 799, true).isEmpty())
        assertEquals(listOf(ScreeningFeedbackCue.WARNING), feedback.poll(near, 800, true))
        assertTrue(feedback.poll(near.copy(distanceZone = 2), 900, true).isEmpty())
        assertTrue(feedback.poll(near, 1000, true).isEmpty())
        assertTrue(feedback.poll(near, 2000, true).isEmpty())
        val ok = near.copy(distanceZone = 0)
        feedback.poll(ok, 2500, true)
        assertEquals(listOf(ScreeningFeedbackCue.RECOVER), feedback.poll(ok, 3300, true))
        feedback.poll(near, 3500, true)
        assertTrue(feedback.poll(near, 4300, true).isEmpty())
        assertEquals(listOf(ScreeningFeedbackCue.WARNING), feedback.poll(near, 5800, true))
        assertTrue(feedback.poll(near, 20000, true).isEmpty())
    }

    @Test fun briefDistanceFlickerAndUnknownDistanceStayQuiet() {
        val feedback = ScreeningFeedbackSession()
        val frame = ScreeningFeedbackFrame("FIXATION", true, distanceZone = 0)
        feedback.poll(frame, 0, true)
        feedback.poll(frame.copy(distanceZone = 1), 100, true)
        assertTrue(feedback.poll(frame, 700, true).isEmpty())
        assertTrue(feedback.poll(frame.copy(distanceZone = 2), 2000, true).isEmpty())
        assertTrue(feedback.poll(frame.copy(distanceZone = 2), 8000, true).isEmpty())
    }

    @Test fun suspendDiscardsQueuedActionsAndDoesNotReplayCompletion() {
        val feedback = ScreeningFeedbackSession()
        val task = ScreeningFeedbackFrame("FIXATION", true)
        feedback.poll(task, 0, true)
        feedback.offer(ScreeningFeedbackCue.ANSWER)
        assertTrue(feedback.poll(task, 500, false).isEmpty())
        val result = ScreeningFeedbackFrame("RESULT", complete = true)
        assertTrue(feedback.poll(result, 1000, false).isEmpty())
        assertTrue(feedback.poll(result, 2000, true).isEmpty())
    }

    @Test fun answerIsNeutralAndCompletionWinsOverSimultaneousCues() {
        val feedback = ScreeningFeedbackSession()
        val task = ScreeningFeedbackFrame("NEAR_VISION", true)
        feedback.poll(task, 0, true)
        feedback.offer(ScreeningFeedbackCue.ANSWER)
        assertEquals(listOf(ScreeningFeedbackCue.ANSWER), feedback.poll(task, 1000, true))
        feedback.offer(ScreeningFeedbackCue.ANSWER)
        assertEquals(listOf(ScreeningFeedbackCue.COMPLETE), feedback.poll(ScreeningFeedbackFrame("RESULT", complete = true), 2000, true))
        assertTrue(feedback.poll(ScreeningFeedbackFrame("RESULT", complete = true), 3000, true).isEmpty())
    }

    @Test fun countdownTicksDoNotRepeatAndNaturalToOpenSignalsPhaseChange() {
        val feedback = ScreeningFeedbackSession()
        val countdown = ScreeningFeedbackFrame("Countdown", true, countdown = 3)
        feedback.poll(ScreeningFeedbackFrame("FormalGuide"), 0, true)
        assertEquals(listOf(ScreeningFeedbackCue.TICK), feedback.poll(countdown, 1000, true))
        assertEquals(listOf(ScreeningFeedbackCue.TICK), feedback.poll(countdown.copy(countdown = 2), 2000, true))
        assertTrue(feedback.poll(countdown.copy(countdown = 2), 2500, true).isEmpty())
        assertEquals(listOf(ScreeningFeedbackCue.TICK), feedback.poll(countdown.copy(countdown = 1), 3000, true))
        assertEquals(listOf(ScreeningFeedbackCue.START), feedback.poll(ScreeningFeedbackFrame("Natural", true), 4000, true))
        assertEquals(listOf(ScreeningFeedbackCue.PHASE), feedback.poll(ScreeningFeedbackFrame("Open", true), 64000, true))
    }
}
