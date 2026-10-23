package com.zkyjss.eyeproject.feature.mine

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.zkyjss.eyeproject.core.data.account.AccountState
import com.zkyjss.eyeproject.core.data.record.RecordSyncStatus
import com.zkyjss.eyeproject.core.designsystem.theme.EyeProjectTheme
import com.zkyjss.eyeproject.core.ui.component.EyeAccountBadge
import com.zkyjss.eyeproject.core.ui.component.LocalEyeAccountBadge
import com.zkyjss.eyeproject.feature.services.LocalRecordCatalog
import com.zkyjss.eyeproject.feature.services.SubjectProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 「我的」入口页与二级页面的展示截图，仅使用虚构资料，不连接账号、相机或服务器。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MineWalkthroughCapture {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val me = SubjectProfile("demo-me", "SELF", "小林", mapOf(
        "出生年份" to "1992", "性别" to "女", "日常矫正方式" to "近视或远视眼镜", "屈光情况" to "近视、散光", "职业" to "办公室工作",
        "佩戴隐形眼镜史" to "现在在戴", "右眼球镜" to "-2.50", "既往眼病" to "没有",
    ), 0)
    private val members get() = listOf(me,
        SubjectProfile("demo-child", "CHILD", "小明", mapOf("出生年份" to "2018", "性别" to "男"), 0),
        SubjectProfile("demo-parent", "PARENT", "妈妈", mapOf("出生年份" to "1968", "性别" to "女", "日常矫正方式" to "老花镜"), 0))

    @Test fun hubIsAnEntryPageWithoutControls() {
        var opened = ""
        compose.setContent {
            EyeProjectTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalEyeAccountBadge provides EyeAccountBadge(true, "d")) {
                    MineContent(MineUiState.Ready(me, 3, 8, "demo@example.com", true, true, 6), MineNavigation(
                        onOpenRecords = { opened = "records" }, onOpenAccount = { opened = "account" }, onOpenSettings = { opened = "settings" }))
                }
            }
        }
        capture("12-我的-入口页")
        compose.onAllNodesWithText("测试记录").onFirst().performClick()
        assertEquals("records", opened)
        compose.onNodeWithContentDescription("账户（已登录）").performClick()
        assertEquals("account", opened)
        compose.onNodeWithText("系统设置").performScrollTo().performClick()
        assertEquals("settings", opened)
    }

    @Test fun accountPageSignsInAndOut() {
        var account by mutableStateOf(AccountState(cloudConfigured = true, serverUrl = "http://192.168.1.5:8080"))
        var signedIn: Pair<String, String>? = null
        compose.setContent {
            EyeProjectTheme {
                AccountContent(account, false, null, AccountActions(signIn = { e, p -> signedIn = e to p }), {})
            }
        }
        capture("13-账户-未登录")
        account = account.copy(email = "demo@example.com", healthDataSync = true)
        compose.waitForIdle()
        capture("14-账户-已登录")
        compose.onNodeWithText("注销账号").performClick()
        compose.onNodeWithText("确认注销账号").assertExists()
        assertTrue(signedIn == null)
    }

    @Test fun cloudSyncShowsConsentsWithHelp() {
        compose.setContent {
            EyeProjectTheme {
                CloudSyncContent(CloudSyncUiState.SignedIn("demo@example.com", true, true, false,
                    RecordSyncStatus(pending = 1, synced = 8, lastSyncAt = 1_792_476_000_000L), false, null, aiExplanation = true),
                    CloudSyncActions(), {}, {})
            }
        }
        compose.onNodeWithText("AI 解读").assertExists()
        compose.onNodeWithText("AI 解读（去标识结果发给大模型）").assertDoesNotExist()
        compose.onNodeWithContentDescription("AI 解读 说明").performScrollTo().performClick()
        compose.waitForIdle()
        capture("15-云端同步-问号说明", modal = true)
    }

    @Test fun recordsSubjectsAndPermissionsArePages() {
        val record = LocalRecordCatalog.entry("dryeye_1792135044762.json", """{"kind":"DRY_EYE","valid":true,"riskLevel":"UNCERTAIN",
            "subject":{"id":"demo-me","relation":"SELF","name":"小林"},"report":{"version":"dryeye-9.18-rules-2",
            "advice":"部分指标接近参考线，建议按提示复测。","qualityNotes":["分析帧率不足，眨眼指标无法评估。"],
            "sections":[{"title":"看 E 作答","rows":[{"label":"自然眨眼段","value":"26 题，答对 23 题"}]}]}}""")!!
        var page by mutableStateOf(0)
        compose.setContent {
            EyeProjectTheme {
                when (page) {
                    0 -> RecordsContent(listOf(record), {}, {})
                    1 -> SubjectsContent(members, {}, {})
                    else -> PermissionsContent(PermissionStatus(camera = true, microphone = false, notifications = true, exactAlarm = true, usageAccess = false), {}, {})
                }
            }
        }
        compose.onNodeWithText("干眼自测").performClick()
        capture("16-测试记录")
        page = 1
        compose.onNodeWithText("添加家庭成员").assertExists()
        capture("17-家庭成员")
        page = 2
        compose.onNodeWithText("用屏统计").assertExists()
        capture("18-权限与通知")
    }

    @Test fun memberInformationAndHealthFields() {
        var draft by mutableStateOf(me)
        compose.setContent { EyeProjectTheme {
            SubjectEditContent(draft, false, null, {}, {}, {}, { key, value -> draft = draft.copy(fields = draft.fields + (key to value)) }, {}, {})
        } }
        capture("19-成员资料")
        focus("视力与验光记录")
        capture("20-成员资料-视力与验光")
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
        val bitmap = compose.runOnIdle { snapshot(compose.activity, modal) }
        for (dir in listOf("build/reports/ui", "../../docs/界面截图-2026-10-23/安卓")) {
            val file = File("$dir/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

/** 页面截图；[overlay] 为 true 时把最上层的弹出窗口（问号小窗、全屏页）按其位置叠画在页面上。 */
internal fun snapshot(activity: ComponentActivity, overlay: Boolean): Bitmap {
    val root = activity.window.decorView
    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    root.draw(canvas)
    if (overlay) {
        val top = android.view.inspector.WindowInspector.getGlobalWindowViews().last()
        if (top !== root) {
            val params = top.layoutParams as? android.view.WindowManager.LayoutParams
            canvas.save()
            canvas.translate((params?.x ?: 0).toFloat(), (params?.y ?: 0).toFloat())
            top.draw(canvas)
            canvas.restore()
        }
    }
    return bitmap
}
