package com.zkyjss.eyeproject.monitoring

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.zkyjss.eyeproject.MainActivity
import com.zkyjss.eyeproject.core.notifications.NotificationChannels
import com.zkyjss.eyeproject.core.notifications.Notifier
import com.zkyjss.eyeproject.feature.fatigue.FatigueMonitor
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 后台实时监测前台服务（`foregroundServiceType="camera"`）：
 * 持有 [FatigueMonitor]，让相机与检测管线在应用退到后台后继续运行。
 */
@AndroidEntryPoint
class MonitoringService : LifecycleService() {

    @Inject
    lateinit var monitor: FatigueMonitor

    @Inject
    lateinit var notifier: Notifier

    private var stopWatcher: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(
                NotificationChannels.MONITORING_NOTIFICATION_ID,
                notifier.buildMonitoringNotification(
                    openAppIntent = Intent(this, MainActivity::class.java),
                    stopIntent = Intent(this, MonitoringService::class.java).setAction(ACTION_STOP)
                )
            )
        } catch (e: RuntimeException) {
            // Android 14+ 后台无相机权限/不允许启动相机型前台服务时会抛异常，放弃本次启动而不是崩溃
            Log.w(TAG, "无法进入前台，停止监测服务", e)
            stopSelf()
            return START_NOT_STICKY
        }
        monitor.start(this)
        // 监测因相机失败等原因自行停止时，同步结束服务，避免通知栏残留「运行中」
        if (stopWatcher == null) {
            stopWatcher = lifecycleScope.launch {
                monitor.isRunning.drop(1).first { running -> !running }
                stopSelf()
            }
        }
        // 相机型前台服务不能由系统在后台重建（Android 14+ 会被拒绝），被杀后由用户回到前台重新开启
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        monitor.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MonitoringService"
        private const val ACTION_STOP = "com.zkyjss.eyeproject.action.STOP_MONITORING"

        fun createIntent(context: Context): Intent = Intent(context, MonitoringService::class.java)
    }
}
