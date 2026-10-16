package com.zkyjss.eyeproject.server

import com.zkyjss.eyeproject.core.sync.ComparisonDto
import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.sync.ErrorCodes
import com.zkyjss.eyeproject.core.sync.HistoryDto
import com.zkyjss.eyeproject.core.sync.HistoryPoint
import com.zkyjss.eyeproject.core.sync.InvalidRecordException
import com.zkyjss.eyeproject.core.sync.RecordDetailDto
import com.zkyjss.eyeproject.core.sync.RecordPage
import com.zkyjss.eyeproject.core.sync.RecordQuality
import com.zkyjss.eyeproject.core.sync.RecordSummaries
import com.zkyjss.eyeproject.core.sync.RecordSummaryDto
import com.zkyjss.eyeproject.core.sync.RecordUpload
import com.zkyjss.eyeproject.core.sync.SubjectRelation
import com.zkyjss.eyeproject.core.sync.SyncContract
import com.zkyjss.eyeproject.server.ProfileService.Companion.consentGranted
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** 测试记录：幂等上传、摘要查询、趋势与对比。原文加密保存，只在本人查看与导出时解密。 */
class RecordService(
    private val db: Database,
    private val cipher: PayloadCipher,
    private val audit: AuditLog,
    private val clock: () -> Long,
) {
    private val featureSerializer = MapSerializer(String.serializer(), Double.serializer().nullable)

    /** 返回 (是否新建, 摘要)。同 id 同内容视为重复上传，同 id 不同内容为冲突。 */
    suspend fun upload(accountId: String, recordId: String, upload: RecordUpload, ip: String?): Pair<Boolean, RecordSummaryDto> {
        requireUuid(recordId)
        if (upload.payload.encodeToByteArray().size > SyncContract.MAX_PAYLOAD_BYTES)
            throw ApiException(HttpStatusCode.PayloadTooLarge, ErrorCodes.PAYLOAD_TOO_LARGE, "记录超过大小上限")
        val sha = sha256Hex(upload.payload)
        if (!sha.equals(upload.payloadSha256, ignoreCase = true)) throw ApiException.badRequest("记录校验值不匹配，请重新上传")
        val now = clock()
        if (upload.testedAt < MIN_TESTED_AT || upload.testedAt > now + 24 * 3_600_000L) throw ApiException.badRequest("测试时间无效")
        val summary = try {
            RecordSummaries.parse(upload.payload)
        } catch (error: InvalidRecordException) {
            throw ApiException(HttpStatusCode.UnprocessableEntity, ErrorCodes.INVALID_RECORD, error.message ?: "记录无效")
        }
        return db.transaction {
            val existing = single("SELECT account_id, payload_sha256 FROM records WHERE id = ?", recordId) { it.getString(1) to it.getString(2) }
            if (existing != null) {
                if (existing.first == accountId && existing.second == sha) return@transaction false to summary(accountId, recordId)
                throw ApiException(HttpStatusCode.Conflict, ErrorCodes.CONFLICT, "云端已有同编号的不同记录")
            }
            val subject = (upload.subjectId?.let { id ->
                single("SELECT * FROM subjects WHERE id = ? AND account_id = ?", id, accountId, map = ProfileService::subjectRow)
            } ?: if (upload.subjectId == null)
                single("SELECT * FROM subjects WHERE account_id = ? AND is_default", accountId, map = ProfileService::subjectRow)
            else null) ?: throw ApiException.notFound("被测者")
            if (!consentGranted(accountId, ConsentType.HEALTH_DATA_SYNC))
                throw ApiException.consentRequired("上传测试记录前需要同意健康数据云端存储")
            val minor = subject.relation == SubjectRelation.CHILD || RecordSummaries.isMinor(summary.ageYears) ||
                RecordSummaries.isMinor(subject.birthYear?.let { ageAt(it, subject.birthMonth, upload.testedAt) })
            if (minor && !consentGranted(accountId, ConsentType.GUARDIAN, subject.id))
                throw ApiException(HttpStatusCode.Forbidden, ErrorCodes.GUARDIAN_CONSENT_REQUIRED, "儿童的测试记录需要监护人同意后上传")
            update("""INSERT INTO records (id, account_id, subject_id, kind, protocol_version, assessment_version, risk_level, quality,
                      tested_at, uploaded_at, payload_sha256, features_json, key_id, payload_cipher, client_platform, app_version)
                      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                recordId, accountId, subject.id, summary.kind, summary.protocolVersion, summary.assessmentVersion?.take(64),
                summary.riskLevel, summary.quality.name, upload.testedAt, now, sha,
                SyncContract.json.encodeToString(featureSerializer, summary.features), cipher.activeKeyId,
                cipher.encrypt(upload.payload, PayloadCipher.aad(recordId, accountId)),
                upload.clientPlatform?.take(16), upload.appVersion?.take(32))
            audit.write(this, AuditAction.RECORD_UPLOAD, accountId, "record", recordId, ip = ip)
            true to summary(accountId, recordId)
        }
    }

    /** 游标为（测试时间, id），同一毫秒的多条记录在翻页边界也不会遗漏。 */
    suspend fun list(accountId: String, subjectId: String?, kind: String?, before: Long?, beforeId: String?, limit: Int): RecordPage =
        db.transaction {
            val size = limit.coerceIn(1, 200)
            val cursorAt = before ?: Long.MAX_VALUE
            val items = query("""SELECT * FROM records WHERE account_id = ?
                                 AND (CAST(? AS VARCHAR) IS NULL OR subject_id = ?) AND (CAST(? AS VARCHAR) IS NULL OR kind = ?)
                                 AND (tested_at < ? OR (tested_at = ? AND id < ?))
                                 ORDER BY tested_at DESC, id DESC LIMIT ?""",
                accountId, subjectId, subjectId, kind, kind, cursorAt, cursorAt, beforeId ?: "", size + 1, map = ::summaryRow)
            val last = items.getOrNull(size - 1)?.takeIf { items.size > size }
            RecordPage(items.take(size), last?.testedAt, last?.id)
        }

    suspend fun detail(accountId: String, recordId: String, ip: String?): RecordDetailDto = db.transaction {
        val detail = detailRow(accountId, recordId) ?: throw ApiException.notFound("记录")
        audit.write(this, AuditAction.RECORD_VIEW, accountId, "record", recordId, ip = ip)
        detail
    }

    suspend fun delete(accountId: String, recordId: String, ip: String?) = db.transaction {
        if (update("DELETE FROM records WHERE id = ? AND account_id = ?", recordId, accountId) == 0) throw ApiException.notFound("记录")
        audit.write(this, AuditAction.RECORD_DELETE, accountId, "record", recordId, ip = ip)
    }

    /** 同一被测者同病种的时间序列（升序，最多 200 条），用于趋势图。 */
    suspend fun history(accountId: String, subjectId: String, kind: String): HistoryDto = db.transaction {
        if (single("SELECT 1 FROM subjects WHERE id = ? AND account_id = ?", subjectId, accountId) { true } == null)
            throw ApiException.notFound("被测者")
        val points = query("""SELECT * FROM records WHERE account_id = ? AND subject_id = ? AND kind = ?
                              ORDER BY tested_at DESC LIMIT 200""", accountId, subjectId, kind, map = ::summaryRow)
            .reversed().map { HistoryPoint(it.id, it.testedAt, it.riskLevel, it.quality, it.features) }
        HistoryDto(subjectId, kind, points)
    }

    /** 与同一被测者同病种、测试时间更早且质量合格的上一条记录比较特征。 */
    suspend fun compare(accountId: String, recordId: String): ComparisonDto = db.transaction {
        val current = summary(accountId, recordId)
        val previous = single("""SELECT * FROM records WHERE account_id = ? AND subject_id = ? AND kind = ? AND quality = ?
                                 AND tested_at < ? ORDER BY tested_at DESC LIMIT 1""",
            accountId, current.subjectId, current.kind, RecordQuality.SUFFICIENT.name, current.testedAt, map = ::summaryRow)
        ComparisonDto(recordId, previous?.id, previous?.testedAt,
            previous?.let { RecordSummaries.compare(it.features, current.features) } ?: emptyList())
    }

    /** 导出用：逐条解密，避免一次把所有密文读入内存。 */
    suspend fun allDetails(accountId: String): List<RecordDetailDto> {
        val ids = db.transaction { query("SELECT id FROM records WHERE account_id = ? ORDER BY tested_at", accountId) { it.getString(1) } }
        return ids.mapNotNull { id -> db.transaction { detailRow(accountId, id) } }
    }

    private fun Connection.summary(accountId: String, recordId: String): RecordSummaryDto =
        single("SELECT * FROM records WHERE id = ? AND account_id = ?", recordId, accountId, map = ::summaryRow)
            ?: throw ApiException.notFound("记录")

    private fun Connection.detailRow(accountId: String, recordId: String): RecordDetailDto? =
        single("SELECT * FROM records WHERE id = ? AND account_id = ?", recordId, accountId) { row ->
            RecordDetailDto(summaryRow(row),
                cipher.decrypt(row.getString("key_id"), row.getBytes("payload_cipher"), PayloadCipher.aad(recordId, accountId)))
        }

    private fun summaryRow(row: ResultSet) = RecordSummaryDto(
        id = row.getString("id"),
        subjectId = row.getString("subject_id"),
        kind = row.getString("kind"),
        protocolVersion = row.intOrNull("protocol_version"),
        assessmentVersion = row.getString("assessment_version"),
        riskLevel = row.getString("risk_level"),
        quality = RecordQuality.valueOf(row.getString("quality")),
        testedAt = row.getLong("tested_at"),
        uploadedAt = row.getLong("uploaded_at"),
        features = SyncContract.json.decodeFromString(featureSerializer, row.getString("features_json")),
    )

    companion object {
        /** 2024-01-01 UTC；更早的时间戳视为设备时钟错误。 */
        private const val MIN_TESTED_AT = 1_704_067_200_000L

        fun requireUuid(id: String) {
            if (runCatching { UUID.fromString(id).toString() == id.lowercase() }.getOrDefault(false).not())
                throw ApiException.badRequest("记录编号必须是 UUID")
        }

        fun ageAt(birthYear: Int, birthMonth: Int?, atMs: Long): Int {
            val date = Instant.ofEpochMilli(atMs).atZone(ZoneOffset.UTC)
            val beforeBirthday = birthMonth != null && date.monthValue < birthMonth
            return date.year - birthYear - if (beforeBirthday) 1 else 0
        }
    }
}
