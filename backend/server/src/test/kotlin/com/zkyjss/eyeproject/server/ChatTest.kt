package com.zkyjss.eyeproject.server

import com.zkyjss.eyeproject.core.sync.AdviceRequest
import com.zkyjss.eyeproject.core.sync.ChatMessage
import com.zkyjss.eyeproject.core.sync.ChatReplyDto
import com.zkyjss.eyeproject.core.sync.ChatRequest
import com.zkyjss.eyeproject.core.sync.Chats
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AI 对话：单独同意、多轮与概况进入提示、危险信号由规则判断、输出校验、每日条数、对话内容不入库。 */
class ChatTest {
    private val now = 1_760_500_000_000L

    private class FakeLlm(vararg replies: String) : LlmClient {
        private val queue = ArrayDeque(replies.toList())
        val systems = mutableListOf<String>()
        val conversations = mutableListOf<List<LlmTurn>>()
        override val model = "fake-model"
        override suspend fun complete(system: String, user: String): LlmReply = error("对话不走单轮接口")
        override suspend fun chat(system: String, turns: List<LlmTurn>): LlmReply {
            systems += system; conversations += turns
            return LlmReply(queue.removeFirstOrNull() ?: error("没有更多预设回复"), 300, 80)
        }
    }

    private val context = AdviceRequest(fatigueLevel = "MEDIUM", fatigueScore = 53, factors = listOf("连续用眼太久"),
        ageGroup = "30–39 岁", occupation = "办公室工作", blinksPerMinute = 9, symptoms = listOf("眼睛干涩"))

    private fun reply(text: String) = """{"reply":"$text","refs":["gen-screen-breaks"]}"""

    private fun api(llm: LlmClient?, chatLimit: Int = 40, block: suspend ApplicationTestBuilder.(HttpClient, Services) -> Unit) {
        val config = ServerConfig.forTest(UUID.randomUUID().toString()).copy(llmChatDailyLimit = chatLimit)
        val services = Services(config, llm) { now }
        try {
            testApplication {
                application { eyeProjectModule(config, services, runJobs = false) }
                block(createClient { install(ContentNegotiation) { json(SyncContract.json) } }, services)
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

    private suspend fun HttpClient.chat(token: String, vararg messages: ChatMessage, ctx: AdviceRequest? = context): HttpResponse =
        post("/v1/chat") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(ChatRequest(messages.toList(), ctx)) }

    private fun user(text: String) = ChatMessage(Chats.USER, text)

    @Test fun needsItsOwnConsent() = api(FakeLlm(reply("好的"))) { client, _ ->
        // 同意了综合建议不等于同意对话（对话会发送用户自己打的字）
        val blocked = client.chat(client.signUp(ConsentType.AI_ADVICE), user("眼睛干"))
        assertEquals(HttpStatusCode.Forbidden, blocked.status)
        assertEquals(ErrorCodes.CONSENT_REQUIRED, blocked.body<ErrorBody>().error)
    }

    @Test fun answersWithContextAndHistoryWithoutStoringContent() {
        val llm = FakeLlm(reply("看屏幕久了眨眼会变少，可能和眼睛干涩有关。每用眼 20 分钟抬头看远处一会儿，有意识地多眨眼。"))
        api(llm) { client, services ->
            val token = client.signUp(ConsentType.AI_CHAT)
            val response = client.chat(token,
                ChatMessage(Chats.ASSISTANT, "今天连续用眼有点久，记得休息"),
                user("今天下午盯电脑，眼睛又干又涩"))
            assertEquals(HttpStatusCode.OK, response.status)
            val dto = response.body<ChatReplyDto>()
            assertTrue(dto.reply.startsWith("看屏幕久了"))
            assertFalse(dto.urgent)
            assertEquals(Chats.DISCLAIMER, dto.disclaimer)
            // 概况与知识放在系统提示里，多轮对话原样传给模型
            val system = llm.systems.single()
            assertTrue("办公室工作" in system && "眨眼约每分钟 9 次" in system && "【参考知识】" in system)
            assertEquals(listOf("assistant", "user"), llm.conversations.single().map { it.role })
            // 对话内容不入库：审计只有动作与是否有危险信号
            val audit = services.db.tx { query("SELECT target_id, outcome FROM audit_events WHERE action = 'AI_CHAT'") { it.getString(1) to it.getString(2) } }
            assertEquals(listOf("NORMAL" to "OK"), audit)
        }
    }

    @Test fun redFlagsAreDecidedByRulesAndTheReplyMustSendThemToADoctor() {
        // 第一次回答没提就医：判为不合格，重试一次
        val llm = FakeLlm(reply("多休息，热敷一下就好。"), reply("眼睛疼又突然看不清，请尽快到医院眼科就诊，必要时去急诊。在这之前不要揉眼。"))
        api(llm) { client, _ ->
            val dto = client.chat(client.signUp(ConsentType.AI_CHAT), user("今天眼睛疼，还突然看不清")).body<ChatReplyDto>()
            assertTrue(dto.urgent)
            assertTrue("眼科" in dto.reply)
            assertEquals(2, llm.conversations.size)
            assertTrue("危险信号" in llm.systems.first())
            assertTrue("不合格" in llm.conversations.last().last().content)
        }
    }

    @Test fun rejectsDiagnosesAndBadRequests() {
        api(FakeLlm(reply("你得了干眼症。"), reply("你肯定是干眼症。"))) { client, _ ->
            val token = client.signUp(ConsentType.AI_CHAT)
            assertEquals(HttpStatusCode.BadGateway, client.chat(token, user("眼睛干")).status)
            // 最后一条不是用户的提问
            assertEquals(HttpStatusCode.BadRequest, client.chat(token, user("你好"), ChatMessage(Chats.ASSISTANT, "你好呀")).status)
        }
    }

    @Test fun limitsMessagesPerDay() = api(FakeLlm(reply("好的，多眨眼。"), reply("记得休息。")), chatLimit = 2) { client, _ ->
        val token = client.signUp(ConsentType.AI_CHAT)
        assertEquals(HttpStatusCode.OK, client.chat(token, user("眼睛干"), ctx = null).status)
        assertEquals(HttpStatusCode.OK, client.chat(token, user("还是干"), ctx = null).status)
        val third = client.chat(token, user("再问一次"))
        assertEquals(HttpStatusCode.TooManyRequests, third.status)
        assertEquals(ErrorCodes.AI_QUOTA_EXCEEDED, third.body<ErrorBody>().error)
    }

    @Test fun sharedRequestKeepsRecentTurnsAndOpening() {
        val history = (1..20).map { com.zkyjss.eyeproject.core.sync.ChatEntry(if (it % 2 == 1) Chats.USER else Chats.ASSISTANT, "第 $it 条", it.toLong()) }
        val request = Chats.request(history.dropLast(1), null, context)
        assertEquals(Chats.MAX_HISTORY, request.messages.size)
        assertEquals("第 19 条", request.messages.last().text)
        assertTrue(Chats.redFlag("右眼突然重影") && !Chats.redFlag("眼睛有点干"))
    }
}
