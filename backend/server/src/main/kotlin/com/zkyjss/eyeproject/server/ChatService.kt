package com.zkyjss.eyeproject.server

import com.zkyjss.eyeproject.core.sync.Advices
import com.zkyjss.eyeproject.core.sync.Advices.bounded
import com.zkyjss.eyeproject.core.sync.ChatReplyDto
import com.zkyjss.eyeproject.core.sync.ChatRequest
import com.zkyjss.eyeproject.core.sync.ChatQuestion
import com.zkyjss.eyeproject.core.sync.Chats
import com.zkyjss.eyeproject.core.sync.Chats.bounded
import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.sync.ErrorCodes
import com.zkyjss.eyeproject.core.sync.KnowledgeSourceDto
import com.zkyjss.eyeproject.core.sync.SyncContract
import com.zkyjss.eyeproject.server.ProfileService.Companion.consentGranted
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId

/**
 * AI 对话（和爱科聊聊）：用户自由描述用眼感受或提问，模型结合去标识用眼概况与知识库回答。
 * 流程：校验与截断 → 检查「AI 对话」同意 → 每日条数额度（与解读、综合建议分开）→ 规则判断危险信号 → 检索知识库 →
 * 调用模型 → 校验输出（诊断性措辞、长度、有危险信号时必须建议就医）→ 不合格重试一次。
 * 对话内容不入库、不进日志与审计（审计只记次数与是否有危险信号），不缓存。
 */
