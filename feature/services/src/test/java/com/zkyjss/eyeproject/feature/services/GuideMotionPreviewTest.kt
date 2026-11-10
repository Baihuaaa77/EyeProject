package com.zkyjss.eyeproject.feature.services

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
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

/**
 * 真实 Compose 帧序列，供回看动作衔接与循环接缝；不参与测试采集或计时。
 * 输出到 build/reports/guide-motion-frames/：遮眼两个场景（NEAR / COVER）各一整圈的浅色帧，
 * 以及全部场景（含自遮与协助遮盖）在浅色、深色下的关键帧。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w390dp-h260dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GuideMotionPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var motion by mutableStateOf(GuideMotion.COVER)
    private var dark by mutableStateOf(false)
    private var phase by mutableFloatStateOf(0f)

    @Test fun handMotionFramesRenderAcrossOneCycle() {
        // 相位由测试显式传入，自动推进时钟只用于完成重组和布局。
        compose.mainClock.autoAdvance = true
        compose.setContent {
            EyeProjectTheme(darkTheme = dark) {
                Column(Modifier.fillMaxSize().background(LocalEyeColors.current.appBackground).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EyeText(motion.name, style = LocalEyeTypography.current.title, color = LocalEyeColors.current.textPrimary)
                    GuideFrame(motion, { phase }, motion.name, Modifier.fillMaxWidth().height(200.dp))
                }
            }
        }
        val root = File("build/reports/guide-motion-frames").apply { deleteRecursively(); mkdirs() }
        for (target in listOf(GuideMotion.COVER, GuideMotion.COVER_SELF, GuideMotion.NEAR)) {
            val folder = File(root, target.name.lowercase()).apply { mkdirs() }
            repeat(48) { frame -> capture(target, false, frame / 48f, File(folder, "frame-%03d.png".format(frame))) }
            File(folder, "cycle-ms.txt").writeText(guideCycleMs(target).toString())
        }
        val stills = File(root, "stills").apply { mkdirs() }
        for (target in GuideMotion.entries) for (night in listOf(false, true)) for ((index, at) in listOf(0.12f, 0.5f, 0.8f).withIndex()) {
            capture(target, night, at, File(stills, "${target.name.lowercase()}-${if (night) "dark" else "light"}-$index.png"))
        }
    }

    private fun capture(target: GuideMotion, night: Boolean, at: Float, file: File) {
        compose.runOnIdle { motion = target; dark = night; phase = at }
        // 切换场景、主题与绘制相位后等待完整重组及绘制，避免捕获上一场景。
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNodeWithText(target.name).assertIsDisplayed()
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            // 原生图形测试先更新显示列表，再捕获当前帧，避免输出上一场景的缓存。
            val warm = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(warm)); warm.recycle()
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
