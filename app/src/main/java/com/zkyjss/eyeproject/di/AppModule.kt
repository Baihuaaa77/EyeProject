package com.zkyjss.eyeproject.di

import com.zkyjss.eyeproject.core.domain.ReminderController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * app 层装配：接口在 domain/feature 层、实现只能在 app 时在此绑定。
 * 通知层的绑定位于 core:notifications 的 NotifierModule。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    abstract fun bindReminderController(
        impl: ReminderControllerImpl
    ): ReminderController

}
