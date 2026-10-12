package com.zkyjss.eyeproject.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.zkyjss.eyeproject.core.data.ReminderPreferences

/** 护眼提醒的闹钟调度：偏好读写走 [ReminderPreferences]（core:data），这里只管 AlarmManager。 */
object EyeCareReminderScheduler {
    private const val REQUEST_CODE = 3201

    fun isEnabled(context: Context): Boolean =
        ReminderPreferences.isEnabled(context)

    fun getIntervalMinutes(context: Context): Int =
        ReminderPreferences.intervalMinutes(context)

    fun getNextTriggerElapsed(context: Context): Long =
        ReminderPreferences.nextTriggerElapsed(context)

    fun setIntervalMinutes(context: Context, minutes: Int) {
        require(minutes > 0)
        ReminderPreferences.setIntervalMinutes(context, minutes)
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
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAt,
            reminderPendingIntent(context)
        )
        ReminderPreferences.setScheduled(context, nextTriggerElapsed = triggerAt, minutes = minutes)
        return true
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)
            .cancel(reminderPendingIntent(context))
        ReminderPreferences.setCancelled(context)
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
}
