package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 视标作答计时从界面回报「已画出」时开始（借鉴同学工程的呈现确认）；没有回报时仍按发出时刻计。 */
class DryEyeTimingTest {
    private class Platform(private val time: () -> Long) : ScreeningPlatform {
        override val display = DisplayGeometry(1080, 2400, 420f, 420f)
        var onFrame: ((FaceLandmarkFrame) -> Unit)? = null
        override fun nowMs() = time()
        override fun epochMs() = time()
        override fun cameraGeometry(width: Int, height: Int) = CameraGeometry(width, height, 400f)
        override fun startCamera(onFrame: (FaceLandmarkFrame) -> Unit, onError: (Throwable) -> Unit) { this.onFrame = onFrame }
        override fun stopCamera() { onFrame = null }
        override fun startLight(onLux: (Float) -> Unit) = false
        override fun feedback(cue: ScreeningFeedbackCue) = Unit
        override suspend fun saveRecord(name: String, json: String) = Unit
        override suspend fun compareHistory(state: ScreeningState, assessment: ScreeningAssessment) = emptyList<String>()
    }

    /** 约 40 cm、双眼可见、头位端正的相机帧（虹膜 11.7 px、焦距 400 px）。 */
    private fun frame(now: Long): FaceLandmarkFrame {
        val eye = EyeLandmarks("LEFT", listOf(Point2f(0f, 0f), Point2f(20f, -10f), Point2f(60f, -10f), Point2f(80f, 0f),
            Point2f(60f, 10f), Point2f(20f, 10f)), listOf(Point2f(40f, 0f)), 11.7f)
        return FaceLandmarkFrame(listOf(FaceMetrics(BoundingBox(0f, 0f, 300f, 300f), eye, eye, headPose = HeadPoseEstimate(0f, 0f, 0f))),
            400, 400, now, 0, 30f, 150f, now)
    }

    @Test fun timeLimitStartsWhenTheOptotypeIsDrawn() = runTest {
        val platform = Platform { 1_000 + testScheduler.currentTime }
        val engine = DryEyeEngine(platform, backgroundScope)
        engine.skipProfile(); engine.startMonitoring()
        backgroundScope.launch { while (true) { platform.onFrame?.invoke(frame(platform.nowMs())); delay(33) } }
        advanceTimeBy(1_500); runCurrent()
        engine.startTest(); engine.confirmPracticeGuide(); runCurrent()
        val first = engine.uiState.value
        assertTrue(first.trialVisible, "练习开始后出第一题")

        // 界面 200 ms 后才画出：2 秒时限从画出时起算，发出后 2.15 秒仍在显示
        advanceTimeBy(200); engine.markTrialPresented(first.trialId, platform.nowMs()); runCurrent()
        engine.markTrialPresented(first.trialId - 1, platform.nowMs()) // 旧题号回报被忽略
        advanceTimeBy(1_950); runCurrent()
        assertEquals(first.trialId, engine.uiState.value.trialId)
        assertTrue(engine.uiState.value.trialVisible)
        advanceTimeBy(100); runCurrent()
        assertFalse(engine.uiState.value.trialVisible && engine.uiState.value.trialId == first.trialId, "画出后满 2 秒超时")

        // 下一题界面一直不回报：仍在发出后 2 秒结束，不会无限等待
        advanceTimeBy(400); runCurrent()
        val second = engine.uiState.value
        assertTrue(second.trialVisible && second.trialId == first.trialId + 1)
        advanceTimeBy(2_050); runCurrent()
        assertFalse(engine.uiState.value.trialVisible && engine.uiState.value.trialId == second.trialId)
        engine.close()
    }

    /** 方案二：看不到双眼持续 1 秒即暂停（计时冻结、收起视标），恢复后点「继续」才接着计时。 */
    @Test fun lostEyesPauseTheTimerUntilTheUserContinues() = runTest {
        val platform = Platform { 1_000 + testScheduler.currentTime }
        val engine = DryEyeEngine(platform, backgroundScope)
        engine.skipProfile(); engine.startMonitoring()
        var eyes = true
        backgroundScope.launch {
            while (true) {
                val now = platform.nowMs()
                platform.onFrame?.invoke(if (eyes) frame(now) else FaceLandmarkFrame(emptyList(), 400, 400, now, 0, 30f, 150f, now))
                delay(33)
            }
        }
        advanceTimeBy(1_500); runCurrent()
        engine.startTest(); engine.confirmPracticeGuide(); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        val before = engine.uiState.value.remainingSeconds
        eyes = false
        advanceTimeBy(1_400); runCurrent()
        val paused = engine.uiState.value
        assertTrue(paused.paused, "看不到双眼 1 秒后暂停")
        assertFalse(paused.trialVisible, "暂停时不出题")
        advanceTimeBy(5_000); runCurrent()
        assertEquals(paused.remainingSeconds, engine.uiState.value.remainingSeconds, "暂停期间计时不走")
        engine.resumeFromPause(); runCurrent()
        assertTrue(engine.uiState.value.paused, "还没恢复正常时点继续无效")
        eyes = true
        advanceTimeBy(1_000); runCurrent()
        engine.resumeFromPause(); advanceTimeBy(200); runCurrent()
        assertFalse(engine.uiState.value.paused)
        assertTrue(engine.uiState.value.remainingSeconds <= before, "继续后接着原来的时间倒数")
        advanceTimeBy(1_000); runCurrent()
        assertTrue(engine.uiState.value.trialVisible, "继续后接着出题")
        engine.close()
    }
}
