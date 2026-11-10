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
            schedule(context, minutes, allowInexact = true)
        }
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    /**
     * 排下一次提醒。有「闹钟和提醒」权限时用精确闹钟；没有权限时：
     * - 首次开启（[allowInexact] 为 false）返回 false，由界面先引导授权；
     * - 已开启的提醒续排（到点续下一次、改间隔、开机或升级后恢复）退回非精确闹钟，系统可能推迟几分钟，
     *   但提醒链不会在界面仍显示「已开启」时悄悄断掉。
     */
    fun schedule(context: Context, minutes: Int = getIntervalMinutes(context), allowInexact: Boolean = false): Boolean {
        val exact = canScheduleExactAlarms(context)
        if (!exact && !allowInexact) {
            return false
        }
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val triggerAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        if (exact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, reminderPendingIntent(context))
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, reminderPendingIntent(context))
        }
        ReminderPreferences.setScheduled(context, nextTriggerElapsed = triggerAt, minutes = minutes)
        return true
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)
            .cancel(reminderPendingIntent(context))
        ReminderPreferences.setCancelled(context)
    }

    /** 开机或应用升级后系统会清掉闹钟（开机后已存的触发时刻也失效）：已开启的提醒重新排一次。 */
    fun restoreAfterBoot(context: Context) {
        if (isEnabled(context)) {
            schedule(context, allowInexact = true)
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
