package com.zkyjss.eyeproject.feature.services

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeProjectTheme
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.ui.component.EyeText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 真实 Compose 帧序列，供回看动作衔接与循环接缝；不参与测试采集或计时。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w390dp-h260dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GuideMotionPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun coverMotionFramesRenderAcrossOneCycle() {
        var phase by mutableFloatStateOf(0f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EyeProjectTheme {
                Column(Modifier.fillMaxSize().background(LocalEyeColors.current.appBackground).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EyeText("交替遮眼示范", style = LocalEyeTypography.current.title, color = LocalEyeColors.current.textPrimary)
                    GuideFrame(GuideMotion.COVER, { phase }, "交替遮眼示范", Modifier.fillMaxWidth().height(200.dp))
                }
            }
        }
        val folder = File("build/reports/guide-motion-frames").apply { mkdirs() }
        repeat(48) { frame ->
            compose.runOnIdle { phase = frame / 48f }
            compose.mainClock.advanceTimeByFrame()
            val bitmap = compose.runOnIdle {
                val view = compose.activity.window.decorView
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            }
            File(folder, "frame-%03d.png".format(frame)).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        File(folder, "cycle-ms.txt").writeText(guideCycleMs(GuideMotion.COVER).toString())
    }
}
