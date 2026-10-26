package com.zkyjss.eyeproject.feature.services

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.zkyjss.eyeproject.core.designsystem.theme.EyeProjectTheme
import com.zkyjss.eyeproject.core.ui.component.EyeChoiceList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-mdpi")
/** 资料编辑页与选人页共用的选项列表（core:ui EyeChoiceList）。 */
class QuestionnaireTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun firstSymptomCanBeSelectedAndRemoved() =
        verifyMultipleChoice("干眼症状", "都没有")

    @Test fun firstEyeDiseaseCanBeSelectedAndRemoved() =
        verifyMultipleChoice("既往眼病", "没有")

    private fun verifyMultipleChoice(key: String, none: String) {
        val question = (SubjectProfiles.questions + PreTestCheck.questions("DRY_EYE")).single { it.key == key }
        val options = question.options!!
        val first = options[0]
        val second = options[1]
        var fields by mutableStateOf(emptyMap<String, String>())
        compose.setContent {
            EyeProjectTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    EyeChoiceList(question.options!!, fields[key].orEmpty(), question.multiple,
                        { fields = fields + (key to it) }, question.hints, question.exclusive)
                }
            }
        }
        compose.onNodeWithText(first).performTouchInput { click() }
        compose.onNodeWithText(first).assertIsOn()
        compose.runOnIdle { assertEquals(first, fields[key]) }

        compose.onNodeWithText(second).performTouchInput { click() }
        compose.onNodeWithText(first).assertIsOn()
        compose.onNodeWithText(second).assertIsOn()
        compose.onNodeWithText(first).performTouchInput { click() }
        compose.onNodeWithText(first).assertIsOff()
        compose.runOnIdle { assertEquals(second, fields[key]) }
        compose.onNodeWithText(first).performTouchInput { click() }
        compose.runOnIdle { assertEquals("$second、$first", fields[key]) }

        // 已存答案中的顿号属于选项文案时，也必须正确恢复选中状态。
        compose.runOnIdle { fields = mapOf(key to "$first、$second") }
        compose.onNodeWithText(first).assertIsOn()
        compose.onNodeWithText(second).assertIsOn()

        compose.onNodeWithText(none).performTouchInput { click() }
        compose.onNodeWithText(first).assertIsOff()
        compose.onNodeWithText(second).assertIsOff()
        compose.onNodeWithText(none).assertIsOn()
        compose.onNodeWithText(first).performTouchInput { click() }
        compose.onNodeWithText(first).assertIsOn()
        compose.onNodeWithText(none).assertIsOff()
        compose.runOnIdle { assertEquals(first, fields[key]) }
    }
}
