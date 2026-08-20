package com.zkyjss.eyeproject.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

/** 通知渠道与通知 ID 定义，供 SystemNotifier 使用。 */
object NotificationChannels {
    const val REMINDER_CHANNEL_ID = "eye_care_reminders"
    const val COLOR_TEMPERATURE_CHANNEL_ID = "color_temperature_service"
    const val REMINDER_NOTIFICATION_ID = 3201
    const val COLOR_TEMPERATURE_NOTIFICATION_ID = 2101

    fun createReminderChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(
            REMINDER_CHANNEL_ID,
            "定时护眼提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "按设定间隔发出声音、振动和护眼通知"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 350, 180, 350)
            setSound(soundUri, audioAttributes)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun createColorTemperatureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            COLOR_TEMPERATURE_CHANNEL_ID,
            "全局护眼色温",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持护眼色温滤镜在其他应用上层运行"
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
