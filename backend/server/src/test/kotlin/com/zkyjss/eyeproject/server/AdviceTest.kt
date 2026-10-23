package com.zkyjss.eyeproject.server

import com.zkyjss.eyeproject.core.sync.AdviceDto
import com.zkyjss.eyeproject.core.sync.AdviceRequest
import com.zkyjss.eyeproject.core.sync.AdviceTest
import com.zkyjss.eyeproject.core.sync.ConsentInput
import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.sync.ErrorBody
import com.zkyjss.eyeproject.core.sync.ErrorCodes
import com.zkyjss.eyeproject.core.sync.RegisterRequest
import com.zkyjss.eyeproject.core.sync.SyncContract
import com.zkyjss.eyeproject.core.sync.TokenResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AI 综合建议：单独同意、去标识概况进入提示、知识检索、输出校验与缓存。 */
class AdviceTest {
    private val now = 1_760_500_000_000L

    private class FakeLlm(vararg replies: String) : LlmClient {
        private val queue = ArrayDeque(replies.toList())
        val prompts = mutableListOf<String>()
        override val model = "fake-model"
        override suspend fun complete(system: String, user: String): LlmReply {
            prompts += user
            return LlmReply(queue.removeFirstOrNull() ?: error("没有更多预设回复"), 100, 50)
        }
    }

    private val good = """{"summary":"今天连续看屏幕太久，眼睛有点累","priorities":["现在抬头看看远处约 20 秒","把屏幕拿远一点"],
        "habits":["打开休息提醒，按时放松"],"seeDoctor":"休息后仍反复干涩或眼痛时去眼科","refs":["gen-screen-breaks"]}"""

    private val request = AdviceRequest(
        fatigueLevel = "MEDIUM", fatigueScore = 53, factors = listOf("用屏时间偏长", "连续用眼太久"),
        ageGroup = "30–39 岁", occupation = "办公室工作", averageScreenMinutes = 332, averageLongestSessionMinutes = 75,
        nightUseDays = 1, averageBreaks = 9, blinksPerMinute = 9, viewingDistanceCm = 31,
        screenBrightness = "适中", tests = listOf(AdviceTest("DRY_EYE", "MEDIUM")), symptoms = listOf("眼睛干涩"),
    )

    private fun api(llm: LlmClient?, block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) {
        val config = ServerConfig.forTest(UUID.randomUUID().toString())
        val services = Services(config, llm) { now }
        try {
            testApplication {
                application { eyeProjectModule(config, services, runJobs = false) }
                block(createClient { install(ContentNegotiation) { json(SyncContract.json) } })
            }
        } finally {
            services.close()
        }
    }

    private suspend fun HttpClient.signUp(vararg consents: ConsentType): String {
        val tokens: TokenResponse = post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("a@example.com", "secret-123", null, SyncContract.PRIVACY_POLICY_VERSION))
        }.body()
        consents.forEach { type ->
            post("/v1/me/consents") {
                bearerAuth(tokens.accessToken); contentType(ContentType.Application.Json)
                setBody(ConsentInput(type, SyncContract.PRIVACY_POLICY_VERSION, true))
            }
        }
        return tokens.accessToken
    }

    private suspend fun HttpClient.advise(token: String, body: AdviceRequest = request): HttpResponse =
        post("/v1/advice") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }

    @Test fun needsItsOwnConsent() {
        api(FakeLlm(good)) { client ->
            // 只同意了 AI 解读，不等于同意综合建议
            val blocked = client.advise(client.signUp(ConsentType.AI_EXPLANATION))
            assertEquals(HttpStatusCode.Forbidden, blocked.status)
            assertEquals(ErrorCodes.CONSENT_REQUIRED, blocked.body<ErrorBody>().error)
        }
    }

    @Test fun advisesFromTheSummaryAndCaches() {
        val llm = FakeLlm(good)
        api(llm) { client ->
            val token = client.signUp(ConsentType.AI_ADVICE)
            val first = client.advise(token)
            assertEquals(HttpStatusCode.OK, first.status)
            val dto: AdviceDto = first.body()
            assertEquals("MEDIUM", dto.fatigueLevel)
            assertEquals(listOf("现在抬头看看远处约 20 秒", "把屏幕拿远一点"), dto.priorities)
            assertEquals("aao-screen", dto.sources.first().id)
            val prompt = llm.prompts.single()
            assertTrue("今天的用眼负荷：留意，53 分" in prompt, prompt)
            assertTrue("日均用屏 332 分钟" in prompt)
            assertTrue("最近的自测：干眼自测中风险" in prompt)
            assertTrue("[gen-screen-habits]" in prompt, "固定带上用屏习惯")
            assertTrue("[gen-red-flags]" in prompt, "自测中风险时带上危险信号")
            val again: AdviceDto = client.advise(token).body()
            assertEquals(dto.summary, again.summary)
            assertEquals(1, llm.prompts.size, "同样的概况命中缓存")
        }
    }

    @Test fun rejectsDiagnosesAndInventedNumbersThenRetries() {
        val diagnosis = """{"summary":"你患有干眼","priorities":["多眨眼"],"habits":[],"seeDoctor":"","refs":[]}"""
        val invented = """{"summary":"每天用屏不要超过 500 分钟","priorities":["多眨眼"],"habits":[],"seeDoctor":"","refs":[]}"""
        assertTrue(AdviceOutput.parse(diagnosis, request, setOf("53")) is AdviceOutput.Result.Invalid)
        assertTrue(AdviceOutput.parse(invented, request, setOf("53")) is AdviceOutput.Result.Invalid)
        val llm = FakeLlm(diagnosis, good)
        api(llm) { client ->
            val response = client.advise(client.signUp(ConsentType.AI_ADVICE))
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(2, llm.prompts.size)
            assertTrue("上一次输出不合格" in llm.prompts.last())
        }
    }
}
