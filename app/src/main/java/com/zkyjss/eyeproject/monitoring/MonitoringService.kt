package com.zkyjss.eyeproject.monitoring

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.zkyjss.eyeproject.MainActivity
import com.zkyjss.eyeproject.core.notifications.NotificationChannels
import com.zkyjss.eyeproject.feature.fatigue.FatigueMonitor
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 后台实时监测前台服务（`foregroundServiceType="camera"`）：
 * 持有 [FatigueMonitor]，让相机与检测管线在应用退到后台后继续运行。
 */
@AndroidEntryPoint
class MonitoringService : LifecycleService() {

    @Inject
    lateinit var monitor: FatigueMonitor

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NotificationChannels.MONITORING_NOTIFICATION_ID, buildNotification())
        monitor.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        monitor.stop()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        NotificationChannels.createMonitoringChannel(this)
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NotificationChannels.MONITORING_CHANNEL_ID)
            .setSmallIcon(com.zkyjss.eyeproject.core.notifications.R.drawable.ic_stat_eye)
            .setContentTitle("眼健康监测运行中")
            .setContentText("正在后台实时采集用眼数据")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