class ChatService(
    private val db: Database,
    private val config: ServerConfig,
    private val audit: AuditLog,
    private val knowledge: KnowledgeBase,
    private val llm: LlmClient?,
    private val clock: () -> Long,
) {
    private val log = LoggerFactory.getLogger(ChatService::class.java)

    suspend fun reply(accountId: String, input: ChatRequest, ip: String?): ChatReplyDto {
        val request = input.bounded() ?: throw ApiException.badRequest("最后一条必须是你的提问")
        val context = request.context?.takeIf { it.fatigueLevel in Advices.LEVELS }?.bounded()
        db.transaction {
            if (!consentGranted(accountId, ConsentType.AI_CHAT))
                throw ApiException.consentRequired("和爱科聊天前需要同意把你输入的文字与去标识的用眼概况发送给大模型")
        }
        val client = llm ?: throw ApiException(HttpStatusCode.ServiceUnavailable, ErrorCodes.AI_UNAVAILABLE, "服务器尚未开启 AI 对话")
        val question = request.messages.last().text
        val urgent = Chats.redFlag(question)
        val target = if (urgent) "URGENT" else "NORMAL"
        // 「结束问询」由规则直接回复：不调用模型，也不占每日条数
        if (question == Chats.STOP_INQUIRY) {
            db.transaction { audit.write(this, AuditAction.AI_CHAT, accountId, "chat", target, "OK", ip) }
            return ChatReplyDto("已结束本次问询。你仍可以继续聊眼健康，或重新开始梳理不适。", model = "rule-inquiry", generatedAt = clock())
        }
        val day = Instant.ofEpochMilli(clock()).atZone(ZONE).toLocalDate().toString()
        db.transaction {
            if (usedToday(accountId, day) >= config.llmChatDailyLimit) {
                audit.write(this, AuditAction.AI_CHAT, accountId, "chat", target, "QUOTA", ip)
                throw ApiException(HttpStatusCode.TooManyRequests, ErrorCodes.AI_QUOTA_EXCEEDED, "今天和爱科聊得够多啦，明天再来吧")
            }
            addUsage(accountId, day)
        }
        val questions = request.messages.filter { it.role == Chats.USER }.takeLast(2).map { it.text }
        val inquiryEnabled = request.inquiryVersion == 1
        val previousQuestion = request.messages.dropLast(1).lastOrNull()?.question
        val chunks = knowledge.searchChat(questions, context, urgent)
        val system = ChatPrompt.SYSTEM + (if (inquiryEnabled) "\n\n" + InquiryPrompt.SYSTEM else "") + "\n\n" + ChatPrompt.background(context, chunks, knowledge, urgent)
        val turns = request.messages.map { m -> LlmTurn(m.role, m.text + (m.question?.let {
            "\n【本条助手提出的问题】${SyncContract.json.encodeToString(ChatQuestion.serializer(), it)}"
        } ?: "") + if (m.inquiryComplete) "\n【本次问询已总结结束】" else "") }
        var problem: String? = null
        repeat(MAX_ATTEMPTS) {
            val asked = problem?.let { turns + LlmTurn("user", "（系统提示：上一次回答不合格：$it。请改正后重新只输出 JSON，回答我上面的问题。）") } ?: turns
            val reply = try {
                client.chat(system, asked)
            } catch (error: LlmException) {
                log.warn("AI 对话调用失败：{}", error.message)
                db.transaction { audit.write(this, AuditAction.AI_CHAT, accountId, "chat", target, "FAILED", ip) }
                throw ApiException(HttpStatusCode.ServiceUnavailable, ErrorCodes.AI_UNAVAILABLE, "爱科暂时联系不上大模型，请稍后再试")
            }
            log.info("AI 对话：模型 {}，输入 {} tokens，输出 {} tokens", client.model, reply.promptTokens, reply.completionTokens)
            when (val result = ChatOutput.parse(reply.content, urgent, previousQuestion,
                requireQuestion = inquiryEnabled && question == Chats.START_INQUIRY, inquiryEnabled = inquiryEnabled)) {
                is ChatOutput.Result.Invalid -> problem = result.reason
                is ChatOutput.Result.Valid -> {
                    db.transaction { audit.write(this, AuditAction.AI_CHAT, accountId, "chat", target, "OK", ip) }
                    return ChatReplyDto(result.reply, urgent, sourcesFor(result.refs, chunks), client.model, clock(),
                        question = result.question, inquiryComplete = result.inquiryComplete)
                }
            }
        }
        log.warn("AI 对话输出两次不合格：{}", problem)
        db.transaction { audit.write(this, AuditAction.AI_CHAT, accountId, "chat", target, "INVALID_OUTPUT", ip) }
        throw ApiException(HttpStatusCode.BadGateway, ErrorCodes.AI_UNAVAILABLE, "这次没能给出合适的回答，换个说法再试试吧")
    }

    private fun java.sql.Connection.usedToday(accountId: String, day: String): Int =
        single("SELECT messages FROM ai_chat_usage WHERE account_id = ? AND usage_day = ?", accountId, day) { it.getInt(1) } ?: 0

    private fun java.sql.Connection.addUsage(accountId: String, day: String) {
        if (update("UPDATE ai_chat_usage SET messages = messages + 1 WHERE account_id = ? AND usage_day = ?", accountId, day) == 0)
            update("INSERT INTO ai_chat_usage (account_id, usage_day, messages) VALUES (?, ?, 1)", accountId, day)
    }

    /** 模型引用的条目的来源；只列实际引用的，最多 3 个。 */
    private fun sourcesFor(refs: List<String>, chunks: List<KnowledgeBase.Entry>): List<KnowledgeSourceDto> =
        chunks.filter { it.id in refs }.flatMap { it.sourceIds }.distinct()
            .sortedBy { if (it == "app-protocol") 1 else 0 }
            .mapNotNull(knowledge.sources::get).take(3)

    companion object {
        private const val MAX_ATTEMPTS = 2
        private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}

/** 提示词：规则部分固定不变，背景（用眼概况 + 本轮检索到的知识）随提问变化。 */
object ChatPrompt {
    val SYSTEM = """
        你是爱尔眼科的 AI 助手「爱科」（Eyecho），在眼健康 App 里用温和、口语化的中文和用户聊用眼感受与护眼方法。必须遵守：
        1. 你是科普助手，不是医生：不得下诊断，不说「确诊」「患有」「你得了」「肯定是」，也不说「不用就医」；可以说「可能和……有关」。
        2. 应用规则算出的用眼负荷等级与自测风险等级以【用眼概况】为准，不得改变或质疑。
        3. 只依据【用眼概况】【参考知识】和对话内容回答，不编造研究、数据或用户没说过的情况；不确定时直说，并建议到眼科检查。
        4. 提问里有眼痛、突然看不清、突然重影、闪光或黑影增多、眼外伤、化学物质溅入等情况，先明确建议尽快到医院眼科（必要时急诊），再给简短的注意事项。
        5. 不提具体药品名称、剂量和用法，不推荐具体医院、医生、产品或品牌；眼药水只能说「在医生指导下使用」。
        6. 回答简短具体：一般 2 到 5 句、不超过 200 字，给今天就能做到的建议；可以结合用户的年龄段、职业和用眼数据。
        7. 与眼睛、用眼和视力无关的问题，礼貌说明只能聊眼健康，并把话题拉回来。
        只输出一个 JSON 对象：{"reply":"给用户的回答","refs":["实际用到的参考知识编号"]}
    """.trimIndent()

    fun background(context: com.zkyjss.eyeproject.core.sync.AdviceRequest?, chunks: List<KnowledgeBase.Entry>, knowledge: KnowledgeBase,
                   urgent: Boolean): String = buildString {
        if (context != null) {
            // 与 AI 综合建议同一份概况写法，去掉其中的【参考知识】部分
            append(AdvicePrompt.user(context, emptyList(), knowledge).substringBefore("【参考知识】").trimEnd())
            appendLine()
        } else appendLine("【用眼概况】暂无（用户还没有用眼记录）")
        if (urgent) appendLine("【提醒】用户这次的提问里有需要尽快就医的危险信号。")
        appendLine()
        appendLine("【参考知识】")
        if (chunks.isEmpty()) appendLine("（没有检索到相关条目，只做一般性建议，并提醒有不适到眼科检查）")
        chunks.forEach { c ->
            val source = c.sourceIds.mapNotNull { knowledge.sources[it]?.publisher }.distinct().joinToString("、")
            appendLine("[${c.id}] ${c.text}（来源：$source）")
        }
    }
}

/** 模型输出的解析与校验。 */
object ChatOutput {
    sealed interface Result {
        data class Valid(val reply: String, val refs: List<String>, val question: ChatQuestion? = null,
            val inquiryComplete: Boolean = false) : Result
        data class Invalid(val reason: String) : Result
    }

    private const val MAX_LENGTH = 400
    private val SEE_DOCTOR = listOf("就医", "医院", "眼科", "急诊", "就诊", "看医生")

    fun parse(content: String, urgent: Boolean, previousQuestion: ChatQuestion? = null, requireQuestion: Boolean = false,
              inquiryEnabled: Boolean = true): Result {
        val root = runCatching { SyncContract.json.parseToJsonElement(content.trim()).jsonObject }.getOrNull()
            ?: return Result.Invalid("不是 JSON 对象")
        val reply = (root["reply"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        val refs = (root["refs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        if (reply.isEmpty()) return Result.Invalid("缺少 reply")
        val completeValue = root["inquiryComplete"]
        if (completeValue != null && completeValue.toString() !in listOf("true", "false")) return Result.Invalid("inquiryComplete 必须是布尔值")
        val complete = completeValue?.toString() == "true"
        val questionValue = root["question"]?.takeIf { it.toString() != "null" }
        val question = questionValue?.let { value ->
            runCatching { SyncContract.json.decodeFromString(ChatQuestion.serializer(), value.toString()) }.getOrNull()
                ?: return Result.Invalid("question 必须包含问题正文、简短选项及布尔标记")
        }
        if (!inquiryEnabled && (question != null || complete)) return Result.Invalid("旧客户端仅支持普通 reply 文本，不返回 question 或 inquiryComplete")
        if (question != null && !Chats.validQuestion(question)) return Result.Invalid("问题或选项不合格；最后确认只能有「没有」且允许自由补充")
        if (complete && question != null) return Result.Invalid("不能同时总结结束并继续提问")
        if (urgent && question != null) return Result.Invalid("危险信号必须停止追问并建议就医")
        if (requireQuestion && question == null) return Result.Invalid("用户明确要求逐步问询，请先提出一个关键问题")
        if (complete && previousQuestion?.finalConfirmation != true) return Result.Invalid("总结前必须先做最后补充确认并等待用户回答")
        if (complete && InquiryPrompt.SECTIONS.any { it !in reply }) return Result.Invalid("总结缺少综合判断、就医建议、就医前的缓解与注意事项、需要警惕的信号或复诊与跟进")
        if (reply.length > if (complete) Chats.MAX_HISTORY_TEXT else MAX_LENGTH) return Result.Invalid("回答太长，请缩短")
        ExplanationOutput.FORBIDDEN.firstOrNull { it in reply }?.let { return Result.Invalid("出现了不允许的说法「$it」") }
        if (urgent && SEE_DOCTOR.none { it in reply }) return Result.Invalid("用户提到了危险信号，回答必须明确建议尽快到医院眼科就诊")
        question?.let { q -> ExplanationOutput.FORBIDDEN.firstOrNull { it in q.prompt || q.options.any { option -> it in option } }
            ?.let { return Result.Invalid("问题包含不允许的诊断性说法") } }
        return Result.Valid(reply, refs, question, complete)
    }
}
