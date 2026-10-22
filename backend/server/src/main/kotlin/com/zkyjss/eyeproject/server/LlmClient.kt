package com.zkyjss.eyeproject.server

import kotlinx.coroutines.future.await
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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
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
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/chat/completions"))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), Charsets.UTF_8))
            .build()
        val response = try {
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8)).await()
        } catch (error: Exception) {
            throw LlmException("连接大模型失败：${error.javaClass.simpleName}")
        }
        if (response.statusCode() !in 200..299) {
            // 只取错误说明（如余额不足、密钥无效），不回显请求
            val reason = runCatching {
                (json.parseToJsonElement(response.body()).jsonObject["error"] as? JsonObject)?.get("message")?.let { (it as JsonPrimitive).content }
            }.getOrNull()
            throw LlmException("大模型返回 ${response.statusCode()}${reason?.let { "：${it.take(120)}" }.orEmpty()}")
        }
        val root = runCatching { json.parseToJsonElement(response.body()).jsonObject }.getOrElse { throw LlmException("大模型回复无法解析") }
        val content = runCatching {
            (root["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"] as JsonPrimitive).content
        }.getOrElse { throw LlmException("大模型回复缺少内容") }
        val usage = root["usage"] as? JsonObject
        return LlmReply(content, (usage?.get("prompt_tokens") as? JsonPrimitive)?.intOrNull,
            (usage?.get("completion_tokens") as? JsonPrimitive)?.intOrNull)
    }
}
