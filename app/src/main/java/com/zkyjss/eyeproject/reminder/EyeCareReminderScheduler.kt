package com.zkyjss.eyeproject.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

object EyeCareReminderScheduler {
    private const val PREFS_NAME = "eye_care_reminder"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_INTERVAL_MINUTES = "interval_minutes"
    private const val KEY_NEXT_TRIGGER_ELAPSED = "next_trigger_elapsed"
    private const val DEFAULT_INTERVAL_MINUTES = 20
    private const val REQUEST_CODE = 3201

    fun isEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ENABLED, false)

    fun getIntervalMinutes(context: Context): Int =
        preferences(context).getInt(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)

    fun getNextTriggerElapsed(context: Context): Long =
        preferences(context).getLong(KEY_NEXT_TRIGGER_ELAPSED, 0L)

    fun setIntervalMinutes(context: Context, minutes: Int) {
        require(minutes > 0)
        preferences(context).edit().putInt(KEY_INTERVAL_MINUTES, minutes).apply()
        if (isEnabled(context)) {
            schedule(context, minutes)
        }
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    fun schedule(context: Context, minutes: Int = getIntervalMinutes(context)): Boolean {
        if (!canScheduleExactAlarms(context)) {
            return false
        }
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val triggerAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        val pendingIntent = reminderPendingIntent(context)
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAt,
            pendingIntent
        )
        preferences(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putInt(KEY_INTERVAL_MINUTES, minutes)
            .putLong(KEY_NEXT_TRIGGER_ELAPSED, triggerAt)
            .apply()
        return true
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)
            .cancel(reminderPendingIntent(context))
        preferences(context).edit()
            .putBoolean(KEY_ENABLED, false)
            .putLong(KEY_NEXT_TRIGGER_ELAPSED, 0L)
            .apply()
    }

    fun restoreAfterBoot(context: Context) {
        if (isEnabled(context)) {
            schedule(context)
        }
    }

    private fun reminderPendingIntent(context: Context): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, EyeCareReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
