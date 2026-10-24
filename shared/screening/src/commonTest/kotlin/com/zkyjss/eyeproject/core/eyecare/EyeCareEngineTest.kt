package com.zkyjss.eyeproject.core.eyecare

import com.zkyjss.eyeproject.core.domain.LiveEyeMonitor
import com.zkyjss.eyeproject.feature.services.LocalRecordEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 主动护眼闭环：日期换算、账本读写、评估等级与归因、干预与咨询建议、7 天趋势（两端同一套规则）。 */
class EyeCareEngineTest {
    private val today = "2026-10-23"

    @Test fun convertsLocalDaysAndWeekdays() {
        // 2026-10-23 00:30 北京时间 = 2026-10-22 16:30 UTC
        val ms = 1_792_686_600_000L
        assertEquals("2026-10-22", EyeCareDays.dayOf(ms, 0))
        assertEquals("2026-10-23", EyeCareDays.dayOf(ms, 480))
        assertEquals(0, EyeCareDays.hourOf(ms, 480))
        assertEquals(5, EyeCareDays.weekday("2026-10-23"), "2026-10-23 是周五")
        assertEquals("2026-11-01", EyeCareDays.plusDays("2026-10-25", 7))
        assertEquals("2024-02-29", EyeCareDays.plusDays("2024-03-01", -1))
        assertEquals("今天", EyeCareDays.label(today, today))
        assertEquals("周四", EyeCareDays.label("2026-10-22", today))
        assertEquals("10月23日", EyeCareDays.monthDay(today))
    }

    @Test fun ledgerRoundTripsAndPrunes() {
        val session = LiveEyeMonitor.Session(60_000, 9, 9f, 28.44f, 0.6f, 12.3f)
        val ledger = LedgerData()
            .withSession(today, 1L, session)
            .withAppSeconds(today, 90).withAppSeconds(today, 30)
            .withAppSeconds("2026-07-01", 100)
            .withCheckIn(today, listOf("眼睛干涩", "不在清单里的"), 2L)
        val restored = LedgerData.fromJson(ledger.toJson()).pruned(today)
        assertEquals(28.4f, restored.sessions.single().meanDistanceCm)
        assertEquals(60, restored.sessions.single().trackedSec)
        assertEquals(mapOf(today to 120L), restored.appSeconds, "60 天前的数据被裁掉")
        assertEquals(listOf("眼睛干涩"), restored.checkIns.single().symptoms)
        assertEquals(LedgerData(), LedgerData.fromJson("坏的 JSON"))
        // 旧版本账本里的手动填写字段被忽略
        assertEquals(1, LedgerData.fromJson("""{"manualScreenMinutes":{"$today":30},"checkIns":[{"day":"$today","symptoms":[],"atMs":1}]}""").checkIns.size)
    }

    @Test fun withoutDataAsksToPerceiveFirst() {
        val report = EyeCareEngine.report(LedgerData(), EyeCareContext(today, 10, screenBrightness = 0.5f))
        assertEquals("UNKNOWN", report.level)
        assertNull(report.score)
        assertFalse(report.loop.first { it.key == "ASSESS" }.done)
        assertTrue(report.actions.any { it.kind == "LIVE" })
        // 系统不提供用屏统计与环境光（苹果）：这些读数、入口与说明都不出现
        assertEquals(listOf("brightness"), report.signals.filter { it.group == "BASIC" }.map { it.key })
        assertTrue(report.actions.none { it.kind == "USAGE_ACCESS" })
        assertTrue(report.trend.summary.none { "用屏" in it })
        assertTrue(report.signals.none { "不提供" in it.note || "不支持" in it.value })
        val android = EyeCareEngine.report(LedgerData(), EyeCareContext(today, 10, usageAccess = EyeCareEngine.ACCESS_DENIED))
        assertTrue(android.actions.any { it.kind == "USAGE_ACCESS" })
        assertTrue(android.headline.contains("用屏统计"))
    }

    @Test fun heavyDayIsAttributedAndGetsInterventions() {
        val usage = UsageDay(today, screenMinutes = 7 * 60, longestSessionMinutes = 130, nightMinutes = 70, breaks = 2)
        val ledger = LedgerData()
            .withSession(today, 1L, LiveEyeMonitor.Session(120_000, 12, 6f, 24f, 0.8f, 10f))
            .withCheckIn(today, listOf("眼睛干涩"), 3L)
        val report = EyeCareEngine.report(ledger, EyeCareContext(today, 23, systemUsage = listOf(usage),
            usageAccess = EyeCareEngine.ACCESS_GRANTED, ambientLux = 20f, ambientSupported = true, screenBrightness = 0.8f, age = 30))
        // 18 用屏 + 20 连续 + 8 休息少 + 10 夜间 + 15 眨眼少 + 12 太近 + 6 不适 + 6 暗处过亮 = 95
        assertEquals(95, report.score)
        assertEquals("HIGH", report.level)
        assertEquals("TIRED", report.mood)
        assertEquals("眼睛累坏啦，闭上眼歇一会儿吧", report.tips.first().text)
        assertTrue(report.tips.any { it.text == "把屏幕拿远一点" })
        assertTrue(report.tips.filter { it.kind != null }.all { it.action != null })
        assertEquals(report.tips.size, report.tips.map { it.text }.toSet().size)
        assertEquals("连续用眼太久", report.factors.first().title)
        val kinds = report.actions.map { it.kind }
        assertTrue(listOf("BREAK", "REMINDER", "BLINK", "DISTANCE", "BRIGHTNESS", "NIGHT", "TEST").all { it in kinds }, "$kinds")
        assertEquals(20, report.actions.first { it.kind == "REMINDER" }.value)
        assertFalse("CHECK_IN" in kinds, "今天已打卡")
        assertEquals("ATTENTION", report.signals.first { it.key == "blink" }.status)
        assertEquals("7小时", report.signals.first { it.key == "screen" }.value)
        assertTrue(report.loop.all { it.done })
    }

