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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.*
import com.zkyjss.eyeproject.core.ui.component.*
import org.junit.Assert.assertEquals
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

    @Test fun questionnaireRequiresValidAge() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val vm = ScreeningTestViewModel(context,
            com.zkyjss.eyeproject.core.camera.CameraController(context),
            com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider(context))
        compose.setContent {
            EyeProjectTheme {
                Column(Modifier.fillMaxSize().background(LocalEyeColors.current.appBackground).verticalScroll(rememberScrollState()).padding(20.dp)) {
                    val state by vm.state.collectAsState()
                    ProfileContent(ScreeningKind.PRESBYOPIA, state, vm)
                }
            }
        }
        compose.onNodeWithText("下一题").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("46")
        compose.onNodeWithText("下一题").assertIsEnabled()
        compose.onNodeWithText("下一题").performClick()
        compose.onNodeWithText("你的性别").assertIsDisplayed()
        // 单选：点选后自动进入下一题
        compose.onNodeWithText("女").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("平时戴什么眼镜？").assertIsDisplayed()
        compose.onNodeWithText("渐进多焦点眼镜").performClick()
        capture("profile")
    }

    @Test fun unknownResultKeepsQualityWarning() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val vm = ScreeningTestViewModel(context,
            com.zkyjss.eyeproject.core.camera.CameraController(context),
            com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider(context))
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
        compose.onNodeWithText("完整报告").performClick()
        compose.onNodeWithText("有效数据不足").assertExists()
    }

    @Test fun nearVisionUsesVisibleStimulusAndDisabledAnswerGate() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val vm = ScreeningTestViewModel(context,
            com.zkyjss.eyeproject.core.camera.CameraController(context),
            com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider(context))
        compose.setContent {
            EyeProjectTheme(darkTheme = true) {
                Column(Modifier.fillMaxSize().background(LocalEyeColors.current.appBackground).padding(20.dp)) {
                    VisionContent(ScreeningState(step = ScreeningStep.NEAR_VISION, trialVisible = true, trialOptotypePx = 48f), vm)
                }
            }
        }
        compose.onNodeWithText("右 →").assertIsEnabled()
        capture("near-vision-dark")
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
            com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider(context))
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
        compose.onNodeWithText("↑ 上").performClick()
        assertEquals(3, answered)
        capture("dry-eye-open")
        page = 1
        compose.onNodeWithText("下一步：移开遮盖，双眼注视").assertExists()
        capture("cover-coach")
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
                    VoiceAnswerBar(VoiceAnswerState().apply { available = true; permissionGranted = true; heard = "左" })
                    Box(Modifier.fillMaxWidth().height(220.dp)) { DistanceEdgeGlow(DistanceZone.TOO_CLOSE) }
                }
            }
        }
        compose.onNodeWithText("把手机往远处移约 7 cm").assertExists()
        compose.onNodeWithText("把手机往近处移约 8 cm").assertExists()
        compose.onNodeWithText("听到：左").assertExists()
        capture("distance-feedback")
    }

    @Test fun reportsUseHeroMetricsAndFindings() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val vm = ScreeningTestViewModel(context,
            com.zkyjss.eyeproject.core.camera.CameraController(context),
            com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider(context))
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
        compose.onNodeWithText("关键指标").assertExists()
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
        compose.onNodeWithText("需要关注").assertExists()
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
            com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider(context))
        compose.setContent {
            EyeProjectTheme {
                Column(Modifier.fillMaxSize().background(LocalEyeColors.current.appBackground).padding(20.dp)) {
                    GazeCalibrationContent(ScreeningState(kind = ScreeningKind.STRABISMUS,
                        step = ScreeningStep.GAZE_CALIBRATION, gazeCalibrationIndex = 3, gazeCalibrationFailed = true), vm)
                }
            }
        }
        compose.onNodeWithText("注视校准 · 4/5").assertIsDisplayed()
        compose.onNodeWithText("重试注视校准").assertIsDisplayed().assertIsEnabled()
        capture("gaze-calibration")
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(700)
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                view.draw(android.graphics.Canvas(it))
            }
        }
        val output = File("build/reports/ui/$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
