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
        compose.onNodeWithText("继续").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("46")
        compose.onNodeWithText("继续").assertIsEnabled()
        capture("profile")
        compose.onNodeWithText("继续").performClick()
        compose.onNodeWithText("性别").assertIsDisplayed()
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
