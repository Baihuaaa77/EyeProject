package com.zkyjss.eyeproject.server

import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.sync.ErrorCodes
import com.zkyjss.eyeproject.core.sync.SyncContract
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Serializable data class AdminLoginRequest(val password: String)
@Serializable data class AdminLoginResponse(val token: String, val expiresAt: Long)

@Serializable data class AdminKindStats(val kind: String, val total: Int, val byRisk: Map<String, Int>)
@Serializable data class AdminDayCount(val date: String, val count: Int)

@Serializable
data class AdminOverview(
    /** 注册账号总数（不涉及健康数据，全部计入） */
    val accounts: Int,
    /** 同意「研究用途」的账号数：以下健康数据统计只含这些账号 */
    val researchAccounts: Int,
    val subjects: Int,
    val records: Int,
    val byKind: List<AdminKindStats>,
    val byPlatform: Map<String, Int>,
    /** 最近 30 天每天的测试次数（按测试时间，北京时间） */
    val daily: List<AdminDayCount>,
    val generatedAt: Long,
)

/** 去标识的一条记录：没有邮箱、昵称、记录编号，账号与被测者用不可逆的假名编号代替。 */
@Serializable
data class AdminRecordRow(
    val user: String,
    val subject: String,
    val relation: String,
    val ageBand: String?,
    val sex: String?,
    val kind: String,
    val testedAt: Long,
    val riskLevel: String,
    val quality: String,
    val assessmentVersion: String?,
    val platform: String?,
    val appVersion: String?,
    val features: Map<String, Double?>,
)

@Serializable data class AdminRecordPage(val total: Int, val offset: Int, val rows: List<AdminRecordRow>)

/** 记录筛选条件：时间为毫秒，[to] 不含。 */
data class AdminFilter(val kind: String? = null, val risk: String? = null, val from: Long? = null, val to: Long? = null)

/**
 * 管理网页（课题组内部看数据）：一个管理口令登录（`EYE_ADMIN_PASSWORD`，未设置则整个管理入口返回 404）。
 * 只给统计与去标识记录，且健康数据只含**最新一次「研究用途」同意为同意**的账号；不解密问卷与记录原文。
 * 每次登录、查看、导出写审计（actor 为 `admin`）。
 */
