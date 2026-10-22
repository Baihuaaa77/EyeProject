package com.zkyjss.eyeproject.server

import com.zkyjss.eyeproject.core.sync.ConsentInput
import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.sync.ErrorBody
import com.zkyjss.eyeproject.core.sync.ErrorCodes
import com.zkyjss.eyeproject.core.sync.ExplanationDto
import com.zkyjss.eyeproject.core.sync.ExplanationEvidence
import com.zkyjss.eyeproject.core.sync.ExplanationItem
import com.zkyjss.eyeproject.core.sync.ExplanationRequest
import com.zkyjss.eyeproject.core.sync.Explanations
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExplanationTest {
    private val now = 1_760_500_000_000L

    /** 大模型替身：按顺序返回预设回复，并记录被调用的次数与收到的提示。 */
    private class FakeLlm(vararg replies: String) : LlmClient {
        private val queue = ArrayDeque(replies.toList())
        val prompts = mutableListOf<String>()
        override val model = "fake-model"
        override suspend fun complete(system: String, user: String): LlmReply {
            prompts += user
            return LlmReply(queue.removeFirstOrNull() ?: error("没有更多预设回复"), 100, 50)
        }
    }

    private val good = """{"summary":"眨眼偏少，眼睛表面容易干","meaning":["专注看屏幕时眨眼会变少"],
        "suggestions":["看屏幕时有意识地多眨眼","避免空调直吹"],"seeDoctor":"不适持续几周或眼痛时去眼科","refs":["dry-screen-blink"]}"""

    private val request = ExplanationRequest(
        kind = "DRY_EYE", riskLevel = "MEDIUM", badge = "干眼中风险", title = "有一些干眼迹象",
        advice = "建议减少连续看屏幕的时间。",
        metrics = listOf(ExplanationItem("眨眼频率", "6 次/分", "ABNORMAL"), ExplanationItem("最长坚持睁眼", "12 秒", "NORMAL")),
        evidence = listOf(ExplanationEvidence("眨眼", "ABNORMAL", "眨眼频率低于参考范围", listOf("每分钟 6 次，参考 10–16 次"))),
        ageGroup = "30–39 岁", background = mapOf("每日电子产品使用" to "8 小时以上"),
    )

    private fun api(llm: LlmClient?, dailyLimit: Int = 20, block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) {
        val config = ServerConfig.forTest(UUID.randomUUID().toString()).copy(llmDailyLimit = dailyLimit)
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

    private suspend fun HttpClient.signUp(consent: Boolean = true): String {
        val tokens: TokenResponse = post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("a@example.com", "secret-123", null, SyncContract.PRIVACY_POLICY_VERSION))
        }.body()
        if (consent) post("/v1/me/consents") {
            bearerAuth(tokens.accessToken); contentType(ContentType.Application.Json)
            setBody(ConsentInput(ConsentType.AI_EXPLANATION, SyncContract.PRIVACY_POLICY_VERSION, true))
        }
        return tokens.accessToken
    }

    private suspend fun HttpClient.explain(token: String, body: ExplanationRequest = request): HttpResponse =
        post("/v1/explanations") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpResponse.error() = body<ErrorBody>().error

    @Test fun requiresConsentAndConfiguredModel() {
        api(FakeLlm(good)) { client ->
            val token = client.signUp(consent = false)
            val blocked = client.explain(token)
            assertEquals(HttpStatusCode.Forbidden, blocked.status)
            assertEquals(ErrorCodes.CONSENT_REQUIRED, blocked.error())
        }
        api(llm = null) { client ->
            val off = client.explain(client.signUp())
            assertEquals(HttpStatusCode.ServiceUnavailable, off.status)
            assertEquals(ErrorCodes.AI_UNAVAILABLE, off.error())
        }
    }

    @Test fun explainsWithKnowledgeAndCachesIdenticalRequests() {
        val llm = FakeLlm(good)
        api(llm) { client ->
            val token = client.signUp()
            val first = client.explain(token)
            assertEquals(HttpStatusCode.OK, first.status)
            val dto: ExplanationDto = first.body()
            assertEquals("MEDIUM", dto.riskLevel)
            assertEquals(listOf("看屏幕时有意识地多眨眼", "避免空调直吹"), dto.suggestions)
            assertEquals("aao-screen", dto.sources.first().id)
            // 提示里有规则等级、检索到的知识，且中风险固定带上就医条目
            val prompt = llm.prompts.single()
            assertTrue("规则判定等级：MEDIUM" in prompt)
            assertTrue("[dry-doctor]" in prompt)
            assertTrue("8 小时以上" in prompt)
            // 同样的输入命中缓存，不再调用模型
            val again: ExplanationDto = client.explain(token).body()
            assertEquals(dto, again)
            assertEquals(1, llm.prompts.size)
        }
    }

    @Test fun retriesOnceWhenOutputBreaksRules() {
        val diagnosis = good.replace("眨眼偏少，眼睛表面容易干", "你患有干眼")
        val invented = good.replace("专注看屏幕时眨眼会变少", "正常人每分钟眨眼 47 次")
        val llm = FakeLlm(diagnosis, good)
        api(llm) { client ->
            val token = client.signUp()
            assertEquals(HttpStatusCode.OK, client.explain(token).status)
            assertEquals(2, llm.prompts.size)
            assertTrue("不允许的说法「患有」" in llm.prompts[1])
        }
        api(FakeLlm(invented, invented)) { client ->
            val failed = client.explain(client.signUp())
            assertEquals(HttpStatusCode.BadGateway, failed.status)
            assertEquals(ErrorCodes.AI_UNAVAILABLE, failed.error())
        }
    }

    @Test fun dailyLimitCountsModelCallsNotCacheHits() {
        api(FakeLlm(good, good), dailyLimit = 1) { client ->
            val token = client.signUp()
            assertEquals(HttpStatusCode.OK, client.explain(token).status)
            assertEquals(HttpStatusCode.OK, client.explain(token).status)
            val other = client.explain(token, request.copy(riskLevel = "HIGH", badge = "干眼高风险"))
            assertEquals(HttpStatusCode.TooManyRequests, other.status)
            assertEquals(ErrorCodes.AI_QUOTA_EXCEEDED, other.error())
        }
    }

    @Test fun rejectsUnknownKindsAndContradictions() {
        api(FakeLlm(good)) { client ->
            val token = client.signUp()
            assertEquals(HttpStatusCode.BadRequest, client.explain(token, request.copy(kind = "MYOPIA")).status)
        }
        val low = request.copy(riskLevel = "LOW")
        val output = ExplanationOutput.parse(good.replace("眼睛表面容易干", "属于高风险"), low, setOf("6"))
        assertTrue(output is ExplanationOutput.Result.Invalid)
    }

    @Test fun knowledgeBaseLoadsAndPinsEssentialEntries() {
        val kb = KnowledgeBase.load()
        assertTrue(kb.entries.size >= 30)
        assertNull(kb.entries.firstOrNull { it.sourceIds.isEmpty() || it.text.length !in 20..260 }?.id)
        val strabismus = request.copy(kind = "STRABISMUS", riskLevel = "HIGH", title = "高度疑似斜视",
            evidence = listOf(ExplanationEvidence("注视眼位", "ABNORMAL", "水平偏斜稳定超过参考线")), ageGroup = "6–8 岁", background = emptyMap())
        val hits = kb.search(strabismus).map { it.id }
        assertEquals("stra-doctor", hits.first())
        assertTrue(hits.all { kb.entry(it)!!.kinds.any { k -> k == "STRABISMUS" || k == KnowledgeBase.GENERAL } })
        assertEquals(listOf("gen-invalid", "gen-retest"), kb.search(request.copy(riskLevel = "UNKNOWN")).take(2).map { it.id })
        // 眨眼少 + 长时间用电子产品：应检出「屏幕时眨眼减少」
        assertTrue("dry-screen-blink" in kb.search(request).map { it.id })
    }

    @Test fun requestIsExtractedFromRecordWithoutIdentity() {
        val record = """{"kind":"DRY_EYE","valid":true,"riskLevel":"MEDIUM",
            "report":{"level":"MEDIUM","badge":"干眼中风险","title":"有一些干眼迹象","advice":"少看屏幕","version":"dryeye-9.18-rules-2",
              "metrics":[{"label":"眨眼频率","value":"6 次/分","status":"ABNORMAL"}],
              "evidence":[{"key":"blink","title":"眨眼","status":"ABNORMAL","summary":"偏少","details":["每分钟 6 次"]}],
              "problems":[],"qualityNotes":["光线偏暗"]},
            "profile":{"年龄":"34","干眼症状":"眼睛发干、发涩","职业":"办公室工作","出生年份":"1992"},
            "subject":{"id":"s-1","name":"小明","relation":"SELF"}}"""
        val parsed = assertNotNull(Explanations.fromRecord(record))
        assertEquals("30–39 岁", parsed.ageGroup)
        assertEquals(mapOf("干眼症状" to "眼睛发干、发涩"), parsed.background)
        assertEquals("dryeye-9.18-rules-2", parsed.assessmentVersion)
        val text = SyncContract.json.encodeToString(ExplanationRequest.serializer(), parsed)
        assertTrue("小明" !in text && "s-1" !in text && "1992" !in text)
        // 旧记录没有结果页快照：退回规则依据
        val legacy = Explanations.fromRecord("""{"kind":"STRABISMUS","assessment":{"quality":"不足","riskLevel":"UNKNOWN","findings":["光线不足"]}}""")
        assertEquals("UNKNOWN", legacy?.riskLevel)
        assertEquals(listOf("光线不足"), legacy?.problems)
        assertNull(Explanations.fromRecord("""{"kind":"MYOPIA"}"""))
    }
}
