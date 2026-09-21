package com.zkyjss.eyeproject.core.data.di

import com.zkyjss.eyeproject.core.data.JsonMonitoringRepository
import com.zkyjss.eyeproject.core.data.MonitoringRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 数据层 DI：接口 + 实现在同一 core 模块内装配（NIA 规范）。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun bindMonitoringRepository(impl: JsonMonitoringRepository): MonitoringRepository
}
