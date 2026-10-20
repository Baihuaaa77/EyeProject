package com.zkyjss.eyeproject.feature.mine

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.zkyjss.eyeproject.core.data.record.RecordSyncStatus
import com.zkyjss.eyeproject.core.designsystem.theme.EyeProjectTheme
import com.zkyjss.eyeproject.feature.services.LocalRecordCatalog
import com.zkyjss.eyeproject.feature.services.SubjectProfile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 当前组件的展示截图，仅使用虚构资料，不连接账号、相机或服务器。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MineWalkthroughCapture {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val me = SubjectProfile("demo-me", "SELF", "我", mapOf(
        "出生年份" to "1998", "性别" to "男", "日常矫正方式" to "近视或远视眼镜", "屈光情况" to "近视、散光",
        "干眼症状" to "眼睛发干、发涩", "症状频率" to "偶尔", "近期眼部用药" to "人工泪液",
        "佩戴隐形眼镜史" to "现在在戴", "验光日期" to "2026-10-12",
        "右眼裸眼视力" to "0.3", "右眼矫正视力" to "1.0", "右眼球镜" to "-2.50", "右眼柱镜" to "-0.75", "右眼轴位" to "90",
        "左眼裸眼视力" to "0.4", "左眼矫正视力" to "1.0", "左眼球镜" to "-2.00",
        "眼药水名称" to "人工泪液（示例）", "眼药水使用频次" to "每日 3 次", "眼药水开始日期" to "2026-10-12",
        "隐形眼镜类型" to "软性镜片", "隐形眼镜更换周期" to "日抛", "隐形眼镜每日佩戴时长" to "4–8 小时",
        "家族眼病史" to "近视", "过敏史" to "无已知药物过敏", "眼病与手术补充" to "暂无手术史",
    ), 0)
    private val members get() = listOf(me,
        SubjectProfile("demo-child", "CHILD", "小明", mapOf("出生年份" to "2018", "性别" to "男", "干眼症状" to "都没有"), 0),
        SubjectProfile("demo-parent", "PARENT", "妈妈", mapOf("出生年份" to "1968", "性别" to "女", "日常矫正方式" to "老花镜", "屈光情况" to "老花"), 0))

    @Test fun overviewAndCloudControls() {
        var cloud by mutableStateOf<CloudSyncUiState>(CloudSyncUiState.NotConfigured)
        compose.setContent { EyeProjectTheme { MineContent("0.1.0", {}, subjects = members, cloudState = cloud) } }
        capture("01-我的-家庭成员总览")
        cloud = CloudSyncUiState.SignedIn("demo@example.com", true, true, false,
            RecordSyncStatus(pending = 1, synced = 8, lastSyncAt = 1_792_476_000_000L), false, null)
        focus("云端同步与数据", 20f)
        capture("07-我的-云端同步-演示状态")
    }

    @Test fun memberInformationAndHealthFields() {
        var draft by mutableStateOf(me)
        compose.setContent { EyeProjectTheme {
            SubjectEditContent(draft, false, null, {}, {}, {}, { key, value -> draft = draft.copy(fields = draft.fields + (key to value)) }, {}, {})
        } }
        capture("02-成员资料-基本信息")
        focus("视力与验光记录（选填，照检查单填写）")
        capture("03-成员资料-视力与验光")
        focus("用药、隐形眼镜与家族史（选填）")
        capture("04-成员资料-近期用药")
        focus("隐形眼镜类型")
        capture("05-成员资料-隐形眼镜")
        focus("近亲属有医生确诊的眼病吗？（可多选）")
        capture("06-成员资料-家族与过敏史")
    }

    @Test fun recordsAndExports() {
        val record = LocalRecordCatalog.entry("dryeye_1792135044762.json", """{"kind":"DRY_EYE","valid":true,"riskLevel":"UNCERTAIN",
            "subject":{"id":"demo-me","relation":"SELF","name":"我"},"report":{"version":"dryeye-9.18-rules-2",
            "advice":"部分指标接近参考线，建议按提示复测。","qualityNotes":["分析帧率不足，眨眼指标无法评估。"],
            "sections":[{"title":"看 E 作答","rows":[{"label":"自然眨眼段","value":"26 题，答对 23 题"}]},
            {"title":"测试时的健康资料","rows":[{"label":"日常矫正方式","value":"近视或远视眼镜"}]}]}}""")!!
        compose.setContent { EyeProjectTheme { LocalRecordsSheet(listOf(record), {}, {}) } }
        compose.onNodeWithText("干眼自测").performClick()
        capture("08-本机记录-报告与导出", modal = true)
    }

    private fun focus(text: String, top: Float = 64f) {
        val node = compose.onNodeWithText(text)
        node.performScrollTo()
        val delta = node.fetchSemanticsNode().boundsInRoot.top - top
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
        compose.waitForIdle()
    }

    private fun capture(name: String, modal: Boolean = false) {
        compose.mainClock.advanceTimeBy(700)
        val bitmap = compose.runOnIdle {
            val view = if (modal) android.view.inspector.WindowInspector.getGlobalWindowViews().last() else compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        }
        val file = File("../../docs/我的页面截图-2026-10-20/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
