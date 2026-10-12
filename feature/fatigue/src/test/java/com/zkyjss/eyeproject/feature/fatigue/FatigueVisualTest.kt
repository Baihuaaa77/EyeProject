package com.zkyjss.eyeproject.feature.fatigue

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.zkyjss.eyeproject.core.designsystem.theme.EyeProjectTheme
import com.zkyjss.eyeproject.core.model.MonitoringSample
import com.zkyjss.eyeproject.core.model.RealTimeMetrics
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
class FatigueVisualTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun homeActionsAndHiddenMetrics() {
        var started = false
        var reminder = false
        compose.setContent {
            EyeProjectTheme {
                FatigueDashboard(FatigueUiState(), onNavigateToTestModel = {}, onToggleMonitoring = { started = true },
                    onSelectColorTemperature = {}, onToggleReminder = { reminder = true }, onSelectReminderInterval = {})
            }
        }
        compose.onNodeWithText("环境光照").assertDoesNotExist()
        compose.onNodeWithText("开始监测").performClick()
        assertEquals(true, started)
        val output = File("build/reports/ui/home-light.png")
        output.parentFile?.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithText("休息提醒").performScrollTo().performClick()
        assertEquals(true, reminder)
    }

    @Test fun liveMetricsShowOnlyWhileMonitoring() {
        val state = FatigueUiState(
            monitoring = true,
            realtime = RealTimeMetrics(faceDetected = true, distanceCm = 24f, blinkCount = 6, durationMin = 12f)
        )
        compose.setContent {
            EyeProjectTheme {
                FatigueDashboard(state, onNavigateToTestModel = {}, onToggleMonitoring = {},
                    onSelectColorTemperature = {}, onToggleReminder = {}, onSelectReminderInterval = {})
            }
        }
        compose.onNodeWithText("24 cm").assertExists()
        compose.onNodeWithText("偏近").assertExists()
        compose.onNodeWithText("6 次").assertExists()
        compose.onNodeWithText("停止监测").assertExists()
        val output = File("build/reports/ui/home-monitoring.png")
        output.parentFile?.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun darkHomeWithRisk() {
        val state = FatigueUiState(
            monitoring = true,
            samples = listOf(MonitoringSample(0L, 300f, 50, 42f, 1f, 6)),
            realtime = RealTimeMetrics(faceDetected = true, distanceCm = 42f, blinkCount = 7, durationMin = 26f, totalRisk = 0.46f, advice = "眨眼频率偏低，记得多眨眼")
        )
        compose.setContent {
            EyeProjectTheme(darkTheme = true) {
                FatigueDashboard(state, onNavigateToTestModel = {}, onToggleMonitoring = {},
                    onSelectColorTemperature = {}, onToggleReminder = {}, onSelectReminderInterval = {})
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithText("46").assertExists()
        val output = File("build/reports/ui/home-dark-risk.png")
        output.parentFile?.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun cameraDeniedOffersSettings() {
        var opened = false
        compose.setContent {
            EyeProjectTheme {
                FatigueDashboard(FatigueUiState(cameraPermissionDenied = true), onNavigateToTestModel = {},
                    onToggleMonitoring = {}, onSelectColorTemperature = {}, onToggleReminder = {},
                    onSelectReminderInterval = {}, onOpenAppSettings = { opened = true })
            }
        }
        compose.onNodeWithText("打开系统设置授权").performScrollTo().performClick()
        assertEquals(true, opened)
    }
}
