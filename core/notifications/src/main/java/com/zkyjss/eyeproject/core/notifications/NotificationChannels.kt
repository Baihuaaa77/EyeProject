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
    const val REMINDER_NOTIFICATION_ID = 3201
    /** 首页实时检测在后台继续时的常驻通知（前台服务） */
    const val LIVE_EYE_CHANNEL_ID = "live_eye_detection"
    const val LIVE_EYE_NOTIFICATION_ID = 3202

    /** 升级安装后清理旧版本注册的服务渠道，保留定时提醒渠道。 */
    fun clearLegacyChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        listOf("color_temperature_service", "eye_care_monitoring").forEach(manager::deleteNotificationChannel)
    }

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

    /** 后台实时检测：静默、低优先级，只用于前台服务的常驻通知。 */
    fun createLiveEyeChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(LIVE_EYE_CHANNEL_ID, "后台用眼检测", NotificationManager.IMPORTANCE_LOW).apply {
            description = "实时检测在后台继续时显示，可在通知里停止"
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
