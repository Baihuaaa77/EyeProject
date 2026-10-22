package com.zkyjss.eyeproject.feature.services

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeProjectTheme
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.sync.Explanations
import com.zkyjss.eyeproject.core.ui.component.AiCardState
import com.zkyjss.eyeproject.core.ui.component.AiExplanationCard
import com.zkyjss.eyeproject.core.ui.component.AiSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w390dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiExplanationCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val done = AiCardState.Done(
        summary = "这次有一些干眼迹象，主要是眨眼偏少",
        meaning = listOf("专注看屏幕时眨眼会变少，泪膜得不到及时更新"),
        suggestions = listOf("看屏幕时有意识地多眨眼", "避免空调直吹，干燥时可用加湿器"),
        seeDoctor = "不适持续几周或出现眼痛、视力下降时，去眼科眼表门诊",
        sources = listOf(AiSource("Computers, Digital Devices and Eye Strain（屏幕与视疲劳）", "美国眼科学会（AAO）")),
        footnote = Explanations.DISCLAIMER, urgent = true,
    )

    @Test fun firstUseAsksForConsentInsideTheCard() {
        var agreed = 0
        var generated = 0
        compose.setContent {
            EyeProjectTheme {
                AiExplanationCard(AiCardState.Idle(Explanations.CONSENT_TEXT), onGenerate = { generated++ }, onAgree = { agreed++ })
            }
        }
        compose.onNodeWithText("生成解读").performClick()
        compose.onNodeWithText("首次使用需要你的同意").assertIsDisplayed()
        capture("ai-consent")
        compose.onNodeWithText("同意并生成").performClick()
        compose.runOnIdle { assertEquals(1, agreed); assertEquals(0, generated) }
    }

    @Test fun allStatesRender() {
        compose.setContent {
            EyeProjectTheme {
                Column(Modifier.background(LocalEyeColors.current.appBackground).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    AiExplanationCard(AiCardState.Idle(null), {}, {})
                    AiExplanationCard(AiCardState.Loading, {}, {})
                    AiExplanationCard(done, {}, {})
                    AiExplanationCard(AiCardState.Locked("在「我的 → 云端同步」登录后，可用 AI 解读这次结果"), {}, {})
                    AiExplanationCard(AiCardState.Failed("AI 解读服务暂时不可用，请稍后再试"), {}, {}, Modifier.fillMaxWidth())
                }
            }
        }
        compose.onNodeWithText("什么时候看医生").assertIsDisplayed()
        compose.onNodeWithText("参考资料（1）▾").performClick()
        compose.onNodeWithText("· Computers, Digital Devices and Eye Strain（屏幕与视疲劳）（美国眼科学会（AAO））").assertIsDisplayed()
        capture("ai-states")
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(700)
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        }
        val output = File("build/reports/ui/$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
