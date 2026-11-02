package com.zkyjss.eyeproject.feature.services

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.zkyjss.eyeproject.core.camera.CameraController
import com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider
import com.zkyjss.eyeproject.core.designsystem.theme.EyeProjectTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.sin

/**
 * 三项测试各环节的整屏截图（真实页面 + 注入的引擎状态，不启动相机），输出到
 * build/reports/walkthrough/，文件名按测试流程编号。用于评审界面，不做像素比对。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenWalkthroughCapture {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    // region 干眼

    @Test fun dryEye() {
        val vm = DryEyeTestViewModel(context, CameraController(context), CameraIntrinsicsProvider(context), TestScreeningRecordRepository(context), TestSubjectRepository(), TestExplanationRepository())
        @Suppress("UNCHECKED_CAST")
        val state = DryEyeEngine::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
            .get(vm.engine) as MutableStateFlow<DryEyeTestUiState>
        val ready = DryEyeTestUiState(faceDetected = true, distanceCm = 40f, gateReady = true, lux = 320f)
        state.value = DryEyeTestUiState()
        compose.setContent { EyeProjectTheme { DryEyeScreen(onBack = {}, onEditSubject = {}, viewModel = vm) } }
        shot("干眼-01-选择被测者")
        state.value = ready.copy(phase = DryEyePhase.Setup)
        shot("干眼-02-准备与测距")
        state.value = ready.copy(phase = DryEyePhase.Setup, distanceCm = 31f, gateReady = false)
        shot("干眼-03-准备-太近")
        state.value = ready.copy(phase = DryEyePhase.Setup, distanceCm = 49f, gateReady = false)
        shot("干眼-03a-准备-太远")
        state.value = ready.copy(phase = DryEyePhase.Setup, faceDetected = false, distanceCm = 0f, gateReady = false)
        shot("干眼-03b-准备-未看到双眼")
        state.value = ready.copy(phase = DryEyePhase.PracticeGuide)
        shot("干眼-04-练习说明")
        state.value = ready.copy(phase = DryEyePhase.Practice, trialVisible = true, trialId = 1, direction = 0, optotypePx = 31f,
            remainingSeconds = 8, progressFraction = 0.3f)
        shot("干眼-05-练习")
        state.value = ready.copy(phase = DryEyePhase.FormalGuide)
        shot("干眼-06-正式说明")
        state.value = ready.copy(phase = DryEyePhase.Countdown, remainingSeconds = 3, instruction = "建议佩戴最佳矫正眼镜，保持约 40 cm")
        shot("干眼-07-倒计时")
        state.value = ready.copy(phase = DryEyePhase.Natural, trialVisible = true, trialId = 2, direction = 1, optotypePx = 24f,
            remainingSeconds = 42, progressFraction = 0.3f, phase1BlinkCount = 4)
        shot("干眼-08-自然眨眼")
        state.value = state.value.copy(distanceCm = 32f, gateReady = false)
        shot("干眼-08a-自然眨眼-太近")
        state.value = state.value.copy(distanceCm = 48f, gateReady = false, headTurned = true, qualityWarning = "请把头摆正")
        shot("干眼-08b-自然眨眼-太远且头偏")
        state.value = ready.copy(phase = DryEyePhase.Open, trialVisible = true, trialId = 3, direction = 3, optotypePx = 24f,
            remainingSeconds = 12, progressFraction = 0.4f)
        shot("干眼-09-持续睁眼")
        state.value = state.value.copy(distanceCm = 52f, gateReady = false)
        shot("干眼-10-持续睁眼-太远")
        state.value = ready.copy(phase = DryEyePhase.Rest, remainingSeconds = 42, progressFraction = 0.3f)
        shot("干眼-11-闭眼休息")

        val trials = generateSequence(0f) { it + 1.2f }.takeWhile { it < 80f }.map { t ->
            DryEyeTrial(if (t < 60f) DryEyeStage.NATURAL else DryEyeStage.OPEN, t, 0.5f, 0, if ((t * 10).toInt() % 7 == 0) 1 else 0, 700L, 40f, 30f)
        }.toList()
        val blinks = listOf(4f, 13f, 22f, 31f, 40f, 48f, 55f, 75f)
        val session = DryEyeSession(60, 0.5f, trials, blinks, 0.97, 0.92, 0.03, false, true, analysisFps = 30.0)
        val report = DryEyeAssessment.evaluate(session, mapOf("干眼症状" to "眼睛发干、发涩", "症状频率" to "有时"))
        state.value = ready.copy(phase = DryEyePhase.Result, report = report, savedFile = "dryeye_demo.json",
            phase1Blinks = blinks.filter { it < 60f }, phase1BlinkCount = 7, phase2Blinks = listOf(15f), phase2BlinkCount = 1,
            distanceSamples = (0..160).map { DryEyeDistanceSample(it * 0.5f, 40f + 2f * sin(it / 9f)) })
        shot("干眼-12-结果")
        compose.onNodeWithText("看 E 时视力是否变差").performScrollTo().performClick()
        shot("干眼-13-结果-判断依据展开")
        compose.onNodeWithText("查看本次数据").performScrollTo()
        shot("干眼-14-结果-底部操作")
        compose.onNodeWithText("查看本次数据").performClick()
        shot("干眼-15-本次测试数据", popup = true)
    }

    // endregion

    // region 老视

    @Test fun presbyopia() {
        val me = SubjectProfile("me", "SELF", "妈妈", mapOf("出生年份" to "1974"), 0L)
        val vm = ScreeningTestViewModel(context, CameraController(context), CameraIntrinsicsProvider(context), TestScreeningRecordRepository(context), TestSubjectRepository(listOf(me)), TestExplanationRepository())
        val state = stateOf(vm)
        val intake = with(PresbyopiaIntake) { mapOf(REFRACTION to REFRACTION_YES, GLASSES_USE to USE_DISTANCE,
            DISTANCE_NEED to NEED_CLEAR_WITH, TEST_STATE to STATE_DISTANCE) }
        val base = ScreeningState(kind = ScreeningKind.PRESBYOPIA, presbyopiaPath = PresbyopiaPath.A1, mode = PresbyopiaPath.A1.modeLabel,
            faceDetected = true, distanceCm = 40f, fields = mapOf("年龄" to "52") + intake, lux = 300f, lightSensorAvailable = true,
            subject = TestSubject("me", "妈妈", "SELF"))
        state.value = base.copy(step = ScreeningStep.PROFILE, fields = mapOf("年龄" to "52"))
        compose.setContent { EyeProjectTheme { PresbyopiaScreen(onBack = {}, onEditSubject = {}, viewModel = vm) } }
        shot("老视-01-选择被测者与戴镜问卷")
        compose.onNodeWithText(PresbyopiaIntake.REFRACTION_YES).performScrollTo().performClick()
        compose.onNodeWithText(PresbyopiaIntake.USE_SAME).performScrollTo().performClick()
        shot("老视-01a-戴镜问卷-同一副眼镜追问")
        state.value = base.copy(step = ScreeningStep.ENVIRONMENT, trackingStatus = "双眼跟踪与测距正常")
        shot("老视-02-准备与测距")
        state.value = base.copy(step = ScreeningStep.NEAR_VISION, guidePending = true, glassesCheckPending = true,
            nearAwaitingEyeReady = true, instruction = PresbyopiaIntake.glassesCheck(base.fields).prompt)
        shot("老视-03-戴镜确认")
        state.value = base.copy(step = ScreeningStep.NEAR_VISION, visionEye = VisionEye.RIGHT, trialVisible = true, trialId = 1,
            direction = 2, trialOptotypePx = 20f, visionLevel = 0.5f)
        shot("老视-04-近视力-作答")
        state.value = base.copy(step = ScreeningStep.DISTANCE_CURVE, curveIndex = 0, distanceCm = 67f, trialVisible = true, trialId = 3,
            direction = 1, trialOptotypePx = 22f)
        shot("老视-05-多距离视力-67厘米")
        state.value = base.copy(step = ScreeningStep.DISTANCE_CURVE, curveIndex = 1, distanceCm = 64f, distanceMoveToCm = 50f)
        shot("老视-05a-多距离-请移动到50厘米")
        state.value = base.copy(step = ScreeningStep.PUSH_UP, guidePending = true)
        shot("老视-06-单眼移近法说明")
        state.value = base.copy(step = ScreeningStep.PUSH_UP, pushStage = PushStage.READY, distanceCm = 48f, visionLevel = 0.5f,
            visionEye = VisionEye.LEFT, nearPoints = List(3) { NearPoint(30f, 1.6f, 50f, eye = VisionEye.RIGHT) })
        shot("老视-06a-移近法-左眼准备")
        state.value = base.copy(step = ScreeningStep.PUSH_UP, pushStage = PushStage.APPROACH, distanceCm = 27f, trialVisible = true,
            trialId = 2, trialOptotypePx = 20f, pushSpeedCmPerSec = 4f, visionEye = VisionEye.RIGHT)
        shot("老视-06b-移近法-慢一点")
        state.value = base.copy(step = ScreeningStep.FLIPPER, guidePending = true)
        shot("老视-07-远近切换-可选")
        state.value = base.copy(step = ScreeningStep.FLIPPER, flipperCheck = true, distanceCm = 25f, secondsLeft = 60, trialIndex = 1,
            trialVisible = true, trialId = 4, direction = 0, trialOptotypePx = 16f)
        shot("老视-07a-远近切换-先确认看得清")
        state.value = base.copy(step = ScreeningStep.FLIPPER, distanceCm = 50f, secondsLeft = 38, trialIndex = 2, trialVisible = true,
            trialId = 5, direction = 3, trialOptotypePx = 16f)
        shot("老视-07b-远近切换-计时中")

        val answers = VisionEye.entries.flatMap { eye -> List(4) { VisionAnswer(ScreeningStep.NEAR_VISION, 40f, 0.6f, 0, 0, true, 700, 20f, eye) } } +
            List(9) { VisionAnswer(ScreeningStep.FLIPPER, if (it % 2 == 0) 50f else 25f, 0.2f, 1, 1, it != 5, 900, 20f) }
        val curve = listOf(CurvePoint(67f, 0.8f), CurvePoint(50f, 0.6f), CurvePoint(33f, 0.4f))
        val measured = base.copy(answers = answers, curvePoints = curve, nearPoints =
            listOf(32f, 33.5f, 32.5f).map { NearPoint(it, 1.8f, 50f, eye = VisionEye.RIGHT) } +
            listOf(35f, 36f, 34f).map { NearPoint(it, 1.6f, 50f, eye = VisionEye.LEFT) })
        fun result(s: ScreeningState) = ScreeningAssessmentModel.presbyopia(s).let { a ->
            s.copy(step = ScreeningStep.RESULT, riskLevel = a.riskLevel, category = a.category, riskScope = a.riskScope,
                findings = a.findings, limitations = a.limitations, features = a.features,
                evidence = a.evidence, result = a.displayLines(), savedFile = "presbyopia_demo.json")
        }
        state.value = result(measured)
        shot("老视-08-结果-A1")
        compose.onNodeWithText("查看本次数据").performScrollTo()
        shot("老视-08a-结果-底部操作")
        compose.onNodeWithText("查看本次数据").performClick()
        shot("老视-08b-本次测试数据", popup = true)
        // B：戴老花镜测，只评价戴镜后的近视觉
        state.value = result(base.copy(presbyopiaPath = PresbyopiaPath.B, mode = PresbyopiaPath.B.modeLabel,
            fields = base.fields + (PresbyopiaIntake.TEST_STATE to PresbyopiaIntake.STATE_READING), answers = answers.filter { it.step == ScreeningStep.NEAR_VISION },
            curvePoints = curve))
        shot("老视-09-结果-B-戴老花镜")
    }

    // endregion

    // region 斜视

    @Test fun strabismus() {
        val vm = ScreeningTestViewModel(context, CameraController(context), CameraIntrinsicsProvider(context), TestScreeningRecordRepository(context), TestSubjectRepository(), TestExplanationRepository())
        val state = stateOf(vm)
        val base = ScreeningState(kind = ScreeningKind.STRABISMUS, faceDetected = true, distanceCm = 33f,
            fields = mapOf("年龄" to "8", "协助测试者" to "家长"), lux = 300f, lightSensorAvailable = true)
        state.value = base.copy(step = ScreeningStep.PROFILE)
        compose.setContent { EyeProjectTheme { StrabismusScreen(onBack = {}, onEditSubject = {}, viewModel = vm) } }
        shot("斜视-01-选择被测者")
        state.value = base.copy(step = ScreeningStep.ENVIRONMENT, trackingStatus = "双眼跟踪与测距正常", opticalStatus = "双眼光学测量可用")
        shot("斜视-02-准备与测距")
        state.value = base.copy(step = ScreeningStep.ENVIRONMENT, distanceCm = 25f, trackingStatus = "双眼跟踪与测距正常")
        shot("斜视-02a-准备-太近")
        state.value = base.copy(step = ScreeningStep.ENVIRONMENT, distanceCm = 42f, trackingStatus = "双眼跟踪与测距正常")
        shot("斜视-02b-准备-太远")
        state.value = base.copy(step = ScreeningStep.FIXATION, guidePending = true)
        shot("斜视-03-注视与校准说明")
        state.value = base.copy(step = ScreeningStep.GAZE_CALIBRATION, gazeCalibrationIndex = 1)
        shot("斜视-04-注视校准")
        state.value = base.copy(step = ScreeningStep.GAZE_CALIBRATION, gazeCalibrationIndex = 1, gazeCalibrationPaused = true,
            gazeWarning = "校准已暂停：请把头摆正、保持约 33 cm，眼睛看着圆点")
        shot("斜视-05-注视校准-异常暂停")
        state.value = base.copy(step = ScreeningStep.GAZE_CALIBRATION, gazeCalibrationIndex = 3, distanceCm = 41f, gazeCalibrationPaused = true,
            gazeWarning = "校准已暂停：请把头摆正、保持约 33 cm，眼睛看着圆点")
        shot("斜视-05a-注视校准-太远暂停")
        state.value = base.copy(step = ScreeningStep.GAZE_CALIBRATION, gazeCalibrationIndex = 4, gazeCalibrationFailed = true,
            warning = "未获得可靠注视校准。请在光线均匀处保持头部不动后重试。")
        shot("斜视-06-注视校准-失败重试")
        state.value = base.copy(step = ScreeningStep.FIXATION, gazeCalibrated = true, secondsLeft = 9, targetX = 1f)
        shot("斜视-07-稳定注视")
        state.value = state.value.copy(distanceCm = 26f, gazeWarning = "注视明显偏离，请看着屏幕上的小瞳")
        shot("斜视-07a-稳定注视-太近且偏离")
        state.value = state.value.copy(distanceCm = 0f, faceDetected = false, gazeWarning = "暂时无法确认注视，请保持头部稳定并看着屏幕目标")
        shot("斜视-07b-稳定注视-未看到双眼")
        state.value = base.copy(step = ScreeningStep.COVER, gazeCalibrated = true,
            coverMarks = listOf(CoverMark("遮右眼", 0L, covered = VisionEye.RIGHT)), coverHolding = true, instruction = "保持：遮右眼")
        shot("斜视-08-交替遮盖")
        state.value = base.copy(step = ScreeningStep.PURSUIT, gazeCalibrated = true, secondsLeft = 15, targetX = 6f, targetY = 0f)
        shot("斜视-09-追踪")
        state.value = state.value.copy(distanceCm = 40f, gazeWarning = "未检测到有效跟随，请只用眼睛跟随移动的小瞳")
        shot("斜视-09a-追踪-太远且未跟随")

        val (samples, marks) = strabismusSamples()
        val measured = base.copy(coverMarks = marks, gazeCalibrated = true, pursuitMaxDeg = 9f to 9f)
        val assessment = ScreeningAssessmentModel.strabismus(measured, samples, 100, 92)
        state.value = measured.copy(step = ScreeningStep.RESULT, riskLevel = assessment.riskLevel, category = assessment.category,
            findings = assessment.findings, limitations = assessment.limitations, features = assessment.features,
            evidence = assessment.evidence, result = assessment.displayLines(), savedFile = "strabismus_demo.json")
        shot("斜视-10-结果")
        compose.onNodeWithText("注视时眼位").performScrollTo().performClick()
        shot("斜视-11-结果-判断依据展开")
        compose.onNodeWithText("查看本次数据").performClick()
        shot("斜视-12-本次测试数据", popup = true)
    }

    /** 约 4Δ 的轻度水平偏差、遮盖后小幅移动、协调良好的演示数据。 */
    private fun strabismusSamples(): Pair<List<EyeMotionSample>, List<CoverMark>> {
        val fixation = List(120) { i -> EyeMotionSample(i * 100L, ScreeningStep.FIXATION, 0f, 0f, 0f, 0f, 33f,
            leftNasal = 0.123f + if (i % 2 == 0) 0.004f else -0.004f, rightNasal = 0.1f, leftVertical = 0.002f, rightVertical = 0f, gazeOnTarget = true) }
        val marks = listOf(CoverMark("移开遮盖，双眼注视", 15_000, uncovered = VisionEye.RIGHT),
            CoverMark("移开遮盖，双眼注视", 16_000, uncovered = VisionEye.LEFT))
        val cover = marks.flatMap { mark -> listOf(100L to 0.1f, 400L to 0.115f).map { (offset, nasal) ->
            EyeMotionSample(mark.timeMs + offset, ScreeningStep.COVER, 0f, 0f, 0f, 0f, 33f,
                leftNasal = nasal, rightNasal = nasal, leftVertical = 0f, rightVertical = 0f, gazeOnTarget = true)
        } }
        val pursuit = List(72) { i ->
            val segment = i / 24; val a = sin(i * 0.3f) * 0.2f
            val x = if (segment == 1) 0f else a; val y = if (segment == 0) 0f else a
            EyeMotionSample(17_000 + i * 100L, ScreeningStep.PURSUIT, x, y, x * 0.98f, y * 0.98f, 33f, pursuitSegment = segment,
                targetScreenX = 0.5f + x, targetScreenY = 0.5f + y,
                leftGazeX = 0.5f + x, leftGazeY = 0.5f + y, rightGazeX = 0.5f + x, rightGazeY = 0.5f + y, gazeOnTarget = true)
        }
        return fixation + cover + pursuit to marks
    }

    // endregion

    @Suppress("UNCHECKED_CAST")
    private fun stateOf(vm: ScreeningTestViewModel) = ScreeningEngine::class.java.getDeclaredField("_state").apply { isAccessible = true }
        .get(vm.engine) as MutableStateFlow<ScreeningState>

    /** 整屏截图；[popup] 截底板等弹出窗口（叠在页面之上）。 */
    private fun shot(name: String, popup: Boolean = false) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(900)
        val bitmap = compose.runOnIdle {
            val views = android.view.inspector.WindowInspector.getGlobalWindowViews()
            val page = compose.activity.window.decorView
            val top = if (popup) views.last() else page
            Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888).also { out ->
                val canvas = android.graphics.Canvas(out)
                page.draw(canvas)
                if (popup && top !== page) {
                    canvas.drawColor(0x66000000)
                    val location = IntArray(2).also(top::getLocationOnScreen)
                    canvas.translate(0f, location[1].toFloat().coerceAtLeast(page.height - top.height.toFloat()))
                    top.draw(canvas)
                }
            }
        }
        val output = File("build/reports/walkthrough/$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
