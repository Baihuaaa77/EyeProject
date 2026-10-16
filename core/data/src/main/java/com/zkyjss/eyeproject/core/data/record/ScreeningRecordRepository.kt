package com.zkyjss.eyeproject.core.data.record

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.zkyjss.eyeproject.core.data.account.AuthorizedCaller
import com.zkyjss.eyeproject.core.data.account.SessionStore
import com.zkyjss.eyeproject.core.data.sync.SyncScheduler
import com.zkyjss.eyeproject.core.network.EyeNetworkDataSource
import com.zkyjss.eyeproject.core.network.NetworkResult
import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.sync.ErrorCodes
import com.zkyjss.eyeproject.core.sync.OutboxEntry
import com.zkyjss.eyeproject.core.sync.RecordOutbox
import com.zkyjss.eyeproject.core.sync.RecordRemote
import com.zkyjss.eyeproject.core.sync.RecordSyncEngine
import com.zkyjss.eyeproject.core.sync.RecordUpload
import com.zkyjss.eyeproject.core.sync.SyncContract
import com.zkyjss.eyeproject.core.sync.SyncReport
import com.zkyjss.eyeproject.core.sync.SyncState
import com.zkyjss.eyeproject.core.sync.SyncStopReason
import com.zkyjss.eyeproject.core.sync.UploadResult
import com.zkyjss.eyeproject.feature.services.LocalRecordCatalog
import com.zkyjss.eyeproject.feature.services.LocalRecordEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 本机记录的云端同步概况，供「我的」页展示。 */
data class RecordSyncStatus(
    val pending: Int = 0,
    val synced: Int = 0,
    /** 冲突或被服务端判为无效、不再自动重试的记录数。 */
    val failed: Int = 0,
    val awaitingGuardian: Int = 0,
    val syncing: Boolean = false,
    val lastSyncAt: Long? = null,
    val lastMessage: String? = null,
)

/**
 * 测试记录仓库（离线优先）：本机文件是事实源，保存永远先落盘；
 * 发件箱记录每条的云端状态，满足「已配置服务器 + 已登录 + 已同意」时才上传。
 */
interface ScreeningRecordRepository {
    val syncStatus: Flow<RecordSyncStatus>
    /** 本机保存的全部测试记录摘要，最新在前；保存新记录后自动更新。 */
    val localRecords: Flow<List<LocalRecordEntry>>
    /** 把一条本机记录（完整 JSON）写到用户选择的位置；成功返回 true。 */
    suspend fun exportRecord(fileName: String, target: Uri): Boolean
    /** 写入本机记录（文件名与内容格式由 shared/screening 决定），并按条件排队上传。 */
    suspend fun saveRecord(name: String, json: String)
    /** 立即推送待上传记录；不满足同步条件时返回 null。 */
    suspend fun syncNow(): SyncReport?
    /** 满足同步条件时排队一次后台同步；用于应用启动时补传上次未完成的记录。 */
    fun requestSync()
}

/**
 * 发件箱文件。同步状态只对上传它的账号有意义：换账号（含注销后重新注册）时
 * 全部记录换新编号重新待传，避免「已上传」停留在旧账号、或旧编号与他人记录冲突。
 */
@Serializable
private data class OutboxFile(val accountId: String? = null, val entries: List<OutboxEntry> = emptyList())

