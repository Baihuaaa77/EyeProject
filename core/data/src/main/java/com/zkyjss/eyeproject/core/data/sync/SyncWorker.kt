package com.zkyjss.eyeproject.core.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zkyjss.eyeproject.core.data.record.ScreeningRecordRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 同步调度端口：仓库只表达「需要同步」，何时执行由实现决定；单测可替换。 */
interface SyncScheduler {
    fun requestSync()
}

/** WorkManager 实现：需联网，失败按指数退避重试；同名任务串接，保存期间新增的记录不会漏掉。 */
@Singleton
internal class WorkManagerSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SyncScheduler {
    override fun requestSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private companion object {
        const val WORK_NAME = "screening-record-sync"
    }
}

/** 推送待上传记录。只有网络类失败才重试；未登录、未同意由用户在「我的」页处理。 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SyncWorkerEntryPoint {
        fun screeningRecordRepository(): ScreeningRecordRepository
    }

    override suspend fun doWork(): Result {
        val repository = EntryPointAccessors.fromApplication(applicationContext, SyncWorkerEntryPoint::class.java)
            .screeningRecordRepository()
        val report = repository.syncNow() ?: return Result.success()
        return if (report.shouldRetry && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    private companion object {
        const val MAX_ATTEMPTS = 8
    }
}
