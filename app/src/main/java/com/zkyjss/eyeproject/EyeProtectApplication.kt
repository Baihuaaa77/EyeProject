package com.zkyjss.eyeproject

import android.app.Application
import com.zkyjss.eyeproject.core.data.record.ScreeningRecordRepository
import com.zkyjss.eyeproject.core.notifications.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class EyeProtectApplication : Application() {
    @Inject lateinit var records: ScreeningRecordRepository

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.clearLegacyChannels(this)
        // 补传上次因断网或退避耗尽而积压的记录；未登录、未同意或未配置服务器时什么也不做
        records.requestSync()
    }
}
