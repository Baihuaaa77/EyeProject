package com.zkyjss.eyeproject.core.model

data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val totalTimeInForegroundMillis: Long,
    val lastTimeUsedMillis: Long
)
