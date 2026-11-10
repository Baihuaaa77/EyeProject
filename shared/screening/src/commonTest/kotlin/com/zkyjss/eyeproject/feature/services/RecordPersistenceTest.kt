package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** 在落盘中途销毁页面，写入仍完成；重新开始的页面不接收旧记录的回调。 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordPersistenceTest {
    private class Platform : ScreeningPlatform {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var saved: String? = null
        override val display = DisplayGeometry(400, 800, 160f, 160f)
        override fun nowMs() = 1_000L
        override fun epochMs() = 1_000L
        override fun cameraGeometry(width: Int, height: Int) = CameraGeometry(width, height, 400f)
        override fun startCamera(onFrame: (FaceLandmarkFrame) -> Unit, onError: (Throwable) -> Unit) = Unit
        override fun stopCamera() = Unit
        override fun startLight(onLux: (Float) -> Unit) = false
        override suspend fun compareHistory(state: ScreeningState, assessment: ScreeningAssessment) = emptyList<String>()
        override suspend fun saveRecord(name: String, json: String) { entered.complete(Unit); release.await(); saved = json }
    }

    @Test fun screeningRecordsSurvivePageCancellationForBothKinds() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        for (kind in ScreeningKind.entries) {
            val page = CoroutineScope(SupervisorJob() + dispatcher)
            val application = CoroutineScope(SupervisorJob() + dispatcher)
            val platform = Platform()
            val engine = ScreeningEngine(platform, page, application, dispatcher)
            engine.configure(kind); engine.finish(); runCurrent()
            assertTrue(platform.entered.isCompleted)
            engine.close(); page.cancel(); engine.restart()
            platform.release.complete(Unit); runCurrent()
            assertNotNull(platform.saved, kind.name)
            assertNull(engine.state.value.savedFile, "旧保存完成不污染重新开始的状态")
            assertEquals(ScreeningStep.PROFILE, engine.state.value.step)
            application.cancel()
        }
    }

    @Test fun colorRecordSurvivesPageCancellation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val page = CoroutineScope(SupervisorJob() + dispatcher)
        val application = CoroutineScope(SupervisorJob() + dispatcher)
        val platform = Platform()
        val engine = ColorVisionEngine(platform, page, dispatcher, application)
        engine.skipProfile(); engine.start(); engine.answer("12")
        repeat(8) { engine.answer(engine.uiState.value.plate!!.number) }
        runCurrent(); assertTrue(platform.entered.isCompleted)
        page.cancel(); engine.restart()
        platform.release.complete(Unit); runCurrent()
        assertNotNull(platform.saved)
        assertNull(engine.uiState.value.savedFile)
        assertEquals(ColorVisionPhase.Setup, engine.uiState.value.phase)
        application.cancel()
    }
}
