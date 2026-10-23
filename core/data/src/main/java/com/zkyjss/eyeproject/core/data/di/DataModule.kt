package com.zkyjss.eyeproject.core.data.di

import com.zkyjss.eyeproject.core.data.account.AccountRepository
import com.zkyjss.eyeproject.core.data.advice.AdviceRepository
import com.zkyjss.eyeproject.core.data.advice.DefaultAdviceRepository
import com.zkyjss.eyeproject.core.data.account.DefaultAccountRepository
import com.zkyjss.eyeproject.core.data.explanation.DefaultExplanationRepository
import com.zkyjss.eyeproject.core.data.explanation.ExplanationRepository
import com.zkyjss.eyeproject.core.data.eyecare.EyeCareRepository
import com.zkyjss.eyeproject.core.data.eyecare.OfflineFirstEyeCareRepository
import com.zkyjss.eyeproject.core.data.record.OfflineFirstScreeningRecordRepository
import com.zkyjss.eyeproject.core.data.record.ScreeningRecordRepository
import com.zkyjss.eyeproject.core.data.subject.OfflineFirstSubjectRepository
import com.zkyjss.eyeproject.core.data.subject.SubjectRepository
import com.zkyjss.eyeproject.core.data.sync.SyncScheduler
import com.zkyjss.eyeproject.core.data.sync.WorkManagerSyncScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 数据层 DI：仓库接口 + 实现在本模块内装配（NIA 规范）。 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class DataModule {
    @Binds
    abstract fun bindAccountRepository(impl: DefaultAccountRepository): AccountRepository

    @Binds
    abstract fun bindScreeningRecordRepository(impl: OfflineFirstScreeningRecordRepository): ScreeningRecordRepository

    @Binds
    abstract fun bindSubjectRepository(impl: OfflineFirstSubjectRepository): SubjectRepository

    @Binds
    abstract fun bindExplanationRepository(impl: DefaultExplanationRepository): ExplanationRepository

    @Binds
    abstract fun bindAdviceRepository(impl: DefaultAdviceRepository): AdviceRepository

    @Binds
    abstract fun bindEyeCareRepository(impl: OfflineFirstEyeCareRepository): EyeCareRepository

    @Binds
    abstract fun bindSyncScheduler(impl: WorkManagerSyncScheduler): SyncScheduler
}
