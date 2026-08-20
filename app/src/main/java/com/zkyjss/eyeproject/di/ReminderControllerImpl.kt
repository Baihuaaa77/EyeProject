package com.zkyjss.eyeproject.di

import android.content.Context
import com.zkyjss.eyeproject.core.domain.ReminderController
import com.zkyjss.eyeproject.reminder.EyeCareReminderScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ReminderController {

    override fun isEnabled(): Boolean = EyeCareReminderScheduler.isEnabled(context)

    override fun intervalMinutes(): Int = EyeCareReminderScheduler.getIntervalMinutes(context)

    override fun setIntervalMinutes(minutes: Int) =
        EyeCareReminderScheduler.setIntervalMinutes(context, minutes)

    override fun schedule(minutes: Int): Boolean =
        EyeCareReminderScheduler.schedule(context, minutes)

    override fun cancel() = EyeCareReminderScheduler.cancel(context)

    override fun nextTriggerElapsed(): Long =
        EyeCareReminderScheduler.getNextTriggerElapsed(context)

    override fun canScheduleExactAlarms(): Boolean =
        EyeCareReminderScheduler.canScheduleExactAlarms(context)
}
