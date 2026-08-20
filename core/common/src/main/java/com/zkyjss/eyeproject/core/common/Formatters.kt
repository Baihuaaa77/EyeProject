package com.zkyjss.eyeproject.core.common

import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** 毫秒时长格式化为可读字符串（如 "1h 05m"、"3m 20s"、"45s"）。 */
fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> String.format(Locale.US, "%dh %02dm", hours, minutes)
        minutes > 0 -> String.format(Locale.US, "%dm %02ds", minutes, seconds)
        else -> String.format(Locale.US, "%ds", seconds)
    }
}

/** 时间戳格式化为短时间文本。 */
fun formatTime(millis: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

/** 光照 lux 读数格式化为文本。 */
fun formatLux(lux: Float): String =
    if (lux < 100f) {
        String.format(Locale.US, "%.1f lx", lux)
    } else {
        String.format(Locale.US, "%.0f lx", lux)
    }

/** 根据 lux 判断环境光照描述。 */
fun luxDetail(lux: Float): String = when {
    lux < 50f -> "环境偏暗"
    lux > 1000f -> "环境过亮"
    else -> "光线正常"
}
