package com.zkyjss.eyeproject.server

import com.zkyjss.eyeproject.core.sync.AccountDto
import com.zkyjss.eyeproject.core.sync.ConsentDto
import com.zkyjss.eyeproject.core.sync.ConsentInput
import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.sync.ErrorCodes
import com.zkyjss.eyeproject.core.sync.RecordSummaries
import com.zkyjss.eyeproject.core.sync.ExportBundle
import com.zkyjss.eyeproject.core.sync.SubjectDto
import com.zkyjss.eyeproject.core.sync.SubjectInput
import com.zkyjss.eyeproject.core.sync.SubjectProfileInput
import com.zkyjss.eyeproject.core.sync.SyncContract
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import com.zkyjss.eyeproject.core.sync.SubjectRelation
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** 账号资料、家庭成员及其健康资料、同意、导出与注销。所有查询都带 account_id 过滤。 */
class ProfileService(
    private val db: Database,
    private val config: ServerConfig,
    private val audit: AuditLog,
    private val records: RecordService,
    /** 健康资料与测试记录同一套 AES-256-GCM 密钥。 */
    private val cipher: PayloadCipher,
    private val clock: () -> Long,
) {
    private val fieldsSerializer = MapSerializer(String.serializer(), String.serializer())

    suspend fun account(accountId: String): AccountDto = db.transaction { accountDto(accountId) }

    suspend fun subjects(accountId: String): List<SubjectDto> = db.transaction { subjectList(accountId) }

    suspend fun createSubject(accountId: String, input: SubjectInput, ip: String?): SubjectDto = db.transaction {
        validate(input)
        if (input.relation == SubjectRelation.SELF) throw ApiException.badRequest("每个账号只有一个「本人」")
        val count = single("SELECT COUNT(*) FROM subjects WHERE account_id = ?", accountId) { it.getInt(1) } ?: 0
        if (count >= config.maxSubjectsPerAccount) throw ApiException.badRequest("家庭成员已达上限")
        // 客户端离线新建时给出的编号：合法 UUID 且未被占用才采用，否则由服务端生成
        val proposed = input.id?.takeIf { runCatching { UUID.fromString(it) }.isSuccess && it.length == 36 }
        if (proposed != null && single("SELECT 1 FROM subjects WHERE id = ?", proposed) { it.getInt(1) } != null)
            throw ApiException(HttpStatusCode.Conflict, ErrorCodes.CONFLICT, "成员编号已存在")
        val id = proposed ?: UUID.randomUUID().toString()
        update("""INSERT INTO subjects (id, account_id, relation, nickname, birth_year, birth_month, sex, is_default, created_at)
                  VALUES (?, ?, ?, ?, ?, ?, ?, FALSE, ?)""",
            id, accountId, input.relation.name, input.nickname.trim(), input.birthYear, input.birthMonth, input.sex, clock())
        audit.write(this, AuditAction.SUBJECT_CREATE, accountId, "subject", id, ip = ip)
        subject(accountId, id)
    }

    suspend fun updateSubject(accountId: String, subjectId: String, input: SubjectInput, ip: String?): SubjectDto = db.transaction {
        validate(input)
        val current = subject(accountId, subjectId)
        if ((input.relation == SubjectRelation.SELF) != (current.relation == SubjectRelation.SELF))
            throw ApiException.badRequest("「本人」关系不能修改")
        update("UPDATE subjects SET relation = ?, nickname = ?, birth_year = ?, birth_month = ?, sex = ? WHERE id = ? AND account_id = ?",
            input.relation.name, input.nickname.trim(), input.birthYear, input.birthMonth, input.sex, subjectId, accountId)
        audit.write(this, AuditAction.SUBJECT_UPDATE, accountId, "subject", subjectId, ip = ip)
        subject(accountId, subjectId)
    }

    /**
     * 保存被测者健康资料（原测试问卷），加密存储。需「健康数据云端存储」同意；儿童（关系为孩子或未满 14 周岁）
     * 还需该成员的监护人同意。客户端版本早于云端已有版本时返回 409，避免旧手机覆盖新资料。
     */
    suspend fun updateHealthProfile(accountId: String, subjectId: String, input: SubjectProfileInput, ip: String?): SubjectDto =
        db.transaction {
            val fields = input.fields
            if (fields.size > SyncContract.MAX_PROFILE_FIELDS ||
                fields.any { (k, v) -> k.isBlank() || k.length > SyncContract.MAX_PROFILE_KEY_LENGTH || v.length > SyncContract.MAX_PROFILE_VALUE_LENGTH })
                throw ApiException.badRequest("资料项过多或内容过长")
            if (input.updatedAt <= 0 || input.updatedAt > clock() + 24 * 3_600_000L) throw ApiException.badRequest("资料修改时间无效")
            val subject = subject(accountId, subjectId)
            if (!consentGranted(accountId, ConsentType.HEALTH_DATA_SYNC))
                throw ApiException.consentRequired("上传健康资料前需要同意健康数据云端存储")
            val now = clock()
            val birthYear = subject.birthYear ?: fields[BIRTH_YEAR_KEY]?.toIntOrNull()
            val minor = subject.relation == SubjectRelation.CHILD ||
                RecordSummaries.isMinor(birthYear?.let { RecordService.ageAt(it, subject.birthMonth, now) })
            if (minor && !consentGranted(accountId, ConsentType.GUARDIAN, subjectId))
                throw ApiException(HttpStatusCode.Forbidden, ErrorCodes.GUARDIAN_CONSENT_REQUIRED, "儿童的健康资料需要监护人同意后上传")
            val stored = single("SELECT profile_updated_at FROM subjects WHERE id = ?", subjectId) { it.longOrNull("profile_updated_at") }
            if (stored != null && input.updatedAt < stored)
                throw ApiException(HttpStatusCode.Conflict, ErrorCodes.CONFLICT, "云端资料更新，请先同步")
            val plain = SyncContract.json.encodeToString(fieldsSerializer, fields)
            update("UPDATE subjects SET profile_key_id = ?, profile_cipher = ?, profile_updated_at = ? WHERE id = ? AND account_id = ?",
                cipher.activeKeyId, cipher.encrypt(plain, profileAad(subjectId, accountId)), input.updatedAt, subjectId, accountId)
            audit.write(this, AuditAction.SUBJECT_PROFILE_UPDATE, accountId, "subject", subjectId, ip = ip)
            subject(accountId, subjectId)
        }

    /** 删除家庭成员连同其全部记录（外键级联）。 */
    suspend fun deleteSubject(accountId: String, subjectId: String, ip: String?) = db.transaction {
        if (subject(accountId, subjectId).isDefault) throw ApiException.badRequest("不能删除「本人」；如需删除请注销账号")
        update("DELETE FROM subjects WHERE id = ? AND account_id = ?", subjectId, accountId)
        audit.write(this, AuditAction.SUBJECT_DELETE, accountId, "subject", subjectId, ip = ip)
    }

    suspend fun consents(accountId: String): List<ConsentDto> = db.transaction { currentConsents(accountId) }

    suspend fun recordConsent(accountId: String, input: ConsentInput, ip: String?): List<ConsentDto> = db.transaction {
        if (input.policyVersion.isBlank() || input.policyVersion.length > 32) throw ApiException.badRequest("同意版本无效")
        if (input.type == ConsentType.GUARDIAN && input.subjectId == null) throw ApiException.badRequest("监护人同意需指定被测者")
        if (input.type == ConsentType.PRIVACY_POLICY && !input.granted)
            throw ApiException.badRequest("撤回隐私政策同意请直接注销账号")
        input.subjectId?.let { subject(accountId, it) }
        update("INSERT INTO consents (account_id, subject_id, type, policy_version, granted, recorded_at) VALUES (?, ?, ?, ?, ?, ?)",
            accountId, input.subjectId, input.type.name, input.policyVersion, input.granted, clock())
        audit.write(this, AuditAction.CONSENT_CHANGE, accountId, "consent", input.type.name,
            if (input.granted) "GRANTED" else "WITHDRAWN", ip)
        currentConsents(accountId)
    }

    /** 查阅复制权：账号、成员、同意与全部记录原文（解密后）一次导出。 */
    suspend fun export(accountId: String, ip: String?): ExportBundle {
        val bundle = db.transaction {
            ExportBundle(clock(), accountDto(accountId), subjectList(accountId), currentConsents(accountId), emptyList())
        }
        val details = records.allDetails(accountId)
        db.transaction { audit.write(this, AuditAction.EXPORT, accountId, "account", accountId, ip = ip) }
        return bundle.copy(records = details)
    }

    /** 注销：立即硬删除账号及其全部数据（级联），仅留不含内容的审计。 */
    suspend fun deleteAccount(accountId: String, ip: String?) = db.transaction {
        update("DELETE FROM accounts WHERE id = ?", accountId)
        audit.write(this, AuditAction.ACCOUNT_DELETE, accountId, "account", accountId, ip = ip)
    }

    private fun validate(input: SubjectInput) {
        val name = input.nickname.trim()
        if (name.isEmpty() || name.length > 32) throw ApiException.badRequest("称呼需为 1–32 个字")
        val year = Instant.ofEpochMilli(clock()).atZone(ZoneOffset.UTC).year
        if (input.birthYear != null && input.birthYear !in 1900..year) throw ApiException.badRequest("出生年份无效")
        if (input.birthMonth != null && input.birthMonth !in 1..12) throw ApiException.badRequest("出生月份无效")
        if (input.sex != null && input.sex !in setOf("MALE", "FEMALE", "OTHER")) throw ApiException.badRequest("性别取值无效")
    }

    private fun Connection.accountDto(accountId: String): AccountDto =
        single("""SELECT a.id, a.email, a.display_name, a.created_at, s.id AS subject_id FROM accounts a
                  JOIN subjects s ON s.account_id = a.id AND s.is_default WHERE a.id = ?""", accountId) {
            AccountDto(it.getString("id"), it.getString("email"), it.getString("display_name"), it.getLong("created_at"), it.getString("subject_id"))
        } ?: throw ApiException.unauthorized()

    private fun Connection.subjectList(accountId: String) =
        query("SELECT * FROM subjects WHERE account_id = ? ORDER BY is_default DESC, created_at", accountId, map = ::profiledRow)

    private fun Connection.subject(accountId: String, subjectId: String) =
        single("SELECT * FROM subjects WHERE id = ? AND account_id = ?", subjectId, accountId, map = ::profiledRow)
            ?: throw ApiException.notFound("被测者")

    /** 成员行 + 解密后的健康资料。 */
    private fun profiledRow(row: ResultSet): SubjectDto {
        val base = subjectRow(row)
        val sealed = row.getBytes("profile_cipher") ?: return base
        val plain = cipher.decrypt(row.getString("profile_key_id"), sealed, profileAad(base.id, row.getString("account_id")))
        return base.copy(profile = SyncContract.json.decodeFromString(fieldsSerializer, plain),
            profileUpdatedAt = row.longOrNull("profile_updated_at"))
    }

    private fun Connection.currentConsents(accountId: String): List<ConsentDto> =
        query("SELECT type, policy_version, granted, subject_id, recorded_at FROM consents WHERE account_id = ? ORDER BY id", accountId) {
            ConsentDto(ConsentType.valueOf(it.getString(1)), it.getString(2), it.getBoolean(3), it.getString(4), it.getLong(5))
        }.associateBy { it.type to it.subjectId }.values.toList()

    companion object {
        /** 资料里的出生年份键（与客户端 SubjectProfiles.BIRTH_YEAR 一致）。 */
        private const val BIRTH_YEAR_KEY = "出生年份"

        /** AAD 绑定被测者与账号：密文被挪到别的成员或账号时解密失败。 */
        fun profileAad(subjectId: String, accountId: String) = "subject:$subjectId|account:$accountId"

        fun subjectRow(row: ResultSet) = SubjectDto(
            id = row.getString("id"),
            relation = SubjectRelation.valueOf(row.getString("relation")),
            nickname = row.getString("nickname"),
            birthYear = row.intOrNull("birth_year"),
            birthMonth = row.intOrNull("birth_month"),
            sex = row.getString("sex"),
            isDefault = row.getBoolean("is_default"),
            createdAt = row.getLong("created_at"),
        )

        /** 该类型同意的最新一条是否为授予；subjectId 为 null 时只看账号级。 */
        fun Connection.consentGranted(accountId: String, type: ConsentType, subjectId: String? = null): Boolean =
            single(
                if (subjectId == null) "SELECT granted FROM consents WHERE account_id = ? AND type = ? AND subject_id IS NULL ORDER BY id DESC LIMIT 1"
                else "SELECT granted FROM consents WHERE account_id = ? AND type = ? AND subject_id = ? ORDER BY id DESC LIMIT 1",
                *listOfNotNull(accountId, type.name, subjectId).toTypedArray(),
            ) { it.getBoolean(1) } ?: false
    }
}
