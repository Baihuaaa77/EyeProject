package com.zkyjss.eyeproject.core.network

import com.zkyjss.eyeproject.core.sync.AdviceDto
import com.zkyjss.eyeproject.core.sync.AdviceRequest
import com.zkyjss.eyeproject.core.sync.AccountDto
import com.zkyjss.eyeproject.core.sync.ConsentDto
import com.zkyjss.eyeproject.core.sync.ConsentInput
import com.zkyjss.eyeproject.core.sync.ExplanationDto
import com.zkyjss.eyeproject.core.sync.ExplanationRequest
import com.zkyjss.eyeproject.core.sync.LoginRequest
import com.zkyjss.eyeproject.core.sync.RecordSummaryDto
import com.zkyjss.eyeproject.core.sync.RecordUpload
import com.zkyjss.eyeproject.core.sync.RegisterRequest
import com.zkyjss.eyeproject.core.sync.SubjectDto
import com.zkyjss.eyeproject.core.sync.SubjectInput
import com.zkyjss.eyeproject.core.sync.SubjectProfileInput
import com.zkyjss.eyeproject.core.sync.TokenResponse

/** 一次远端调用的结果：成功、服务端拒绝（带稳定错误代码），或网络不可达。 */
sealed interface NetworkResult<out T> {
    data class Success<T>(val value: T, val status: Int) : NetworkResult<T>
    data class HttpError(val status: Int, val code: String, val message: String) : NetworkResult<Nothing>
    data class Unreachable(val message: String) : NetworkResult<Nothing>
}

/**
 * 云端接口数据源（契约见 shared/sync 与 backend/server）。只负责一次 HTTP 往返；
 * 令牌保存、刷新与重试策略属于 core:data 的仓库。
 */
interface EyeNetworkDataSource {
    /** 构建时未配置服务器地址则为 false，此时上层不得发起任何请求。 */
    val isConfigured: Boolean

    suspend fun register(request: RegisterRequest): NetworkResult<TokenResponse>
    suspend fun login(request: LoginRequest): NetworkResult<TokenResponse>
    suspend fun refresh(refreshToken: String): NetworkResult<TokenResponse>
    suspend fun logout(accessToken: String, refreshToken: String): NetworkResult<Unit>
    suspend fun account(accessToken: String): NetworkResult<AccountDto>
    suspend fun consents(accessToken: String): NetworkResult<List<ConsentDto>>
    suspend fun recordConsent(accessToken: String, input: ConsentInput): NetworkResult<List<ConsentDto>>
    suspend fun uploadRecord(accessToken: String, recordId: String, upload: RecordUpload): NetworkResult<RecordSummaryDto>
    /** 家庭成员（被测者）与其健康资料。 */
    suspend fun subjects(accessToken: String): NetworkResult<List<SubjectDto>>
    suspend fun createSubject(accessToken: String, input: SubjectInput): NetworkResult<SubjectDto>
    suspend fun updateSubject(accessToken: String, subjectId: String, input: SubjectInput): NetworkResult<SubjectDto>
    suspend fun deleteSubject(accessToken: String, subjectId: String): NetworkResult<Unit>
    suspend fun putSubjectProfile(accessToken: String, subjectId: String, input: SubjectProfileInput): NetworkResult<SubjectDto>
    /** 返回服务端导出的原始 JSON 文本，由调用方写入用户选择的位置。 */
    suspend fun exportAll(accessToken: String): NetworkResult<String>
    suspend fun deleteAccount(accessToken: String): NetworkResult<Unit>
    /** AI 解读：服务端检索知识库后由大模型生成（需「AI 解读」同意）。 */
    suspend fun explain(accessToken: String, request: ExplanationRequest): NetworkResult<ExplanationDto>
    /** AI 综合建议：去标识的用眼概况由大模型生成建议（需「AI 综合建议」同意）。 */
    suspend fun advise(accessToken: String, request: AdviceRequest): NetworkResult<AdviceDto>
}
