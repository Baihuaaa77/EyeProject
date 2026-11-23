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

    @Test fun careTimerStartsCountsDownAndRecordsOnlyOnce() {
        compose.mainClock.autoAdvance = false
        var completed = 0
        var closed = false
        compose.setContent { EyeProjectTheme {
            RestBreakPage({ closed = true },title = "远眺20秒",seconds = 2,
                hint = "看向6米外的远处。\n放松视线，持续20秒。",onComplete = { completed++ })
        } }
        compose.onNodeWithText("开始").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithText("开始").performClick()
        compose.mainClock.advanceTimeBy(1050)
        compose.onNodeWithText("1").assertIsDisplayed()
        capture("care-timer-running",advance = false,dialog = true)
        compose.mainClock.advanceTimeBy(1050)
        compose.onNodeWithText("本次已记录。").assertIsDisplayed()
        assertEquals(1,completed)
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(1,completed)
        compose.onAllNodesWithText("完成",useUnmergedTree = true).onLast().performClick()
        assertTrue(closed)
    }

    @Test fun leavingCareTimerEarlyDoesNotRecordCompletion() {
        compose.mainClock.autoAdvance = false
        var completed = 0
        var closed by mutableStateOf(false)
        compose.setContent { EyeProjectTheme {
            if (!closed) RestBreakPage({ closed = true },title = "闭眼休息",seconds = 20,onComplete = { completed++ })
        } }
        compose.onNodeWithText("开始").performClick()
        compose.mainClock.advanceTimeBy(1050)
        compose.onNodeWithText("提前结束").performClick()
        compose.mainClock.advanceTimeBy(30000)
        assertTrue(closed)
        assertEquals(0,completed)
    }

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

    private val today = "2026-10-23"

    /** 一周的虚构用眼数据：今天用屏 6 小时多、连续 75 分钟、眨眼偏少，已打卡眼睛干涩。 */
    private fun demoState(checkIn: Boolean = true, reminder: Boolean = false, reportDate: String = today): HomeUiState {
        val usage = (0..13).map { back ->
            val day = EyeCareDays.plusDays(reportDate, -back)
            UsageDay(day, screenMinutes = listOf(385, 300, 420, 260, 330, 280, 350, 240, 260, 300, 250, 270, 260, 240)[back],
                unlocks = 60 + back, longestSessionMinutes = if (back == 0) 75 else 40, nightMinutes = if (back == 2) 80 else 15, breaks = 9)
        }
        var ledger = LedgerData()
            .withSession(reportDate, 1L, LiveEyeMonitor.Session(150_000, 22, 8.8f, 31f, 0.3f, 12f))
            .withSession(EyeCareDays.plusDays(reportDate, -2), 1L, LiveEyeMonitor.Session(90_000, 18, 12f, 36f, 0.1f, 9f))
        for (back in 1..6) ledger = ledger.withSession(EyeCareDays.plusDays(reportDate, -back), 1L,
            LiveEyeMonitor.Session(150_000, 30, 12f, 36f, .1f, 5f))
        if (checkIn) ledger = ledger.withCheckIn(reportDate, listOf("眼睛干涩"), 2L)
        val report = EyeCareEngine.report(ledger, EyeCareContext(reportDate, 15, usage, EyeCareEngine.ACCESS_GRANTED, 180f, true, 0.72f,
            mapOf("出生年份" to "1990", "职业" to "办公室工作", "基础视力" to "一般", "既往眼病" to "没有"), 36,
            listOf(TestBrief("DRY_EYE", "MEDIUM", "干眼中风险", "2026-10-20")), reminder, 30))
        return HomeUiState(reminderEnabled = reminder, reminderIntervalMinutes = 30, reminderNextTimeText = "15:30", report = report,
            selfId = "me", usageAccess = EyeCareEngine.ACCESS_GRANTED, screenBrightness = 0.72f, ambientLux = 180f)
    }

    @Test fun homeShowsTheEyeCareLoopAndServices() {
        var chat = false
        var trend = false
        var reminder = false
        compose.setContent { EyeProjectTheme {
            HomeContent(demoState(), HomeNavigation(onOpenChat = {chat=true},onOpenTrend={trend=true}), HomeActions(toggleReminder={reminder=true}))
        } }
        compose.onNodeWithText("用眼负荷").assertIsDisplayed()
        compose.onNodeWithText("用眼全记录", substring = true).assertIsDisplayed()
        capture("01-首页-首屏")
        compose.onNodeWithText("用眼报告 ›").performScrollTo().performClick()
        assertTrue(trend)
        compose.onNodeWithText("智能挂号").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("AI问诊").performScrollTo().performClick()
        assertTrue(chat)
        compose.onNodeWithText("休息一下").performScrollTo().performClick()
        assertTrue(reminder)
    }

    @Test fun dailyRecordsMoveOneDayAndKeepTheDateWhenClosingDetails() {
        val state = demoState()
        compose.setContent { EyeProjectTheme {
            HomeContent(state, HomeNavigation(), HomeActions())
        } }
        compose.onNodeWithText("用眼全记录", substring = true).performClick()
        compose.onNodeWithText("10月23日 · 今天").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("后一天").assertIsNotEnabled()
        compose.onNodeWithText("前一天").performScrollTo().performClick()
        compose.onNodeWithText("10月22日 · 周四").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("累计用屏").performScrollTo().performClick()
        compose.onNodeWithText("来自这一天的系统用屏统计。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("累计用屏").performScrollTo().performClick()
        compose.onNodeWithText("10月22日 · 周四").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("后一天").performScrollTo().performClick()
        compose.onNodeWithText("10月23日 · 今天").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("后一天").assertIsNotEnabled()
        repeat(13) { compose.onNodeWithText("前一天").performScrollTo().performClick() }
        compose.onNodeWithText("10月10日 · 周六").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("前一天").assertIsNotEnabled()
    }

    @Test fun dryEyeInquiryDemoShowsBookingAfterSummaryWithoutSendingChat() {
        val sent = mutableListOf<String>()
        compose.setContent { EyeProjectTheme {
            EyeChatContent(null, {}, {}, { text, _, _ -> sent += text }, {}) {}
        } }
        compose.onNodeWithText("体验干眼问诊").performScrollTo().performClick()
        compose.onNodeWithText("最近几天").assertIsDisplayed().performClick()
        compose.onNodeWithText("长时间看屏幕后").assertIsDisplayed().performClick()
        compose.onNodeWithText("问诊总结").assertIsDisplayed()
        compose.onNodeWithText("继续智能挂号").performClick()
        compose.onNodeWithText("智能挂号").assertIsDisplayed()
        assertTrue(sent.isEmpty())
    }

    @Test fun newInquiryReturnsToSuggestionsWithoutSendingOrClearingHistory() {
        val sent = mutableListOf<String>()
        var cleared = false
        val state = com.zkyjss.eyeproject.core.data.chat.ChatState(
            com.zkyjss.eyeproject.core.data.chat.ChatAccess.READY,
            messages = listOf(com.zkyjss.eyeproject.core.sync.ChatEntry("assistant", "本轮问询已完成", 1)),
        )
        compose.setContent { EyeProjectTheme {
            EyeChatContent(state, {}, {}, { text, _, _ -> sent += text }, { cleared = true }) {}
        } }
        compose.onNodeWithText("开始新的问询").performScrollTo().performClick()
        compose.onNodeWithText("可以这样问爱科").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("本轮问询已完成").assertExists()
        assertTrue(sent.isEmpty())
        assertTrue(!cleared)
    }

    @Test @Config(qualifiers = "zh-rCN-w390dp-h2600dp-mdpi")
    fun fullHomePage() {
        compose.mainClock.autoAdvance = false
        compose.setContent { EyeProjectTheme { HomeContent(demoState(reminder = true), HomeNavigation(), HomeActions()) { LiveEyeContent(LiveEyeUiState(), null, {}, {}) {} } } }
        compose.mainClock.advanceTimeBy(1_500)
        capture("02-首页-整页", advance = false)
    }

    @Test @Config(qualifiers = "zh-rCN-w390dp-h2600dp-mdpi")
    fun fullHomePageDarkWithoutData() {
        compose.setContent {
            EyeProjectTheme(darkTheme = true) {
                HomeContent(HomeUiState(report = EyeCareEngine.report(LedgerData(), EyeCareContext(today, 21))), HomeNavigation(), HomeActions()) { LiveEyeContent(LiveEyeUiState(), null, {}, {}) {} }
            }
        }
        compose.onNodeWithText("暂无读数").assertExists()
        compose.onNodeWithText("暂无用屏记录").assertExists()
        capture("03-首页-深色-还没有数据")
    }

    /** 深色模式展示真实页面与虚构报告，检查圆环、空槽和正文层次。 */
    @Test fun homeDarkWithData() {
        compose.setContent { EyeProjectTheme(darkTheme = true) { HomeContent(demoState(), HomeNavigation(), HomeActions()) } }
        compose.onNodeWithText("用眼负荷").assertIsDisplayed()
        compose.onNodeWithText("做 1 分钟护眼操").assertDoesNotExist()
        compose.onNodeWithContentDescription("账户").assertIsDisplayed()
        capture("01c-首页-深色-首屏")
    }

    /** 仪表头像替代原顶栏入口后，账户仍可打开；用不同状态检查表情、充能和圆润数字。 */
    @Test fun gaugeKeepsAccountReachableAndReflectsEvaluation() {
        var opened = false
        var dark by mutableStateOf(false)
        val initial = demoState()
        var report by mutableStateOf(initial.report!!.copy(score = 21, level = "LOW", label = "轻松"))
        compose.setContent {
            EyeProjectTheme(darkTheme = dark) {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.zkyjss.eyeproject.core.ui.component.LocalEyeThemeToggle provides
                        com.zkyjss.eyeproject.core.ui.component.EyeThemeToggle(dark) { dark = !dark }) {
                    HomeContent(initial.copy(report = report), HomeNavigation(onOpenAccount = { opened = true }), HomeActions())
                }
            }
        }
        compose.onAllNodesWithText("较轻").onFirst().assertIsDisplayed()
        compose.onNodeWithContentDescription("账户").performClick()
        assertTrue(opened)
        capture("01e-首页-79-暖金")
        dark = true
        capture("01f-首页-79-深色暖金")
        report = report.copy(score = 80, level = "HIGH", label = "偏累")
        compose.onAllNodesWithText("重度").onFirst().assertIsDisplayed()
        capture("01g-首页-20-银色")
    }

    /** 375dp 小屏放大字号后，所有护眼工具仍能滚动触达并正常派发事件。 */
    @Test @Config(qualifiers = "zh-rCN-w375dp-h812dp-mdpi")
    fun smallHomeLargeTextKeepsToolsReachable() {
        var openedBrightness = false
        compose.setContent {
            EyeProjectTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 1.5f),
                ) {
                    HomeContent(demoState(), HomeNavigation(onOpenBrightness = { openedBrightness = true }), HomeActions())
                }
            }
        }
        compose.onNodeWithText("用眼负荷").assertIsDisplayed()
        compose.onNodeWithText("日常护眼").performScrollTo().performClick()
        compose.onNodeWithText("屏幕光").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(openedBrightness)
        compose.onNodeWithText("休息一下").performScrollTo().assertIsDisplayed()
        capture("01d-首页-375-大字号")
    }

    /** 负荷偏高的一天：主卡等级为「偏累」。 */
    @Test fun highLoadDaySuggestsRest() {
        val usage = UsageDay(today, screenMinutes = 7 * 60, longestSessionMinutes = 130, nightMinutes = 70, breaks = 2)
        val ledger = LedgerData().withSession(today, 1L, LiveEyeMonitor.Session(120_000, 12, 6f, 24f, 0.8f, 10f))
            .withCheckIn(today, listOf("眼睛干涩"), 3L)
        val report = EyeCareEngine.report(ledger, EyeCareContext(today, 16, systemUsage = listOf(usage),
            usageAccess = EyeCareEngine.ACCESS_GRANTED, ambientLux = 20f, ambientSupported = true, screenBrightness = 0.8f, age = 30))
        compose.mainClock.autoAdvance = false
        compose.setContent { EyeProjectTheme { HomeContent(HomeUiState(report = report), HomeNavigation(), HomeActions()) } }
        compose.mainClock.advanceTimeBy(2_400)
        compose.onAllNodesWithText("重度", useUnmergedTree = true).onFirst().assertExists()
        capture("01b-首页-负荷偏高", advance = false)
    }

    @Test fun feelingFormRecordsSymptomsAndNote() {
        var saved: List<String>? = null
        var savedNote = ""
        var opened by mutableStateOf(true)
        compose.setContent {
            // 截图测试关闭光标闪烁，产品中保留系统输入行为。
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalCursorBlinkEnabled provides false) {
                EyeProjectTheme { if (opened) FeelingPicker(emptyList(),{},onSaveNote = { picked,note -> saved = picked; savedNote = note; opened = false }) }
            }
        }
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onAllNodesWithText("舒适").onLast().performClick()
        compose.onAllNodesWithText("酸胀").onLast().performClick()
        compose.onNodeWithText("疲劳").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("连续用屏后的感受")
        capture("04-首页-今日感受")
        compose.onNodeWithText("保存").performClick()
        assertEquals(listOf("眼睛酸胀","眼睛疲劳"),saved)
        assertEquals("连续用屏后的感受",savedNote)
        compose.onNodeWithText("保存").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "zh-rCN-w390dp-h1100dp-mdpi")
    fun liveDetectionStartsWithTheButtonAndLandmarksAreOptional() {
        val monitor = LiveEyeMonitor()
        var t = 0L
        repeat(40) { monitor.update(face(0.3f), 720, 1280, t, 900f); t += 33 }
        repeat(3) { monitor.update(face(0.05f), 720, 1280, t, 900f); t += 33 }
        repeat(20) { monitor.update(face(0.3f), 720, 1280, t, 900f); t += 33 }
        var running by mutableStateOf(false)
        var showLandmarks by mutableStateOf(false)
        var paused = false
        compose.setContent {
            EyeProjectTheme {
                Box(Modifier.fillMaxSize().background(LocalEyeColors.current.appBackground).padding(16.dp)) {
                    LiveEyeContent(LiveEyeUiState(running = running, snapshot = if (running) monitor.snapshot(t) else null, showLandmarks = showLandmarks),
                        note = null, onStart = { running = true }, onPause = { paused = true; running = false },
                        onShowLandmarksChange = { showLandmarks = it }) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF3A4A48)))
                    }
                }
            }
        }
        compose.onNodeWithText("打开前置摄像头，看看眨眼和观看距离").assertDoesNotExist()
        compose.onNodeWithText("眨眼与距离，仅在本机处理").assertDoesNotExist()
        // 未检测时只有一行（名称 +「开始」），开始后才展开取景框与读数
        compose.onNodeWithText("开始检测").assertDoesNotExist()
        capture("05a-首页-实时检测-未开始")
        compose.onNodeWithContentDescription("开始实时检测").performClick()
        compose.onNodeWithText("运行正常").assertExists()
        compose.onNodeWithText("睁眼").assertDoesNotExist()
        compose.onNodeWithText("检测详情").performClick()
        compose.onNodeWithText("睁眼").assertExists()
        compose.onNodeWithText("33 厘米", useUnmergedTree = true).assertExists()
        // 关键点叠加默认关闭，点一下才画出来；检测读数不受影响
        assertFalse(showLandmarks)
        capture("05-首页-实时检测-开启")
        compose.onNodeWithText(SHOW_LANDMARKS).performClick()
        assertTrue(showLandmarks)
        compose.onNodeWithText("睁眼").assertExists()
        capture("05b-首页-实时检测-显示关键点")
        compose.onNodeWithText("停止检测").performClick()
        assertTrue(paused)
    }

    @Test @Config(qualifiers = "zh-rCN-w390dp-h1500dp-mdpi")
    fun trendReport() {
        compose.setContent { EyeProjectTheme { EyeTrendContent(demoState().report, {}) } }
        compose.onNodeWithText("平均每日用屏").assertExists()
        capture("06-用眼趋势报告")
    }

    @Test @Config(qualifiers = "zh-rCN-w390dp-h1500dp-mdpi")
    fun trendReportDark() {
        compose.setContent { EyeProjectTheme(darkTheme = true) { EyeTrendContent(demoState().report, {}) } }
        compose.onNodeWithText("平均每日用屏").assertExists()
        capture("06b-用眼趋势报告-深色")
    }

    @Test @Config(qualifiers = "zh-rCN-w390dp-h1200dp-mdpi")
    fun aiChatShowsHistoryRedFlagsAndConsent() {
        var state by mutableStateOf(com.zkyjss.eyeproject.core.data.chat.ChatState(com.zkyjss.eyeproject.core.data.chat.ChatAccess.READY, messages = listOf(
            com.zkyjss.eyeproject.core.sync.ChatEntry(com.zkyjss.eyeproject.core.sync.Chats.USER, "下午盯电脑，眼睛又干又涩", 1L),
            com.zkyjss.eyeproject.core.sync.ChatEntry(com.zkyjss.eyeproject.core.sync.Chats.ASSISTANT,
                "看屏幕久了眨眼会变少，可能和眼睛干涩有关。每用眼 20 分钟抬头看远处一会儿，有意识地多眨眼。", 2L,
                sources = listOf(com.zkyjss.eyeproject.core.sync.KnowledgeSourceDto("aao", "电脑视觉综合征", "美国眼科学会"))),
            com.zkyjss.eyeproject.core.sync.ChatEntry(com.zkyjss.eyeproject.core.sync.Chats.USER, "右眼突然看不清了", 3L, urgent = true),
        )))
        val sent = mutableListOf<Triple<String, Boolean, Boolean>>()
        compose.setContent {
            EyeProjectTheme {
                EyeChatContent(state, {}, {}, { text, agree, quiet -> sent += Triple(text, agree, quiet) }, {}) {
                    EyeText("（今天的综合建议卡片）", style = com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography.current.body)
                }
            }
        }
        // 对话由爱科主持：顶部是爱科的自我介绍
        compose.onNodeWithText(EYECHO_HELLO).assertExists()
        compose.onNodeWithContentDescription("爱科 Eyecho").assertExists()
        compose.onNodeWithText(com.zkyjss.eyeproject.core.sync.Chats.URGENT_NOTICE).assertExists()
        // 同意说明平时不在页面上
        compose.onNodeWithText(com.zkyjss.eyeproject.core.sync.Chats.CONSENT_TEXT).assertDoesNotExist()
        capture("12-AI综合建议对话")
        // 未同意：点「发送」先弹同意说明；「不同意」只关闭，输入框里的字还在
        state = state.copy(access = com.zkyjss.eyeproject.core.data.chat.ChatAccess.NEEDS_CONSENT)
        compose.onNode(hasSetTextAction()).performTextInput("今天眼睛有点干涩")
        compose.onNodeWithText("发送").performClick()
        compose.onNodeWithText(com.zkyjss.eyeproject.core.sync.Chats.CONSENT_TEXT).assertExists()
        compose.onNodeWithText("不同意").performClick()
        compose.onNodeWithText(com.zkyjss.eyeproject.core.sync.Chats.CONSENT_TEXT).assertDoesNotExist()
        assertTrue(sent.isEmpty())
        // 再点发送：勾「以后不再提示」并同意，先保存同意再发送
        compose.onNodeWithText("发送").performClick()
        compose.onNodeWithText(com.zkyjss.eyeproject.core.sync.Chats.DONT_ASK_AGAIN).performClick()
        compose.onNodeWithText("同意").performClick()
        assertEquals(listOf(Triple("今天眼睛有点干涩", true, true)), sent)
        // 已同意且不再提示：直接发送，不再弹窗
        state = state.copy(access = com.zkyjss.eyeproject.core.data.chat.ChatAccess.READY, askConsent = false)
        compose.onNode(hasSetTextAction()).performTextInput("晚上怎么护眼")
        compose.onNodeWithText("发送").performClick()
        compose.onNodeWithText(com.zkyjss.eyeproject.core.sync.Chats.CONSENT_TEXT).assertDoesNotExist()
        assertEquals(Triple("晚上怎么护眼", false, false), sent.last())
    }

    @Test @Config(qualifiers = "zh-rCN-w390dp-h1200dp-mdpi")
    fun aiInquiryOptionsConfirmCancelAndRememberWithoutLosingDraft() {
        val q = com.zkyjss.eyeproject.core.sync.ChatQuestion("从什么时候开始？", listOf("今天", "几天前"))
        var state by mutableStateOf(com.zkyjss.eyeproject.core.data.chat.ChatState(
            com.zkyjss.eyeproject.core.data.chat.ChatAccess.READY, askConsent = false,
            messages = listOf(com.zkyjss.eyeproject.core.sync.ChatEntry("assistant", "先了解一下", 1, question = q))))
        val sent = mutableListOf<String>()
        compose.setContent { EyeProjectTheme {
            EyeChatContent(state, {}, {}, { text, _, _ -> sent += text }, {},
                onConfirmOptionsChange = { state = state.copy(confirmOptionAnswers = it) }) {}
        } }
        compose.onNode(hasSetTextAction()).performTextInput("我还有一点补充")
        compose.onNodeWithText("几天前").performClick()
        compose.onNodeWithText("发送回答？").assertExists()
        assertTrue(sent.isEmpty())
        capture("13-AI逐步问询")
        val dialogBitmap = compose.runOnIdle {
            val view = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        File("build/reports/ui/13a-问询回答确认.png").outputStream().use { dialogBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        dialogBitmap.recycle()
        compose.onNodeWithText("取消").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("我还有一点补充")
        compose.onNodeWithText("几天前").performClick()
        compose.onNodeWithText("不再询问").performClick()
        compose.onNodeWithText("确认").performClick()
        assertEquals(listOf("几天前"), sent)
        state = state.copy(messages = state.messages + com.zkyjss.eyeproject.core.sync.ChatEntry("assistant", "继续了解", 2,
            question = com.zkyjss.eyeproject.core.sync.ChatQuestion("有没有疼痛？", listOf("没有", "有"))))
        compose.onNodeWithText("没有").performClick()
        compose.onNodeWithText("发送回答？").assertDoesNotExist()
        assertEquals(listOf("几天前", "没有"), sent)
        compose.onNodeWithText("发送").performClick()
        assertEquals("我还有一点补充", sent.last())
    }

    @Test @Config(qualifiers = "zh-rCN-w390dp-h1200dp-mdpi")
    fun inquiryOptionConfirmationDoesNotBypassDataConsent() {
        val q = com.zkyjss.eyeproject.core.sync.ChatQuestion("持续多久？", listOf("一天"))
        val state = com.zkyjss.eyeproject.core.data.chat.ChatState(
            com.zkyjss.eyeproject.core.data.chat.ChatAccess.NEEDS_CONSENT, askConsent = true,
            messages = listOf(com.zkyjss.eyeproject.core.sync.ChatEntry("assistant", "先了解一下", 1, question = q)))
        val sent = mutableListOf<Triple<String, Boolean, Boolean>>()
        compose.setContent { EyeProjectTheme { EyeChatContent(state, {}, {}, { t, a, q -> sent += Triple(t, a, q) }, {}) {} } }
        compose.onNodeWithText("一天").performClick()
        compose.onNodeWithText("确认").performClick()
        assertTrue(sent.isEmpty())
        compose.onNodeWithText(com.zkyjss.eyeproject.core.sync.Chats.CONSENT_TEXT).assertExists()
        compose.onNodeWithText("同意").performClick()
        assertEquals(listOf(Triple("一天", true, false)), sent)
    }

    @Test @Config(qualifiers = "zh-rCN-w390dp-h1200dp-mdpi")
    fun adviceAndBrightness() {
        var page by mutableStateOf(0)
        compose.setContent {
            EyeProjectTheme {
                if (page == 0) EyeAdviceContent(demoState().report, {}, {})
                else EyeBrightnessContent(demoState(), {}, {})
            }
        }
        compose.onNodeWithText("干眼自测提示中风险").assertExists()
        capture("07-健康建议")
        page = 1
        compose.onNodeWithText("这样做更舒服").assertExists()
        capture("08-亮度与蓝光")
    }

    @Test fun splash() {
        compose.mainClock.autoAdvance = false
        compose.setContent { EyeProjectTheme { EyeSplash(onFinished = {}) } }
        compose.mainClock.advanceTimeBy(700)
        capture("09-启动动画", advance = false)
    }

    @Test @Config(qualifiers = "zh-rCN-w390dp-h2600dp-mdpi")
    fun fullHomePageDark() {
        compose.mainClock.autoAdvance = false
        val sample = demoState(reportDate = java.time.LocalDate.now().toString())
        val lightLoad = sample.copy(report = sample.report?.copy(score = 21, level = "LOW", label = "轻松"))
        compose.setContent { EyeProjectTheme(darkTheme = true) { HomeContent(lightLoad, HomeNavigation(), HomeActions()) { LiveEyeContent(LiveEyeUiState(), null, {}, {}) {} } } }
        compose.mainClock.advanceTimeBy(1_500)
        capture("02b-首页-整页-深色", advance = false)
    }

    /** 眼裂宽 100 px、上下各偏 h 的 6 点（EAR = 2h / 100 × 100），落在画面中部；虹膜直径 36 px。 */
    private fun face(ear: Float): FaceMetrics {
        fun eye(cx: Float, label: String) = EyeLandmarks(label, listOf(
            Point2f(cx - 50f, 600f), Point2f(cx - 20f, 600f - ear * 50f), Point2f(cx + 20f, 600f - ear * 50f),
            Point2f(cx + 50f, 600f), Point2f(cx + 20f, 600f + ear * 50f), Point2f(cx - 20f, 600f + ear * 50f)),
            listOf(Point2f(cx, 600f)), 32f)
        return FaceMetrics(BoundingBox(150f, 400f, 570f, 900f), eye(470f, "LEFT"), eye(250f, "RIGHT"))
    }

    /** 与 app 同一底栏组件：验证 Tab 事件与选中状态，并输出明暗主题及真实滑动帧。 */
    @Test fun liquidGlassDockKeepsTabSemanticsAndRendersInBothThemes() {
        var dark by mutableStateOf(false)
        var renderedDark = false
        var selected by mutableStateOf(0)
        val items = listOf(
            EyeNavigationItem("首页", com.zkyjss.eyeproject.core.designsystem.R.drawable.ic_nav_home, com.zkyjss.eyeproject.core.designsystem.R.drawable.ic_nav_home_filled),
            EyeNavigationItem("服务", com.zkyjss.eyeproject.core.designsystem.R.drawable.ic_nav_services, com.zkyjss.eyeproject.core.designsystem.R.drawable.ic_nav_services_filled),
            EyeNavigationItem("我的", com.zkyjss.eyeproject.core.designsystem.R.drawable.ic_nav_mine, com.zkyjss.eyeproject.core.designsystem.R.drawable.ic_nav_mine_filled),
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                com.zkyjss.eyeproject.core.ui.component.LocalEyeThemeToggle provides
                    com.zkyjss.eyeproject.core.ui.component.EyeThemeToggle(dark) { dark = !dark }) {
            EyeProjectTheme(darkTheme = dark) {
                val themeIsDark = LocalEyeColors.current.isDark
                androidx.compose.runtime.SideEffect { renderedDark = themeIsDark }
                Column(Modifier.fillMaxSize().background(LocalEyeColors.current.appBackground)) {
                    Box(Modifier.weight(1f)) {
                        HomeContent(demoState(), HomeNavigation(), HomeActions()) {
                            LiveEyeContent(LiveEyeUiState(), null, {}, {}) {}
                        }
                    }
                    EyeNavigationDock(items, selected, { selected = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithText("首页").assertIsSelected()
        capture("brown-home", advance = false)
        compose.onAllNodes(hasScrollAction()).onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 820f) }
        compose.runOnIdle { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        capture("01i-首页-玻璃工具", advance = false)
        compose.runOnIdle {
            dark = true
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(renderedDark) }
        capture("01j-首页-深色玻璃工具", advance = false)
        compose.runOnIdle {
            dark = false
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(renderedDark) }
        val folder = File("build/reports/glass-dock-motion").apply { mkdirs() }
        var frame = 0
        for (label in listOf("服务", "我的", "首页")) {
            compose.onNodeWithText(label).performClick()
            compose.runOnIdle {
                assertEquals(items.indexOfFirst { it.label == label }, selected)
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            compose.onNodeWithText(label).assertIsSelected()
            repeat(24) {
                compose.mainClock.advanceTimeBy(16)
                val bitmap = compose.runOnIdle {
                    val view = compose.activity.window.decorView
                    Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
                }
                val dock = Bitmap.createBitmap(bitmap, 0, bitmap.height - 108, bitmap.width, 108)
                File(folder, "frame-%03d.png".format(frame++)).outputStream().use { dock.compress(Bitmap.CompressFormat.PNG, 100, it) }
                dock.recycle(); bitmap.recycle()
            }
        }
        assertEquals(0, selected)
    }

    private fun capture(name: String, advance: Boolean = true, dialog: Boolean = false) {
        if (advance) compose.mainClock.advanceTimeBy(800)
        val bitmap = compose.runOnIdle {
            val view = if (dialog) org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView else compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        for (dir in listOf("build/reports/ui")) {
            val output = File("$dir/$name.png")
            output.parentFile?.mkdirs()
            // Windows 上截图可能正被预览程序映射占用，稍等重试几次
            for (attempt in 1..5) {
                try { output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; break }
                catch (e: java.io.IOException) { if (attempt == 5) throw e; Thread.sleep(300) }
            }
        }
    }
}
