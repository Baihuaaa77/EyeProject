package com.zkyjss.eyeproject.feature.home.live

import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.zkyjss.eyeproject.core.notifications.NotificationChannels
import com.zkyjss.eyeproject.core.notifications.R as NotificationsR
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 实时检测的前台服务（类型 camera）：让相机在退到后台后继续分析，通知栏常驻「小瞳正在检测用眼」与「停止」按钮。
 * 只在应用位于前台、用户打开实时检测时启动（Android 14 起相机类前台服务只能从前台启动）；
 * 被系统杀掉后不自动重启。用户从最近任务划掉应用（[onTaskRemoved]）即视为主动退出，停止检测。
 * 相机绑定与统计都在 [LiveEyeTracker]，这里只提供后台生命周期、通知与熄屏 / 亮屏事件。
 */
@AndroidEntryPoint
class LiveEyeService : LifecycleService() {
    @Inject lateinit var tracker: LiveEyeTracker

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> tracker.onScreenOff()
                Intent.ACTION_USER_PRESENT -> tracker.onScreenOn()
                // 没有锁屏密码时亮屏即可用；有锁屏时等解锁（USER_PRESENT）
                Intent.ACTION_SCREEN_ON -> if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true) tracker.onScreenOn()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createLiveEyeChannel(this)
        ServiceCompat.startForeground(this, NotificationChannels.LIVE_EYE_NOTIFICATION_ID, notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
        ContextCompat.registerReceiver(this, screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        tracker.onServiceReady(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { tracker.stop(); stopSelf() }
        return START_NOT_STICKY
    }

    /** 从最近任务划掉应用：主动退出，停止后台检测。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        tracker.stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screen) }
        tracker.onServiceGone()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, LiveEyeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, NotificationChannels.LIVE_EYE_CHANNEL_ID)
            .setSmallIcon(NotificationsR.drawable.ic_stat_eye)
            .setContentTitle("小瞳正在检测用眼")
            .setContentText("记录眨眼、观看距离和姿势，画面不保存")
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .build()
    }

    private companion object { const val ACTION_STOP = "com.zkyjss.eyeproject.live.STOP" }
}
