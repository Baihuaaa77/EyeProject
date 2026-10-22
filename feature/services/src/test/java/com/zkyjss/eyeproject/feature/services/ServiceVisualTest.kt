package com.zkyjss.eyeproject.feature.services

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.*
import com.zkyjss.eyeproject.core.ui.component.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 运行真实 Compose 组件，使用本地 Android 图形渲染；不启动相机或模型。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServiceVisualTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun feedbackEventsReachTheDeviceOnlyWhileTheTestIsActive() {
        val context = compose.activity
        val preferences = context.getSharedPreferences("screening_feedback_effect_test", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val settings = TestSettings(preferences).apply { setSound(false) }
        val voice = ScreeningVoice(context)
        val events = ScreeningFeedbackSession()
        var menuOpen by androidx.compose.runtime.mutableStateOf(false)
        val vibration = org.robolectric.Shadows.shadowOf(context.getSystemService(android.os.Vibrator::class.java))
        vibration.setHasVibrator(true)
        try {
            compose.setContent {
                TestFeedbackEffect(settings, voice, menuOpen) { enabled ->
                    events.poll(ScreeningFeedbackFrame("NEAR_VISION", true), android.os.SystemClock.elapsedRealtime(), enabled)
                }
            }
            compose.runOnIdle { events.offer(ScreeningFeedbackCue.ANSWER) }
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
            assertEquals(android.os.VibrationEffect.EFFECT_CLICK, vibration.effectId)
            compose.runOnIdle { menuOpen = true }
            compose.mainClock.advanceTimeBy(100)
            compose.runOnIdle { events.offer(ScreeningFeedbackCue.WARNING) }
            compose.mainClock.advanceTimeBy(100)
            compose.runOnIdle { menuOpen = false }
            compose.mainClock.advanceTimeBy(200)
            compose.waitForIdle()
            assertEquals("菜单期间不应播放或补播双震动告警", android.os.VibrationEffect.EFFECT_CLICK, vibration.effectId)
        } finally { voice.close() }
    }

    @Test fun servicesNavigation() {
        var destination = ""
        compose.setContent { EyeProjectTheme { ServicesScreen({ destination = "dry" }, { destination = "near" }, { destination = "cover" }) } }
        capture("services-light")
        compose.onNodeWithText("干眼自测").performClick()
        assertEquals("dry", destination)
        compose.onNodeWithText("老视自测").performClick()
        assertEquals("near", destination)
        compose.onNodeWithText("眼位自测").performClick()
        assertEquals("cover", destination)
    }

    @Test fun guideAndDisclosure() {
        var confirmed = 0
        compose.setContent {
            EyeProjectTheme {
                Column(Modifier.fillMaxSize().background(LocalEyeColors.current.appBackground).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TestGuide(GuideMotion.MOVE, "移近测试", guideCue(ScreeningStep.PUSH_UP)) { confirmed++ }
                    EyeDisclosure("测量详情") { EyeText("40 cm · 300 lux", style = LocalEyeTypography.current.body) }
                }
            }
        }
        compose.onNodeWithText("40 cm · 300 lux").assertDoesNotExist()
        capture("guide-light")
        compose.onNodeWithText("准备好了").performClick()
        assertEquals(1, confirmed)
        compose.onNodeWithText("测量详情").performClick()
        compose.onNodeWithText("40 cm · 300 lux").assertIsDisplayed()
    }

    @Test fun smallDarkLargeText() {
        compose.setContent {
            EyeProjectTheme(darkTheme = true) {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                    Column(Modifier.width(320.dp).fillMaxHeight().background(LocalEyeColors.current.appBackground).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        TestGuide(GuideMotion.COVER, "交替遮眼", guideCue(ScreeningStep.COVER)) {}
                        EyeButton("开始测试", EyeButtonRole.PRIMARY, {}, Modifier.fillMaxWidth(), enabled = false)
                    }
                }
            }
        }
        compose.onNodeWithText("开始测试").assertIsNotEnabled()
        compose.onNodeWithText("准备好了").performScrollTo().assertIsDisplayed()
        capture("guide-dark-large-text")
    }

    @Test fun dryEyeGateAndDetails() {
        var started = false
        compose.setContent {
            EyeProjectTheme {
                Column(Modifier.fillMaxSize().background(LocalEyeColors.current.appBackground).padding(20.dp)) {
                    SetupGate(DryEyeTestUiState(distanceCm = 30f, faceDetected = true, gateReady = false)) { started = true }
                }
            }
        }
        compose.onNodeWithText("开始测试").assertIsNotEnabled()
        compose.onNodeWithText("太近了").assertIsDisplayed()
        capture("dry-eye-setup")
        assertEquals(false, started)
    }

    /** 测试开始页只选人：缺必需项的成员去「我的」补充，资料齐全的直接开始（老视再选戴镜方式）。 */
    @Test fun subjectPickerStartsCompleteMembersAndSendsOthersToProfile() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val members = listOf(
            SubjectProfile("me", "SELF", "妈妈", mapOf("出生年份" to "1975", "日常矫正方式" to "老花镜", "屈光情况" to "老花"), 0L),
            SubjectProfile("kid", "CHILD", "小明", mapOf("出生年份" to "2018"), 0L),
        )
        val vm = ScreeningTestViewModel(context,
            com.zkyjss.eyeproject.core.camera.CameraController(context),
            com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider(context), TestScreeningRecordRepository(context), TestSubjectRepository(members), TestExplanationRepository())
        vm.configure(ScreeningKind.PRESBYOPIA)
        var edited: String? = "none"
        compose.setContent {
            EyeProjectTheme {
                Column(Modifier.fillMaxSize().background(LocalEyeColors.current.appBackground).verticalScroll(rememberScrollState()).padding(20.dp)) {
                    val state by vm.state.collectAsState()
                    ProfileContent(ScreeningKind.PRESBYOPIA, state, vm) { edited = it }
                }
            }
        }
        compose.onNodeWithText("小明").performClick()
        assertEquals("kid", edited)
        compose.onNodeWithText("添加家庭成员").performScrollTo().performClick()
        assertEquals(null, edited)
        compose.onNodeWithText("妈妈").performScrollTo().performClick()
        compose.onNodeWithText("这次测试戴哪副眼镜？").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("开始").assertIsNotEnabled()
        compose.onNodeWithText("戴看远的眼镜，或不戴眼镜").performScrollTo().performClick()
        capture("profile")
        compose.onNodeWithText("开始").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ScreeningStep.ENVIRONMENT, vm.state.value.step) }
        // 年龄由出生年份换算
        assertEquals((java.time.Year.now().value - 1975).toString(), vm.state.value.fields["年龄"])
    }

    @Test fun unknownResultKeepsQualityWarning() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val vm = ScreeningTestViewModel(context,
            com.zkyjss.eyeproject.core.camera.CameraController(context),
            com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider(context), TestScreeningRecordRepository(context), TestSubjectRepository(), TestExplanationRepository())
        compose.setContent {
            EyeProjectTheme {
                Column(Modifier.fillMaxSize().background(LocalEyeColors.current.appBackground).verticalScroll(rememberScrollState()).padding(20.dp)) {
                    ScreeningResultContent(ScreeningState(step = ScreeningStep.RESULT, riskScope = "本次数据不足", result = listOf("有效数据不足")), vm)
                }
            }
        }
        compose.onNodeWithText("关键数据不足，请改善测试条件并重新完成测试。").assertIsDisplayed()
        compose.onNodeWithText("有效数据不足").assertDoesNotExist()
        capture("result-insufficient-data")
        compose.onNodeWithText("查看本次数据").performClick()
        compose.onNodeWithText("有效数据不足").assertExists()
    }

    @Test fun nearVisionUsesVisibleStimulusAndDisabledAnswerGate() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val vm = ScreeningTestViewModel(context,
            com.zkyjss.eyeproject.core.camera.CameraController(context),
            com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider(context), TestScreeningRecordRepository(context), TestSubjectRepository(), TestExplanationRepository())
        compose.setContent {
            EyeProjectTheme(darkTheme = true) {
                Column(Modifier.fillMaxSize().background(LocalEyeColors.current.appBackground).padding(20.dp)) {
                    VisionContent(ScreeningState(step = ScreeningStep.NEAR_VISION, trialVisible = true, trialOptotypePx = 48f), vm)
                }
            }
        }
        compose.onNodeWithContentDescription("向右").assertIsEnabled()
        capture("near-vision-dark")
    }

    @Test fun liveTasksCenterStimuliWithoutExplanatoryRows() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val vm = ScreeningTestViewModel(context,
            com.zkyjss.eyeproject.core.camera.CameraController(context),
            com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider(context), TestScreeningRecordRepository(context), TestSubjectRepository(), TestExplanationRepository())
        var task by androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent {
            EyeProjectTheme {
                TestSessionLayout(if (task == 0) "老视自测" else if (task == 1) "干眼自测" else "眼位自测", {}, {}, true,
                    modifier = Modifier.testTag("test-session"), reflexStimulus = task == 2,
                    progress = when (task) { 0 -> TestProgress(0.82f, 30); 1 -> TestProgress(0.4f, 57); else -> TestProgress(0.15f, 80) },
                    status = { DistanceBanner(distanceStatus(if (task == 0) 50f else if (task == 1) 40f else 33f,
                        true, if (task == 0) 50f else if (task == 1) 40f else 33f, 5f), compact = true) }) {
                    when (task) {
                        0 -> FlipperContent(ScreeningState(step = ScreeningStep.FLIPPER, secondsLeft = 38,
                            trialVisible = true, trialOptotypePx = 12f, faceDetected = true, distanceCm = 50f), vm)
                        1 -> TrialContent(DryEyeTestUiState(phase = DryEyePhase.Natural, trialVisible = true,
                            optotypePx = 24f, remainingSeconds = 48, instruction = "请持续看 E 并选择方向")) {}
                        else -> GazeCalibrationContent(ScreeningState(kind = ScreeningKind.STRABISMUS,
                            step = ScreeningStep.GAZE_CALIBRATION), vm)
                    }
                }
            }
        }
        fun assertCentered() {
            // 舞台在顶栏、进度条与距离横幅之下的内容区居中。
            val viewport = compose.onNodeWithTag("test-content").fetchSemanticsNode().boundsInRoot
            val target = compose.onNodeWithTag("answer-pad").fetchSemanticsNode().boundsInRoot
            assertTrue("测试舞台应在内容区居中", kotlin.math.abs(target.center.y - viewport.center.y) < viewport.height * 0.1f)
        }
        assertCentered()
        compose.onNodeWithTag("test-progress").assertIsDisplayed()
        compose.onNodeWithText("剩余约 30 秒").assertIsDisplayed()
        compose.onNodeWithText("距离合适").assertIsDisplayed()
        compose.onNodeWithText("点选 E 的开口方向").assertDoesNotExist()
        compose.onNodeWithText("调节灵敏度（远近切换）").assertDoesNotExist()
        compose.onNodeWithText("语音").assertDoesNotExist()
        capture("session-presbyopia")
        task = 1
        assertCentered()
        compose.onNodeWithText("请持续看 E 并选择方向").assertDoesNotExist()
        capture("session-dry-eye")
        task = 2
        compose.onNodeWithText("只用眼睛看着圆点，头和手机保持不动。").assertDoesNotExist()
        capture("session-calibration")
    }

    @Test fun voiceSettingsAreInTheGearMenuAndPersistAcrossTests() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences("screening_settings_test", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val settings = TestSettings(preferences)
        val voice = VoiceAnswerState().apply { permissionGranted = true; available = true; heard = "左" }
        var open by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            EyeProjectTheme {
                TestSessionLayout("老视自测", {}, { open = true }, true) { OptotypeAnswerPad(0, 40.dp, true) {} }
                if (open) TestSettingsSheet(settings, voice, { open = false })
            }
        }
        compose.onNodeWithText("语音播报").assertDoesNotExist()
        compose.onNodeWithText("语音作答").assertDoesNotExist()
        compose.onNodeWithContentDescription("系统设置").performClick()
        compose.onNodeWithText("听到：左").assertExists()
        val vibration = org.robolectric.Shadows.shadowOf(context.getSystemService(android.os.Vibrator::class.java))
        vibration.setHasVibrator(true)
        compose.onNodeWithText("试一下反馈").performClick()
        assertEquals(android.os.VibrationEffect.EFFECT_CLICK, vibration.effectId)
        compose.onNodeWithText("音效反馈").performClick()
        compose.onNodeWithText("震动反馈").performClick()
        assertEquals(false, TestSettings(preferences).soundEnabled)
        assertEquals(false, TestSettings(preferences).hapticsEnabled)
        compose.onNodeWithText("试一下反馈").assertIsNotEnabled()
        compose.onNodeWithText("语音播报").performClick()
        compose.onNodeWithText("语音作答").performClick()
        assertEquals(false, TestSettings(preferences).speechEnabled)
        assertEquals(false, TestSettings(preferences).answersEnabled)
        capture("test-settings")
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("语音播报").assertDoesNotExist()
        compose.onNodeWithText("语音作答").assertDoesNotExist()
    }

    @Test fun smallScreenKeepsWarningsAndArrowActionsVisible() {
        compose.setContent {
            EyeProjectTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                    Box(Modifier.size(320.dp, 640.dp)) {
                        TestSessionLayout("干眼自测", {}, {}, true, progress = TestProgress(0.9f, 12),
                            status = { DistanceBanner(distanceStatus(25f, true, 40f, 5f), compact = true) }) {
                            TrialContent(DryEyeTestUiState(phase = DryEyePhase.Open, trialVisible = true,
                                optotypePx = 24f, remainingSeconds = 12)) {}
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("把手机往远处移约 10 cm").assertIsDisplayed()
        compose.onNodeWithText("太近了").assertIsDisplayed()
        compose.onNodeWithText("跳过").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("向下").assertIsDisplayed()
        capture("session-small-large-text")
    }

    @Test fun arrowsSurroundTheOptotypeAndKeepAnswerMapping() {
        val answers = mutableListOf<Int>()
        compose.setContent {
            EyeProjectTheme {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    OptotypeAnswerPad(0, 120.dp, true) { answers.add(it) }
                }
            }
        }
        val target = compose.onNodeWithTag("answer-optotype").fetchSemanticsNode().boundsInRoot
        val up = compose.onNodeWithContentDescription("向上").fetchSemanticsNode().boundsInRoot
        val down = compose.onNodeWithContentDescription("向下").fetchSemanticsNode().boundsInRoot
        val left = compose.onNodeWithContentDescription("向左").fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithContentDescription("向右").fetchSemanticsNode().boundsInRoot
        val skip = compose.onNodeWithText("跳过").fetchSemanticsNode().boundsInRoot
        // 所有作答键都在视标下方；四个方向键环绕中央的「看不清 / 跳过」。
        assertTrue(listOf(up, down, left, right, skip).all { it.top >= target.bottom })
        assertTrue(up.bottom <= skip.top && down.top >= skip.bottom)
        assertTrue(left.right <= skip.left && right.left >= skip.right)
        assertEquals(skip.center.x, up.center.x, 0.01f)
        assertEquals(skip.center.x, down.center.x, 0.01f)
        assertEquals(skip.center.y, left.center.y, 0.01f)
        assertEquals(skip.center.y, right.center.y, 0.01f)
        assertEquals(target.center.x, skip.center.x, 0.01f)
        assertEquals(120f, target.width, 0.01f)
        compose.onNodeWithContentDescription("向上").performClick()
        compose.onNodeWithContentDescription("向左").performClick()
        compose.onNodeWithContentDescription("向右").performClick()
        compose.onNodeWithContentDescription("向下").performClick()
        compose.onNodeWithText("跳过").performClick()
        assertEquals(listOf(3, 2, 0, 1, -1), answers)
        capture("direction-pad")
    }

    @Test fun invisibleOptotypeDisablesAllFiveAnswerOptions() {
        compose.setContent {
            EyeProjectTheme {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    OptotypeAnswerPad(0, 60.dp, false) { error("视标未显示时不可作答") }
                }
            }
        }
        compose.onNodeWithTag("answer-optotype").assertDoesNotExist()
        for (arrow in listOf("向上", "向下", "向左", "向右")) {
            compose.onNodeWithContentDescription(arrow).assertIsNotEnabled()
        }
        compose.onNodeWithText("跳过").assertIsNotEnabled()
    }

    /** 逐组渲染全部演示场景，供人工检查卡通画面。 */
    @Test fun allGuideScenesRender() {
        val groups = GuideMotion.entries.chunked(4)
        var group by androidx.compose.runtime.mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EyeProjectTheme {
                Column(Modifier.fillMaxSize().background(LocalEyeColors.current.appBackground).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    groups[group].forEach { GuideIllustration(it, it.name, Modifier.height(196.dp)) }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_900)
        capture("scenes-1")
        group = 1
        compose.mainClock.advanceTimeBy(2_300)
        capture("scenes-2")
        group = 2
        compose.mainClock.advanceTimeBy(2_700)
        capture("scenes-3")
    }

    @Test fun dryEyeTrialAndCoverCoach() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val vm = ScreeningTestViewModel(context,
            com.zkyjss.eyeproject.core.camera.CameraController(context),
            com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider(context), TestScreeningRecordRepository(context), TestSubjectRepository(), TestExplanationRepository())
        var answered = -1
        var page by androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent {
            EyeProjectTheme {
                Column(Modifier.fillMaxSize().background(LocalEyeColors.current.appBackground).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (page == 0) {
                        TrialContent(DryEyeTestUiState(phase = DryEyePhase.Open, trialVisible = true, trialId = 1, direction = 3,
                            optotypePx = 60f, remainingSeconds = 12, progressFraction = 0.4f, instruction = "现在尽量保持睁眼，继续看 E 选方向",
                            phase2BlinkCount = 1, trialCount = 30)) { answered = it }
                    } else {
                        CoverContent(ScreeningState(kind = ScreeningKind.STRABISMUS, step = ScreeningStep.COVER,
                            coverMarks = listOf(CoverMark("遮右眼", 0L, covered = VisionEye.RIGHT))), vm)
                    }
                }
            }
        }
        compose.onNodeWithText("持续睁眼").assertExists()
        compose.onNodeWithContentDescription("向上").performClick()
        assertEquals(3, answered)
        capture("dry-eye-open")
        page = 1
        compose.onNodeWithText("下一步：移开遮盖，双眼注视").assertExists()
        capture("cover-coach")
    }

    /** 渲染真实的干眼页面（非单独组件），确认任务中常驻距离横幅。 */
    @Test fun dryEyeScreenKeepsDistanceBannerDuringTrials() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val vm = DryEyeTestViewModel(context, com.zkyjss.eyeproject.core.camera.CameraController(context),
            com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider(context), TestScreeningRecordRepository(context), TestSubjectRepository(), TestExplanationRepository())
        @Suppress("UNCHECKED_CAST")
        val state = DryEyeEngine::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
            .get(vm.engine) as kotlinx.coroutines.flow.MutableStateFlow<DryEyeTestUiState>
        state.value = DryEyeTestUiState(phase = DryEyePhase.Open, trialVisible = true, trialId = 1, direction = 2,
            optotypePx = 40f, remainingSeconds = 12, progressFraction = 0.4f, faceDetected = true, distanceCm = 40f, gateReady = true)
        compose.setContent { EyeProjectTheme { DryEyeScreen(onBack = {}, onEditSubject = {}, viewModel = vm) } }
        compose.onNodeWithText("距离合适").assertIsDisplayed()
        compose.onNodeWithText("持续睁眼").assertIsDisplayed()
        capture("dry-eye-screen-open-ok")
        state.value = state.value.copy(distanceCm = 30f)
        compose.onNodeWithText("太近了").assertIsDisplayed()
        capture("dry-eye-screen-open-close")
        state.value = state.value.copy(distanceCm = 52f)
        compose.onNodeWithText("太远了").assertIsDisplayed()
        capture("dry-eye-screen-open-far")
        state.value = state.value.copy(phase = DryEyePhase.Natural, distanceCm = 41f)
        compose.onNodeWithText("距离合适").assertIsDisplayed()
        capture("dry-eye-screen-natural")
    }

    @Test fun calibrationCoversNineWidePoints() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val vm = ScreeningTestViewModel(context,
            com.zkyjss.eyeproject.core.camera.CameraController(context),
            com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider(context), TestScreeningRecordRepository(context), TestSubjectRepository(), TestExplanationRepository())
        var index by androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent {
            EyeProjectTheme {
                TestSessionLayout("眼位自测", {}, {}, true, reflexStimulus = true,
                    progress = screeningProgress(ScreeningState(kind = ScreeningKind.STRABISMUS,
                        step = ScreeningStep.GAZE_CALIBRATION, gazeCalibrationIndex = index)),
                    status = { DistanceBanner(distanceStatus(33f, true, 33f, 5f), compact = true) }) {
                    GazeCalibrationContent(ScreeningState(kind = ScreeningKind.STRABISMUS,
                        step = ScreeningStep.GAZE_CALIBRATION, gazeCalibrationIndex = index), vm)
                }
            }
        }
        for (i in StrabismusGaze.calibrationTargets.indices) {
            index = i
            compose.onNodeWithText("注视校准 · ${i + 1}/9").assertExists()
            capture("calibration-9-$i")
        }
    }

    @Test fun countdownAndRestUseTimerRings() {
        var rest by androidx.compose.runtime.mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EyeProjectTheme {
                Column(Modifier.fillMaxSize().background(LocalEyeColors.current.appBackground).padding(20.dp),
                    verticalArrangement = Arrangement.Center) {
                    DryEyeTimerForTest(if (rest) DryEyeTestUiState(phase = DryEyePhase.Rest, remainingSeconds = 42, progressFraction = 0.3f)
                        else DryEyeTestUiState(phase = DryEyePhase.Countdown, remainingSeconds = 2, instruction = "建议佩戴最佳矫正眼镜，保持约 40 cm"))
                }
            }
        }
        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithText("2").assertExists()
        capture("dry-eye-countdown")
        compose.mainClock.autoAdvance = true
        rest = true
        compose.waitForIdle()
        compose.onNodeWithText("42").assertExists()
        capture("dry-eye-rest")
    }

    @Test fun distanceBannersUseRedForNearAndBlueForFar() {
        compose.setContent {
            EyeProjectTheme {
                Column(Modifier.fillMaxSize().background(LocalEyeColors.current.appBackground).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DistanceBanner(distanceStatus(28f, true, 40f, 5f))
                    DistanceBanner(distanceStatus(53f, true, 40f, 5f))
                    DistanceBanner(distanceStatus(41f, true, 40f, 5f))
                    DistanceBanner(distanceStatus(0f, false, 40f, 5f), compact = true)
                    Box(Modifier.fillMaxWidth().height(220.dp)) { DistanceEdgeGlow(DistanceZone.TOO_CLOSE) }
                }
            }
        }
        compose.onNodeWithText("把手机往远处移约 7 cm").assertExists()
        compose.onNodeWithText("把手机往近处移约 8 cm").assertExists()
        capture("distance-feedback")
    }

    @Test fun reportsUseHeroMetricsAndFindings() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val vm = ScreeningTestViewModel(context,
            com.zkyjss.eyeproject.core.camera.CameraController(context),
            com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider(context), TestScreeningRecordRepository(context), TestSubjectRepository(), TestExplanationRepository())
        var kind by androidx.compose.runtime.mutableStateOf(ScreeningKind.PRESBYOPIA)
        compose.setContent {
            EyeProjectTheme {
                Column(Modifier.fillMaxSize().background(LocalEyeColors.current.appBackground).verticalScroll(rememberScrollState()).padding(20.dp)) {
                    if (kind == ScreeningKind.PRESBYOPIA) {
                        ScreeningResultContent(ScreeningState(kind = ScreeningKind.PRESBYOPIA, step = ScreeningStep.RESULT,
                            riskLevel = ScreeningRiskLevel.MEDIUM, category = "调节功能较同龄人偏低", mode = PRESBYOPIA_MODE_STANDARD,
                            findings = listOf("调节功能较同龄人偏低：调节幅度约 2.4 D", "40 cm 双眼近视力未通过 0.5 档。"),
                            features = mapOf("amplitude_d" to 2.4, "hofstetter_min_d" to 3.75, "near_point_cm" to 42.0,
                                "near_both_decimal" to 0.4, "flipper_cycles_per_min" to 5.0),
                            fields = mapOf("年龄" to "45", "日常矫正方式" to "老花镜")), vm)
                    } else {
                        ScreeningResultContent(ScreeningState(kind = ScreeningKind.STRABISMUS, step = ScreeningStep.RESULT,
                            riskLevel = ScreeningRiskLevel.HIGH, category = "高度疑似斜视",
                            findings = listOf("注视偏差持续 ≥ 10Δ（水平）或 ≥ 5Δ（垂直），建议尽快到斜视专科检查。"),
                            features = mapOf("fixation_hde_prism" to 14.0, "fixation_vde_prism" to 1.2,
                                "cover_refix_median_prism" to 7.5, "bsi" to 0.82)), vm)
                    }
                }
            }
        }
        compose.onNodeWithText("调节功能较同龄人偏低").assertExists()
        compose.onNodeWithText("查看本次数据").assertExists()
        capture("report-presbyopia")
        kind = ScreeningKind.STRABISMUS
        compose.onNodeWithText("高度疑似斜视").assertExists()
        capture("report-strabismus")
    }

    @Test fun dryEyeReportLayout() {
        val session = DryEyeSession(60, 0.5f,
            (0 until 26).map { DryEyeTrial(DryEyeStage.NATURAL, it * 2.3f, 0.5f, 0, 0, 900L, 40f, 30f) } +
                (0 until 8).map { DryEyeTrial(DryEyeStage.OPEN, 60f + it * 2.3f, if (it < 3) 0.5f else 0.25f, 0, if (it % 2 == 0) 0 else 1, 900L, 40f, 30f) },
            (1..22).map { it * 2.6f } + listOf(64f, 70f), 0.95, 0.9, 0.05, false, true)
        val report = DryEyeAssessment.evaluate(session, mapOf("干眼症状" to "眼睛发干、发涩", "症状频率" to "经常"))
        compose.setContent {
            EyeProjectTheme {
                Column(Modifier.fillMaxSize().background(LocalEyeColors.current.appBackground).verticalScroll(rememberScrollState()).padding(20.dp)) {
                    DryEyeResultForTest(DryEyeTestUiState(phase = DryEyePhase.Result, report = report))
                }
            }
        }
        compose.onNodeWithText("判断依据").assertExists()
        capture("report-dry-eye")
    }

    /** 品牌标志与字标的大尺寸渲染（亮/暗），用于检查 Logo 细节。 */
    @Test fun brandMarkRender() {
        compose.setContent {
            Row(Modifier.fillMaxSize()) {
                for (dark in listOf(false, true)) {
                    EyeProjectTheme(darkTheme = dark) {
                        Column(Modifier.weight(1f).fillMaxHeight().background(LocalEyeColors.current.appBackground).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            EyeBrandMark(size = 160.dp)
                            EyeBrandMark(size = 48.dp)
                            EyeBrandLockup()
                        }
                    }
                }
            }
        }
        compose.onAllNodesWithText("眼健康助手").assertCountEquals(2)
        capture("brand")
    }

    @Test fun gazeCalibrationRetryIsVisible() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val vm = ScreeningTestViewModel(context,
            com.zkyjss.eyeproject.core.camera.CameraController(context),
            com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider(context), TestScreeningRecordRepository(context), TestSubjectRepository(), TestExplanationRepository())
        compose.setContent {
            EyeProjectTheme {
                Column(Modifier.fillMaxSize().background(LocalEyeColors.current.appBackground).padding(20.dp)) {
                    GazeCalibrationContent(ScreeningState(kind = ScreeningKind.STRABISMUS,
                        step = ScreeningStep.GAZE_CALIBRATION, gazeCalibrationIndex = 3, gazeCalibrationFailed = true), vm)
                }
            }
        }
        compose.onNodeWithText("注视校准 · 4/9").assertIsDisplayed()
        compose.onNodeWithText("重试注视校准").assertIsDisplayed().assertIsEnabled()
        capture("gaze-calibration")
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(700)
        val bitmap = compose.runOnIdle {
            val view = if (name == "test-settings") android.view.inspector.WindowInspector.getGlobalWindowViews().last()
                else compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                view.draw(android.graphics.Canvas(it))
            }
        }
        val output = File("build/reports/ui/$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
