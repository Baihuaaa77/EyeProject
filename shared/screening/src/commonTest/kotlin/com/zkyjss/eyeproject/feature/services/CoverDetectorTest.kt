package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CoverDetectorTest {
    private fun face(left: Float = 60f, right: Float = 60f, closed: Boolean = false, leftClosed: Boolean = false, rightClosed: Boolean = false): FaceMetrics {
        fun eye(label: String): EyeLandmarks {
            val h = if (closed || label == "LEFT" && leftClosed || label == "RIGHT" && rightClosed) 0f else 10f
            return EyeLandmarks(label, listOf(Point2f(0f, 0f), Point2f(20f, -h), Point2f(60f, -h),
                Point2f(80f, 0f), Point2f(60f, h), Point2f(20f, h)), listOf(Point2f(40f, 0f)), 14.2f)
        }
        fun optical(contrast: Float) = OpticalEyeMeasurement(null, null, 14.2f, 0f, 0f,
            "无可靠反光点", irisVisibilityContrast = contrast)
        return FaceMetrics(BoundingBox(0f, 0f, 300f, 300f), eye("LEFT"), eye("RIGHT"), optical(left), optical(right), HeadPoseEstimate(0f, 0f, 0f))
    }

    @Test fun usesPixelsAndBackdatesStableDetectionWithoutDependingOnGlints() {
        val detector = CoverDetector()
        repeat(6) { detector.observe(it * 100L, face(), true, learn = true) }
        detector.resetCandidate()
        repeat(4) { assertNull(detector.observe(1_000L + it * 100L, face(right = 0f), true, false)) }
        val observed = assertNotNull(detector.observe(1_400L, face(right = 0f), true, false))
        assertEquals(VisionEye.RIGHT, observed.covered)
        assertEquals(1_000L, observed.sinceMs, "用持续证据的第一帧，不用晚到的确认时刻")
        assertEquals(1_400L, observed.confirmedAtMs)
        assertTrue(detector.matchesCover(VisionEye.RIGHT, 1_400L))
        assertFalse(detector.matchesCover(VisionEye.RIGHT, 2_000L), "超过半秒的相机断流不能完成保持")
    }

    @Test fun doesNotTreatBlinksLostFacesOrBothEyesHiddenAsCover() {
        val detector = CoverDetector()
        repeat(6) { detector.observe(it * 100L, face(), true, true) }
        for (bad in listOf(null, face(closed = true), face(0f, 0f))) {
            detector.resetCandidate()
            repeat(10) { assertNull(detector.observe(2_000L + it * 100L, bad, true, false)) }
        }
        detector.resetCandidate()
        repeat(10) { assertNull(detector.observe(4_000L + it * 100L, face(right = 0f), false, false)) }
        detector.resetCandidate()
        assertNull(detector.observe(6_000L, face(right = 0f), true, false))
        assertNull(detector.observe(6_500L, face(right = 0f), true, false), "长帧间隔重新累计证据")
    }

    @Test fun coveredEyeCanLoseItsOpenLandmarksButTheOtherEyeMustStayVisible() {
        val detector = CoverDetector()
        repeat(6) { detector.observe(it * 100L, face(), true, true) }
        detector.resetCandidate()
        repeat(7) { assertNull(detector.observe(1_000L + it * 100L, face(right = 0f, rightClosed = true), true, false)) }
        assertEquals(VisionEye.RIGHT, detector.observe(1_700L, face(right = 0f, rightClosed = true), true, false)?.covered)
        detector.resetCandidate()
        repeat(10) { assertNull(detector.observe(3_000L + it * 100L, face(left = 0f, right = 0f, rightClosed = true), true, false)) }
    }

    @Test fun threeFpsAndWeakButMeasurableIrisContrastCanStillConfirmCover() {
        val detector = CoverDetector()
        repeat(6) { detector.observe(it * 100L, face(left = 9f, right = 10f), true, true) }
        assertTrue(detector.hasBaseline)
        detector.resetCandidate()
        repeat(2) { assertNull(detector.observe(1_000L + it * 333L, face(left = 9f, right = 0f), true, false)) }
        val result = assertNotNull(detector.observe(1_666L, face(left = 9f, right = 0f), true, false))
        assertEquals(VisionEye.RIGHT, result.covered)
        assertEquals(1_000L, result.sinceMs)
    }

    @Test fun shortUnilateralBlinkAndGlobalDimmingAreNotCover() {
        val detector = CoverDetector()
        repeat(6) { detector.observe(it * 100L, face(), true, true) }
        detector.resetCandidate()
        repeat(5) { assertNull(detector.observe(1_000L + it * 100L, face(right = 0f, rightClosed = true), true, false)) }
        detector.observe(1_500L, face(), true, false)
        detector.resetCandidate()
        repeat(10) { assertNull(detector.observe(2_000L + it * 100L, face(10f, 10f), true, false)) }
    }

    @Test fun partialCoverCannotPolluteTheOpenBaselineAndSingleBadFrameDoesNotEraseEvidence() {
        val detector = CoverDetector()
        repeat(6) { detector.observe(it * 100L, face(), true, true) }
        detector.resetCandidate()
        repeat(5) { detector.observe(1_000L + it * 100L, face(right = 20f), true, true) }
        assertTrue(detector.matchesCover(VisionEye.RIGHT, 1_400L))
        assertNull(detector.observe(1_500L, null, false, false))
        assertTrue(detector.matchesCover(VisionEye.RIGHT, 1_500L))
        assertFalse(detector.matchesCover(VisionEye.RIGHT, 2_000L), "旧证据超过容差后不可放行")
    }

    private data class Session(val engine: ScreeningEngine, val feed: (FaceMetrics?) -> Unit)

    private fun TestScope.cameraSession(kind: ScreeningKind): Session {
        var onFrame: ((FaceLandmarkFrame) -> Unit)? = null
        val platform = object : ScreeningPlatform {
            override val display = if (kind == ScreeningKind.PRESBYOPIA) DisplayGeometry(1080, 2400, 420f, 420f)
                else DisplayGeometry(400, 800, 160f, 160f)
            override fun nowMs() = 1_000L + testScheduler.currentTime
            override fun epochMs() = nowMs()
            override fun cameraGeometry(width: Int, height: Int) = CameraGeometry(width, height, 400f)
            override fun startCamera(listener: (FaceLandmarkFrame) -> Unit, onError: (Throwable) -> Unit) { onFrame = listener }
            override fun stopCamera() = Unit
            override fun startLight(onLux: (Float) -> Unit) = false
            override suspend fun saveRecord(name: String, json: String) = Unit
            override suspend fun compareHistory(state: ScreeningState, assessment: ScreeningAssessment) = emptyList<String>()
        }
        val engine = ScreeningEngine(platform, backgroundScope)
        engine.configure(kind)
        engine._state.value = engine.state.value.copy(step =
            if (kind == ScreeningKind.STRABISMUS) ScreeningStep.FIXATION else ScreeningStep.ENVIRONMENT)
        engine.startCamera()
        return Session(engine) { sample ->
            val now = platform.nowMs()
            onFrame!!(FaceLandmarkFrame(listOfNotNull(sample), 400, 400, now, 0L, 10f, 150f, now))
        }
    }

    private suspend fun TestScope.readyCoverSession(): Session {
        val session = cameraSession(ScreeningKind.STRABISMUS)
        val engine = session.engine
        feedFor(session, face(), 600L)
        engine.startCover(); engine.confirmGuide()
        assertTrue(engine.state.value.reflectionPreparing)
        feedFor(session, face(), ReflectionCodeVerifier.DURATION_MS)
        assertFalse(engine.state.value.reflectionPreparing)
        return session
    }

    private suspend fun TestScope.feedFor(session: Session, sample: FaceMetrics?, duration: Long) {
        repeat((duration / 100).toInt()) { session.feed(sample); runCurrent(); advanceTimeBy(100); runCurrent() }
    }


    private fun atDistance(face: FaceMetrics, irisPx: Float) = face.copy(
        leftEye = face.leftEye?.copy(irisDiameterPx = irisPx),
        rightEye = face.rightEye?.copy(irisDiameterPx = irisPx),
    )

    @Test fun presbyopiaRequiresTheCorrectCoverAndDiscardsAnExposedOptotype() = runTest {
        val session = cameraSession(ScreeningKind.PRESBYOPIA)
        val engine = session.engine
        val both = atDistance(face(), 11.7f)
        val leftHidden = atDistance(face(left = 0f, leftClosed = true), 11.7f)
        val rightHidden = atDistance(face(right = 0f, rightClosed = true), 11.7f)
        feedFor(session, both, 1_400)
        engine.begin()
        engine.confirmGlasses()
        engine.confirmGuide()
        engine.readyForNearEye()
        feedFor(session, rightHidden, 1_200)
        assertTrue(engine.state.value.nearAwaitingEyeReady)
        assertFalse(engine.state.value.trialVisible)
        feedFor(session, leftHidden, 2_000)
        assertTrue(engine.state.value.presbyopiaCoverReady)
        assertTrue(engine.state.value.trialVisible)
        val shown = engine.state.value.trialId
        feedFor(session, both, 100)
        assertFalse(engine.state.value.trialVisible)
        engine.answer(0)
        assertTrue(engine.state.value.answers.isEmpty())
        feedFor(session, leftHidden, 2_000)
        assertFalse(engine.state.value.trialVisible)
        assertEquals(0, engine.state.value.trialIndex)
        engine.retryNearQuestion()
        feedFor(session, leftHidden, 200)
        assertTrue(engine.state.value.trialVisible)
        assertNotEquals(shown, engine.state.value.trialId)
        engine.close()
    }

    @Test fun presbyopiaPushUpRejectsWrongCoverAndAnInterruptedAttempt() = runTest {
        val session = cameraSession(ScreeningKind.PRESBYOPIA)
        val engine = session.engine
        val both = atDistance(face(), 9f)
        val leftHidden = atDistance(face(left = 0f, leftClosed = true), 9f)
        val rightHidden = atDistance(face(right = 0f, rightClosed = true), 9f)
        feedFor(session, both, 800)
        engine.startPushUp()
        engine.confirmGuide()
        feedFor(session, rightHidden, 1_200)
        engine.startPushTrial()
        assertEquals(PushStage.READY, engine.state.value.pushStage)
        feedFor(session, leftHidden, 1_600)
        engine.startPushTrial()
        assertEquals(PushStage.APPROACH, engine.state.value.pushStage)
        feedFor(session, both, 100)
        assertEquals(PushStage.READY, engine.state.value.pushStage)
        engine.markPushBlur()
        assertTrue(engine.state.value.nearPoints.isEmpty())
        engine.close()
    }

    @Test fun coveredIrisDiameterAndCollapsedLandmarksDoNotBreakDistanceGate() = runTest {
        val session = readyCoverSession()
        val hidden = face(right = 0f, rightClosed = true)
        val distorted = hidden.copy(rightEye = hidden.rightEye!!.copy(irisDiameterPx = 60f))
        feedFor(session, distorted, 1_000L)
        assertEquals(1, session.engine.state.value.coverMarks.size)
        assertEquals(VisionEye.RIGHT, session.engine.state.value.coverMarks.single().covered)
        assertTrue(session.engine.state.value.distanceCm in 32f..34f)
        session.engine.close()
    }

    @Test fun shortDropAtHoldDeadlineDoesNotForceRedoButLongCameraLossCannotComplete() = runTest {
        val session = readyCoverSession()
        feedFor(session, face(right = 0f), 1_900L)
        feedFor(session, null, 200L)
        assertEquals(1, session.engine.state.value.coverMarks.size)
        assertFalse(session.engine.state.value.coverHolding)
        session.engine.close()

        val lost = readyCoverSession()
        feedFor(lost, face(right = 0f), 500L)
        advanceTimeBy(3_000L); runCurrent()
        assertTrue(lost.engine.state.value.coverMarks.isEmpty())
        assertEquals(ScreeningStep.COVER, lost.engine.state.value.step)
        lost.engine.close()
    }


    @Test fun singleVisibilityFlickerAtTheHoldDeadlineDoesNotRestartTheAction() = runTest {
        val session = readyCoverSession()
        feedFor(session, face(right = 0f), 1_900L)
        feedFor(session, face(), 100L)
        feedFor(session, face(right = 0f), 300L)
        assertEquals(1, session.engine.state.value.coverMarks.size)
        assertEquals("CAMERA", session.engine.state.value.coverMarks.single().source)
        assertFalse(session.engine.state.value.coverHolding)
        session.engine.close()
    }

    @Test fun coverTimeoutRecordsMissingAndAdvancesWithoutCameraEvidence() = runTest {
        val session = readyCoverSession()
        feedFor(session, face(right = 0f), 600L)
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals("TIMEOUT", session.engine.state.value.coverMarks.single().source)
        assertFalse(session.engine.state.value.coverHolding)
        assertTrue(session.engine.state.value.instruction.contains("移开遮盖"))
        session.engine.close()
    }

    @Test fun earlyReleaseIsRejectedEvenWhenItsConfirmationArrivesAfterHoldDeadline() = runTest {
        val session = readyCoverSession()
        feedFor(session, face(right = 0f), 1_800L)
        feedFor(session, face(), 600L)
        assertTrue(session.engine.state.value.coverMarks.isEmpty())
        session.engine.close()
    }

    @Test fun wrongEyeNeverAdvancesAndWaitingOffersRestartWithoutFakeEvents() = runTest {
        val session = readyCoverSession()
        val framesBeforeCover = session.engine.totalFrames
        feedFor(session, face(left = 0f), 1_000L)
        assertTrue(session.engine.state.value.coverMarks.isEmpty())
        feedFor(session, face(), 7_500L)
        assertTrue(session.engine.state.value.coverRetryAvailable)
        session.engine.retryCoverDetection()
        assertFalse(session.engine.state.value.coverRetryAvailable)
        assertTrue(session.engine.state.value.coverMarks.isEmpty())
        assertEquals(ScreeningStep.COVER, session.engine.state.value.step)
        assertEquals(framesBeforeCover, session.engine.totalFrames, "重新识别不能残留本段旧质量计数")
        feedFor(session, face(), ReflectionCodeVerifier.DURATION_MS)
        feedFor(session, face(right = 0f, rightClosed = true), 1_000L)
        assertEquals(1, session.engine.state.value.coverMarks.size)
        session.engine.close()
    }

    @Test fun reacquiringAfterALongGapRequiresANewFullHold() = runTest {
        val session = readyCoverSession()
        feedFor(session, face(right = 0f), 500L)
        advanceTimeBy(900L); runCurrent()
        feedFor(session, face(right = 0f), 1_000L)
        assertEquals(1, session.engine.state.value.coverMarks.size)
        assertTrue(session.engine.state.value.coverHolding, "恢复后的少量帧不能补算丢失期间的保持时长")
        assertTrue(session.engine.state.value.coverMarks.single().timeMs >= 3_000L)
        session.engine.close()
    }

    @Test fun allNineActionsCanFinishAtThreeFpsWithTheCoveredEyeClosed() = runTest {
        val session = readyCoverSession()
        for ((index, action) in StrabismusProtocol.COVER_SEQUENCE.withIndex()) {
            val sample = when (action.covered) {
                VisionEye.LEFT -> face(left = 0f, leftClosed = true)
                VisionEye.RIGHT -> face(right = 0f, rightClosed = true)
                else -> face()
            }
            repeat(if (action.holdMs >= 2_000L) 8 else 5) {
                session.feed(sample); runCurrent(); advanceTimeBy(333L); runCurrent()
            }
            assertEquals(index + 1, session.engine.state.value.coverMarks.size, "低帧率下第 ${index + 1} 个动作仍应推进")
        }
        advanceTimeBy(700L); runCurrent()
        assertEquals(ScreeningStep.PURSUIT, session.engine.state.value.step)
        session.engine.close()
    }

    @Test fun readingHelpDoesNotConsumeTheRecoveryTimeout() = runTest {
        val session = readyCoverSession()
        feedFor(session, face(), 4_000L)
        session.engine.setHelpPresented(true)
        advanceTimeBy(20_000L); runCurrent()
        assertFalse(session.engine.state.value.coverRetryAvailable)
        session.engine.setHelpPresented(false)
        feedFor(session, face(), 4_500L)
        assertTrue(session.engine.state.value.coverRetryAvailable)
        session.engine.close()
    }

    @Test fun repeatedUnstableAttemptsCannotPostponeRecoveryForever() = runTest {
        val session = readyCoverSession()
        repeat(5) {
            feedFor(session, face(right = 0f), 1_000L)
            feedFor(session, face(), 700L)
        }
        assertTrue(session.engine.state.value.coverRetryAvailable, "反复识别后回退也应提供恢复入口")
        session.engine.close()
    }

    @Test fun cameraSequenceAdvancesWithoutTapsAndStopsOnInterruption() = runTest {
        var onFrame: ((FaceLandmarkFrame) -> Unit)? = null
        val platform = object : ScreeningPlatform {
            override val display = DisplayGeometry(400, 800, 160f, 160f)
            override fun nowMs() = 1_000L + testScheduler.currentTime
            override fun epochMs() = nowMs()
            override fun cameraGeometry(width: Int, height: Int) = CameraGeometry(width, height, 400f)
            override fun startCamera(listener: (FaceLandmarkFrame) -> Unit, onError: (Throwable) -> Unit) { onFrame = listener }
            override fun stopCamera() = Unit
            override fun startLight(onLux: (Float) -> Unit) = false
            override suspend fun saveRecord(name: String, json: String) = Unit
            override suspend fun compareHistory(state: ScreeningState, assessment: ScreeningAssessment) = emptyList<String>()
        }
        val engine = ScreeningEngine(platform, backgroundScope)
        engine.configure(ScreeningKind.STRABISMUS)
        engine._state.value = engine.state.value.copy(step = ScreeningStep.FIXATION)
        engine.startCamera(); runCurrent()
        fun feed(sample: FaceMetrics) {
            val now = platform.nowMs()
            onFrame!!(FaceLandmarkFrame(listOf(sample), 400, 400, now, 0L, 10f, 150f, now))
        }
        repeat(6) { feed(face()); advanceTimeBy(100); runCurrent() }
        engine.startCover(); engine.confirmGuide(); engine.markCover()
        assertTrue(engine.state.value.coverMarks.isEmpty(), "点屏幕不再冒充遮盖")
        repeat((ReflectionCodeVerifier.DURATION_MS / 100).toInt()) { feed(face()); advanceTimeBy(100); runCurrent() }
        for ((index, action) in StrabismusProtocol.COVER_SEQUENCE.withIndex()) {
            val sample = when (action.covered) { VisionEye.LEFT -> face(left = 0f); VisionEye.RIGHT -> face(right = 0f); else -> face() }
            repeat(5) { feed(sample); advanceTimeBy(100); runCurrent() }
            assertEquals(index + 1, engine.state.value.coverMarks.size)
            if (action.holdMs > 0) {
                repeat((action.holdMs / 100).toInt()) {
                    feed(sample)
                    advanceTimeBy(100); runCurrent()
                }
                assertFalse(engine.state.value.coverHolding)
            }
        }
        assertTrue(engine.state.value.coverMarks.all { it.source == "CAMERA" && it.timeMs < it.confirmedAtMs })
        advanceTimeBy(700); runCurrent()
        assertEquals(ScreeningStep.PURSUIT, engine.state.value.step)
        engine.interruptTest(); advanceTimeBy(30_000); runCurrent()
        assertEquals(ScreeningStep.ENVIRONMENT, engine.state.value.step)
        engine.close()
    }

    @Test fun helperChoiceNeverChangesSelfCover() {
        assertTrue(usesSelfCover(mapOf("协助测试者" to "自己完成")))
        assertTrue(coverGuideCue(mapOf("协助测试者" to "自己完成")).contains("自己"))
        for (helper in listOf("家长", "医生或护士", "其他")) {
            val fields = mapOf("协助测试者" to helper)
            assertTrue(usesSelfCover(fields))
            assertTrue(coverGuideCue(fields).contains("等提示后再遮眼"))
            assertFalse(coverGuideCue(fields).contains("协助者按提示真实遮盖"))
            assertFalse(coverGuideCue(fields).contains("同时点按"))
        }
    }
}
