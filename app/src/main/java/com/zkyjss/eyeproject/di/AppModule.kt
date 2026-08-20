package com.zkyjss.eyeproject.di

import com.zkyjss.eyeproject.core.domain.ColorTemperatureController
import com.zkyjss.eyeproject.core.domain.ReminderController
import com.zkyjss.eyeproject.core.notifications.Notifier
import com.zkyjss.eyeproject.core.notifications.SystemNotifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    abstract fun bindColorTemperatureController(
        impl: ColorTemperatureControllerImpl
    ): ColorTemperatureController

    @Binds
    abstract fun bindReminderController(
        impl: ReminderControllerImpl
    ): ReminderController

    @Binds
    abstract fun bindNotifier(
        impl: SystemNotifier
    ): Notifier
}
