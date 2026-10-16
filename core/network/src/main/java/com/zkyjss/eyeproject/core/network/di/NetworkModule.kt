package com.zkyjss.eyeproject.core.network.di

import com.zkyjss.eyeproject.core.network.EyeNetworkDataSource
import com.zkyjss.eyeproject.core.network.HttpEyeNetworkDataSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 网络 DI：接口 + 实现在同一 core 模块内装配（NIA 规范）。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkModule {
    @Binds
    abstract fun bindEyeNetworkDataSource(impl: HttpEyeNetworkDataSource): EyeNetworkDataSource
}