class AdminService(
    private val db: Database,
    private val config: ServerConfig,
    private val audit: AuditLog,
    private val clock: () -> Long,
) {
    val enabled: Boolean get() = config.adminPassword != null
    private val sessions = ConcurrentHashMap<String, Long>()
    private val random = SecureRandom()
    private val pseudonymKey = SecretKeySpec(config.auditSalt, "HmacSHA256")
    private val featureSerializer = MapSerializer(String.serializer(), Double.serializer().nullable)

    suspend fun login(password: String, ip: String?): AdminLoginResponse {
        val expected = config.adminPassword ?: throw ApiException.notFound("管理入口")
        val ok = MessageDigest.isEqual(sha256(password), sha256(expected))
        db.transaction { audit.write(this, if (ok) AuditAction.ADMIN_LOGIN else AuditAction.ADMIN_LOGIN_FAILED, ACTOR, ip = ip) }
        if (!ok) throw ApiException(HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS, "管理口令不正确")
        val now = clock()
        sessions.entries.removeIf { it.value <= now }
        val token = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val expiresAt = now + SESSION_MS
        sessions[hex(sha256(token))] = expiresAt
        return AdminLoginResponse(token, expiresAt)
    }

    fun logout(token: String) { sessions.remove(hex(sha256(token))) }

    /** 管理会话是否有效（只存令牌哈希，服务重启后需重新登录）。 */
    fun authenticate(token: String): Boolean {
        if (!enabled) return false
        val key = hex(sha256(token))
        val expiresAt = sessions[key] ?: return false
        if (expiresAt > clock()) return true
        sessions.remove(key)
        return false
    }

    suspend fun overview(ip: String?): AdminOverview = db.transaction {
        audit.write(this, AuditAction.ADMIN_VIEW, ACTOR, "overview", ip = ip)
        val accounts = single("SELECT COUNT(*) FROM accounts") { it.getInt(1) } ?: 0
        val researchAccounts = single("$RESEARCH SELECT COUNT(*) FROM research") { it.getInt(1) } ?: 0
        val subjects = single("$RESEARCH SELECT COUNT(*) FROM subjects s JOIN research r ON r.account_id = s.account_id") { it.getInt(1) } ?: 0
        data class Row(val kind: String, val risk: String, val testedAt: Long, val platform: String?)
        val rows = query("$RESEARCH SELECT rec.kind, rec.risk_level, rec.tested_at, rec.client_platform FROM records rec " +
            "JOIN research r ON r.account_id = rec.account_id") { Row(it.getString(1), it.getString(2), it.getLong(3), it.getString(4)) }
        val now = clock()
        val today = Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate()
        val days = (29 downTo 0).map { today.minusDays(it.toLong()) }
        val perDay = rows.groupingBy { Instant.ofEpochMilli(it.testedAt).atZone(ZONE).toLocalDate() }.eachCount()
        AdminOverview(
            accounts = accounts,
            researchAccounts = researchAccounts,
            subjects = subjects,
            records = rows.size,
            byKind = rows.groupBy { it.kind }.map { (kind, list) -> AdminKindStats(kind, list.size, list.groupingBy { it.risk }.eachCount()) }
                .sortedByDescending { it.total },
            byPlatform = rows.groupingBy { it.platform ?: "未知" }.eachCount(),
            daily = days.map { AdminDayCount(it.toString(), perDay[it] ?: 0) },
            generatedAt = now,
        )
    }

    suspend fun records(filter: AdminFilter, offset: Int, limit: Int, ip: String?): AdminRecordPage = db.transaction {
        audit.write(this, AuditAction.ADMIN_VIEW, ACTOR, "records", ip = ip)
        val (where, args) = whereClause(filter)
        val total = single("$RESEARCH SELECT COUNT(*) FROM records rec JOIN research r ON r.account_id = rec.account_id $where", *args) { it.getInt(1) } ?: 0
        val safeOffset = offset.coerceAtLeast(0)
        val rows = query("$RESEARCH $SELECT_ROWS $where ORDER BY rec.tested_at DESC, rec.id LIMIT ? OFFSET ?",
            *args, limit.coerceIn(1, MAX_PAGE), safeOffset, map = ::row)
        AdminRecordPage(total, safeOffset, rows)
    }

    /** 按筛选条件导出 CSV（带 BOM，Excel 直接打开不乱码）；指标按出现过的名称展开成列。 */
    suspend fun csv(filter: AdminFilter, ip: String?): String = db.transaction {
        audit.write(this, AuditAction.ADMIN_EXPORT, ACTOR, "records", ip = ip)
        val (where, args) = whereClause(filter)
        val rows = query("$RESEARCH $SELECT_ROWS $where ORDER BY rec.tested_at DESC, rec.id LIMIT ?", *args, MAX_EXPORT, map = ::row)
        val featureNames = rows.flatMap { it.features.keys }.distinct().sorted()
        val header = listOf("用户", "被测者", "关系", "年龄段", "性别", "测试", "测试时间", "风险等级", "数据质量", "评估版本", "平台", "App 版本") + featureNames
        buildString {
            append('﻿')
            appendLine(header.joinToString(",") { cell(it) })
            rows.forEach { r ->
                val time = Instant.ofEpochMilli(r.testedAt).atZone(ZONE).toLocalDateTime().toString()
                val cells = listOf(r.user, r.subject, r.relation, r.ageBand, r.sex, r.kind, time, r.riskLevel, r.quality,
                    r.assessmentVersion, r.platform, r.appVersion) + featureNames.map { r.features[it]?.toString() }
                appendLine(cells.joinToString(",") { cell(it) })
            }
        }
    }

    private fun whereClause(filter: AdminFilter): Pair<String, Array<Any>> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()
        filter.kind?.takeIf { it.isNotBlank() }?.let { conditions += "rec.kind = ?"; args += it }
        filter.risk?.takeIf { it.isNotBlank() }?.let { conditions += "rec.risk_level = ?"; args += it }
        filter.from?.let { conditions += "rec.tested_at >= ?"; args += it }
        filter.to?.let { conditions += "rec.tested_at < ?"; args += it }
        val where = if (conditions.isEmpty()) "" else conditions.joinToString(" AND ", prefix = "WHERE ")
        return where to args.toTypedArray()
    }

    private fun row(it: java.sql.ResultSet): AdminRecordRow {
        val testedAt = it.getLong("tested_at")
        return AdminRecordRow(
            user = "U-" + pseudonym(it.getString("account_id"), 8),
            subject = "S-" + pseudonym(it.getString("subject_id"), 6),
            relation = it.getString("relation"),
            ageBand = it.intOrNull("birth_year")?.let { year -> ageBand(Instant.ofEpochMilli(testedAt).atZone(ZONE).year - year) },
            sex = it.getString("sex"),
            kind = it.getString("kind"),
            testedAt = testedAt,
            riskLevel = it.getString("risk_level"),
            quality = it.getString("quality"),
            assessmentVersion = it.getString("assessment_version"),
            platform = it.getString("client_platform"),
            appVersion = it.getString("app_version"),
            features = SyncContract.json.decodeFromString(featureSerializer, it.getString("features_json")),
        )
    }

    /** 不可逆假名：同一账号 / 被测者始终同一个编号，便于看同一人的多次测试，但反推不出原编号。 */
    private fun pseudonym(id: String, length: Int): String =
        hex(Mac.getInstance("HmacSHA256").apply { init(pseudonymKey) }.doFinal("admin:$id".encodeToByteArray())).take(length)

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun cell(value: String?): String {
        val text = value.orEmpty()
        return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
    }

    companion object {
        const val ACTOR = "admin"
        const val SESSION_MS = 8 * 3_600_000L
        const val MAX_PAGE = 200
        const val MAX_EXPORT = 50_000
        /** 管理口令最短长度（生产模式下强制） */
        const val MIN_PASSWORD_LENGTH = 12
        private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /** 最新一次「研究用途」同意（账号级，不带成员）为同意的账号。撤回也是一条记录，所以取最新一条。 */
        private val RESEARCH = """
            WITH research AS (
                SELECT c.account_id FROM consents c
                WHERE c.type = '${ConsentType.RESEARCH_USE.name}' AND c.subject_id IS NULL AND c.granted = TRUE
                  AND c.id = (SELECT MAX(c2.id) FROM consents c2
                              WHERE c2.account_id = c.account_id AND c2.type = c.type AND c2.subject_id IS NULL)
            )
        """.trimIndent()

        private const val SELECT_ROWS = "SELECT rec.account_id, rec.subject_id, s.relation, s.birth_year, s.sex, rec.kind, rec.tested_at, " +
            "rec.risk_level, rec.quality, rec.assessment_version, rec.client_platform, rec.app_version, rec.features_json " +
            "FROM records rec JOIN research r ON r.account_id = rec.account_id JOIN subjects s ON s.id = rec.subject_id"

        fun ageBand(age: Int): String? = when {
            age < 0 || age > 130 -> null
            age < 14 -> "14 岁以下"
            age < 18 -> "14–17"
            age < 30 -> "18–29"
            age < 60 -> "${age / 10 * 10}–${age / 10 * 10 + 9}"
            else -> "60 岁及以上"
        }
    }
}
