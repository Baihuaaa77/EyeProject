package com.zkyjss.eyeproject.core.data

import android.content.Context

/**
 * 定时护眼提醒的偏好持久化。
 *
 * 用户偏好在数据层统一持久化，
 * 闹钟调度（AlarmManager）留在 app 层的 `EyeCareReminderScheduler`。
 */
object ReminderPreferences {
    private const val PREFS_NAME = "eye_care_reminder"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_INTERVAL_MINUTES = "interval_minutes"
    private const val KEY_NEXT_TRIGGER_ELAPSED = "next_trigger_elapsed"
    private const val DEFAULT_INTERVAL_MINUTES = 20

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun intervalMinutes(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)

    fun nextTriggerElapsed(context: Context): Long =
        prefs(context).getLong(KEY_NEXT_TRIGGER_ELAPSED, 0L)

    /** 只改间隔，不改变启用状态（启用中的重新排程由调用方决定）。 */
    fun setIntervalMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_INTERVAL_MINUTES, minutes).apply()
    }

    /** 记录「已排程」：启用标记 + 本次间隔 + 下次触发时刻。 */
    fun setScheduled(context: Context, nextTriggerElapsed: Long, minutes: Int) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putInt(KEY_INTERVAL_MINUTES, minutes)
            .putLong(KEY_NEXT_TRIGGER_ELAPSED, nextTriggerElapsed)
            .apply()
    }

    /** 记录「已取消」：清启用标记与下次触发时刻。 */
    fun setCancelled(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, false)
            .putLong(KEY_NEXT_TRIGGER_ELAPSED, 0L)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
