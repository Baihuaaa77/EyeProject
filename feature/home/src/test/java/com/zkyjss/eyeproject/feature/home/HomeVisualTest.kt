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
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.zkyjss.eyeproject.core.ui.component.EyeNavigationDock
import com.zkyjss.eyeproject.core.ui.component.EyeNavigationItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 新首页、二级页面的界面检查与截图（虚构数据，不连接相机与服务器）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeVisualTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun exerciseCompletionKeepsTheActionVisible() {
        var closed = false
        compose.setContent { EyeProjectTheme(darkTheme = true) {
            Box(Modifier.fillMaxSize().background(LocalEyeColors.current.appBackground).padding(20.dp)) {
                ExerciseCompletion(8, 7, true, { closed = true }, Modifier.fillMaxSize())
            }
        } }
        compose.onNodeWithText("完成啦！").assertIsDisplayed()
        compose.onNodeWithText("共 8 步，检测到你做到了 7 步").assertIsDisplayed()
        capture("exercise-complete")
        compose.onNodeWithText("完成").assertIsDisplayed().performClick()
        assertTrue(closed)
    }

