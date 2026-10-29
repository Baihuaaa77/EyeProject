package com.zkyjss.eyeproject.core.network

import com.zkyjss.eyeproject.core.sync.AccountDto
import com.zkyjss.eyeproject.core.sync.AdviceDto
import com.zkyjss.eyeproject.core.sync.AdviceRequest
import com.zkyjss.eyeproject.core.sync.ChatReplyDto
import com.zkyjss.eyeproject.core.sync.ChatRequest
import com.zkyjss.eyeproject.core.sync.ConsentDto
import com.zkyjss.eyeproject.core.sync.ConsentInput
import com.zkyjss.eyeproject.core.sync.ErrorBody
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** 基于 HttpURLConnection 的实现：接口少、无需额外网络库。请求体与令牌不写日志。 */
@Singleton
class HttpEyeNetworkDataSource internal constructor(private val baseUrl: () -> String) : EyeNetworkDataSource {
    @Inject constructor(addresses: ServerAddressStore) : this({ addresses.address.value })
    internal constructor(baseUrl: String) : this({ baseUrl })

    private val json = SyncContract.json
    private val api: String get() = baseUrl().trimEnd('/') + SyncContract.API_PREFIX

    override val isConfigured: Boolean get() = baseUrl().isNotBlank()

    override suspend fun register(request: RegisterRequest) =
        call("POST", "/auth/register", null, encode(request), serializer<TokenResponse>())

    override suspend fun login(request: LoginRequest) =
        call("POST", "/auth/login", null, encode(request), serializer<TokenResponse>())

    override suspend fun refresh(refreshToken: String) =
        call("POST", "/auth/refresh", null, encode(RefreshRequest(refreshToken)), serializer<TokenResponse>())

    override suspend fun logout(accessToken: String, refreshToken: String): NetworkResult<Unit> =
        call("POST", "/auth/logout", accessToken, encode(RefreshRequest(refreshToken)), null)

    override suspend fun account(accessToken: String) = call("GET", "/me", accessToken, null, serializer<AccountDto>())

    override suspend fun consents(accessToken: String) =
        call("GET", "/me/consents", accessToken, null, ListSerializer(ConsentDto.serializer()))

    override suspend fun recordConsent(accessToken: String, input: ConsentInput) =
        call("POST", "/me/consents", accessToken, encode(input), ListSerializer(ConsentDto.serializer()))

    override suspend fun uploadRecord(accessToken: String, recordId: String, upload: RecordUpload) =
        call("PUT", "/records/$recordId", accessToken, encode(upload), serializer<RecordSummaryDto>())

    override suspend fun subjects(accessToken: String) =
        call("GET", "/subjects", accessToken, null, ListSerializer(SubjectDto.serializer()))

    override suspend fun createSubject(accessToken: String, input: SubjectInput) =
        call("POST", "/subjects", accessToken, encode(input), serializer<SubjectDto>())

    // HttpURLConnection 不支持 PATCH：服务端为成员更新同时提供 PUT
    override suspend fun updateSubject(accessToken: String, subjectId: String, input: SubjectInput) =
        call("PUT", "/subjects/$subjectId", accessToken, encode(input), serializer<SubjectDto>())

    override suspend fun deleteSubject(accessToken: String, subjectId: String): NetworkResult<Unit> =
        call("DELETE", "/subjects/$subjectId", accessToken, null, null)

    override suspend fun putSubjectProfile(accessToken: String, subjectId: String, input: SubjectProfileInput) =
        call("PUT", "/subjects/$subjectId/profile", accessToken, encode(input), serializer<SubjectDto>())

    override suspend fun exportAll(accessToken: String): NetworkResult<String> = raw("GET", "/me/export", accessToken, null)

    override suspend fun deleteAccount(accessToken: String): NetworkResult<Unit> = call("DELETE", "/me", accessToken, null, null)

    override suspend fun advise(accessToken: String, request: AdviceRequest) =
        call("POST", "/advice", accessToken, encode(request), serializer<AdviceDto>())

    override suspend fun chat(accessToken: String, request: ChatRequest) =
        call("POST", "/chat", accessToken, encode(request), serializer<ChatReplyDto>())

    override suspend fun explain(accessToken: String, request: ExplanationRequest) =
        call("POST", "/explanations", accessToken, encode(request), serializer<ExplanationDto>())

    private inline fun <reified T> encode(value: T): String = json.encodeToString(serializer<T>(), value)

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> call(method: String, path: String, token: String?, body: String?, parser: KSerializer<T>?): NetworkResult<T> =
        when (val result = raw(method, path, token, body)) {
            is NetworkResult.Success -> runCatching {
                NetworkResult.Success(if (parser == null) Unit as T else json.decodeFromString(parser, result.value), result.status)
            }.getOrElse { NetworkResult.HttpError(result.status, "invalid_response", "服务端返回格式无法识别") }
            is NetworkResult.HttpError -> result
            is NetworkResult.Unreachable -> result
        }

    private suspend fun raw(method: String, path: String, token: String?, body: String?): NetworkResult<String> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext NetworkResult.Unreachable("未配置云端服务")
        val connection = try {
            URL(api + path).openConnection() as HttpURLConnection
        } catch (error: IOException) {
            return@withContext NetworkResult.Unreachable(error.message ?: "地址无效")
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                val bytes = body.encodeToByteArray()
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status in 200..299) NetworkResult.Success(text, status) else {
                val error = runCatching { json.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
                NetworkResult.HttpError(status, error?.error ?: "http_$status", error?.message ?: "服务暂时不可用（$status）")
            }
        } catch (error: IOException) {
            NetworkResult.Unreachable(error.message ?: "网络不可用")
        } finally {
            connection.disconnect()
        }
    }
}
