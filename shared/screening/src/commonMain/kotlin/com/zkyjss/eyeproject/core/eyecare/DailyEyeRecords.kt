package com.zkyjss.eyeproject.core.eyecare

import kotlinx.serialization.Serializable

@Serializable
data class DailyEyeRecord(
    val day: String,
    val dateLabel: String,
    val score: Int?,
    val tip: String?,
    val metrics: List<DailyEyeMetric>,
)

@Serializable
data class DailyEyeMetric(val title: String, val value: String?, val note: String)

internal fun dailyEyeRecords(
    days: List<TrendDay>,
    context: EyeCareContext,
    signals: List<EyeSignal>,
    todayTip: String?,
): List<DailyEyeRecord> {
    val current = signals.associateBy(EyeSignal::key)
    return days.map { day ->
        val today = day.day == context.today
        val metrics = buildList {
            if (context.usageAccess != EyeCareEngine.ACCESS_UNSUPPORTED) {
                val periods = listOf(
                    "累计用屏" to day.screenMinutes,
                    "最长连续使用" to day.longestSessionMinutes,
                    "夜间使用" to day.nightMinutes,
                )
                for ((title, minutes) in periods) {
                    val note = when {
                        minutes != null -> "来自这一天的系统用屏统计。"
                        context.usageAccess == EyeCareEngine.ACCESS_DENIED -> "开启用屏统计权限后可读取系统记录。"
                        else -> "这一天暂无系统用屏记录。"
                    }
                    add(DailyEyeMetric(title, minutes?.let(EyeCareEngine::duration), note))
                }
            }
            val lightNote = when {
                !context.ambientSupported -> "当前设备未提供环境光读数。"
                today -> "当前环境光读数。"
                else -> "环境光仅显示当天的实时读数。"
            }
            add(DailyEyeMetric("环境光", current["light"]?.value.takeIf { today && it != "—" }, lightNote))
            add(DailyEyeMetric("屏幕亮度", current["brightness"]?.value.takeIf { today && it != "—" },
                if (today) "当前屏幕亮度。" else "屏幕亮度仅显示当天的实时读数。"))
            add(DailyEyeMetric("用眼距离", day.distanceCm?.let { "$it 厘米" },
                if (day.distanceCm == null) "这一天暂无有效的用眼距离检测。" else "这一天实时检测的平均用眼距离。"))
        }
        DailyEyeRecord(day.day, "${EyeCareDays.monthDay(day.day)} · ${day.label}", day.score, todayTip.takeIf { today }, metrics)
    }
}
