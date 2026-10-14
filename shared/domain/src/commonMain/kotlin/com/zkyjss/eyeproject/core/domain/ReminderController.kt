package com.zkyjss.eyeproject.core.domain

/** 定时护眼提醒控制入口，由 app 层实现。 */
interface ReminderController {
    fun isEnabled(): Boolean
    fun intervalMinutes(): Int
    fun setIntervalMinutes(minutes: Int)
    fun schedule(minutes: Int): Boolean
    fun cancel()
    fun nextTriggerElapsed(): Long
    fun canScheduleExactAlarms(): Boolean
}
