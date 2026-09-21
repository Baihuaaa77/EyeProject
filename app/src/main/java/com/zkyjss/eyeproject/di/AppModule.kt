package com.zkyjss.eyeproject.di

import com.zkyjss.eyeproject.core.domain.ColorTemperatureController
import com.zkyjss.eyeproject.core.domain.ReminderController
import com.zkyjss.eyeproject.feature.fatigue.MonitoringServiceLauncher
import com.zkyjss.eyeproject.monitoring.MonitoringServiceLauncherImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * app 层装配：接口在 domain/feature 层、实现只能在 app 时在此绑定。
 * 数据层/通知层的绑定已下沉到各自的 core 模块（DataModule / NotifierModule）。
 */
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
    abstract fun bindMonitoringServiceLauncher(
        impl: MonitoringServiceLauncherImpl
    ): MonitoringServiceLauncher
}
