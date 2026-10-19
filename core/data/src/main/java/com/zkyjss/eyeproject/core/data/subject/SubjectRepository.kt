package com.zkyjss.eyeproject.core.data.subject

import android.content.Context
import com.zkyjss.eyeproject.core.data.account.AuthorizedCaller
import com.zkyjss.eyeproject.core.data.account.SessionStore
import com.zkyjss.eyeproject.core.data.sync.SyncScheduler
import com.zkyjss.eyeproject.core.network.EyeNetworkDataSource
import com.zkyjss.eyeproject.core.network.NetworkResult
import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.sync.ErrorCodes
import com.zkyjss.eyeproject.core.sync.SubjectDto
import com.zkyjss.eyeproject.core.sync.SubjectInput
import com.zkyjss.eyeproject.core.sync.SubjectProfileInput
import com.zkyjss.eyeproject.core.sync.SubjectRelation
import com.zkyjss.eyeproject.core.sync.SyncContract
import com.zkyjss.eyeproject.feature.services.SubjectProfile
import com.zkyjss.eyeproject.feature.services.SubjectProfiles
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 被测者（本人与家庭成员）及其资料（原测试问卷）的仓库，离线优先：本机文件是事实源，
 * 满足「已配置服务器 + 已登录 + 已同意健康数据云端存储」时与云端双向同步。
 */
interface SubjectRepository {
    /** 本机成员（本人在前）。首次使用时自动建立空白的「本人」。 */
    val subjects: Flow<List<SubjectProfile>>
    /** 本机成员编号 → 云端成员编号（已同步的成员）；记录上传时据此归属成员。 */
    val remoteIds: Flow<Map<String, String>>
    /** 新建或修改成员；按条件排队同步。 */
    suspend fun save(profile: SubjectProfile)
    /** 删除家庭成员（「本人」不能删除）。 */
    suspend fun delete(id: String)
    /** 立即与云端同步；不满足同步条件或网络失败时返回 false。 */
    suspend fun sync(): Boolean
}

/** 本机保存的成员与同步簿记。 */
@Serializable
internal data class StoredSubject(
    val id: String,
    val relation: String,
    val nickname: String,
    val fields: Map<String, String> = emptyMap(),
    val updatedAt: Long,
    val remoteId: String? = null,
    /** 称呼、关系、出生年份、性别有改动待上传。 */
    val infoDirty: Boolean = true,
    /** 健康资料有改动待上传。 */
    val profileDirty: Boolean = true,
    /** 已在本机删除、待从云端删除。 */
    val deleted: Boolean = false,
    /** 最近一次上传失败的原因（如待监护人同意）。 */
    val lastError: String? = null,
)

/** [accountId]：同步归属的账号；换账号时全部成员重新待传（与记录发件箱一致）。 */
@Serializable
internal data class SubjectFile(val accountId: String? = null, val subjects: List<StoredSubject> = emptyList())

