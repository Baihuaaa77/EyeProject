package com.zkyjss.eyeproject.core.eyecare

/**
 * 主动护眼按「本地日期」汇总（yyyy-MM-dd）。共用代码不依赖日期库：平台传入 UTC 偏移，这里换算公历日期与星期。
 */
object EyeCareDays {
    private const val DAY_MS = 86_400_000L
    private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** [epochMs] 在 UTC 偏移 [utcOffsetMinutes] 下的本地日期。 */
    fun dayOf(epochMs: Long, utcOffsetMinutes: Int): String = fromEpochDay((epochMs + utcOffsetMinutes * 60_000L).floorDiv(DAY_MS))

    /** 本地小时 0–23。 */
    fun hourOf(epochMs: Long, utcOffsetMinutes: Int): Int =
        ((epochMs + utcOffsetMinutes * 60_000L).mod(DAY_MS) / 3_600_000L).toInt()

    fun plusDays(day: String, days: Int): String = fromEpochDay(epochDay(day) + days)

    /** 1 = 周一 … 7 = 周日。 */
    fun weekday(day: String): Int = (epochDay(day) + 3).mod(7L).toInt() + 1

    /** 图表横轴：今天显示「今天」，其余显示星期。 */
    fun label(day: String, today: String): String = if (day == today) "今天" else WEEKDAYS[weekday(day) - 1]

    /** 「10月23日」。 */
    fun monthDay(day: String): String = day.split('-').let { "${it[1].toInt()}月${it[2].toInt()}日" }

    /** 自 1970-01-01 起的天数（Howard Hinnant 公历算法）。格式不对时返回 0。 */
    fun epochDay(day: String): Long {
        val parts = day.split('-').mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return 0
        val (y0, m, d) = parts
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }

    fun fromEpochDay(value: Long): String {
        val z = value + 719_468L
        val era = (if (z >= 0) z else z - 146_096L) / 146_097L
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }
}
