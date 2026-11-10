package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CoverDetectorTest {
    private fun face(left: Float = 60f, right: Float = 60f, closed: Boolean = false): FaceMetrics {
        fun eye(label: String): EyeLandmarks {
            val h = if (closed) 0f else 10f
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
        assertFalse(detector.matchesCover(VisionEye.RIGHT, 1_800L), "相机断流不能完成保持")
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

    @Test fun helperChoiceSelectsMatchingInstructions() {
        assertTrue(usesSelfCover(mapOf("协助测试者" to "自己完成")))
        assertTrue(coverGuideCue(mapOf("协助测试者" to "自己完成")).contains("自己"))
        for (helper in listOf("家长", "医生或护士", "其他")) {
            val fields = mapOf("协助测试者" to helper)
            assertFalse(usesSelfCover(fields))
            assertTrue(coverGuideCue(fields).contains("协助者"))
            assertFalse(coverGuideCue(fields).contains("同时点按"))
        }
    }
}
