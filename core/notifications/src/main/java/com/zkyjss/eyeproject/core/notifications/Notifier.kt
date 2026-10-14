package com.zkyjss.eyeproject.core.notifications

import android.content.Intent

/** 定时护眼提醒的通知抽象。 */
interface Notifier {
    fun postReminderNotification(openAppIntent: Intent)
}
