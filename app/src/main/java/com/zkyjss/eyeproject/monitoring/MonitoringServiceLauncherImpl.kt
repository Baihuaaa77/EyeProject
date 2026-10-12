package com.zkyjss.eyeproject.monitoring

import android.content.Context
import androidx.core.content.ContextCompat
import com.zkyjss.eyeproject.feature.fatigue.MonitoringServiceLauncher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 启动/停止 [MonitoringService] 的实现（app 层）。 */
@Singleton
class MonitoringServiceLauncherImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : MonitoringServiceLauncher {

    override fun start() {
        ContextCompat.startForegroundService(context, MonitoringService.createIntent(context))
    }

    override fun stop() {
        context.stopService(MonitoringService.createIntent(context))
    }
}
