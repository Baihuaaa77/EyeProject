package com.zkyjss.eyeproject.core.data

import androidx.test.core.app.ApplicationProvider
import com.zkyjss.eyeproject.core.data.eyecare.OfflineFirstEyeCareRepository
import com.zkyjss.eyeproject.core.data.eyecare.ScreenUsageAggregator
import com.zkyjss.eyeproject.core.data.eyecare.ScreenUsageAggregator.Event
import com.zkyjss.eyeproject.core.data.eyecare.ScreenUsageAggregator.Kind
import com.zkyjss.eyeproject.core.data.eyecare.ScreenUsageReader
import com.zkyjss.eyeproject.core.domain.LiveEyeMonitor
import com.zkyjss.eyeproject.core.eyecare.EyeCareEngine
import com.zkyjss.eyeproject.core.eyecare.UsageDay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/** 主动护眼数据：系统亮屏事件还原每日用屏，账本落盘与授权状态。 */
@RunWith(RobolectricTestRunner::class)
class EyeCareRepositoryTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun screenEventsBecomeDailyUsage() {
        val now = at(23, 23, 30)
        val events = listOf(
            // 22 日 23:30 亮屏到 23 日 0:40：跨天，各记各的，且都是夜间
            Event(at(22, 23, 30), Kind.ON), Event(at(23, 0, 40), Kind.OFF),
            // 23 日 9:00–10:30 连续使用，中间 30 秒熄屏不算休息
            Event(at(23, 9), Kind.ON), Event(at(23, 9), Kind.UNLOCK), Event(at(23, 9, 45), Kind.OFF),
            Event(at(23, 9, 45) + 30_000, Kind.ON), Event(at(23, 10, 30), Kind.OFF),
            // 休息 30 分钟后再用 20 分钟
            Event(at(23, 11), Kind.ON), Event(at(23, 11), Kind.UNLOCK), Event(at(23, 11, 20), Kind.OFF),
            // 22:50 起一直亮着到现在
            Event(at(23, 22, 50), Kind.ON),
        )
        val days = ScreenUsageAggregator.aggregate(events, ScreenUsageAggregator.windows(now, zone, 3), now)
        assertEquals(listOf("2026-10-21", "2026-10-22", "2026-10-23"), days.map { it.day })
        assertNull("最早的事件之前没有数据", days[0].screenMinutes)
        assertEquals(30, days[1].screenMinutes)
        assertEquals(30, days[1].nightMinutes)
        val today = days[2]
        assertEquals("中间 30 秒熄屏不计入", 189, today.screenMinutes)
        assertEquals("30 秒熄屏合并为同一次连续使用", 90, today.longestSessionMinutes)
        assertEquals(40 + 40, today.nightMinutes)
        assertEquals("白天熄屏超过 5 分钟各算一次休息（10:30 后、11:20 后）", 2, today.breaks)
        assertEquals(2, today.unlocks)
    }

    @Test
    fun ledgerPersistsAndUsageAccessIsReported() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.cacheDir, "eyecare-${System.nanoTime()}").apply { mkdirs() }
        var granted = false
        val reader = object : ScreenUsageReader {
            override fun hasAccess() = granted
            override fun read(nowMs: Long, zone: ZoneId, days: Int) = listOf(UsageDay("2026-10-23", 120))
        }
        fun repository() = OfflineFirstEyeCareRepository(directory, reader, null, { 0.5f }, { at(23, 12) }, { zone })
        val repo = repository()
        repo.refreshUsage()
        assertEquals(EyeCareEngine.ACCESS_DENIED, repo.systemUsage.first().access)
        granted = true
        repo.refreshUsage()
        assertEquals(120, repo.systemUsage.first().days.single().screenMinutes)
        repo.addLiveSession(LiveEyeMonitor.Session(90_000, 15, 10f, 38f, 0.1f, 8f))
        repo.checkIn(listOf("眼睛干涩"))
        repo.addAppSeconds(300)
        val restored = repository().ledger.first()
        assertEquals("2026-10-23", restored.sessions.single().day)
        assertEquals(listOf("眼睛干涩"), restored.checkIns.single().symptoms)
        assertEquals(300L, restored.appSeconds["2026-10-23"])
        val reading = repo.environment().first()
        assertEquals(0.5f, reading.brightness)
        assertTrue(!reading.luxSupported)
    }
}
