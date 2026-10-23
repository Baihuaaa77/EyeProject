package com.zkyjss.eyeproject.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration

/** 一次大模型回复；token 数用于记录费用（不记内容）。 */
data class LlmReply(val content: String, val promptTokens: Int?, val completionTokens: Int?)

class LlmException(message: String) : RuntimeException(message)

/** 大模型端口；测试注入替身，不联网。 */
interface LlmClient {
    val model: String
    suspend fun complete(system: String, user: String): LlmReply
}

/**
 * OpenAI 兼容的 Chat Completions 客户端（默认 DeepSeek）：JSON 输出模式、关闭思考、低温度。
 * 请求与回复内容、密钥都不写日志。
 */
class DeepSeekClient(
    private val baseUrl: String,
    private val apiKey: String,
    override val model: String,
    private val timeoutMs: Long,
    private val disableThinking: Boolean,
) : LlmClient {
    /**
     * 域名常解析出多个地址，个别网络里其中一个连不通：JDK HttpClient 只连第一个地址，轮到坏地址就整次超时。
     * OkHttp 每个地址最多等 [CONNECT_SECONDS] 秒，连不上自动换下一个；整次调用仍以 [timeoutMs] 封顶。
     */
    private val http = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(CONNECT_SECONDS))
        .readTimeout(Duration.ofMillis(timeoutMs))
        .callTimeout(Duration.ofMillis(timeoutMs + CONNECT_SECONDS * 2_000))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(system: String, user: String): LlmReply {
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
            putJsonObject("response_format") { put("type", "json_object") }
            if (disableThinking) putJsonObject("thinking") { put("type", "disabled") }
            put("temperature", 0.3)
            put("max_tokens", 1024)
            put("stream", false)
        }
        val request = Request.Builder().url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val (status, text) = try {
            withContext(Dispatchers.IO) { http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() } }
        } catch (error: Exception) {
            throw LlmException("连接大模型失败：${error.javaClass.simpleName}")
        }
        if (status !in 200..299) {
            // 只取错误说明（如余额不足、密钥无效），不回显请求
            val reason = runCatching {
                (json.parseToJsonElement(text).jsonObject["error"] as? JsonObject)?.get("message")?.let { (it as JsonPrimitive).content }
            }.getOrNull()
            throw LlmException("大模型返回 $status${reason?.let { "：${it.take(120)}" }.orEmpty()}")
        }
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { throw LlmException("大模型回复无法解析") }
        val content = runCatching {
            (root["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"] as JsonPrimitive).content
        }.getOrElse { throw LlmException("大模型回复缺少内容") }
        val usage = root["usage"] as? JsonObject
        return LlmReply(content, (usage?.get("prompt_tokens") as? JsonPrimitive)?.intOrNull,
            (usage?.get("completion_tokens") as? JsonPrimitive)?.intOrNull)
    }

    private companion object { const val CONNECT_SECONDS = 4L }
}
