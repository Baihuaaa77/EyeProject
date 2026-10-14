package com.zkyjss.eyeproject.feature.home

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.zkyjss.eyeproject.core.designsystem.theme.EyeProjectTheme
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