@Singleton
internal class OfflineFirstScreeningRecordRepository(
    private val directory: File,
    private val network: EyeNetworkDataSource,
    private val sessions: SessionStore,
    private val caller: AuthorizedCaller,
    private val scheduler: SyncScheduler,
    private val resolver: ContentResolver? = null,
    private val clock: () -> Long,
) : ScreeningRecordRepository {
    @Inject constructor(
        @ApplicationContext context: Context,
        network: EyeNetworkDataSource,
        sessions: SessionStore,
        caller: AuthorizedCaller,
        scheduler: SyncScheduler,
    ) : this(context.filesDir, network, sessions, caller, scheduler, context.contentResolver, System::currentTimeMillis)

    private val outboxFile = File(directory, OUTBOX_FILE)
    private val lock = Mutex()
    private val syncLock = Mutex()
    private val outboxState = MutableStateFlow(readOutbox())
    private val progress = MutableStateFlow(RecordSyncStatus())
    /** 本机记录文件变化的版本号：保存后加一，触发列表重新读取。 */
    private val recordsVersion = MutableStateFlow(0)

    override val localRecords: Flow<List<LocalRecordEntry>> = recordsVersion.map {
        directory.listFiles { file -> RECORD_NAME.matches(file.name) }.orEmpty()
            .mapNotNull { file -> runCatching { LocalRecordCatalog.entry(file.name, file.readText()) }.getOrNull() }
            .sortedByDescending { it.testedAtMs ?: 0L }
    }.flowOn(Dispatchers.IO)

    override suspend fun exportRecord(fileName: String, target: Uri): Boolean = withContext(Dispatchers.IO) {
        if (!RECORD_NAME.matches(fileName)) return@withContext false
        runCatching {
            val text = File(directory, fileName).readText()
            resolver!!.openOutputStream(target, "wt")!!.use { it.write(text.toByteArray()) }
        }.isSuccess
    }

    override val syncStatus: Flow<RecordSyncStatus> = combine(outboxState, progress) { file, run ->
        val list = file.entries
        run.copy(
            pending = list.count { it.state == SyncState.PENDING },
            synced = list.count { it.state == SyncState.SYNCED },
            failed = list.count { it.state == SyncState.CONFLICT || it.state == SyncState.REJECTED },
            awaitingGuardian = list.count { it.state == SyncState.PENDING && it.lastError == RecordSyncEngine.GUARDIAN_PENDING },
        )
    }

    override suspend fun saveRecord(name: String, json: String) {
        require(RECORD_NAME.matches(name)) { "记录文件名不符合约定：$name" }
        withContext(Dispatchers.IO) { File(directory, name).writeText(json) }
        recordsVersion.update { it + 1 }
        lock.withLock {
            mutate { it + OutboxEntry(UUID.randomUUID().toString(), name, testedAt(name)) }
        }
        requestSync()
    }

    override fun requestSync() {
        if (canSync()) scheduler.requestSync()
    }

    override suspend fun syncNow(): SyncReport? {
        if (!canSync()) return null
        val accountId = sessions.session.value?.accountId ?: return null
        return syncLock.withLock {
            progress.update { it.copy(syncing = true) }
            adoptAccount(accountId)
            backfill()
            val report = runCatching {
                RecordSyncEngine(outbox, remote, clock, clientPlatform = "Android").pushPending()
            }.getOrElse { SyncReport(stopReason = SyncStopReason.TRANSIENT) }
            progress.update { RecordSyncStatus(lastSyncAt = clock(), lastMessage = describe(report)) }
            report
        }
    }

    private fun canSync(): Boolean = network.isConfigured &&
        sessions.session.value?.consents?.get(ConsentType.HEALTH_DATA_SYNC) == true

    /** 发件箱属于别的账号（或尚无归属）时，全部记录换新编号、清空状态，改为待传到当前账号。 */
    private suspend fun adoptAccount(accountId: String) = lock.withLock {
        val current = outboxState.value
        if (current.accountId == accountId) return@withLock
        write(OutboxFile(accountId, current.entries.map { OutboxEntry(UUID.randomUUID().toString(), it.fileName, it.testedAt) }))
    }

    /** 把升级前已保存在本机、尚未进入发件箱的记录补登为待上传。 */
    private suspend fun backfill() = lock.withLock {
        val known = outboxState.value.entries.mapTo(HashSet()) { it.fileName }
        val files = withContext(Dispatchers.IO) {
            directory.listFiles { file -> RECORD_NAME.matches(file.name) && file.name !in known }.orEmpty().map(File::getName)
        }
        if (files.isNotEmpty()) mutate { list -> list + files.map { OutboxEntry(UUID.randomUUID().toString(), it, testedAt(it)) } }
    }

    private val outbox = object : RecordOutbox {
        override suspend fun entries(): List<OutboxEntry> = outboxState.value.entries
        override suspend fun payload(entry: OutboxEntry): String? = withContext(Dispatchers.IO) {
            File(directory, entry.fileName).takeIf(File::exists)?.readText()
        }
        override suspend fun update(entry: OutboxEntry) = lock.withLock {
            mutate { list -> list.map { if (it.recordId == entry.recordId) entry else it } }
        }
    }

    private val remote = object : RecordRemote {
        override suspend fun upload(recordId: String, upload: RecordUpload): UploadResult =
            when (val result = caller.call { token -> network.uploadRecord(token, recordId, upload) }) {
                is NetworkResult.Success -> if (result.status == 201) UploadResult.Created else UploadResult.Duplicate
                is NetworkResult.Unreachable -> UploadResult.Transient(result.message)
                is NetworkResult.HttpError -> when {
                    result.status == 401 -> UploadResult.Unauthorized
                    result.code == ErrorCodes.GUARDIAN_CONSENT_REQUIRED -> UploadResult.GuardianConsentRequired
                    result.code == ErrorCodes.CONSENT_REQUIRED -> {
                        // 同意已在别处撤回：同步本机缓存，停止上传
                        sessions.update { it.copy(consents = it.consents + (ConsentType.HEALTH_DATA_SYNC to false)) }
                        UploadResult.ConsentRequired
                    }
                    result.status == 409 -> UploadResult.Conflict
                    result.status == 429 || result.status >= 500 -> UploadResult.Transient(result.message)
                    else -> UploadResult.Rejected(result.message)
                }
            }
    }

    /** 调用方须持有 [lock]。 */
    private suspend fun mutate(transform: (List<OutboxEntry>) -> List<OutboxEntry>) =
        write(outboxState.value.let { it.copy(entries = transform(it.entries)) })

    /** 先写临时文件再改名，避免进程被杀时留下半个发件箱。 */
    private suspend fun write(next: OutboxFile) {
        withContext(Dispatchers.IO) {
            val temp = File(directory, "$OUTBOX_FILE.tmp")
            temp.writeText(SyncContract.json.encodeToString(OutboxFile.serializer(), next))
            if (!temp.renameTo(outboxFile)) { outboxFile.delete(); temp.renameTo(outboxFile) }
        }
        outboxState.value = next
    }

    /** 文件缺失或损坏时从空发件箱开始；下次同步的补登会把本机记录重新登记为待传。 */
    private fun readOutbox(): OutboxFile = runCatching {
        outboxFile.takeIf(File::exists)?.readText()?.let { SyncContract.json.decodeFromString(OutboxFile.serializer(), it) }
    }.getOrNull() ?: OutboxFile()

    private fun testedAt(name: String): Long = name.substringAfterLast('_').removeSuffix(".json").toLongOrNull() ?: clock()

    private fun describe(report: SyncReport): String = when (report.stopReason) {
        SyncStopReason.TRANSIENT -> "网络不可用，稍后自动重试"
        SyncStopReason.UNAUTHORIZED -> "登录已失效，请重新登录"
        SyncStopReason.CONSENT_REQUIRED -> "未同意云端存储，已停止上传"
        null -> buildList {
            add("本次上传 ${report.uploaded} 条")
            if (report.awaitingGuardian > 0) add("${report.awaitingGuardian} 条儿童记录待监护人同意")
            if (report.conflicts + report.rejected > 0) add("${report.conflicts + report.rejected} 条未能上传")
        }.joinToString("，")
    }

    companion object {
        const val OUTBOX_FILE = "screening_outbox.json"
        /** shared/screening 的记录命名：`dryeye_<ms>.json`、`presbyopia_<ms>.json`、`strabismus_<ms>.json`。 */
        val RECORD_NAME = Regex("^(dryeye|presbyopia|strabismus)_\\d+\\.json$")
    }
}
