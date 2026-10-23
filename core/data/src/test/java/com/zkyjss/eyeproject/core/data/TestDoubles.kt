package com.zkyjss.eyeproject.core.data

import com.zkyjss.eyeproject.core.data.account.SecretBox
import com.zkyjss.eyeproject.core.data.sync.SyncScheduler
import com.zkyjss.eyeproject.core.network.EyeNetworkDataSource
import com.zkyjss.eyeproject.core.network.NetworkResult
import com.zkyjss.eyeproject.core.sync.AccountDto
import com.zkyjss.eyeproject.core.sync.ConsentDto
import com.zkyjss.eyeproject.core.sync.ConsentInput
import com.zkyjss.eyeproject.core.sync.ErrorCodes
import com.zkyjss.eyeproject.core.sync.ExplanationDto
import com.zkyjss.eyeproject.core.sync.ExplanationRequest
import com.zkyjss.eyeproject.core.sync.LoginRequest
import com.zkyjss.eyeproject.core.sync.RecordQuality
import com.zkyjss.eyeproject.core.sync.RecordSummaryDto
import com.zkyjss.eyeproject.core.sync.RecordUpload
import com.zkyjss.eyeproject.core.sync.RegisterRequest
import com.zkyjss.eyeproject.core.sync.SubjectDto
import com.zkyjss.eyeproject.core.sync.SubjectInput
import com.zkyjss.eyeproject.core.sync.SubjectProfileInput
import com.zkyjss.eyeproject.core.sync.SubjectRelation
import com.zkyjss.eyeproject.core.sync.TokenResponse
import java.util.UUID

/** 单测不用 Keystore：原样存取（仅用于验证仓库逻辑）。 */
internal object PlainBox : SecretBox {
    override fun seal(plain: ByteArray) = plain
    override fun open(sealed: ByteArray) = sealed
}

internal class FakeScheduler : SyncScheduler {
    var requests = 0
    override fun requestSync() { requests++ }
}

/**
 * 假云端：记录按 id 编排（默认上传成功，可指定某个令牌已失效）；成员与资料存在内存里，
 * 与服务端相同的规则：资料版本旧于云端返回 409，孩子资料可设为需要监护人同意。
 */
internal class FakeNetwork : EyeNetworkDataSource {
    override val isConfigured = true
    val uploads = mutableListOf<Pair<String, RecordUpload>>()
    var rejectToken: String? = null
    var uploadError: NetworkResult.HttpError? = null
    var refreshes = 0
    val subjects = linkedMapOf("remote-self" to SubjectDto("remote-self", SubjectRelation.SELF, "我", isDefault = true, createdAt = 1L))
    var guardianRequired = false
    var subjectCalls = 0
    val explanations = mutableListOf<ExplanationRequest>()
    var explainError: NetworkResult.HttpError? = null

    override suspend fun register(request: RegisterRequest) = error("未使用")
    override suspend fun login(request: LoginRequest) = error("未使用")
    override suspend fun refresh(refreshToken: String): NetworkResult<TokenResponse> {
        refreshes++
        return NetworkResult.Success(TokenResponse("acc", "access-$refreshes", Long.MAX_VALUE, "refresh-$refreshes", Long.MAX_VALUE), 200)
    }
    override suspend fun logout(accessToken: String, refreshToken: String) = NetworkResult.Success(Unit, 204)
    override suspend fun account(accessToken: String): NetworkResult<AccountDto> = error("未使用")
    override suspend fun consents(accessToken: String): NetworkResult<List<ConsentDto>> = error("未使用")
    override suspend fun recordConsent(accessToken: String, input: ConsentInput): NetworkResult<List<ConsentDto>> = error("未使用")
    override suspend fun uploadRecord(accessToken: String, recordId: String, upload: RecordUpload): NetworkResult<RecordSummaryDto> {
        if (accessToken == rejectToken) return NetworkResult.HttpError(401, ErrorCodes.INVALID_TOKEN, "过期")
        uploadError?.let { return it }
        uploads += recordId to upload
        return NetworkResult.Success(RecordSummaryDto(recordId, upload.subjectId ?: "remote-self", "DRY_EYE", riskLevel = "LOW",
            quality = RecordQuality.SUFFICIENT, testedAt = upload.testedAt, uploadedAt = 0), 201)
    }
    override suspend fun subjects(accessToken: String): NetworkResult<List<SubjectDto>> {
        subjectCalls++
        return NetworkResult.Success(subjects.values.toList(), 200)
    }
    override suspend fun createSubject(accessToken: String, input: SubjectInput): NetworkResult<SubjectDto> {
        val id = input.id ?: UUID.randomUUID().toString()
        val dto = SubjectDto(id, input.relation, input.nickname, input.birthYear, sex = input.sex, isDefault = false, createdAt = 2L)
        subjects[id] = dto
        return NetworkResult.Success(dto, 201)
    }
    override suspend fun updateSubject(accessToken: String, subjectId: String, input: SubjectInput): NetworkResult<SubjectDto> {
        val current = subjects[subjectId] ?: return NetworkResult.HttpError(404, ErrorCodes.NOT_FOUND, "无")
        return NetworkResult.Success(current.copy(nickname = input.nickname, birthYear = input.birthYear, sex = input.sex)
            .also { subjects[subjectId] = it }, 200)
    }
    override suspend fun deleteSubject(accessToken: String, subjectId: String): NetworkResult<Unit> {
        subjects.remove(subjectId)
        return NetworkResult.Success(Unit, 204)
    }
    override suspend fun putSubjectProfile(accessToken: String, subjectId: String, input: SubjectProfileInput): NetworkResult<SubjectDto> {
        val current = subjects[subjectId] ?: return NetworkResult.HttpError(404, ErrorCodes.NOT_FOUND, "无")
        if (guardianRequired && current.relation == SubjectRelation.CHILD)
            return NetworkResult.HttpError(403, ErrorCodes.GUARDIAN_CONSENT_REQUIRED, "需要监护人同意")
        if ((current.profileUpdatedAt ?: 0L) > input.updatedAt) return NetworkResult.HttpError(409, ErrorCodes.CONFLICT, "云端更新")
        return NetworkResult.Success(current.copy(profile = input.fields, profileUpdatedAt = input.updatedAt)
            .also { subjects[subjectId] = it }, 200)
    }
    override suspend fun exportAll(accessToken: String): NetworkResult<String> = error("未使用")
    override suspend fun deleteAccount(accessToken: String): NetworkResult<Unit> = error("未使用")
    val advices = mutableListOf<com.zkyjss.eyeproject.core.sync.AdviceRequest>()
    override suspend fun advise(accessToken: String, request: com.zkyjss.eyeproject.core.sync.AdviceRequest):
        NetworkResult<com.zkyjss.eyeproject.core.sync.AdviceDto> {
        advices += request
        return NetworkResult.Success(com.zkyjss.eyeproject.core.sync.AdviceDto("今天有点累", listOf("现在休息一下"), listOf("打开休息提醒"),
            "不适持续时就医", fatigueLevel = request.fatigueLevel, model = "fake", generatedAt = 1L), 200)
    }
    override suspend fun explain(accessToken: String, request: ExplanationRequest): NetworkResult<ExplanationDto> {
        explainError?.let { return it }
        explanations += request
        return NetworkResult.Success(ExplanationDto("一句话", listOf("说明"), listOf("建议"), "不适时就医",
            riskLevel = request.riskLevel, model = "fake", generatedAt = 1L), 200)
    }
}
