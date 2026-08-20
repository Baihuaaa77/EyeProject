package com.zkyjss.eyeproject.core.notifications

import android.app.Notification
import android.content.Intent
import com.zkyjss.eyeproject.core.model.ColorTemperatureMode

/** 通知抽象（参照 NIA 的 core:notifications/Notifier）。 */
interface Notifier {
    fun postReminderNotification(openAppIntent: Intent)

    /** 构建色温前台服务通知，供 Service.startForeground 使用。 */
    fun buildColorTemperatureNotification(
        mode: ColorTemperatureMode,
        openAppIntent: Intent,
        stopIntent: Intent
    ): Notification
}
