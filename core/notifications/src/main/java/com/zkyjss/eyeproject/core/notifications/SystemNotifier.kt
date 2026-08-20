package com.zkyjss.eyeproject.core.notifications

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zkyjss.eyeproject.core.model.ColorTemperatureMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 系统通知实现（参照 NIA 的 core:notifications/SystemNotifier）。 */
@Singleton
class SystemNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) : Notifier {

    override fun postReminderNotification(openAppIntent: Intent) {
        NotificationChannels.createReminderChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle("该休息一下眼睛了")
            .setContentText("请放下屏幕，眺望远处并放松眼睛。")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 350, 180, 350))
            .build()
        NotificationManagerCompat.from(context)
            .notify(NotificationChannels.REMINDER_NOTIFICATION_ID, notification)
    }

    override fun buildColorTemperatureNotification(
        mode: ColorTemperatureMode,
        openAppIntent: Intent,
        stopIntent: Intent
    ): Notification {
        NotificationChannels.createColorTemperatureChannel(context)
        val openPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPendingIntent = PendingIntent.getService(
            context,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, NotificationChannels.COLOR_TEMPERATURE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle("护眼色温正在运行")
            .setContentText("当前模式：${mode.label}")
            .setContentIntent(openPendingIntent)
            .addAction(0, "关闭滤镜", stopPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
