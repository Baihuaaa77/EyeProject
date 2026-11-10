package com.zkyjss.eyeproject.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // 应用升级同样会清掉已排的闹钟
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            EyeCareReminderScheduler.restoreAfterBoot(context)
        }
    }
}
