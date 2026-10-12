package com.zkyjss.eyeproject.core.common

import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** 时间戳格式化为短时间文本。 */
fun formatTime(millis: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

/** 用眼距离（厘米）格式化为文本；非正值表示未测到，返回 "--"。 */
fun formatDistanceCm(cm: Float): String =
    if (cm > 0f) String.format(Locale.US, "%.0f cm", cm) else "--"
