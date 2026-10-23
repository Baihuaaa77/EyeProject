package com.zkyjss.eyeproject.core.data.eyecare

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.zkyjss.eyeproject.core.eyecare.UsageDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 读取系统屏幕使用统计的端口（测试用替身实现）。 */
internal interface ScreenUsageReader {
    /** 用户是否已在系统设置里允许「使用情况访问」。 */
    fun hasAccess(): Boolean
    /** 最近 [days] 天（含今天）的逐日汇总；没有权限时返回空。 */
    fun read(nowMs: Long, zone: ZoneId, days: Int): List<UsageDay>
}

/**
 * 系统屏幕使用统计（需要「使用情况访问」特殊权限，用户在系统设置里手动开启）：
 * Android 9 起按亮屏 / 熄屏事件还原每段亮屏时间，得到每日用屏、最长连续、夜间使用、休息次数与解锁次数；
 * 更早的系统只能拿到各应用前台时长之和（只有每日总时长）。数据只在本机汇总，不上传。
 */
internal class SystemScreenUsageReader(private val context: Context) : ScreenUsageReader {
    private val manager get() = context.getSystemService(UsageStatsManager::class.java)

    override fun hasAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun read(nowMs: Long, zone: ZoneId, days: Int): List<UsageDay> {
        if (!hasAccess()) return emptyList()
        val manager = manager ?: return emptyList()
        val windows = ScreenUsageAggregator.windows(nowMs, zone, days)
        // 往前多取一天，以便知道第一天零点时屏幕是否亮着
        val from = windows.first().startMs - 86_400_000L
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val events = manager.queryEvents(from, nowMs)
                val list = ArrayList<ScreenUsageAggregator.Event>()
                val event = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    when (event.eventType) {
                        UsageEvents.Event.SCREEN_INTERACTIVE -> list += ScreenUsageAggregator.Event(event.timeStamp, ScreenUsageAggregator.Kind.ON)
                        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> list += ScreenUsageAggregator.Event(event.timeStamp, ScreenUsageAggregator.Kind.OFF)
                        UsageEvents.Event.KEYGUARD_HIDDEN -> list += ScreenUsageAggregator.Event(event.timeStamp, ScreenUsageAggregator.Kind.UNLOCK)
                    }
                }
                ScreenUsageAggregator.aggregate(list, windows, nowMs)
            } else {
                windows.map { w ->
                    val minutes = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, w.startMs, minOf(w.endMs, nowMs))
                        .filter { it.firstTimeStamp >= w.startMs - 3_600_000L }
                        .sumOf { it.totalTimeInForeground } / 60_000L
                    UsageDay(w.day, screenMinutes = minutes.toInt().takeIf { it > 0 })
                }
            }
        }.getOrDefault(emptyList())
    }
}

/** 由亮屏 / 熄屏 / 解锁事件还原每日用屏（纯函数，单测直接构造事件）。 */
internal object ScreenUsageAggregator {
    enum class Kind { ON, OFF, UNLOCK }
    data class Event(val timeMs: Long, val kind: Kind)

    /** 一个本地日：[nightStartMs] 为当天 22:00，[morningEndMs] 为当天 6:00。 */
    data class DayWindow(val day: String, val startMs: Long, val endMs: Long, val morningEndMs: Long, val nightStartMs: Long)

    /** 两段亮屏间隔短于此视为同一次连续使用（瞄一眼通知、自动锁屏又点亮）。 */
    private const val CONTINUOUS_GAP_MS = 60_000L
    /** 熄屏超过此时长算一次休息。 */
    private const val BREAK_MS = 5 * 60_000L

    fun windows(nowMs: Long, zone: ZoneId, days: Int): List<DayWindow> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return (days - 1 downTo 0).map { back ->
            val date: LocalDate = today.minus(back.toLong(), ChronoUnit.DAYS)
            fun at(hour: Int) = date.atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli()
            DayWindow(date.toString(), at(0), date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), at(6), at(22))
        }
    }

    fun aggregate(events: List<Event>, windows: List<DayWindow>, nowMs: Long): List<UsageDay> {
        val sorted = events.sortedBy { it.timeMs }
        val intervals = ArrayList<LongArray>()
        var onSince = -1L
        for (e in sorted) when (e.kind) {
            Kind.ON -> if (onSince < 0) onSince = e.timeMs
            Kind.OFF -> if (onSince >= 0) { intervals += longArrayOf(onSince, e.timeMs); onSince = -1L }
            Kind.UNLOCK -> Unit
        }
        if (onSince >= 0) intervals += longArrayOf(onSince, nowMs)
        // 间隔很短的相邻亮屏合并为一次连续使用
        val merged = ArrayList<LongArray>()
        for (iv in intervals) {
            val last = merged.lastOrNull()
            if (last != null && iv[0] - last[1] < CONTINUOUS_GAP_MS) last[1] = maxOf(last[1], iv[1]) else merged += iv.copyOf()
        }
        val firstEvent = sorted.firstOrNull()?.timeMs
        return windows.map { w ->
            val end = minOf(w.endMs, nowMs)
            // 统计开始之前（系统已不保留那么久的事件）的日子没有数据
            if (firstEvent == null || firstEvent > end || w.startMs > nowMs) return@map UsageDay(w.day)
            fun overlap(a: Long, b: Long, from: Long, to: Long) = (minOf(b, to) - maxOf(a, from)).coerceAtLeast(0L)
            val total = intervals.sumOf { overlap(it[0], it[1], w.startMs, end) }
            val longest = merged.maxOfOrNull { overlap(it[0], it[1], w.startMs, end) } ?: 0L
            val night = intervals.sumOf { overlap(it[0], it[1], w.startMs, w.morningEndMs) + overlap(it[0], it[1], w.nightStartMs, end) }
            val breaks = merged.zipWithNext().count { (a, b) ->
                b[0] - a[1] >= BREAK_MS && a[1] in w.morningEndMs until w.nightStartMs && a[1] < end
            }
            val unlocks = sorted.count { it.kind == Kind.UNLOCK && it.timeMs in w.startMs until end }
            UsageDay(w.day, screenMinutes = (total / 60_000L).toInt(), unlocks = unlocks, longestSessionMinutes = (longest / 60_000L).toInt(),
                nightMinutes = (night / 60_000L).toInt(), breaks = breaks, source = UsageDay.SOURCE_SYSTEM)
        }
    }
}
