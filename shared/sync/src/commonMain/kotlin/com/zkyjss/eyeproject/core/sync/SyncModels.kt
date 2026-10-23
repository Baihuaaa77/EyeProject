package com.zkyjss.eyeproject.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 客户端与服务端共用的 v1 传输契约。字段只增不改；改语义必须升级路径版本。
 * 时间统一为 UTC 毫秒；记录原文以字符串传输，保证哈希按字节校验。
 */
object SyncContract {
    const val API_PREFIX = "/v1"
    /** 当前隐私政策与健康数据处理说明的版本，随 App 文本更新。 */
    const val PRIVACY_POLICY_VERSION = "2026-10-15"
    /** 单条记录原文上限（字节）；斜视逐帧样本约 2 MB。 */
    const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024
    /** 被测者资料（原测试问卷）：最多 64 项，键 ≤ 32 字、值 ≤ 200 字。 */
    const val MAX_PROFILE_FIELDS = 64
    const val MAX_PROFILE_KEY_LENGTH = 32
    const val MAX_PROFILE_VALUE_LENGTH = 200
    const val MIN_PASSWORD_LENGTH = 8

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
}

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String? = null,
    val privacyPolicyVersion: String,
)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class TokenResponse(
    val accountId: String,
    val accessToken: String,
    val accessExpiresAt: Long,
    val refreshToken: String,
    val refreshExpiresAt: Long,
)

@Serializable
data class AccountDto(
    val id: String,
    val email: String,
    val displayName: String? = null,
    val createdAt: Long,
    val defaultSubjectId: String,
)

/** 被测者与账号持有人的关系；斜视多由家长为孩子测试。 */
@Serializable
enum class SubjectRelation { SELF, CHILD, PARENT, SPOUSE, OTHER }

@Serializable
data class SubjectInput(
    val relation: SubjectRelation,
    val nickname: String,
    /** 只收出生年月，不收完整生日（数据最小化）。 */
    val birthYear: Int? = null,
    val birthMonth: Int? = null,
    val sex: String? = null,
    /** 新建时可由客户端给出编号（UUID），离线新建的成员与云端编号一致；为空由服务端生成。 */
    val id: String? = null,
)

@Serializable
data class SubjectDto(
    val id: String,
    val relation: SubjectRelation,
    val nickname: String,
    val birthYear: Int? = null,
    val birthMonth: Int? = null,
    val sex: String? = null,
    val isDefault: Boolean,
    val createdAt: Long,
    /** 健康资料（原测试问卷：键 → 答案）；未上传时为空。服务端加密保存。 */
    val profile: Map<String, String>? = null,
    /** 资料在客户端最后修改的时间，用于判断哪边更新。 */
    val profileUpdatedAt: Long? = null,
)

/**
 * 被测者健康资料（原测试问卷）。上传需「健康数据云端存储」同意；儿童还需监护人同意。
 * [updatedAt] 早于云端已有版本时返回 409，客户端应先拉取云端版本。
 */
@Serializable
data class SubjectProfileInput(
    val fields: Map<String, String>,
    val updatedAt: Long,
)

/**
 * 分项同意。PRIVACY_POLICY 注册必需；HEALTH_DATA_SYNC 上传必需；
 * GUARDIAN 为未满 14 周岁的被测者上传时必需；RESEARCH_USE 决定能否进入去标识研究数据；
 * AI_EXPLANATION 允许把去标识的结果发给第三方大模型生成解读（见 [Explanations.CONSENT_TEXT]）；
 * AI_ADVICE 允许把去标识的用眼概况发给第三方大模型生成综合建议（见 [Advices.CONSENT_TEXT]），与解读分开同意。
 */
@Serializable
enum class ConsentType { PRIVACY_POLICY, HEALTH_DATA_SYNC, GUARDIAN, RESEARCH_USE, AI_EXPLANATION, AI_ADVICE }

@Serializable
data class ConsentInput(
    val type: ConsentType,
    val policyVersion: String,
    val granted: Boolean,
    val subjectId: String? = null,
)

@Serializable
data class ConsentDto(
    val type: ConsentType,
    val policyVersion: String,
    val granted: Boolean,
    val subjectId: String? = null,
    val recordedAt: Long,
)

@Serializable
data class RecordUpload(
    /** 为空时归入账号的默认被测者（本人）。 */
    val subjectId: String? = null,
    val testedAt: Long,
    val payload: String,
    val payloadSha256: String,
    val clientPlatform: String? = null,
    val appVersion: String? = null,
)

@Serializable
data class RecordSummaryDto(
    val id: String,
    val subjectId: String,
    val kind: String,
    val protocolVersion: Int? = null,
    val assessmentVersion: String? = null,
    val riskLevel: String,
    val quality: RecordQuality,
    val testedAt: Long,
    val uploadedAt: Long,
    val features: Map<String, Double?> = emptyMap(),
)

@Serializable
data class RecordDetailDto(val summary: RecordSummaryDto, val payload: String)

/** 按测试时间倒序分页；下一页把 nextBefore / nextBeforeId 原样作为 before / beforeId 传回。 */
@Serializable
data class RecordPage(val items: List<RecordSummaryDto>, val nextBefore: Long? = null, val nextBeforeId: String? = null)

@Serializable
data class HistoryPoint(
    val recordId: String,
    val testedAt: Long,
    val riskLevel: String,
    val quality: RecordQuality,
    val features: Map<String, Double?>,
)

@Serializable
data class HistoryDto(val subjectId: String, val kind: String, val points: List<HistoryPoint>)

@Serializable
data class FeatureDelta(val key: String, val previous: Double? = null, val current: Double? = null, val delta: Double? = null)

@Serializable
data class ComparisonDto(
    val recordId: String,
    val previousRecordId: String? = null,
    val previousTestedAt: Long? = null,
    val deltas: List<FeatureDelta> = emptyList(),
)

@Serializable
data class ExportBundle(
    val exportedAt: Long,
    val account: AccountDto,
    val subjects: List<SubjectDto>,
    val consents: List<ConsentDto>,
    val records: List<RecordDetailDto>,
)

/** 统一错误体；error 为稳定代码，message 为中文说明。 */
@Serializable
data class ErrorBody(val error: String, val message: String)

object ErrorCodes {
    const val INVALID_REQUEST = "invalid_request"
    const val INVALID_CREDENTIALS = "invalid_credentials"
    const val INVALID_TOKEN = "invalid_token"
    const val EMAIL_TAKEN = "email_taken"
    const val CONSENT_REQUIRED = "consent_required"
    const val GUARDIAN_CONSENT_REQUIRED = "guardian_consent_required"
    const val NOT_FOUND = "not_found"
    const val CONFLICT = "conflict"
    const val INVALID_RECORD = "invalid_record"
    const val PAYLOAD_TOO_LARGE = "payload_too_large"
    const val RATE_LIMITED = "rate_limited"
    const val HTTPS_REQUIRED = "https_required"
    /** 服务端未配置大模型或模型暂时不可用。 */
    const val AI_UNAVAILABLE = "ai_unavailable"
    /** 今日 AI 解读次数已用完。 */
    const val AI_QUOTA_EXCEEDED = "ai_quota_exceeded"
}
