package com.zkyjss.eyeproject.feature.home

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.zkyjss.eyeproject.core.designsystem.theme.EyeProjectTheme
import com.zkyjss.eyeproject.core.domain.LiveEyeMonitor
import com.zkyjss.eyeproject.core.model.BoundingBox
import com.zkyjss.eyeproject.core.model.EyeLandmarks
import com.zkyjss.eyeproject.core.model.FaceMetrics
import com.zkyjss.eyeproject.core.model.Point2f
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeVisualTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun reminderAndModelNavigationRemainAvailable() {
        var reminder = false
        var interval = 0
        var openedModel = false
        compose.setContent {
            EyeProjectTheme {
                HomeContent(HomeUiState(), onNavigateToTestModel = { openedModel = true },
                    onToggleReminder = { reminder = true }, onSelectReminderInterval = { interval = it })
            }
        }
        compose.onNodeWithText("开始监测").assertDoesNotExist()
        compose.onNodeWithText("屏幕色温").assertDoesNotExist()
        compose.onNodeWithText("用眼趋势").assertDoesNotExist()
        compose.onNodeWithText("休息提醒").performClick()
        compose.onNodeWithText("30分钟").performClick()
        assertEquals(true, reminder)
        assertEquals(30, interval)
        compose.onNodeWithText("开发者工具").performClick()
        compose.onNodeWithText("模型测试").performScrollTo().performClick()
        assertEquals(true, openedModel)
        capture("home-light.png")
    }

    @Test fun activeReminderKeepsItsScheduleInDarkTheme() {
        compose.setContent {
            EyeProjectTheme(darkTheme = true) {
                HomeContent(HomeUiState(reminderEnabled = true, reminderIntervalMinutes = 30, reminderNextTimeText = "15:30"),
                    onNavigateToTestModel = {}, onToggleReminder = {}, onSelectReminderInterval = {})
            }
        }
        compose.onNodeWithText("每 30 分钟，下次约 15:30").assertExists()
        compose.onNodeWithText("停止监测").assertDoesNotExist()
        capture("home-dark.png")
    }

    @Test fun liveEyeWindowShowsLandmarksBlinksAndControls() {
        // 用共用监视器喂一段睁眼 + 一次眨眼，得到与真机同结构的快照
        val monitor = LiveEyeMonitor()
        var t = 0L
        repeat(40) { monitor.update(face(0.3f), 720, 1280, t); t += 33 }
        repeat(3) { monitor.update(face(0.05f), 720, 1280, t); t += 33 }
        repeat(20) { monitor.update(face(0.3f), 720, 1280, t); t += 33 }
        var started = false
        var paused = false
        var settings = false
        var running by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            EyeProjectTheme {
                HomeContent(HomeUiState(), onNavigateToTestModel = {}, onToggleReminder = {}, onSelectReminderInterval = {},
                    onOpenSettings = { settings = true }, liveEye = {
                        LiveEyeContent(LiveEyeUiState(running = running, snapshot = if (running) monitor.snapshot(t) else null),
                            note = null, onStart = { started = true; running = true }, onPause = { paused = true }) {
                            Box(Modifier.fillMaxSize().background(Color(0xFF3A4A48)))
                        }
                    })
            }
        }
        compose.onNodeWithText("已暂停").assertExists()
        capture("home-live-idle.png")
        compose.onNodeWithText("开始检测").performClick()
        assertEquals(true, started)
        compose.onNodeWithText("运行正常").assertExists()
        compose.onNodeWithText("1").assertExists()
        compose.onNodeWithText("睁眼").assertExists()
        capture("home-live-running.png")
        compose.onNodeWithText("暂停").performClick()
        assertEquals(true, paused)
        compose.onNodeWithContentDescription("系统设置").performClick()
        assertEquals(true, settings)
    }

    /** 眼裂宽 100 px、上下各偏 h 的 6 点（EAR = 2h / 100 × 100），落在画面中部。 */
    private fun face(ear: Float): FaceMetrics {
        fun eye(cx: Float, label: String) = EyeLandmarks(label, listOf(
            Point2f(cx - 50f, 600f), Point2f(cx - 20f, 600f - ear * 50f), Point2f(cx + 20f, 600f - ear * 50f),
            Point2f(cx + 50f, 600f), Point2f(cx + 20f, 600f + ear * 50f), Point2f(cx - 20f, 600f + ear * 50f)),
            listOf(Point2f(cx, 600f)), 36f)
        return FaceMetrics(BoundingBox(150f, 400f, 570f, 900f), eye(470f, "LEFT"), eye(250f, "RIGHT"))
    }

    private fun capture(name: String) {
        val output = File("build/reports/ui/$name")
        output.parentFile?.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
