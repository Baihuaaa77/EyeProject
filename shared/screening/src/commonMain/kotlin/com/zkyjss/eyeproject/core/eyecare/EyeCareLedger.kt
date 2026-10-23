package com.zkyjss.eyeproject.core.eyecare

import com.zkyjss.eyeproject.core.domain.LiveEyeMonitor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * 主动护眼的本机账本：实时检测会话、本应用使用时长与每日身体感受打卡。
 * 只存在本机（安卓 filesDir/eyecare.json，iOS 应用支持目录同名文件），格式两端一致，由共用代码读写与裁剪。
 */

/** 一天的屏幕使用汇总。字段为 null 表示这一项拿不到。 */
@Serializable
data class UsageDay(
    val day: String,
    val screenMinutes: Int? = null,
    val unlocks: Int? = null,
    /** 最长一次连续亮屏使用（分钟） */
    val longestSessionMinutes: Int? = null,
    /** 22:00–次日 6:00 的使用（分钟） */
    val nightMinutes: Int? = null,
    /** 白天两次使用之间熄屏超过 5 分钟的次数 */
    val breaks: Int? = null,
    /** 数据来源（目前只有系统统计 [SOURCE_SYSTEM]） */
    val source: String = SOURCE_SYSTEM,
) {
    companion object {
        const val SOURCE_SYSTEM = "SYSTEM"
    }
}

/** 一次实时眼部检测的视觉行为统计（见 LiveEyeMonitor.Session）。 */
@Serializable
data class LiveSessionRecord(
    val day: String,
    val endedAtMs: Long,
    val trackedSec: Int,
    val blinksPerMinute: Float,
    val meanDistanceCm: Float? = null,
    val closeFraction: Float? = null,
    val meanHeadOffsetDeg: Float? = null,
)

/** 当天的身体感受打卡；[symptoms] 为空表示「没有不适」。 */
@Serializable
data class CheckIn(val day: String, val symptoms: List<String>, val atMs: Long)

@Serializable
data class LedgerData(
    val version: Int = 1,
    val sessions: List<LiveSessionRecord> = emptyList(),
    val appSeconds: Map<String, Long> = emptyMap(),
    val checkIns: List<CheckIn> = emptyList(),
) {
    fun withSession(day: String, endedAtMs: Long, session: LiveEyeMonitor.Session): LedgerData = copy(
        sessions = (sessions + LiveSessionRecord(
            day = day, endedAtMs = endedAtMs, trackedSec = (session.trackedMs / 1000).toInt(),
            blinksPerMinute = round1(session.blinksPerMinute),
            meanDistanceCm = session.meanDistanceCm?.let(::round1),
            closeFraction = session.closeFraction?.let { round1(it * 100) / 100 },
            meanHeadOffsetDeg = session.meanHeadOffsetDeg?.let(::round1),
        )).takeLast(MAX_SESSIONS),
    )

    fun withAppSeconds(day: String, seconds: Long): LedgerData =
        if (seconds <= 0) this else copy(appSeconds = appSeconds + (day to ((appSeconds[day] ?: 0L) + seconds).coerceAtMost(86_400L)))

    /** 同一天再次打卡覆盖上一次。 */
    fun withCheckIn(day: String, symptoms: List<String>, atMs: Long): LedgerData = copy(
        checkIns = checkIns.filter { it.day != day } + CheckIn(day, symptoms.filter { it in EyeCareEngine.SYMPTOMS }.distinct(), atMs),
    )

    /** 只保留最近 [KEEP_DAYS] 天。 */
    fun pruned(today: String): LedgerData {
        val oldest = EyeCareDays.plusDays(today, -KEEP_DAYS)
        return copy(
            sessions = sessions.filter { it.day >= oldest },
            appSeconds = appSeconds.filterKeys { it >= oldest },
            checkIns = checkIns.filter { it.day >= oldest },
        )
    }

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val KEEP_DAYS = 60
        private const val MAX_SESSIONS = 200
        internal val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun fromJson(text: String?): LedgerData =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() } ?: LedgerData()

        private fun round1(value: Float): Float = kotlin.math.round(value * 10) / 10
    }
}
