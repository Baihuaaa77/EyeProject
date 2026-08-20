package com.zkyjss.eyeproject.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zkyjss.eyeproject.MainActivity
import com.zkyjss.eyeproject.core.notifications.Notifier
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class EyeCareReminderReceiver : BroadcastReceiver() {

    @Inject
    lateinit var notifier: Notifier

    override fun onReceive(context: Context, intent: Intent?) {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        notifier.postReminderNotification(openAppIntent)

        if (EyeCareReminderScheduler.isEnabled(context)) {
            EyeCareReminderScheduler.schedule(context)
        }
    }
}
