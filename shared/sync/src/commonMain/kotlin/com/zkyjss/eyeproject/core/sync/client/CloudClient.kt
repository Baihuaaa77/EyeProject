package com.zkyjss.eyeproject.core.sync.client

import com.zkyjss.eyeproject.core.sync.ConsentDto
import com.zkyjss.eyeproject.core.sync.ConsentInput
import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.sync.ErrorCodes
import com.zkyjss.eyeproject.core.sync.LoginRequest
import com.zkyjss.eyeproject.core.sync.OutboxEntry
import com.zkyjss.eyeproject.core.sync.RecordOutbox
import com.zkyjss.eyeproject.core.sync.RecordRemote
import com.zkyjss.eyeproject.core.sync.RecordSummaries
import com.zkyjss.eyeproject.core.sync.RecordSyncEngine
import com.zkyjss.eyeproject.core.sync.RecordUpload
import com.zkyjss.eyeproject.core.sync.RegisterRequest
import com.zkyjss.eyeproject.core.sync.SubjectInput
import com.zkyjss.eyeproject.core.sync.SubjectNotSyncedException
import com.zkyjss.eyeproject.core.sync.SubjectProfileInput
import com.zkyjss.eyeproject.core.sync.SubjectRelation
import com.zkyjss.eyeproject.core.sync.SyncContract
import com.zkyjss.eyeproject.core.sync.SyncReport
import com.zkyjss.eyeproject.core.sync.SyncState
import com.zkyjss.eyeproject.core.sync.SyncStopReason
import com.zkyjss.eyeproject.core.sync.TokenResponse
import com.zkyjss.eyeproject.core.sync.UploadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 平台存储与时钟（苹果由 Swift 实现）：
 * - [readSecret]/[writeSecret]：登录会话，存钥匙串；
 * - [readValue]/[writeValue]：发件箱、成员同步簿记、服务器地址，存应用沙盒文件；
 * - 本机记录与成员资料仍由平台原有存储管理，这里只读取与回写。
 */
interface CloudPlatform {
    fun nowMs(): Long
    fun readSecret(key: String): String?
    fun writeSecret(key: String, value: String?)
    fun readValue(key: String): String?
    fun writeValue(key: String, value: String?)
    /** 本机记录文件名（`dryeye_<ms>.json` 等）。 */
    fun recordFileNames(): List<String>
    fun readRecord(fileName: String): String?
    /** 本机成员资料：`[{id, relation, nickname, fields, updatedAt}]`（与 SubjectProfiles.toJson 同格式）。 */
    fun subjectsJson(): String
    /** 写回云端拉下来的成员（同格式的单个对象）；已存在则覆盖。 */
    fun upsertSubjectJson(json: String)
    fun removeSubject(id: String)
}

/**
 * 苹果端的云端同步客户端，与安卓 core:data 同一套规则：登录凭证快过期时先刷新、401 时刷新重试一次；
 * 「健康数据云端存储」同意后才上传资料与记录；成员用本机编号在云端新建，「本人」绑定账号默认成员；
 * 本机无待传改动且云端资料更新时以云端为准；孩子资料与记录未获监护人同意时保持待传；记录按成员归属上传。
 *
 * 所有调用在 [scope]（主线程）里串行执行；状态变化时回调 [onChange]，界面读取 [snapshotJson]。
 */