    @Test fun childThresholdsAreStricterAndRedFlagsComeFirst() {
        val usage = UsageDay(today, screenMinutes = 150, longestSessionMinutes = 45)
        val adult = EyeCareEngine.report(LedgerData(), EyeCareContext(today, 10, systemUsage = listOf(usage), usageAccess = EyeCareEngine.ACCESS_GRANTED, age = 35))
        val child = EyeCareEngine.report(LedgerData(), EyeCareContext(today, 10, systemUsage = listOf(usage), usageAccess = EyeCareEngine.ACCESS_GRANTED, age = 9))
        assertTrue(child.score!! > adult.score!!)
        assertTrue(child.actions.any { it.kind == "OUTDOOR" })
        val hurt = EyeCareEngine.report(LedgerData().withCheckIn(today, listOf("眼睛疼痛"), 1L), EyeCareContext(today, 10))
        assertEquals("CONCERNED", hurt.mood)
        assertTrue(hurt.consults.first().urgent)
    }

    @Test fun consultsFollowTheLatestSelfTests() {
        val entries = listOf(
            LocalRecordEntry("dry_1792600000000.json", "DRY_EYE", "干眼自测", 1_792_600_000_000L, "HIGH", "干眼高风险", emptyList(), subjectRelation = "SELF"),
            LocalRecordEntry("str_1792600000000.json", "STRABISMUS", "眼位自测", 1_792_600_000_000L, "HIGH", "高度疑似", emptyList(), subjectRelation = "CHILD"),
        )
        val tests = EyeCareTests.from(entries, 480)
        assertEquals(listOf("DRY_EYE"), tests.map { it.kind }, "孩子的结果不算进本人的评估")
        val report = EyeCareEngine.report(LedgerData(), EyeCareContext(today, 10, tests = tests))
        assertEquals("干眼自测提示高风险", report.consults.first().title)
        assertEquals(10, report.factors.first { it.key == "dry-test" }.points)
    }

    @Test fun trendSummarisesTheWeek() {
        val usage = (0..13).map { UsageDay(EyeCareDays.plusDays(today, -it), screenMinutes = if (it < 7) 300 else 240, nightMinutes = if (it == 1) 90 else 0) }
        val ledger = LedgerData().withSession(EyeCareDays.plusDays(today, -2), 1L, LiveEyeMonitor.Session(60_000, 10, 10f, 35f, 0f, 5f))
        val trend = EyeCareEngine.report(ledger, EyeCareContext(today, 10, systemUsage = usage, usageAccess = EyeCareEngine.ACCESS_GRANTED)).trend
        assertEquals(7, trend.days.size)
        assertEquals("今天", trend.days.last().label)
        assertEquals(300, trend.averageScreenMinutes)
        assertEquals(240, trend.previousAverageScreenMinutes)
        assertEquals("日均用屏 5小时，比前一周多 25%", trend.summary.first())
        assertTrue(trend.summary.any { it == "有 1 天夜间用屏超过 1 小时" })
        assertEquals(10f, trend.days[4].blinksPerMinute)
        assertTrue(trend.days.all { it.score != null })
    }

    @Test fun bridgeSpeaksJson() {
        var ledger = EyeCareBridge.addSession("", today, 1L, LiveEyeMonitor.Session(120_000, 12, 6f, 24f, 0.8f, 10f))
        ledger = EyeCareBridge.checkIn(ledger, today, listOf("头痛", "眼睛酸胀"), 1L)
        val json = EyeCareBridge.reportJson(ledger, """{"today":"$today","hour":9,"screenBrightness":0.5,"profile":{"职业":"办公室工作"},"age":41}""")
        // 15 眨眼少 + 12 太近 + 12 两项不适 = 39
        assertTrue(json.contains("\"level\":\"MEDIUM\""), json)
        assertTrue(json.contains("做一次老视自测"))
        assertEquals("{}", EyeCareBridge.reportJson(ledger, "not json"))
    }
}
