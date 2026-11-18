package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

/** 绘制时刻的轨迹独立于状态快照频率，并与采样使用同一单调时钟。 */
@OptIn(ExperimentalCoroutinesApi::class)
class PursuitTimingTest {
    @Test fun closerDistanceAndIndependentAxesUseMoreOfTallScreen() {
        val distance = StrabismusProtocol.PURSUIT_DISTANCE_CM
        assertEquals(25f to 5f, screeningDistanceTarget(ScreeningState(kind = ScreeningKind.STRABISMUS, step = ScreeningStep.PURSUIT)))
        val xLimit = StrabismusProtocol.pursuitDisplayLimit(28f, distance)
        val yLimit = StrabismusProtocol.pursuitDisplayLimit(65f, distance)
        assertTrue(xLimit > StrabismusProtocol.pursuitDisplayLimit(28f, 33f))
        assertEquals(10f, yLimit)
        val vertical = StrabismusProtocol.pursuitDisplayOffset(GazePoint(0f, 10f), xLimit, yLimit, distance)
        val diagonal = StrabismusProtocol.pursuitDisplayOffset(GazePoint(7f, -7f), xLimit, yLimit, distance)
        assertTrue(vertical.y > 40f)
        assertEquals(diagonal.x, -diagonal.y, 0.001f)
    }

    @Test fun renderPositionAdvancesBetweenStateTicksAndUsesProtocolTime() = runTest {
        val platform = object : ScreeningPlatform {
            override val display = DisplayGeometry(400, 800, 160f, 160f)
            override fun nowMs() = 1_000L + testScheduler.currentTime
            override fun epochMs() = nowMs()
            override fun cameraGeometry(width: Int, height: Int) = CameraGeometry(width, height, 400f)
            override fun startCamera(onFrame: (FaceLandmarkFrame) -> Unit, onError: (Throwable) -> Unit) = Unit
            override fun stopCamera() = Unit
            override fun startLight(onLux: (Float) -> Unit) = false
            override suspend fun saveRecord(name: String, json: String) = Unit
            override suspend fun compareHistory(state: ScreeningState, assessment: ScreeningAssessment) = emptyList<String>()
        }
        val engine = ScreeningEngine(platform, backgroundScope)
        engine.configure(ScreeningKind.STRABISMUS); engine.startPursuit(); engine.confirmGuide(); runCurrent()
        val first = engine.pursuitTargetAt(platform.nowMs())
        val oldStateX = engine.state.value.targetX
        advanceTimeBy(16); runCurrent()
        val second = engine.pursuitTargetAt(platform.nowMs())
        assertEquals(oldStateX, engine.state.value.targetX, "尚未到 50 ms 状态更新")
        assertTrue(second.x > first.x, "显示帧已经可以前进，无需等待状态更新")
        val expected = StrabismusProtocol.pursuitTarget(1.234f)
        val actual = engine.pursuitTargetAt(engine.stepStartMs + 1_234)
        assertEquals(expected.first, actual.x, 0.00001f)
        assertEquals(expected.second, actual.y, 0.00001f)
        val positionBeforeHelp = engine.pursuitTargetAt(platform.nowMs())
        val stateBeforeHelp = engine.state.value
        engine.setHelpPresented(true)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(stateBeforeHelp, engine.state.value, "查看演示时不消耗追踪时长")
        assertEquals(positionBeforeHelp, engine.pursuitTargetAt(platform.nowMs()), "绘制轨迹也冻结在同一时钟")
        engine.setHelpPresented(false)
        advanceTimeBy(16); runCurrent()
        assertTrue(engine.pursuitTargetAt(platform.nowMs()).x > positionBeforeHelp.x)
        engine.reportPursuitCapacity(4f, 7f)
        engine.reportPursuitCapacity(6f, 10f)
        assertEquals(6f to 10f, engine.state.value.pursuitMaxDeg, "布局扩大后更新实际可呈现范围")
        engine.close()
    }
}