@OptIn(ExperimentalUuidApi::class)
class CloudClient(
    private val transport: HttpTransport,
    private val platform: CloudPlatform,
    private val scope: CoroutineScope,
    private val onChange: () -> Unit,
) {
    private val json = SyncContract.json
    private val api = CloudApi({ serverUrl }, transport)
    private val syncLock = Mutex()
    private var busy = false
    private var syncing = false
    private var message: String? = null
    private var lastSyncAt: Long? = null
    /** 最近一次用户操作的结果（界面据此给成功 / 失败振动）；序号递增，同一结果不重复触发。 */
    private var outcomeId = 0
    private var outcomeOk = false
    /** 用户点了「立即同步」：本轮同步结束后报告结果。 */
    private var reportSync = false

    /** 服务器地址（如 `http://192.168.1.5:8080`）；为空表示未配置，任何数据都不离开手机。 */
    var serverUrl: String = platform.readValue(KEY_SERVER).orEmpty()
        private set

    private var session: CloudSession? = platform.readSecret(KEY_SESSION)
        ?.let { runCatching { json.decodeFromString(CloudSession.serializer(), it) }.getOrNull() }
        set(value) {
            field = value
            platform.writeSecret(KEY_SESSION, value?.let { json.encodeToString(CloudSession.serializer(), it) })
        }

    // region 界面操作（Swift 直接调用，结果经 onChange + snapshotJson 呈现）

    /** 设置服务器地址；只接受 http(s) 地址，空字符串表示关闭云端。换地址时退出登录。 */
    fun setServer(url: String): Boolean {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isNotEmpty() && !Regex("^https?://[^\\s/]+(:\\d+)?$").matches(trimmed)) {
            message = "地址格式应为 http://IP:端口 或 https://域名"; outcome(false); onChange(); return false
        }
        if (trimmed != serverUrl) { session = null; message = null }
        serverUrl = trimmed
        platform.writeValue(KEY_SERVER, trimmed.ifEmpty { null })
        outcome(true)
        onChange()
        return true
    }

    fun register(email: String, password: String) = act("注册成功") {
        establish(api.register(RegisterRequest(email.trim(), password, null, SyncContract.PRIVACY_POLICY_VERSION)))
    }

    fun signIn(email: String, password: String) = act("已登录") { establish(api.login(LoginRequest(email.trim(), password))) }

    /** 退出登录只清本机会话；本机测试记录与成员资料保留。 */
    fun signOut() = act("已退出登录，本机记录仍保留") {
        val current = session ?: return@act null
        authorized { api.logout(it, current.refreshToken) }
        session = null
        null
    }

    /** [type] 为 HEALTH_DATA_SYNC / GUARDIAN / RESEARCH_USE；GUARDIAN 指「本人」这一成员的监护人同意。 */
    fun setConsent(type: String, granted: Boolean) = act(null) {
        val current = session ?: return@act "请先登录"
        val consentType = runCatching { ConsentType.valueOf(type) }.getOrNull() ?: return@act "未知的同意项"
        val subject = if (consentType == ConsentType.GUARDIAN) current.defaultSubjectId else null
        when (val result = authorized { api.recordConsent(it, ConsentInput(consentType, SyncContract.PRIVACY_POLICY_VERSION, granted, subject)) }) {
            is ApiResult.Ok -> {
                session = session?.copy(consents = consentMap(result.value, current.defaultSubjectId))
                if (granted && consentType != ConsentType.RESEARCH_USE) launchSync()
                null
            }
            else -> describe(result)
        }
    }

    /** 立即同步成员资料与测试记录；[userInitiated] 为用户点按（结束后给出结果），回到前台的自动补传为 false。 */
    fun syncNow(userInitiated: Boolean) {
        if (userInitiated && !canSync()) { message = "请先同意云端存储"; outcome(false); onChange(); return }
        if (userInitiated) reportSync = true
        launchSync()
    }

    /** 新记录已保存到本机：登记到发件箱并在满足条件时上传。 */
    fun recordSaved(fileName: String) {
        if (!RECORD_NAME.matches(fileName)) return
        val outbox = readOutbox()
        if (outbox.entries.none { it.fileName == fileName }) {
            writeOutbox(outbox.copy(entries = outbox.entries + OutboxEntry(Uuid.random().toString(), fileName, testedAt(fileName))))
        }
        onChange()
        launchSync()
    }

    /** 成员资料在本机改动后调用：满足条件时排队同步。 */
    fun subjectsChanged() = launchSync()

    /** 导出云端全部数据（含解密原文）；[done] 收到 JSON 文本，失败时收到 null（原因见 message）。 */
    fun exportCloudData(done: (String?) -> Unit) {
        track(scope.launch {
            busy = true; onChange()
            when (val result = authorized { api.exportAll(it) }) {
                is ApiResult.Ok -> { message = "已导出云端数据"; outcome(true); done(result.value) }
                else -> { message = describe(result); outcome(false); done(null) }
            }
            busy = false; onChange()
        })
    }

    /** 注销云端账号并删除全部云端数据；本机记录保留。 */
    fun deleteCloudAccount() = act("云端账号及数据已删除，本机记录仍保留") {
        when (val result = authorized { api.deleteAccount(it) }) {
            is ApiResult.Ok -> { session = null; writeOutbox(OutboxFile()); writeBook(SubjectBook()); null }
            else -> describe(result)
        }
    }

    /** 界面状态（JSON）：是否配置、登录、各项同意、上传统计、进行中、最近提示。 */
    fun snapshotJson(): String {
        val s = session
        val entries = readOutbox().entries
        return buildJsonObject {
            put("serverUrl", serverUrl)
            put("configured", api.configured)
            put("signedIn", s != null)
            put("email", s?.email)
            put("healthDataSync", s?.consents?.get(ConsentType.HEALTH_DATA_SYNC) == true)
            put("guardian", s?.consents?.get(ConsentType.GUARDIAN) == true)
            put("researchUse", s?.consents?.get(ConsentType.RESEARCH_USE) == true)
            put("pending", entries.count { it.state == SyncState.PENDING })
            put("synced", entries.count { it.state == SyncState.SYNCED })
            put("failed", entries.count { it.state == SyncState.CONFLICT || it.state == SyncState.REJECTED })
            put("awaitingGuardian", entries.count { it.state == SyncState.PENDING && it.lastError == RecordSyncEngine.GUARDIAN_PENDING } +
                readBook().entries.values.count { it.lastError == GUARDIAN_PENDING })
            put("busy", busy || syncing)
            put("message", message)
            put("lastSyncAt", lastSyncAt)
            put("outcomeId", outcomeId)
            put("outcomeOk", outcomeOk)
        }.toString()
    }

    // endregion

    private val jobs = mutableListOf<Job>()

    private fun track(job: Job) { jobs.removeAll { !it.isActive }; jobs += job }

    /** 等待已发起的操作和同步全部完成（测试与后台任务用）。 */
    suspend fun awaitIdle() { while (true) { val active = jobs.filter { it.isActive }; if (active.isEmpty()) return; active.forEach { it.join() } } }

    private fun act(success: String?, block: suspend () -> String?) {
        if (busy) return
        track(scope.launch {
            busy = true; message = null; onChange()
            val failure = block()
            message = failure ?: success
            outcome(failure == null)
            busy = false; onChange()
        })
    }

    private fun outcome(ok: Boolean) { outcomeId++; outcomeOk = ok }

    private fun canSync() = api.configured && session?.consents?.get(ConsentType.HEALTH_DATA_SYNC) == true

    private var syncJob: Job? = null
    /** 同步进行中又有新改动（如刚保存了记录）：本轮结束后再同步一次，不丢改动。 */
    private var resyncRequested = false

    private fun launchSync() {
        if (!canSync()) return
        if (syncJob?.isActive == true) { resyncRequested = true; return }
        syncJob = scope.launch {
            var ok: Boolean
            do { resyncRequested = false; ok = sync() } while (resyncRequested && canSync())
            if (reportSync) { reportSync = false; outcome(ok); onChange() }
        }.also(::track)
    }

    /** 先同步成员（记录要归属到成员），再推送记录。返回是否全部完成。 */
    suspend fun sync(): Boolean = syncLock.withLock {
        if (!canSync()) return false
        val current = session ?: return false
        syncing = true; onChange()
        adoptAccount(current.accountId)
        val subjectsOk = syncSubjects(current.defaultSubjectId)
        backfillOutbox()
        val report = runCatching {
            RecordSyncEngine(outbox, remote, platform::nowMs, subjectFor = ::remoteSubjectFor, clientPlatform = "iOS").pushPending()
        }.getOrElse { SyncReport(stopReason = SyncStopReason.TRANSIENT) }
        lastSyncAt = platform.nowMs()
        message = describe(report)
        syncing = false; onChange()
        subjectsOk && report.stopReason == null
    }

    // region 会话

    private suspend fun establish(login: ApiResult<TokenResponse>): String? {
        val tokens = when (login) {
            is ApiResult.Ok -> login.value
            else -> return describe(login)
        }
        val account = api.account(tokens.accessToken)
        val consents = api.consents(tokens.accessToken)
        if (account !is ApiResult.Ok) return describe(account)
        if (consents !is ApiResult.Ok) return describe(consents)
        session = CloudSession(tokens.accountId, account.value.email, account.value.defaultSubjectId, tokens.accessToken,
            tokens.accessExpiresAt, tokens.refreshToken, tokens.refreshExpiresAt, consentMap(consents.value, account.value.defaultSubjectId))
        launchSync()
        return null
    }

    /** 带访问令牌执行：快过期先刷新，遇 401 刷新后重试一次；刷新被拒清除会话（等同退出登录）。 */
    private suspend fun <T> authorized(block: suspend (String) -> ApiResult<T>): ApiResult<T> {
        val current = session ?: return SIGNED_OUT
        val token = if (current.accessExpiresAt - EXPIRY_MARGIN_MS > platform.nowMs()) current.accessToken
            else when (val renewed = refresh()) {
                is ApiResult.Ok -> renewed.value
                is ApiResult.Failed -> return renewed
                is ApiResult.Unreachable -> return renewed
            }
        val first = block(token)
        if (first !is ApiResult.Failed || first.status != 401) return first
        return when (val renewed = refresh()) {
            is ApiResult.Ok -> block(renewed.value)
            is ApiResult.Failed -> renewed
            is ApiResult.Unreachable -> renewed
        }
    }

    private suspend fun refresh(): ApiResult<String> {
        val current = session ?: return SIGNED_OUT
        return when (val result = api.refresh(current.refreshToken)) {
            is ApiResult.Ok -> {
                val t = result.value
                session = current.copy(accessToken = t.accessToken, accessExpiresAt = t.accessExpiresAt,
                    refreshToken = t.refreshToken, refreshExpiresAt = t.refreshExpiresAt)
                ApiResult.Ok(t.accessToken, result.status)
            }
            is ApiResult.Failed -> { if (result.status == 401) { session = null; SIGNED_OUT } else result }
            is ApiResult.Unreachable -> result
        }
    }

    private fun consentMap(consents: List<ConsentDto>, defaultSubjectId: String): Map<ConsentType, Boolean> =
        consents.filter { it.subjectId == null || (it.type == ConsentType.GUARDIAN && it.subjectId == defaultSubjectId) }
            .associate { it.type to it.granted }

    // endregion

    // region 成员与资料

    /**
     * 成员同步：簿记记录每个本机成员对应的云端编号和最后同步时的修改时间；本机修改时间变了即为待传。
     * 本机删除（簿记有、本机没有）→ 云端删除；别的设备删除且本机无改动 → 本机删除；云端独有 → 拉到本机。
     */
    private suspend fun syncSubjects(defaultRemoteId: String): Boolean {
        val remote = when (val result = authorized { api.subjects(it) }) {
            is ApiResult.Ok -> result.value.associateBy { it.id }
            else -> { message = describe(result); return false }
        }
        val locals = LocalSubjects.parse(platform.subjectsJson())
        var book = readBook()
        val entries = book.entries.toMutableMap()
        locals.firstOrNull { it.relation == SELF }?.let { self ->
            if (entries[self.id]?.remoteId == null) entries[self.id] = BookEntry(defaultRemoteId)
        }
        var ok = true
        // 本机已删除
        for ((localId, entry) in entries.toMap()) if (locals.none { it.id == localId }) {
            val remoteId = entry.remoteId
            if (remoteId != null && remote[remoteId]?.isDefault == false) {
                val result = authorized { api.deleteSubject(it, remoteId) }
                if (result !is ApiResult.Ok && !(result is ApiResult.Failed && result.status == 404)) { ok = false; continue }
            }
            entries.remove(localId)
        }
        for (local in locals) {
            val entry = entries[local.id]
            val r = entry?.remoteId?.let(remote::get)
            val dirty = entry?.syncedUpdatedAt != local.updatedAt
            when {
                // 别的设备删除且本机无改动
                entry?.remoteId != null && r == null && !dirty && local.relation != SELF -> {
                    platform.removeSubject(local.id); entries.remove(local.id)
                }
                // 云端更新、本机无改动：以云端为准
                r != null && !dirty && (r.profileUpdatedAt ?: 0L) > local.updatedAt -> {
                    val pulled = local.copy(nickname = r.nickname, fields = r.profile.orEmpty(), updatedAt = r.profileUpdatedAt!!)
                    platform.upsertSubjectJson(LocalSubjects.encode(pulled))
                    entries[local.id] = BookEntry(r.id, pulled.updatedAt)
                }
                dirty -> { val pushed = push(local, entry); entries[local.id] = pushed; if (pushed.lastError != null) ok = false }
            }
        }
        // 云端独有的成员（别的设备新建）：拉到本机
        val known = entries.values.mapNotNullTo(HashSet()) { it.remoteId } + locals.map { it.id }
        for (r in remote.values) if (r.id !in known) {
            val pulled = LocalSubject(r.id, r.relation.name, r.nickname, r.profile.orEmpty(), r.profileUpdatedAt ?: r.createdAt)
            platform.upsertSubjectJson(LocalSubjects.encode(pulled))
            entries[r.id] = BookEntry(r.id, pulled.updatedAt)
        }
        book = book.copy(entries = entries)
        writeBook(book)
        return ok
    }

    private suspend fun push(local: LocalSubject, entry: BookEntry?): BookEntry {
        var remoteId = entry?.remoteId
        val input = SubjectInput(
            relation = runCatching { SubjectRelation.valueOf(local.relation) }.getOrDefault(SubjectRelation.OTHER),
            nickname = local.nickname.trim().take(32).ifEmpty { if (local.relation == SELF) "我" else "家人" },
            birthYear = local.fields[BIRTH_YEAR]?.toIntOrNull(), sex = sexCode(local.fields["性别"]),
            id = local.id.takeIf { remoteId == null },
        )
        val info = remoteId?.let { id -> authorized { api.updateSubject(it, id, input) } } ?: authorized { api.createSubject(it, input) }
        if (info !is ApiResult.Ok) return BookEntry(remoteId, entry?.syncedUpdatedAt, describe(info))
        remoteId = info.value.id
        // 从未编辑过的空白「本人」没有资料可传
        if (local.updatedAt <= 0L) return BookEntry(remoteId, local.updatedAt)
        val profileId = remoteId
        return when (val p = authorized { api.putProfile(it, profileId, SubjectProfileInput(local.fields, local.updatedAt)) }) {
            is ApiResult.Ok -> BookEntry(remoteId, local.updatedAt)
            is ApiResult.Failed -> when (p.code) {
                ErrorCodes.GUARDIAN_CONSENT_REQUIRED -> BookEntry(remoteId, entry?.syncedUpdatedAt, GUARDIAN_PENDING)
                // 别的设备改得更新：以云端为准
                ErrorCodes.CONFLICT -> {
                    val latest = (authorized { api.subjects(it) } as? ApiResult.Ok)?.value?.firstOrNull { it.id == profileId }
                    if (latest == null) BookEntry(remoteId, entry?.syncedUpdatedAt, p.message) else {
                        val pulled = local.copy(fields = latest.profile.orEmpty(), updatedAt = latest.profileUpdatedAt ?: local.updatedAt)
                        platform.upsertSubjectJson(LocalSubjects.encode(pulled))
                        BookEntry(remoteId, pulled.updatedAt)
                    }
                }
                else -> BookEntry(remoteId, entry?.syncedUpdatedAt, p.message)
            }
            is ApiResult.Unreachable -> BookEntry(remoteId, entry?.syncedUpdatedAt, p.message)
        }
    }

    // endregion

    // region 记录发件箱

    private val outbox = object : RecordOutbox {
        override suspend fun entries(): List<OutboxEntry> = readOutbox().entries
        override suspend fun payload(entry: OutboxEntry): String? = platform.readRecord(entry.fileName)
        override suspend fun update(entry: OutboxEntry) {
            val file = readOutbox()
            writeOutbox(file.copy(entries = file.entries.map { if (it.recordId == entry.recordId) entry else it }))
        }
    }

    private val remote = object : RecordRemote {
        override suspend fun upload(recordId: String, upload: RecordUpload): UploadResult =
            when (val result = authorized { api.uploadRecord(it, recordId, upload) }) {
                is ApiResult.Ok -> if (result.status == 201) UploadResult.Created else UploadResult.Duplicate
                is ApiResult.Unreachable -> UploadResult.Transient(result.message)
                is ApiResult.Failed -> when {
                    result.status == 401 -> UploadResult.Unauthorized
                    result.code == ErrorCodes.GUARDIAN_CONSENT_REQUIRED -> UploadResult.GuardianConsentRequired
                    result.code == ErrorCodes.CONSENT_REQUIRED -> {
                        session = session?.let { it.copy(consents = it.consents + (ConsentType.HEALTH_DATA_SYNC to false)) }
                        UploadResult.ConsentRequired
                    }
                    result.status == 409 -> UploadResult.Conflict
                    result.status == 429 || result.status >= 500 -> UploadResult.Transient(result.message)
                    else -> UploadResult.Rejected(result.message)
                }
            }
    }

    /** 记录所属成员的云端编号；家庭成员尚未同步到云端时留待下次，不能错归到「本人」。 */
    private fun remoteSubjectFor(payload: String): String? {
        val local = RecordSummaries.localSubjectId(payload) ?: return null
        val remoteId = readBook().entries[local]?.remoteId
        if (remoteId == null && RecordSummaries.subjectRelation(payload) != SELF) throw SubjectNotSyncedException()
        return remoteId
    }

    /** 首次同步时把已在本机、尚未登记的记录补登为待上传。 */
    private fun backfillOutbox() {
        val file = readOutbox()
        val known = file.entries.mapTo(HashSet()) { it.fileName }
        val missing = platform.recordFileNames().filter { RECORD_NAME.matches(it) && it !in known }
        if (missing.isNotEmpty()) writeOutbox(file.copy(entries = file.entries + missing.map { OutboxEntry(Uuid.random().toString(), it, testedAt(it)) }))
    }

    /** 换账号（含注销后重新注册）：记录与成员全部重新待传。 */
    private fun adoptAccount(accountId: String) {
        val file = readOutbox()
        if (file.accountId != accountId) writeOutbox(OutboxFile(accountId, file.entries.map { OutboxEntry(Uuid.random().toString(), it.fileName, it.testedAt) }))
        if (readBook().accountId != accountId) writeBook(SubjectBook(accountId))
    }

    // endregion

    private fun readOutbox(): OutboxFile = platform.readValue(KEY_OUTBOX)
        ?.let { runCatching { json.decodeFromString(OutboxFile.serializer(), it) }.getOrNull() } ?: OutboxFile()
    private fun writeOutbox(file: OutboxFile) = platform.writeValue(KEY_OUTBOX, json.encodeToString(OutboxFile.serializer(), file))
    private fun readBook(): SubjectBook = platform.readValue(KEY_BOOK)
        ?.let { runCatching { json.decodeFromString(SubjectBook.serializer(), it) }.getOrNull() } ?: SubjectBook()
    private fun writeBook(book: SubjectBook) = platform.writeValue(KEY_BOOK, json.encodeToString(SubjectBook.serializer(), book))

    private fun testedAt(name: String) = name.substringAfterLast('_').removeSuffix(".json").toLongOrNull() ?: platform.nowMs()

    private fun describe(result: ApiResult<*>): String = when (result) {
        is ApiResult.Failed -> result.message
        is ApiResult.Unreachable -> "无法连接云端服务，请检查网络和服务器地址"
        is ApiResult.Ok -> "未知错误"
    }

    private fun describe(report: SyncReport): String = when (report.stopReason) {
        SyncStopReason.TRANSIENT -> "网络不可用，稍后再试"
        SyncStopReason.UNAUTHORIZED -> "登录已失效，请重新登录"
        SyncStopReason.CONSENT_REQUIRED -> "未同意云端存储，已停止上传"
        null -> buildList {
            add("本次上传 ${report.uploaded} 条")
            if (report.awaitingGuardian > 0) add("${report.awaitingGuardian} 条儿童记录待监护人同意")
            if (report.conflicts + report.rejected > 0) add("${report.conflicts + report.rejected} 条未能上传")
        }.joinToString("，")
    }

    companion object {
        private const val KEY_SESSION = "cloud.session"
        private const val KEY_SERVER = "cloud.serverUrl"
        private const val KEY_OUTBOX = "cloud.outbox"
        private const val KEY_BOOK = "cloud.subjectBook"
        private const val EXPIRY_MARGIN_MS = 30_000L
        private const val SELF = "SELF"
        private const val BIRTH_YEAR = "出生年份"
        const val GUARDIAN_PENDING = "待监护人同意"
        private val SIGNED_OUT = ApiResult.Failed(401, ErrorCodes.INVALID_TOKEN, "请先登录")
        val RECORD_NAME = Regex("^(dryeye|presbyopia|strabismus)_\\d+\\.json$")
        fun sexCode(value: String?) = when (value) { "男" -> "MALE"; "女" -> "FEMALE"; "不愿透露" -> "OTHER"; else -> null }
    }
}

