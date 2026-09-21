package com.zkyjss.eyeproject.core.notifications.di

import com.zkyjss.eyeproject.core.notifications.Notifier
import com.zkyjss.eyeproject.core.notifications.SystemNotifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 通知 DI：接口 + 实现在同一 core 模块内装配（NIA 规范）。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NotifierModule {
    @Binds
    abstract fun bindNotifier(impl: SystemNotifier): Notifier
}
