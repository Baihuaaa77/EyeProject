package com.zkyjss.eyeproject.feature.home

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeProjectTheme
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.domain.LiveEyeMonitor
import com.zkyjss.eyeproject.core.eyecare.EyeCareContext
import com.zkyjss.eyeproject.core.eyecare.EyeCareDays
import com.zkyjss.eyeproject.core.eyecare.EyeCareEngine
import com.zkyjss.eyeproject.core.eyecare.LedgerData
import com.zkyjss.eyeproject.core.eyecare.TestBrief
import com.zkyjss.eyeproject.core.eyecare.UsageDay
import com.zkyjss.eyeproject.core.model.BoundingBox
import com.zkyjss.eyeproject.core.model.EyeLandmarks
import com.zkyjss.eyeproject.core.model.FaceMetrics
import com.zkyjss.eyeproject.core.model.Point2f
import com.zkyjss.eyeproject.core.ui.component.EyeSplash
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.MascotMood
import com.zkyjss.eyeproject.core.ui.component.XiaoTong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 新首页、二级页面与小瞳表情的界面检查与截图（虚构数据，不连接相机与服务器）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeVisualTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val today = "2026-10-23"

    /** 一周的虚构用眼数据：今天用屏 6 小时多、连续 75 分钟、眨眼偏少，已打卡眼睛干涩。 */
    private fun demoState(checkIn: Boolean = true, reminder: Boolean = false): HomeUiState {
        val usage = (0..13).map { back ->
            val day = EyeCareDays.plusDays(today, -back)
            UsageDay(day, screenMinutes = listOf(385, 300, 420, 260, 330, 280, 350, 240, 260, 300, 250, 270, 260, 240)[back],
                unlocks = 60 + back, longestSessionMinutes = if (back == 0) 75 else 40, nightMinutes = if (back == 2) 80 else 15, breaks = 9)
        }
        var ledger = LedgerData()
            .withSession(today, 1L, LiveEyeMonitor.Session(150_000, 22, 8.8f, 31f, 0.3f, 12f))
            .withSession(EyeCareDays.plusDays(today, -2), 1L, LiveEyeMonitor.Session(90_000, 18, 12f, 36f, 0.1f, 9f))
        if (checkIn) ledger = ledger.withCheckIn(today, listOf("眼睛干涩"), 2L)
        val report = EyeCareEngine.report(ledger, EyeCareContext(today, 15, usage, EyeCareEngine.ACCESS_GRANTED, 180f, true, 0.72f,
            mapOf("出生年份" to "1990", "职业" to "办公室工作", "基础视力" to "一般", "既往眼病" to "没有"), 36,
            listOf(TestBrief("DRY_EYE", "MEDIUM", "干眼中风险", "2026-10-20")), reminder, 30))
        return HomeUiState(reminderEnabled = reminder, reminderIntervalMinutes = 30, reminderNextTimeText = "15:30", report = report,
            selfId = "me", usageAccess = EyeCareEngine.ACCESS_GRANTED, screenBrightness = 0.72f, ambientLux = 180f)
    }

    @Test fun homeShowsTheEyeCareLoopAndServices() {
        var reminder = false
        var trend = false
        var openedModel = false
        // 小瞳的气泡靠无限动画轮换，自动推进的测试时钟会停掉它，先用手动时钟
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EyeProjectTheme {
                HomeContent(demoState(), HomeNavigation(onNavigateToTestModel = { openedModel = true }, onOpenTrend = { trend = true }),
                    HomeActions(toggleReminder = { reminder = true }))
            }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("今日用眼").assertExists()
        compose.onNodeWithText("留意", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("为你推荐").assertDoesNotExist()
        compose.onNodeWithText("个体档案").assertDoesNotExist()
        // 第一句气泡是今天最该做的事
        compose.mainClock.advanceTimeBy(1_400)
        compose.onNodeWithText("看了好久啦，看看远处 20 秒吧").assertExists()
        capture("01-首页-首屏", advance = false)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("休息提醒").performScrollTo().performClick()
        assertTrue(reminder)
        compose.onNodeWithText("用眼趋势报告").performScrollTo().performClick()
        assertTrue(trend)
        compose.onNodeWithText("开发者工具").performScrollTo().performClick()
        compose.onNodeWithText("模型测试").performScrollTo().performClick()
        assertTrue(openedModel)
    }

    /** 点可执行的气泡直接去做：「去休息」打开 20 秒休息页。 */
    @Test fun tappingAnActionableBubbleStartsIt() {
        compose.mainClock.autoAdvance = false
        compose.setContent { EyeProjectTheme { HomeContent(demoState(), HomeNavigation(), HomeActions()) } }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithText("去休息 ›").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("提前结束").assertExists()
    }

    @Test @Config(qualifiers = "zh-rCN-w390dp-h2600dp-mdpi")
    fun fullHomePage() {
        compose.mainClock.autoAdvance = false
        compose.setContent { EyeProjectTheme { HomeContent(demoState(), HomeNavigation(), HomeActions()) } }
        compose.mainClock.advanceTimeBy(1_500)
        capture("02-首页-整页", advance = false)
    }

    @Test @Config(qualifiers = "zh-rCN-w390dp-h2600dp-mdpi")
    fun fullHomePageDarkWithoutData() {
        compose.setContent {
            EyeProjectTheme(darkTheme = true) {
                HomeContent(HomeUiState(report = EyeCareEngine.report(LedgerData(), EyeCareContext(today, 21))), HomeNavigation(), HomeActions())
            }
        }
        compose.onNodeWithText("今天眼睛感觉怎么样？").assertExists()
        capture("03-首页-深色-还没有数据")
    }

    /** 负荷偏高的一天：小瞳疲惫（眼皮耷拉、黑眼圈、嫩芽打蔫），第一句气泡劝你闭眼歇会儿。 */
    @Test fun tiredXiaoTongWhenLoadIsHigh() {
        val usage = UsageDay(today, screenMinutes = 7 * 60, longestSessionMinutes = 130, nightMinutes = 70, breaks = 2)
        val ledger = LedgerData().withSession(today, 1L, LiveEyeMonitor.Session(120_000, 12, 6f, 24f, 0.8f, 10f))
            .withCheckIn(today, listOf("眼睛干涩"), 3L)
        val report = EyeCareEngine.report(ledger, EyeCareContext(today, 16, systemUsage = listOf(usage),
            usageAccess = EyeCareEngine.ACCESS_GRANTED, ambientLux = 20f, ambientSupported = true, screenBrightness = 0.8f, age = 30))
        compose.mainClock.autoAdvance = false
        compose.setContent { EyeProjectTheme { HomeContent(HomeUiState(report = report), HomeNavigation(), HomeActions()) } }
        compose.mainClock.advanceTimeBy(2_400)
        compose.onNodeWithText("眼睛累坏啦，闭上眼歇一会儿吧").assertExists()
        capture("01b-首页-负荷偏高", advance = false)
    }

    @Test fun checkInRecordsSymptoms() {
        var saved: List<String>? = null
        compose.setContent { EyeProjectTheme { HomeContent(demoState(checkIn = false), HomeNavigation(), HomeActions(checkIn = { saved = it })) } }
        compose.onNodeWithText("眼睛酸胀").performScrollTo().performClick()
        compose.onNodeWithText("头痛").performClick()
        capture("04-首页-今日感受")
        compose.onNodeWithText("记录今天的感受").performClick()
        assertEquals(listOf("眼睛酸胀", "头痛"), saved)
    }

    @Test fun liveDetectionIsOffByDefaultAndShowsLandmarksWhenOn() {
        val monitor = LiveEyeMonitor()
        var t = 0L
        repeat(40) { monitor.update(face(0.3f), 720, 1280, t, 900f); t += 33 }
        repeat(3) { monitor.update(face(0.05f), 720, 1280, t, 900f); t += 33 }
        repeat(20) { monitor.update(face(0.3f), 720, 1280, t, 900f); t += 33 }
        var running by mutableStateOf(false)
        var paused = false
        compose.setContent {
            EyeProjectTheme {
                Box(Modifier.fillMaxSize().background(LocalEyeColors.current.appBackground).padding(16.dp)) {
                    LiveEyeContent(LiveEyeUiState(running = running, snapshot = if (running) monitor.snapshot(t) else null),
                        note = null, onStart = { running = true }, onPause = { paused = true; running = false }) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF3A4A48)))
                    }
                }
            }
        }
        compose.onNodeWithText("打开前置摄像头，看看眨眼和观看距离").assertDoesNotExist()
        compose.onNodeWithText("实时检测").performClick()
        compose.onNodeWithText("运行正常").assertExists()
        compose.onNodeWithText("睁眼").assertExists()
        compose.onNodeWithText("距离 33 厘米").assertExists()
        capture("05-首页-实时检测-开启")
        compose.onNodeWithText("实时检测").performClick()
        assertTrue(paused)
    }

    @Test @Config(qualifiers = "zh-rCN-w390dp-h1500dp-mdpi")
    fun trendReport() {
        compose.setContent { EyeProjectTheme { EyeTrendContent(demoState().report, {}) } }
        compose.onNodeWithText("近 7 天日均用屏").assertExists()
        capture("06-用眼趋势报告")
    }

    @Test @Config(qualifiers = "zh-rCN-w390dp-h1200dp-mdpi")
    fun adviceAndBrightness() {
        var page by mutableStateOf(0)
        compose.setContent {
            EyeProjectTheme {
                if (page == 0) EyeAdviceContent(demoState().report, {}, {}) {
                    EyeText("（AI 综合建议卡片）", style = com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography.current.body)
                } else EyeBrightnessContent(demoState(), {}, {})
            }
        }
        compose.onNodeWithText("干眼自测提示中风险").assertExists()
        capture("07-健康建议")
        page = 1
        compose.onNodeWithText("这样做更舒服").assertExists()
        capture("08-亮度与蓝光")
    }

    @Test fun splashAndMascotMoods() {
        var showSplash by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EyeProjectTheme {
                if (showSplash) EyeSplash(onFinished = {}) else Column(Modifier.fillMaxSize().background(LocalEyeColors.current.appBackground).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MascotMood.entries.chunked(2).forEach { row ->
                        Row { row.forEach { mood -> Column { XiaoTong(mood, Modifier.size(170.dp)); EyeText(mood.name, style = com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography.current.chip) } } }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(700)
        capture("09-启动动画", advance = false)
        showSplash = false
        // 待机周期 6 秒：避开 0.3 处的眨眼，停在 0.4 左右
        compose.mainClock.advanceTimeBy(2_400)
        capture("10-小瞳五种心情", advance = false)
        val first = compose.onAllNodesWithContentDescription("小瞳，点一下和它互动，长按摸摸头")[0]
        first.performClick()
        compose.mainClock.advanceTimeBy(400)
        capture("11-小瞳点按互动", advance = false)
        compose.mainClock.advanceTimeBy(1_600)
        repeat(5) { first.performClick(); compose.mainClock.advanceTimeBy(60) }
        compose.mainClock.advanceTimeBy(500)
        capture("11b-小瞳连点转晕", advance = false)
        compose.mainClock.advanceTimeBy(1_600)
        first.performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(600)
        capture("11c-小瞳长按比心", advance = false)
    }

    /** 眼裂宽 100 px、上下各偏 h 的 6 点（EAR = 2h / 100 × 100），落在画面中部；虹膜直径 36 px。 */
    private fun face(ear: Float): FaceMetrics {
        fun eye(cx: Float, label: String) = EyeLandmarks(label, listOf(
            Point2f(cx - 50f, 600f), Point2f(cx - 20f, 600f - ear * 50f), Point2f(cx + 20f, 600f - ear * 50f),
            Point2f(cx + 50f, 600f), Point2f(cx + 20f, 600f + ear * 50f), Point2f(cx - 20f, 600f + ear * 50f)),
            listOf(Point2f(cx, 600f)), 32f)
        return FaceMetrics(BoundingBox(150f, 400f, 570f, 900f), eye(470f, "LEFT"), eye(250f, "RIGHT"))
    }

    private fun capture(name: String, advance: Boolean = true) {
        if (advance) compose.mainClock.advanceTimeBy(800)
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        for (dir in listOf("build/reports/ui", "../../docs/界面截图-2026-10-23/安卓")) {
            val output = File("$dir/$name.png")
            output.parentFile?.mkdirs()
            // Windows 上截图可能正被预览程序映射占用，稍等重试几次
            for (attempt in 1..5) {
                try { output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; break }
                catch (e: java.io.IOException) { if (attempt == 5) throw e; Thread.sleep(300) }
            }
        }
    }
}