@Serializable
internal data class CloudSession(
    val accountId: String,
    val email: String,
    val defaultSubjectId: String,
    val accessToken: String,
    val accessExpiresAt: Long,
    val refreshToken: String,
    val refreshExpiresAt: Long,
    val consents: Map<ConsentType, Boolean> = emptyMap(),
)

@Serializable
internal data class OutboxFile(val accountId: String? = null, val entries: List<OutboxEntry> = emptyList())

/** [syncedUpdatedAt]：最后一次成功同步时本机资料的修改时间；与当前不同即为待传。 */
@Serializable
internal data class BookEntry(val remoteId: String? = null, val syncedUpdatedAt: Long? = null, val lastError: String? = null)

@Serializable
internal data class SubjectBook(val accountId: String? = null, val entries: Map<String, BookEntry> = emptyMap())

/** 平台成员资料的最小视图（与 SubjectProfiles.toJson 同格式；updatedAt 为毫秒，可能以小数表示）。 */
internal data class LocalSubject(val id: String, val relation: String, val nickname: String, val fields: Map<String, String>, val updatedAt: Long)

internal object LocalSubjects {
    fun parse(text: String): List<LocalSubject> = runCatching {
        (SyncContract.json.parseToJsonElement(text) as JsonArray).mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            fun str(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            LocalSubject(
                id = str("id") ?: return@mapNotNull null,
                relation = str("relation") ?: "OTHER",
                nickname = str("nickname").orEmpty(),
                fields = (o["fields"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                    (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it }
                }.toMap(),
                updatedAt = (o["updatedAt"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: 0L,
            )
        }
    }.getOrDefault(emptyList())

    fun encode(s: LocalSubject): String = buildJsonObject {
        put("id", s.id); put("relation", s.relation); put("nickname", s.nickname)
        put("fields", JsonObject(s.fields.mapValues { JsonPrimitive(it.value) }))
        put("updatedAt", s.updatedAt.toDouble())
    }.toString()
}
