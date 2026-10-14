package com.zkyjss.eyeproject

import android.app.Application
import com.zkyjss.eyeproject.core.notifications.NotificationChannels
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class EyeProtectApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.clearLegacyChannels(this)
    }
}
