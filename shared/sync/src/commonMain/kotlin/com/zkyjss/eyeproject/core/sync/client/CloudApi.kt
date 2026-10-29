package com.zkyjss.eyeproject.core.sync.client

import com.zkyjss.eyeproject.core.sync.AccountDto
import com.zkyjss.eyeproject.core.sync.ConsentDto
import com.zkyjss.eyeproject.core.sync.ConsentInput
import com.zkyjss.eyeproject.core.sync.ErrorBody
import com.zkyjss.eyeproject.core.sync.AdviceDto
import com.zkyjss.eyeproject.core.sync.AdviceRequest
import com.zkyjss.eyeproject.core.sync.ChatReplyDto
import com.zkyjss.eyeproject.core.sync.ChatRequest
import com.zkyjss.eyeproject.core.sync.ExplanationDto
import com.zkyjss.eyeproject.core.sync.ExplanationRequest
import com.zkyjss.eyeproject.core.sync.LoginRequest
import com.zkyjss.eyeproject.core.sync.RecordSummaryDto
import com.zkyjss.eyeproject.core.sync.RecordUpload
import com.zkyjss.eyeproject.core.sync.RefreshRequest
import com.zkyjss.eyeproject.core.sync.RegisterRequest
import com.zkyjss.eyeproject.core.sync.SubjectDto
import com.zkyjss.eyeproject.core.sync.SubjectInput
import com.zkyjss.eyeproject.core.sync.SubjectProfileInput
import com.zkyjss.eyeproject.core.sync.SyncContract
import com.zkyjss.eyeproject.core.sync.TokenResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlin.coroutines.resume

/**
 * 平台网络层（苹果由 Swift 的 URLSession 实现）：只做一次 HTTP 往返，回调在任意线程均可。
 * 请求体与令牌不得写日志。
 */
interface HttpTransport {
    fun send(method: String, url: String, headers: Map<String, String>, body: String?, callback: HttpCallback)
}

interface HttpCallback {
    /** 收到响应（任何状态码）。 */
    fun onResponse(status: Int, body: String)
    /** 网络不可达、超时、地址无效等。 */
    fun onFailure(message: String)
}

/** 一次调用的结果：成功、服务端拒绝（带稳定错误代码），或网络不可达。 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T, val status: Int) : ApiResult<T>
    data class Failed(val status: Int, val code: String, val message: String) : ApiResult<Nothing>
    data class Unreachable(val message: String) : ApiResult<Nothing>
}

/** 与安卓 core:network 同一份接口契约（shared/sync DTO + backend/server 路由）。 */
class CloudApi(private val baseUrl: () -> String, private val transport: HttpTransport) {
    private val json = SyncContract.json
    val configured: Boolean get() = baseUrl().isNotBlank()

