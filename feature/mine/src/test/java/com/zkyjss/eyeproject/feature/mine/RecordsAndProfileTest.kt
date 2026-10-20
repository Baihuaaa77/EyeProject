package com.zkyjss.eyeproject.feature.mine

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.zkyjss.eyeproject.core.designsystem.theme.EyeProjectTheme
import com.zkyjss.eyeproject.feature.services.LocalRecordCatalog
import com.zkyjss.eyeproject.feature.services.SubjectProfile
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 检查实际资料输入与报告展开、导出入口，不启动相机和后端。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecordsAndProfileTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun archivedReportOpensAndOffersBothExportFormats() {
        val entry = LocalRecordCatalog.entry("dryeye_1792135044762.json", """{"kind":"DRY_EYE","valid":true,"riskLevel":"UNCERTAIN",
            "subject":{"id":"kid","relation":"CHILD","name":"小明"},"report":{"version":"archived-1","advice":"当时的建议",
            "qualityNotes":["分析帧率不足，可能漏记眨眼"],"sections":[{"title":"作答详情","rows":[{"label":"超时","value":"3 题"}]}]}}""")!!
        var exportedText = false
        var exportedJson = false
        compose.setContent { EyeProjectTheme {
            LocalRecordsSheet(listOf(entry), onExport = { exportedJson = true }, onDismiss = {}, onExportReport = { exportedText = true })
        } }
        compose.onNodeWithText("干眼自测").performClick()
        compose.onNodeWithText("小明").assertIsDisplayed()
        capture("record-details", modal = true)
        compose.onNodeWithText("导出文字报告").performScrollTo().performClick()
        compose.onNodeWithText("导出原始数据（JSON）").performScrollTo().performClick()
        assertTrue(exportedText); assertTrue(exportedJson)
    }

    @Test fun signedPrescriptionAndOptionalHistoryRenderInTheMemberEditor() {
        var draft by mutableStateOf(SubjectProfile("me", "SELF", "我", mapOf("出生年份" to "1980", "右眼球镜" to "-2.50", "过敏史" to "某种眼药水过敏"), 0))
        compose.setContent { EyeProjectTheme {
            SubjectEditContent(draft, false, null, {}, {}, {}, { key, value -> draft = draft.copy(fields = draft.fields + (key to value)) }, {}, {})
        } }
        compose.onNodeWithText("右眼球镜 SPH（D，保留正负号）").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("-2.50").performScrollTo().assertIsDisplayed()
        capture("member-refraction")
        compose.onNodeWithText("已知过敏史（药物或其他）").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("某种眼药水过敏").performScrollTo().assertIsDisplayed()
        capture("member-history")
    }

    private fun capture(name: String, modal: Boolean = false) {
        compose.mainClock.advanceTimeBy(700)
        val bitmap = compose.runOnIdle {
            val view = if (modal) android.view.inspector.WindowInspector.getGlobalWindowViews().last() else compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        }
        val output = File("build/reports/ui/$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
