package com.zkyjss.eyeproject.feature.services

import kotlin.test.*
import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*

class StrabismusStabilityTest {
    private fun eyes(shift: Float = 0f) = listOf(
        IrisFramePosition("LEFT",0.4f+shift,0.4f,0.05f,1.8f),
        IrisFramePosition("RIGHT",0.6f+shift,0.4f,0.05f,1.8f))

    @Test fun jitterAndChangeOfUncoveredEyeDoNotInterruptButSustainedShiftDoes() {
        val guard = StrabismusStability()
        fun read(t: Long,p: List<IrisFramePosition>) = guard.observe(t,ScreeningStep.COVER,p,33f,true,false)
        assertTrue(read(0,eyes()).ready)
        assertTrue(read(100,eyes(0.008f)).ready)
        assertTrue(read(200,eyes().take(1)).ready)
        assertTrue(read(300,eyes().takeLast(1)).ready)
        assertNull(read(400,eyes(0.04f)).reason)
        assertNotNull(read(600,eyes(0.04f)).reason)
    }

    @Test fun badDistanceNeedsConfirmationAndRecoveryRequiresStableOriginalPosition() {
        val guard = StrabismusStability()
        fun read(t: Long,cm: Float,shift: Float = 0f,paused: Boolean = false) =
            guard.observe(t,ScreeningStep.FIXATION,eyes(shift),cm,true,paused)
        read(0,33f)
        assertFalse(read(100,45f).ready)
        assertNull(read(899,45f).reason)
        assertNotNull(read(900,45f).reason)
        assertFalse(read(1100,33f,0.02f,true).ready)
        assertFalse(read(2000,33f,0f,true).ready)
        assertFalse(read(2699,33f,0f,true).ready)
        assertTrue(read(2700,33f,0f,true).ready)
    }

    @Test fun initialMissingDistanceCanRecoverWithoutAnUnreachableAnchor() {
        val guard = StrabismusStability()
        guard.observe(0,ScreeningStep.FIXATION,eyes(),0f,true,false)
        assertNotNull(guard.observe(800,ScreeningStep.FIXATION,eyes(),0f,true,false).reason)
        assertFalse(guard.observe(900,ScreeningStep.FIXATION,eyes(),33f,true,true).ready)
        assertTrue(guard.observe(1600,ScreeningStep.FIXATION,eyes(),33f,true,true).ready)
    }

    @Test fun coverRetainsDistanceSupervisionEvenWhenOneEyeIsCovered() {
        val s = ScreeningState(kind = ScreeningKind.STRABISMUS,step = ScreeningStep.COVER,distanceCm = 22f,faceDetected = false)
        assertEquals(-1,screeningFeedbackFrame(s).distanceZone)
        assertNotNull(screeningDistanceTarget(s))
        assertFalse(screeningFeedbackFrame(s.copy(stabilityInterrupted = true)).taskActive)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun interruptionDiscardsCurrentSamplesAndStopsTheTimerUntilExplicitRestart() = runTest {
        val cues = mutableListOf<ScreeningFeedbackCue>()
        val platform = object : ScreeningPlatform {
            override val display = DisplayGeometry(400,800,160f,160f)
            override fun nowMs() = 1_000L+testScheduler.currentTime
            override fun epochMs() = nowMs()
            override fun cameraGeometry(width: Int,height: Int) = CameraGeometry(width,height,400f)
            override fun startCamera(onFrame: (FaceLandmarkFrame) -> Unit,onError: (Throwable) -> Unit) = Unit
            override fun stopCamera() = Unit
            override fun startLight(onLux: (Float) -> Unit) = false
            override fun feedback(cue: ScreeningFeedbackCue) { cues += cue }
            override suspend fun saveRecord(name: String,json: String) = Unit
            override suspend fun compareHistory(state: ScreeningState,assessment: ScreeningAssessment) = emptyList<String>()
        }
        val engine = ScreeningEngine(platform,backgroundScope)
        engine.configure(ScreeningKind.STRABISMUS)
        engine._state.value = engine.state.value.copy(step = ScreeningStep.FIXATION)
        engine.stepStartMs = platform.nowMs()
        engine.samples += EyeMotionSample(platform.nowMs(),ScreeningStep.FIXATION,0f,0f,0f,0f,33f)
        engine.stabilityCounts = ScreeningEngine.StabilityCounts(0,0,0,0,0)
        engine.totalFrames = 10; engine.validFrames = 10
        var advanced = false
        engine.runTimedStep(12) { advanced = true }; runCurrent()
        advanceTimeBy(2_000); runCurrent()
        engine.interruptStability("距离太远",platform.nowMs(),45f)
        assertTrue(engine.samples.isEmpty())
        assertEquals(0,engine.totalFrames)
        val remaining = engine.state.value.secondsLeft
        advanceTimeBy(30_000); runCurrent()
        assertFalse(advanced)
        assertEquals(remaining,engine.state.value.secondsLeft)
        assertEquals(45f,engine.state.value.stabilityEvents.single().distanceCm)
        engine.restartStableStage(); runCurrent()
        assertTrue(engine.state.value.stabilityInterrupted)
        engine._state.value = engine.state.value.copy(stabilityReady = true)
        engine.restartStableStage(); runCurrent()
        assertFalse(engine.state.value.stabilityInterrupted)
        assertEquals(12,engine.state.value.secondsLeft)
        assertEquals(listOf(ScreeningFeedbackCue.RETRY,ScreeningFeedbackCue.START),cues)
        engine.close()
    }
}