    suspend fun register(request: RegisterRequest) = call("POST", "/auth/register", null, encode(RegisterRequest.serializer(), request), TokenResponse.serializer())
    suspend fun login(request: LoginRequest) = call("POST", "/auth/login", null, encode(LoginRequest.serializer(), request), TokenResponse.serializer())
    suspend fun refresh(refreshToken: String) = call("POST", "/auth/refresh", null, encode(RefreshRequest.serializer(), RefreshRequest(refreshToken)), TokenResponse.serializer())
    suspend fun logout(token: String, refreshToken: String) = call("POST", "/auth/logout", token, encode(RefreshRequest.serializer(), RefreshRequest(refreshToken)), null as KSerializer<Unit>?)
    suspend fun account(token: String) = call("GET", "/me", token, null, AccountDto.serializer())
    suspend fun consents(token: String) = call("GET", "/me/consents", token, null, ListSerializer(ConsentDto.serializer()))
    suspend fun recordConsent(token: String, input: ConsentInput) =
        call("POST", "/me/consents", token, encode(ConsentInput.serializer(), input), ListSerializer(ConsentDto.serializer()))
    suspend fun uploadRecord(token: String, recordId: String, upload: RecordUpload) =
        call("PUT", "/records/$recordId", token, encode(RecordUpload.serializer(), upload), RecordSummaryDto.serializer())
    suspend fun subjects(token: String) = call("GET", "/subjects", token, null, ListSerializer(SubjectDto.serializer()))
    suspend fun createSubject(token: String, input: SubjectInput) =
        call("POST", "/subjects", token, encode(SubjectInput.serializer(), input), SubjectDto.serializer())
    suspend fun updateSubject(token: String, id: String, input: SubjectInput) =
        call("PUT", "/subjects/$id", token, encode(SubjectInput.serializer(), input), SubjectDto.serializer())
    suspend fun deleteSubject(token: String, id: String) = call<Unit>("DELETE", "/subjects/$id", token, null, null)
    suspend fun putProfile(token: String, id: String, input: SubjectProfileInput) =
        call("PUT", "/subjects/$id/profile", token, encode(SubjectProfileInput.serializer(), input), SubjectDto.serializer())
    /** 服务端导出的原始 JSON 文本。 */
    suspend fun exportAll(token: String): ApiResult<String> = raw("GET", "/me/export", token, null)
    suspend fun deleteAccount(token: String) = call<Unit>("DELETE", "/me", token, null, null)
    /** AI 解读（需「AI 解读」同意）。 */
    suspend fun explain(token: String, request: ExplanationRequest) =
        call("POST", "/explanations", token, encode(ExplanationRequest.serializer(), request), ExplanationDto.serializer())
    /** AI 综合建议（需「AI 综合建议」同意）。 */
    suspend fun advise(token: String, request: AdviceRequest) =
        call("POST", "/advice", token, encode(AdviceRequest.serializer(), request), AdviceDto.serializer())
    /** AI 对话（需「AI 对话」同意）。 */
    suspend fun chat(token: String, request: ChatRequest) =
        call("POST", "/chat", token, encode(ChatRequest.serializer(), request), ChatReplyDto.serializer())

    private fun <T> encode(serializer: KSerializer<T>, value: T) = json.encodeToString(serializer, value)

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> call(method: String, path: String, token: String?, body: String?, parser: KSerializer<T>?): ApiResult<T> =
        when (val result = raw(method, path, token, body)) {
            is ApiResult.Ok -> runCatching {
                ApiResult.Ok(if (parser == null) Unit as T else json.decodeFromString(parser, result.value), result.status)
            }.getOrElse { ApiResult.Failed(result.status, "invalid_response", "服务端返回格式无法识别") }
            is ApiResult.Failed -> result
            is ApiResult.Unreachable -> result
        }

    private suspend fun raw(method: String, path: String, token: String?, body: String?): ApiResult<String> {
        if (!configured) return ApiResult.Unreachable("未配置云端服务")
        val headers = buildMap {
            put("Accept", "application/json")
            if (body != null) put("Content-Type", "application/json; charset=utf-8")
            if (token != null) put("Authorization", "Bearer $token")
        }
        val url = baseUrl().trimEnd('/') + SyncContract.API_PREFIX + path
        return suspendCancellableCoroutine { continuation ->
            var done = false
            fun finish(result: ApiResult<String>) { if (!done) { done = true; continuation.resume(result) } }
            transport.send(method, url, headers, body, object : HttpCallback {
                override fun onResponse(status: Int, body: String) = finish(
                    if (status in 200..299) ApiResult.Ok(body, status) else {
                        val error = runCatching { json.decodeFromString(ErrorBody.serializer(), body) }.getOrNull()
                        ApiResult.Failed(status, error?.error ?: "http_$status", error?.message ?: "服务暂时不可用（$status）")
                    })
                override fun onFailure(message: String) = finish(ApiResult.Unreachable(message.ifBlank { "网络不可用" }))
            })
        }
    }
}
