package com.zkyjss.eyeproject.server

import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.sync.ErrorCodes
import com.zkyjss.eyeproject.core.sync.ExplanationDto
import com.zkyjss.eyeproject.core.sync.ExplanationRequest
import com.zkyjss.eyeproject.core.sync.Explanations
import com.zkyjss.eyeproject.core.sync.Explanations.bounded
import com.zkyjss.eyeproject.core.sync.KnowledgeSourceDto
import com.zkyjss.eyeproject.core.sync.RecordSummaries
import com.zkyjss.eyeproject.core.sync.Sha256
import com.zkyjss.eyeproject.core.sync.SyncContract
import com.zkyjss.eyeproject.server.ProfileService.Companion.consentGranted
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId

/**
 * AI 解读（设计文档 §5.4）：风险等级来自设备端规则，模型只解释、给建议。
 * 流程：校验输入 → 检查「AI 解读」同意 → 查缓存 → 检查每日额度 → 检索知识库 → 调用模型 →
 * 校验输出（诊断性措辞、与等级矛盾的说法、输入里没有的数字）→ 不合格重试一次 → 缓存并返回。
 * 解读内容不进日志与审计；缓存只以输入哈希为键，不关联账号。
 */
class ExplanationService(
    private val db: Database,
    private val config: ServerConfig,
    private val audit: AuditLog,
    private val knowledge: KnowledgeBase,
    private val llm: LlmClient?,
    private val clock: () -> Long,
) {
    private val log = LoggerFactory.getLogger(ExplanationService::class.java)
    private val json = SyncContract.json

    suspend fun explain(accountId: String, input: ExplanationRequest, ip: String?): ExplanationDto {
        val request = validate(input)
        db.transaction {
            if (!consentGranted(accountId, ConsentType.AI_EXPLANATION))
                throw ApiException.consentRequired("使用 AI 解读前需要同意把去标识的结果发送给大模型")
        }
        val client = llm ?: throw ApiException(HttpStatusCode.ServiceUnavailable, ErrorCodes.AI_UNAVAILABLE, "服务器尚未开启 AI 解读")
        val key = Sha256.hex("$PROMPT_VERSION|${client.model}|${knowledge.version}|${Explanations.cacheKey(request)}")
        cached(key)?.let { hit ->
            db.transaction { audit.write(this, AuditAction.AI_EXPLAIN, accountId, "explanation", request.kind, "CACHED", ip) }
            return hit
        }
        val day = Instant.ofEpochMilli(clock()).atZone(ZONE).toLocalDate().toString()
        db.transaction {
            if (usedToday(accountId, day) >= config.llmDailyLimit) {
                audit.write(this, AuditAction.AI_EXPLAIN, accountId, "explanation", request.kind, "QUOTA", ip)
                throw ApiException(HttpStatusCode.TooManyRequests, ErrorCodes.AI_QUOTA_EXCEEDED, "今天的 AI 解读次数已用完，明天再来吧")
            }
        }
        val chunks = knowledge.search(request)
        val prompt = ExplanationPrompt.user(request, chunks, knowledge)
        val allowedNumbers = ExplanationPrompt.numbers(prompt)
        var problem: String? = null
        repeat(MAX_ATTEMPTS) {
            val reply = try {
                client.complete(ExplanationPrompt.SYSTEM, prompt + (problem?.let { "\n\n上一次输出不合格：$it。请改正后重新只输出 JSON。" }.orEmpty()))
            } catch (error: LlmException) {
                log.warn("AI 解读调用失败：{}", error.message)
                db.transaction { addUsage(accountId, day); audit.write(this, AuditAction.AI_EXPLAIN, accountId, "explanation", request.kind, "FAILED", ip) }
                throw ApiException(HttpStatusCode.ServiceUnavailable, ErrorCodes.AI_UNAVAILABLE, "AI 解读服务暂时不可用，请稍后再试")
            }
            log.info("AI 解读：模型 {}，输入 {} tokens，输出 {} tokens", client.model, reply.promptTokens, reply.completionTokens)
            db.transaction { addUsage(accountId, day) }
            when (val result = ExplanationOutput.parse(reply.content, request, allowedNumbers)) {
                is ExplanationOutput.Result.Invalid -> problem = result.reason
                is ExplanationOutput.Result.Valid -> {
                    val dto = ExplanationDto(
                        summary = result.summary, meaning = result.meaning, suggestions = result.suggestions, seeDoctor = result.seeDoctor,
                        sources = sourcesFor(result.refs, chunks), riskLevel = request.riskLevel, model = client.model, generatedAt = clock(),
                    )
                    db.transaction {
                        if (single("SELECT 1 FROM ai_explanations WHERE cache_key = ?", key) { 1 } == null)
                            update("INSERT INTO ai_explanations (cache_key, output_json, created_at) VALUES (?, ?, ?)",
                                key, json.encodeToString(ExplanationDto.serializer(), dto), clock())
                        audit.write(this, AuditAction.AI_EXPLAIN, accountId, "explanation", request.kind, "OK", ip)
                    }
                    return dto
                }
            }
        }
        log.warn("AI 解读输出两次不合格：{}", problem)
        db.transaction { audit.write(this, AuditAction.AI_EXPLAIN, accountId, "explanation", request.kind, "INVALID_OUTPUT", ip) }
        throw ApiException(HttpStatusCode.BadGateway, ErrorCodes.AI_UNAVAILABLE, "这次没能生成合格的解读，请稍后再试")
    }

    private fun validate(input: ExplanationRequest): ExplanationRequest {
        if (input.kind !in RecordSummaries.KINDS) throw ApiException.badRequest("未知的测试类型")
        if (input.riskLevel !in RecordSummaries.RISK_LEVELS) throw ApiException.badRequest("风险等级无效")
        return input.bounded()
    }

    private suspend fun cached(key: String): ExplanationDto? = db.transaction {
        single("SELECT output_json FROM ai_explanations WHERE cache_key = ?", key) { it.getString(1) }
    }?.let { runCatching { json.decodeFromString(ExplanationDto.serializer(), it) }.getOrNull() }

    private fun java.sql.Connection.usedToday(accountId: String, day: String): Int =
        single("SELECT calls FROM ai_usage WHERE account_id = ? AND usage_day = ?", accountId, day) { it.getInt(1) } ?: 0

    private fun java.sql.Connection.addUsage(accountId: String, day: String) {
        if (update("UPDATE ai_usage SET calls = calls + 1 WHERE account_id = ? AND usage_day = ?", accountId, day) == 0)
            update("INSERT INTO ai_usage (account_id, usage_day, calls) VALUES (?, ?, 1)", accountId, day)
    }

    /** 模型引用的条目的来源；没有引用时取检索到的前两条。本应用说明排在权威来源之后。 */
    private fun sourcesFor(refs: List<String>, chunks: List<KnowledgeBase.Entry>): List<KnowledgeSourceDto> {
        val used = chunks.filter { it.id in refs }.ifEmpty { chunks.take(2) }
        return used.flatMap { it.sourceIds }.distinct()
            .sortedBy { if (it == APP_SOURCE) 1 else 0 }
            .mapNotNull(knowledge.sources::get).take(4)
    }

    companion object {
        /** 改动提示词或输出格式时递增，旧缓存随之失效。 */
        const val PROMPT_VERSION = "p1"
        private const val MAX_ATTEMPTS = 2
        private const val APP_SOURCE = "app-protocol"
        private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}

/** 提示词：系统提示固定不变（利于服务商前缀缓存），用户消息为本次结果 + 检索到的知识。 */
object ExplanationPrompt {
    val SYSTEM = """
        你是眼健康科普助手，为手机眼部自测的结果写通俗解读，读者是没有医学背景的普通人或孩子家长。必须遵守：
        1. 风险等级已经由测试规则确定，你只能解释，不得改变、质疑或重新判断等级；不得下诊断，不说「确诊」「患有」「得了」。
        2. 只依据【本次结果】和【参考知识】写作，不编造信息；只能使用这两部分里出现过的数字。
        3. 口语化、简短、不制造焦虑：全部文字合计不超过 220 字；少用术语，必须用时顺带一句解释；不提具体药品名称和剂量，不推荐具体医院。
        4. 数据无效（UNKNOWN）时：说明没测好的原因和下次怎么测好，不推测眼睛情况。
        5. 结果不确定（UNCERTAIN）时：说明数值在测量误差范围内，建议复测，不往好或坏的方向猜。
        6. 中风险或高风险时：语气平和，明确建议去哪类门诊、多久内就诊；参考知识里的危险信号要提醒。低风险时给保持习惯的建议，并提醒有不适仍应就医。
        7. 建议要具体、可以马上做到，结合年龄段和相关资料。
        只输出一个 JSON 对象，格式：
        {"summary":"一句话总结，不超过40字","meaning":["这个结果说明什么，1到3条，每条不超过45字"],"suggestions":["可以怎么做，1到3条，每条不超过45字"],"seeDoctor":"什么情况下、去哪类门诊看医生，不超过50字","refs":["实际用到的参考知识编号"]}
    """.trimIndent()

    private val KIND_NAMES = mapOf("DRY_EYE" to "干眼自测", "PRESBYOPIA" to "老视（老花眼）自测", "STRABISMUS" to "眼位（斜视）自测")
    private val LEVEL_NAMES = mapOf(
        "LOW" to "低风险", "MEDIUM" to "中风险", "HIGH" to "高风险", "UNCERTAIN" to "结果不确定", "UNKNOWN" to "数据无效，无法判断",
    )
    private val STATUS_NAMES = mapOf("NORMAL" to "正常", "ABNORMAL" to "异常", "UNCERTAIN" to "不确定", "UNAVAILABLE" to "仅供参考")

    fun user(r: ExplanationRequest, chunks: List<KnowledgeBase.Entry>, knowledge: KnowledgeBase): String = buildString {
        appendLine("【本次结果】")
        appendLine("测试：${KIND_NAMES[r.kind] ?: r.kind}")
        appendLine("规则判定等级：${r.riskLevel}（${LEVEL_NAMES[r.riskLevel]}）" + r.badge.takeIf { it.isNotBlank() }?.let { "，界面显示「$it」" }.orEmpty())
        if (r.title.isNotBlank()) appendLine("结论：${r.title}")
        if (r.advice.isNotBlank()) appendLine("规则给出的建议：${r.advice}")
        r.ageGroup?.let { appendLine("年龄段：$it") }
        if (r.metrics.isNotEmpty()) {
            appendLine("关键指标：")
            r.metrics.forEach { m -> appendLine("- ${m.label}：${m.value}" + (m.status?.let { "（${STATUS_NAMES[it] ?: it}）" }.orEmpty())) }
        }
        if (r.evidence.isNotEmpty()) {
            appendLine("判断依据：")
            r.evidence.forEach { e ->
                appendLine("- ${e.title}（${STATUS_NAMES[e.status] ?: e.status}）：${e.summary}")
                e.details.forEach { appendLine("  · $it") }
            }
        }
        if (r.qualityNotes.isNotEmpty()) appendLine("数据可靠性说明：" + r.qualityNotes.joinToString("；"))
        if (r.problems.isNotEmpty()) appendLine("没测好的原因：" + r.problems.joinToString("；"))
        if (r.background.isNotEmpty()) appendLine("相关资料：" + r.background.entries.joinToString("；") { "${it.key}=${it.value}" })
        appendLine()
        appendLine("【参考知识】")
        chunks.forEach { c ->
            val source = c.sourceIds.mapNotNull { knowledge.sources[it]?.publisher }.distinct().joinToString("、")
            appendLine("[${c.id}] ${c.text}（来源：$source）")
        }
    }

    /** 文本中出现的全部数字（含小数），用于校验输出没有编造数值。 */
    fun numbers(text: String): Set<String> = Regex("\\d+(?:\\.\\d+)?").findAll(text).map { normalize(it.value) }.toSet()

    fun normalize(number: String): String = number.trimStart('0').ifEmpty { "0" }.let {
        if ('.' in it) it.trimEnd('0').trimEnd('.') else it
    }.let { if (it.startsWith(".")) "0$it" else it }
}

/** 模型输出的解析与校验。 */
object ExplanationOutput {
    sealed interface Result {
        data class Valid(val summary: String, val meaning: List<String>, val suggestions: List<String>, val seeDoctor: String,
                         val refs: List<String>) : Result
        data class Invalid(val reason: String) : Result
    }

    /** 任何等级都不允许的说法：诊断、绝对化、劝人不就医。 */
    private val FORBIDDEN = listOf("确诊", "诊断为", "患有", "你得了", "您得了", "肯定是", "一定是", "排除了", "无需就医", "不用就医",
        "不需要就医", "不必就医", "不用看医生", "不需要看医生", "治愈")

    /** 与规则等级矛盾的说法。 */
    private val CONTRADICTIONS = mapOf(
        "LOW" to listOf("高度疑似", "高风险", "风险较高", "中风险", "中等风险"),
        "MEDIUM" to listOf("高度疑似", "高风险", "低风险", "一切正常"),
        "HIGH" to listOf("低风险", "一切正常", "不用担心"),
        "UNCERTAIN" to listOf("高度疑似", "高风险", "低风险", "一切正常"),
        "UNKNOWN" to listOf("高度疑似", "高风险", "低风险", "中风险", "一切正常"),
    )

    private val SMALL_NUMBERS = setOf("1", "2", "3")
    private const val MAX_ITEM = 80
    private const val MAX_TOTAL = 400

    fun parse(content: String, request: ExplanationRequest, allowedNumbers: Set<String>): Result {
        val root = runCatching { SyncContract.json.parseToJsonElement(content.trim()).jsonObject }.getOrNull()
            ?: return Result.Invalid("不是 JSON 对象")
        fun text(key: String) = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::clean).orEmpty()
        fun list(key: String) = (root[key] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(::clean) }
            .filter { it.isNotEmpty() }.take(3)
        val summary = text("summary")
        val meaning = list("meaning")
        val suggestions = list("suggestions")
        val seeDoctor = text("seeDoctor")
        val refs = (root["refs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        if (summary.isEmpty()) return Result.Invalid("缺少 summary")
        if (meaning.isEmpty() && suggestions.isEmpty()) return Result.Invalid("meaning 与 suggestions 不能都为空")
        val all = listOf(summary, seeDoctor) + meaning + suggestions
        all.firstOrNull { it.length > MAX_ITEM }?.let { return Result.Invalid("有一条超过 $MAX_ITEM 字，请精简") }
        if (all.sumOf { it.length } > MAX_TOTAL) return Result.Invalid("总字数过多，请控制在 220 字左右")
        val joined = all.joinToString("\n")
        FORBIDDEN.firstOrNull { it in joined }?.let { return Result.Invalid("出现了不允许的说法「$it」") }
        CONTRADICTIONS[request.riskLevel].orEmpty().firstOrNull { it in joined }?.let {
            return Result.Invalid("「$it」与规则等级 ${request.riskLevel} 矛盾")
        }
        // 1–3 常用于「1–2 周」「每天 2 次」等说法，不算编造的测量值
        ExplanationPrompt.numbers(joined).firstOrNull { it !in allowedNumbers && it !in SMALL_NUMBERS }?.let {
            return Result.Invalid("出现了输入中没有的数字 $it")
        }
        return Result.Valid(summary, meaning, suggestions, seeDoctor, refs)
    }

    /** 去掉模型常加的序号、项目符号与多余空白。 */
    private fun clean(text: String): String =
        text.trim().replace(Regex("^(?:[-*•·]|\\d+[.、)）])\\s*"), "").replace(Regex("\\s+"), " ").trim()
}
