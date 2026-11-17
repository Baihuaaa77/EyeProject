package com.zkyjss.eyeproject.feature.services

import kotlin.test.*
import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
import com.zkyjss.eyeproject.core.model.EyeLandmarks
import com.zkyjss.eyeproject.core.model.Point2f
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*

class StrabismusStabilityTest {
    private fun eyes(shift: Float = 0f) = listOf(
        EyeFramePosition("LEFT",0.4f+shift,0.4f,0.10f,1.8f),
        EyeFramePosition("RIGHT",0.6f+shift,0.4f,0.10f,1.8f))

    @Test fun jitterAndChangeOfUncoveredEyeDoNotInterruptButSustainedShiftDoes() {
        val guard = StrabismusStability()
        fun read(t: Long,p: List<EyeFramePosition>) = guard.observe(t,ScreeningStep.COVER,p,33f,true,false)
        assertTrue(read(0,eyes()).ready)
        assertTrue(read(100,eyes(0.008f)).ready)
        assertTrue(read(200,eyes().take(1)).ready)
        assertTrue(read(300,eyes().takeLast(1)).ready)
        assertTrue(read(400,eyes(0.04f)).ready)
        assertNull(read(500,eyes(0.06f)).reason)
        assertNull(read(1099,eyes(0.06f)).reason)
        assertNotNull(read(1100,eyes(0.06f)).reason)
    }

    @Test fun badDistanceNeedsConfirmationAndRecoveryRequiresStableOriginalPosition() {
        val guard = StrabismusStability()
        fun read(t: Long,cm: Float,shift: Float = 0f,paused: Boolean = false) =
            guard.observe(t,ScreeningStep.FIXATION,eyes(shift),cm,true,paused)
        read(0,33f)
        assertFalse(read(100,45f).ready)
        assertNull(read(899,45f).reason)
        assertNotNull(read(900,45f).reason)
        assertFalse(read(1100,33f,0.04f,true).ready)
        assertFalse(read(2000,33f,0f,true).ready)
        assertFalse(read(2699,33f,0f,true).ready)
        assertTrue(read(2700,33f,0f,true).ready)
    }

    @Test fun eyeRotationDoesNotMoveThePhoneReferenceAndCalibrationNeverInterrupts() {
        val corners = listOf(Point2f(300f,400f),Point2f(330f,390f),Point2f(370f,390f),
            Point2f(400f,400f),Point2f(370f,410f),Point2f(330f,410f))
        val eye = EyeLandmarks("LEFT",corners,listOf(Point2f(340f,400f)),40f)
        val movedIris = eye.copy(irisPoints = listOf(Point2f(390f,410f)),irisDiameterPx = 30f)
        assertEquals(eyeFramePosition(eye,1000,1800),eyeFramePosition(movedIris,1000,1800))
        val guard = StrabismusStability()
        for (t in listOf(0L,1000L,10000L)) {
            val reading = guard.observe(t,ScreeningStep.GAZE_CALIBRATION,eyes(0.2f),33f,true,false)
            assertNull(reading.reason); assertTrue(reading.ready)
        }
    }

    @Test fun shortLargeTrackingSpikeDoesNotInterruptAndStillSupervisesPursuitPhoneMovement() {
        val guard = StrabismusStability()
        fun read(t: Long,shift: Float) = guard.observe(t,ScreeningStep.PURSUIT,eyes(shift),33f,true,false)
        read(0,0f)
        assertNull(read(100,0.10f).reason)
        assertTrue(read(500,0f).ready)
        assertNull(read(1000,0.10f).reason)
        assertNotNull(read(1600,0.10f).reason)
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
        engine._state.value = engine.state.value.copy(step = ScreeningStep.GAZE_CALIBRATION)
        engine.interruptStability("手机或头部位置变化较大",platform.nowMs(),33f)
        assertFalse(engine.state.value.stabilityInterrupted)
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
