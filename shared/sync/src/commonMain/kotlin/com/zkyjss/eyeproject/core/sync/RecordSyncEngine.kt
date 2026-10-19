package com.zkyjss.eyeproject.core.sync

import kotlinx.serialization.Serializable

/** 本机发件箱中一条记录的同步状态。记录不可变，因此没有双向合并。 */
@Serializable
enum class SyncState {
    /** 待上传（含网络失败后等待重试）。 */
    PENDING,
    SYNCED,
    /** 服务端已有同 id 但内容不同的记录；不重试，需人工处理。 */
    CONFLICT,
    /** 服务端判定记录无效（格式、质量矛盾等）；不重试。 */
    REJECTED,
}

@Serializable
data class OutboxEntry(
    /** 客户端生成的 UUID，同时作为服务端幂等键。 */
    val recordId: String,
    /** 本机记录文件名，例如 `strabismus_1760500000000.json`。 */
    val fileName: String,
    val testedAt: Long,
    val state: SyncState = SyncState.PENDING,
    val attempts: Int = 0,
    val lastError: String? = null,
    val syncedAt: Long? = null,
)

/** 本机发件箱端口：安卓由 core:data 实现，iOS 后续由 Swift 侧实现。 */
interface RecordOutbox {
    suspend fun entries(): List<OutboxEntry>
    /** 读取原文；本机文件已被删除时返回 null。 */
    suspend fun payload(entry: OutboxEntry): String?
    suspend fun update(entry: OutboxEntry)
}

sealed interface UploadResult {
    data object Created : UploadResult
    data object Duplicate : UploadResult
    data object Conflict : UploadResult
    /** 令牌失效且刷新失败；需要重新登录。 */
    data object Unauthorized : UploadResult
    data object ConsentRequired : UploadResult
    /** 这条记录属于未成年人且缺少监护人同意；保持待上传，不影响其它记录。 */
    data object GuardianConsentRequired : UploadResult
    data class Rejected(val message: String) : UploadResult
    /** 网络错误、超时、5xx、限流；保持待上传，稍后重试。 */
    data class Transient(val message: String) : UploadResult
}

/** 远端端口：平台以各自的 HTTP 栈实现，负责附带令牌并在 401 时刷新一次。 */
interface RecordRemote {
    suspend fun upload(recordId: String, upload: RecordUpload): UploadResult
}

enum class SyncStopReason { UNAUTHORIZED, CONSENT_REQUIRED, TRANSIENT }

data class SyncReport(
    val uploaded: Int = 0,
    val duplicates: Int = 0,
    val conflicts: Int = 0,
    val rejected: Int = 0,
    val remaining: Int = 0,
    /** 等待监护人同意的记录数。 */
    val awaitingGuardian: Int = 0,
    val stopReason: SyncStopReason? = null,
) {
    /** 仅网络类失败值得退避重试；未登录或未同意需要用户操作。 */
    val shouldRetry: Boolean get() = stopReason == SyncStopReason.TRANSIENT
}

/** [RecordSyncEngine] 的成员换算抛出：记录所属成员尚未同步到云端，本条留待下次（不能错归到「本人」）。 */
class SubjectNotSyncedException : Exception("记录所属成员尚未同步")

/**
 * 按测试时间顺序推送待上传记录。遇到需要用户处理的情况（未登录、未同意）或网络失败时整轮停止，
 * 其余记录保持 PENDING；单条记录的冲突或无效不影响后续记录。
 */
class RecordSyncEngine(
    private val outbox: RecordOutbox,
    private val remote: RecordRemote,
    private val clock: () -> Long,
    /** 记录归属的云端成员编号（由记录里的本机成员编号换算）；为空归入账号的「本人」。 */
    private val subjectFor: suspend (payload: String) -> String? = { null },
    private val clientPlatform: String? = null,
    private val appVersion: String? = null,
) {
    suspend fun pushPending(): SyncReport {
        val pending = outbox.entries().filter { it.state == SyncState.PENDING }.sortedBy { it.testedAt }
        var report = SyncReport()
        for ((index, entry) in pending.withIndex()) {
            val payload = outbox.payload(entry)
            if (payload == null) {
                outbox.update(entry.copy(state = SyncState.REJECTED, lastError = "本机记录文件已不存在"))
                report = report.copy(rejected = report.rejected + 1)
                continue
            }
            val subjectId = try { subjectFor(payload) } catch (_: SubjectNotSyncedException) { continue }
            val upload = RecordUpload(subjectId, entry.testedAt, payload, Sha256.hex(payload), clientPlatform, appVersion)
            val attempted = entry.copy(attempts = entry.attempts + 1)
            val stop = when (val result = remote.upload(entry.recordId, upload)) {
                UploadResult.Created, UploadResult.Duplicate -> {
                    outbox.update(attempted.copy(state = SyncState.SYNCED, lastError = null, syncedAt = clock()))
                    report = if (result == UploadResult.Created) report.copy(uploaded = report.uploaded + 1)
                        else report.copy(duplicates = report.duplicates + 1)
                    null
                }
                UploadResult.Conflict -> {
                    outbox.update(attempted.copy(state = SyncState.CONFLICT, lastError = "云端已有同编号的不同记录"))
                    report = report.copy(conflicts = report.conflicts + 1)
                    null
                }
                is UploadResult.Rejected -> {
                    outbox.update(attempted.copy(state = SyncState.REJECTED, lastError = result.message))
                    report = report.copy(rejected = report.rejected + 1)
                    null
                }
                UploadResult.GuardianConsentRequired -> {
                    outbox.update(attempted.copy(lastError = GUARDIAN_PENDING))
                    report = report.copy(awaitingGuardian = report.awaitingGuardian + 1)
                    null
                }
                UploadResult.Unauthorized -> SyncStopReason.UNAUTHORIZED
                UploadResult.ConsentRequired -> SyncStopReason.CONSENT_REQUIRED
                is UploadResult.Transient -> {
                    outbox.update(attempted.copy(lastError = result.message))
                    SyncStopReason.TRANSIENT
                }
            }
            if (stop != null) return report.copy(remaining = pending.size - index, stopReason = stop)
        }
        return report
    }

    companion object {
        const val GUARDIAN_PENDING = "儿童测试记录需监护人同意后上传"
    }
}
