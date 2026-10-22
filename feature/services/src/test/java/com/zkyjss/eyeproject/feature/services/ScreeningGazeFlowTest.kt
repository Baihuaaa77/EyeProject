package com.zkyjss.eyeproject.feature.services

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.zkyjss.eyeproject.core.camera.CameraController
import com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider
import com.zkyjss.eyeproject.core.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreeningGazeFlowTest {
    private fun viewModel(): ScreeningTestViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ScreeningTestViewModel(context, CameraController(context), CameraIntrinsicsProvider(context), TestScreeningRecordRepository(context), TestSubjectRepository(), TestExplanationRepository()).also { vm ->
            @Suppress("UNCHECKED_CAST")
            val state = ScreeningEngine::class.java.getDeclaredField("_state").apply { isAccessible = true }
                .get(vm.engine) as MutableStateFlow<ScreeningState>
            state.value = ScreeningState(kind = ScreeningKind.STRABISMUS, step = ScreeningStep.FIXATION, guidePending = true)
        }
    }

    @Test fun failedCalibrationStopsAndRetryCanBeInterruptedWithoutOldTimerAdvancing() {
        val vm = viewModel()
        vm.confirmGuide()
        assertEquals(ScreeningStep.GAZE_CALIBRATION, vm.state.value.step)
        // 没有相机帧时每点采样不足，按最长等待时间顺延后才判失败
        val calibrationSeconds = StrabismusGaze.calibrationTargets.size * StrabismusGaze.CALIBRATION_POINT_MAX_MS / 1000
        ShadowLooper.idleMainLooper(calibrationSeconds - 1, TimeUnit.SECONDS)
        assertFalse(vm.state.value.gazeCalibrationFailed)
        assertEquals(ScreeningStep.GAZE_CALIBRATION, vm.state.value.step)
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        assertTrue(vm.state.value.gazeCalibrationFailed)
        assertFalse(vm.state.value.gazeCalibrated)
        assertEquals(ScreeningStep.GAZE_CALIBRATION, vm.state.value.step)
        vm.retryGazeCalibration()
        assertFalse(vm.state.value.gazeCalibrationFailed)
        assertEquals(0, vm.state.value.gazeCalibrationIndex)
        vm.interruptTest()
        ShadowLooper.idleMainLooper(30, TimeUnit.SECONDS)
        assertEquals(ScreeningStep.ENVIRONMENT, vm.state.value.step)
        assertFalse(vm.state.value.gazeCalibrated)
    }

    @Test fun calibrationIsSeparateFromTheFullTwelveSecondFixationTask() {
        val vm = viewModel()
        vm.confirmGuide()
        @Suppress("UNCHECKED_CAST")
        val calibration = ScreeningEngine::class.java.getDeclaredField("gazeCalibration").apply { isAccessible = true }
            .get(vm.engine) as MutableList<GazeCalibrationSample>
        StrabismusGaze.calibrationTargets.forEachIndexed { index, target ->
            repeat(6) {
                calibration.add(GazeCalibrationSample(index, target,
                    GazePoint((target.x - 0.5f) / 5f, (target.y - 0.5f) / 3f), null))
            }
        }
        // 注入的样本不计入实时采样时长，每点等到最长时间后换点
        ShadowLooper.idleMainLooper(StrabismusGaze.calibrationTargets.size * StrabismusGaze.CALIBRATION_POINT_MAX_MS / 1000, TimeUnit.SECONDS)
        assertTrue(vm.state.value.gazeCalibrated)
        assertEquals(ScreeningStep.FIXATION, vm.state.value.step)
        ShadowLooper.idleMainLooper(11, TimeUnit.SECONDS)
        assertEquals(ScreeningStep.FIXATION, vm.state.value.step)
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        assertEquals(ScreeningStep.COVER, vm.state.value.step)
        vm.interruptTest()
    }

    @Test fun coveredEyesPredictionCannotMaskTheUncoveredEyesLookAway() {
        val vm = viewModel()
        fun field(name: String) = ScreeningEngine::class.java.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field("_state").get(vm.engine) as MutableStateFlow<ScreeningState>
        state.value = ScreeningState(kind = ScreeningKind.STRABISMUS, step = ScreeningStep.COVER, gazeCalibrated = true,
            coverMarks = listOf(CoverMark("遮左眼", 0L, covered = VisionEye.LEFT)))
        val calibration = StrabismusGaze.calibrationTargets.flatMapIndexed { index, target ->
            List(6) { GazeCalibrationSample(index, target,
                GazePoint((target.x - 0.5f) / 5f, (target.y - 0.5f) / 3f),
                GazePoint((target.x - 0.5f) / 5f, (target.y - 0.5f) / 3f)) }
        }
        field("gaze").set(vm.engine, StrabismusGaze.calibrate(calibration))
        field("cachedFocalWidth").set(vm.engine, 400)
        field("cachedFocalPx").set(vm.engine, 400f)
        field("cachedGeometry").set(vm.engine, CameraGeometry(400, 400, 400f))
        field("cachedCalibrationVersion").set(vm.engine, 0L)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val display = context.resources.displayMetrics
        vm.reportGazeTarget(display.widthPixels * 0.5f, display.heightPixels * 0.5f)
        val process = ScreeningEngine::class.java.getDeclaredMethod("processFrame", Long::class.javaPrimitiveType,
            ScreeningStep::class.java, FaceLandmarkFrame::class.java).apply { isAccessible = true }
        fun eye(label: String, centerX: Float, rawX: Float, rawY: Float) = EyeLandmarks(label,
            listOf(Point2f(centerX - 40, 100f), Point2f(centerX - 20, 90f), Point2f(centerX + 20, 90f),
                Point2f(centerX + 40, 100f), Point2f(centerX + 20, 110f), Point2f(centerX - 20, 110f)),
            listOf(Point2f(centerX + rawX * 80f, 100f + rawY * 80f)), 11.7f * 400f / 330f)
        fun feed(rightX: Float, rightY: Float) {
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            val now = SystemClock.elapsedRealtime()
            val face = FaceMetrics(BoundingBox(40f, 40f, 360f, 360f), eye("LEFT", 100f, 0f, 0f),
                eye("RIGHT", 300f, rightX, rightY), headPose = HeadPoseEstimate(0f, 0f, 0f))
            process.invoke(vm.engine, now, ScreeningStep.COVER, FaceLandmarkFrame(listOf(face), 400, 400, now, 0L, 10f))
        }
        repeat(30) { feed(0.09f, 0.15f) }  // 看偏持续 2.5 秒以上才提醒
        assertTrue(vm.state.value.gazeWarning!!.contains("偏离"))
        @Suppress("UNCHECKED_CAST")
        val samples = field("samples").get(vm.engine) as List<EyeMotionSample>
        assertEquals(false, samples.last().gazeOnTarget)
        assertNull(samples.last().leftGazeX)
        feed(0f, 0f)
        assertNull(vm.state.value.gazeWarning)
        assertEquals(true, samples.last().gazeOnTarget)
        vm.interruptTest()
    }
}