@Singleton
internal class OfflineFirstSubjectRepository(
    directory: File,
    private val network: EyeNetworkDataSource,
    private val sessions: SessionStore,
    private val caller: AuthorizedCaller,
    private val scheduler: SyncScheduler,
    private val clock: () -> Long,
) : SubjectRepository {
    @Inject constructor(
        @ApplicationContext context: Context,
        network: EyeNetworkDataSource,
        sessions: SessionStore,
        caller: AuthorizedCaller,
        scheduler: SyncScheduler,
    ) : this(context.filesDir, network, sessions, caller, scheduler, System::currentTimeMillis)

    private val file = File(directory, FILE_NAME)
    private val lock = Mutex()
    private val syncLock = Mutex()
    private val state = MutableStateFlow(read().withSelf())

    override val subjects: Flow<List<SubjectProfile>> = state.map { f ->
        f.subjects.filterNot { it.deleted }.sortedBy { if (it.relation == SELF) 0 else 1 }.map { it.toProfile() }
    }

    override val remoteIds: Flow<Map<String, String>> = state.map { f ->
        f.subjects.mapNotNull { s -> s.remoteId?.let { s.id to it } }.toMap()
    }

    override suspend fun save(profile: SubjectProfile) {
        lock.withLock {
            val now = clock()
            val nickname = profile.nickname.trim().take(32).ifEmpty { defaultName(profile.relation) }
            mutate { list ->
                val existing = list.firstOrNull { it.id == profile.id }
                if (existing == null) list + StoredSubject(profile.id, profile.relation, nickname, profile.fields, now)
                else list.map {
                    if (it.id != profile.id) it else it.copy(
                        // 「本人」关系不可改，其余成员不能改成「本人」
                        relation = if (it.relation == SELF) SELF else profile.relation.takeIf { r -> r != SELF } ?: it.relation,
                        nickname = nickname, fields = profile.fields, updatedAt = now,
                        infoDirty = it.infoDirty || basicInfo(it) != basicInfo(profile.relation, nickname, profile.fields),
                        profileDirty = it.profileDirty || it.fields != profile.fields, lastError = null,
                    )
                }
            }
        }
        requestSync()
    }

    override suspend fun delete(id: String) {
        lock.withLock {
            mutate { list ->
                list.mapNotNull {
                    when {
                        it.id != id || it.relation == SELF -> it
                        it.remoteId == null -> null
                        else -> it.copy(deleted = true)
                    }
                }
            }
        }
        requestSync()
    }

    private fun requestSync() { if (canSync()) scheduler.requestSync() }

    private fun canSync(): Boolean = network.isConfigured &&
        sessions.session.value?.consents?.get(ConsentType.HEALTH_DATA_SYNC) == true

    override suspend fun sync(): Boolean = syncLock.withLock {
        if (!canSync()) return false
        val session = sessions.session.value ?: return false
        adoptAccount(session.accountId)
        val remote = when (val result = caller.call(network::subjects)) {
            is NetworkResult.Success -> result.value
            else -> return false
        }
        lock.withLock { mutate { list -> merge(list, remote, session.defaultSubjectId) } }
        var ok = true
        for (subject in state.value.subjects) ok = push(subject) && ok
        ok
    }

    /** 换账号（含注销后重新注册）：全部成员与云端解除关联，重新待传。 */
    private suspend fun adoptAccount(accountId: String) = lock.withLock {
        if (state.value.accountId == accountId) return@withLock
        write(SubjectFile(accountId, state.value.subjects.filterNot { it.deleted }
            .map { it.copy(remoteId = null, infoDirty = true, profileDirty = true, lastError = null) }))
    }

    /**
     * 合并云端成员：「本人」绑定账号默认成员；本机没有的云端成员拉到本机；本机无待传改动、云端资料更新时取云端；
     * 本机已同步过、云端已删除（别的设备删的）且本机无改动的成员在本机删除。
     */
    private fun merge(local: List<StoredSubject>, remote: List<SubjectDto>, defaultRemoteId: String): List<StoredSubject> {
        val byRemote = remote.associateBy { it.id }
        val bound = local.map { if (it.relation == SELF && it.remoteId == null) it.copy(remoteId = defaultRemoteId) else it }
        val merged = bound.mapNotNull { s ->
            val r = s.remoteId?.let(byRemote::get)
            when {
                s.remoteId != null && r == null && !s.deleted && !s.infoDirty && !s.profileDirty && s.relation != SELF -> null
                r == null || s.deleted -> s
                !s.profileDirty && (r.profileUpdatedAt ?: 0L) > s.updatedAt -> s.copy(fields = r.profile.orEmpty(),
                    updatedAt = r.profileUpdatedAt ?: s.updatedAt, nickname = if (s.infoDirty) s.nickname else r.nickname)
                !s.infoDirty -> s.copy(nickname = r.nickname)
                else -> s
            }
        }
        val known = merged.mapNotNullTo(HashSet()) { it.remoteId }
        val pulled = remote.filter { it.id !in known }.map { r ->
            StoredSubject(r.id, r.relation.name, r.nickname, r.profile.orEmpty(), r.profileUpdatedAt ?: r.createdAt,
                remoteId = r.id, infoDirty = false, profileDirty = false)
        }
        return merged + pulled
    }

    /** 上传一个成员的待传改动；成功或无需上传返回 true。 */
    private suspend fun push(subject: StoredSubject): Boolean {
        var current = subject
        if (current.deleted) {
            val result = current.remoteId?.let { id -> caller.call { network.deleteSubject(it, id) } }
            val gone = result == null || result is NetworkResult.Success ||
                (result is NetworkResult.HttpError && result.status == 404)
            if (gone) lock.withLock { mutate { list -> list.filterNot { it.id == current.id } } }
            return gone
        }
        if (current.infoDirty || current.remoteId == null) {
            val input = SubjectInput(SubjectRelation.valueOf(current.relation), current.nickname,
                birthYear = current.fields[SubjectProfiles.BIRTH_YEAR]?.toIntOrNull(), sex = sexCode(current.fields["性别"]),
                id = current.id.takeIf { current.remoteId == null })
            val result = current.remoteId?.let { id -> caller.call { network.updateSubject(it, id, input) } }
                ?: caller.call { network.createSubject(it, input) }
            if (result !is NetworkResult.Success) { remember(current, result); return false }
            current = settle(current) { it.copy(remoteId = result.value.id, infoDirty = it.infoDirty && it.updatedAt != current.updatedAt) }
        }
        if (current.profileDirty) {
            val remoteId = current.remoteId ?: return false
            val result = caller.call { network.putSubjectProfile(it, remoteId, SubjectProfileInput(current.fields, current.updatedAt)) }
            when {
                result is NetworkResult.Success -> settle(current) { it.copy(profileDirty = it.updatedAt != current.updatedAt, lastError = null) }
                // 别的设备改得更新：以云端为准
                result is NetworkResult.HttpError && result.code == ErrorCodes.CONFLICT -> {
                    val latest = (caller.call(network::subjects) as? NetworkResult.Success)?.value?.firstOrNull { it.id == remoteId }
                    if (latest != null) settle(current) { it.copy(fields = latest.profile.orEmpty(),
                        updatedAt = latest.profileUpdatedAt ?: it.updatedAt, profileDirty = false, lastError = null) }
                }
                else -> { remember(current, result); return false }
            }
        }
        return true
    }

    /** 把上传结果写回本机；上传期间本机又改动过的字段保持待传。 */
    private suspend fun settle(sent: StoredSubject, change: (StoredSubject) -> StoredSubject): StoredSubject = lock.withLock {
        mutate { list -> list.map { if (it.id == sent.id) change(it) else it } }
        state.value.subjects.first { it.id == sent.id }
    }

    private suspend fun remember(subject: StoredSubject, result: NetworkResult<*>?) {
        val message = when {
            result is NetworkResult.HttpError && result.code == ErrorCodes.GUARDIAN_CONSENT_REQUIRED -> GUARDIAN_PENDING
            result is NetworkResult.HttpError -> result.message
            result is NetworkResult.Unreachable -> result.message
            else -> null
        }
        lock.withLock { mutate { list -> list.map { if (it.id == subject.id) it.copy(lastError = message) else it } } }
    }

    /** 调用方须持有 [lock]。 */
    private suspend fun mutate(transform: (List<StoredSubject>) -> List<StoredSubject>) =
        write(state.value.let { it.copy(subjects = transform(it.subjects)) })

    /** 先写临时文件再改名，避免进程被杀时留下半个文件。 */
    private suspend fun write(next: SubjectFile) {
        withContext(Dispatchers.IO) {
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(SyncContract.json.encodeToString(SubjectFile.serializer(), next))
            if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
        }
        state.value = next
    }

    private fun read(): SubjectFile = runCatching {
        file.takeIf(File::exists)?.readText()?.let { SyncContract.json.decodeFromString(SubjectFile.serializer(), it) }
    }.getOrNull() ?: SubjectFile()

    /** 首次使用：建立空白的「本人」（只在内存，首次保存时落盘）。 */
    private fun SubjectFile.withSelf(): SubjectFile =
        if (subjects.any { it.relation == SELF }) this
        else copy(subjects = listOf(StoredSubject(UUID.randomUUID().toString(), SELF, defaultName(SELF), updatedAt = clock())) + subjects)

    private fun StoredSubject.toProfile() = SubjectProfile(id, relation, nickname, fields, updatedAt)

    private fun basicInfo(s: StoredSubject) = basicInfo(s.relation, s.nickname, s.fields)
    private fun basicInfo(relation: String, nickname: String, fields: Map<String, String>) =
        listOf(relation, nickname, fields[SubjectProfiles.BIRTH_YEAR], fields["性别"])

    companion object {
        const val FILE_NAME = "subjects.json"
        const val SELF = "SELF"
        /** 上传被拒、等待监护人同意时记录的原因。 */
        const val GUARDIAN_PENDING = "待监护人同意"

        fun defaultName(relation: String) = if (relation == SELF) "我" else SubjectProfiles.relationLabel(relation)

        /** 资料里的性别 → 服务端取值。 */
        fun sexCode(value: String?) = when (value) { "男" -> "MALE"; "女" -> "FEMALE"; "不愿透露" -> "OTHER"; else -> null }
    }
}
