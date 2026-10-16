package com.zkyjss.eyeproject.core.network

import com.zkyjss.eyeproject.core.sync.AccountDto
import com.zkyjss.eyeproject.core.sync.ConsentDto
import com.zkyjss.eyeproject.core.sync.ConsentInput
import com.zkyjss.eyeproject.core.sync.LoginRequest
import com.zkyjss.eyeproject.core.sync.RecordSummaryDto
import com.zkyjss.eyeproject.core.sync.RecordUpload
import com.zkyjss.eyeproject.core.sync.RegisterRequest
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
    /** 返回服务端导出的原始 JSON 文本，由调用方写入用户选择的位置。 */
    suspend fun exportAll(accessToken: String): NetworkResult<String>
    suspend fun deleteAccount(accessToken: String): NetworkResult<Unit>
}
