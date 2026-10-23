package com.zkyjss.eyeproject.server

import com.zkyjss.eyeproject.core.sync.AdviceDto
import com.zkyjss.eyeproject.core.sync.AdviceRequest
import com.zkyjss.eyeproject.core.sync.Advices
import com.zkyjss.eyeproject.core.sync.Advices.bounded
import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.sync.ErrorCodes
import com.zkyjss.eyeproject.core.sync.KnowledgeSourceDto
import com.zkyjss.eyeproject.core.sync.Sha256
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
 * AI 综合建议：用眼负荷等级与主要原因由设备端规则（共用 EyeCareEngine）算出，模型只结合去标识的用眼概况给建议。
 * 流程与 AI 解读一致：校验输入 → 检查「AI 综合建议」同意（与解读分开）→ 查缓存 → 每日额度（与解读共用）→ 检索知识库 →
 * 调用模型 → 校验输出（诊断性措辞、与等级矛盾的说法、输入里没有的数字）→ 不合格重试一次 → 缓存并返回。
 * 建议内容不进日志与审计；缓存只以输入哈希为键，不关联账号。
 */
class AdviceService(
    private val db: Database,
    private val config: ServerConfig,
    private val audit: AuditLog,
    private val knowledge: KnowledgeBase,
    private val llm: LlmClient?,
    private val clock: () -> Long,
) {
    private val log = LoggerFactory.getLogger(AdviceService::class.java)
    private val json = SyncContract.json

    suspend fun advise(accountId: String, input: AdviceRequest, ip: String?): AdviceDto {
        if (input.fatigueLevel !in Advices.LEVELS) throw ApiException.badRequest("用眼负荷等级无效")
        val request = input.bounded()
        db.transaction {
            if (!consentGranted(accountId, ConsentType.AI_ADVICE))
                throw ApiException.consentRequired("使用 AI 综合建议前需要同意把去标识的用眼概况发送给大模型")
        }
        val client = llm ?: throw ApiException(HttpStatusCode.ServiceUnavailable, ErrorCodes.AI_UNAVAILABLE, "服务器尚未开启 AI 综合建议")
        val key = Sha256.hex("$PROMPT_VERSION|${client.model}|${knowledge.version}|${Advices.cacheKey(request)}")
        cached(key)?.let { hit ->
            db.transaction { audit.write(this, AuditAction.AI_ADVICE, accountId, "advice", request.fatigueLevel, "CACHED", ip) }
            return hit
        }
        val day = Instant.ofEpochMilli(clock()).atZone(ZONE).toLocalDate().toString()
        db.transaction {
            if (usedToday(accountId, day) >= config.llmDailyLimit) {
                audit.write(this, AuditAction.AI_ADVICE, accountId, "advice", request.fatigueLevel, "QUOTA", ip)
                throw ApiException(HttpStatusCode.TooManyRequests, ErrorCodes.AI_QUOTA_EXCEEDED, "今天的 AI 次数已用完，明天再来吧")
            }
        }
        val chunks = knowledge.searchAdvice(request)
        val prompt = AdvicePrompt.user(request, chunks, knowledge)
        val allowedNumbers = ExplanationPrompt.numbers(prompt)
        var problem: String? = null
        repeat(MAX_ATTEMPTS) {
            val reply = try {
                client.complete(AdvicePrompt.SYSTEM, prompt + (problem?.let { "\n\n上一次输出不合格：$it。请改正后重新只输出 JSON。" }.orEmpty()))
            } catch (error: LlmException) {
                log.warn("AI 综合建议调用失败：{}", error.message)
                db.transaction { addUsage(accountId, day); audit.write(this, AuditAction.AI_ADVICE, accountId, "advice", request.fatigueLevel, "FAILED", ip) }
                throw ApiException(HttpStatusCode.ServiceUnavailable, ErrorCodes.AI_UNAVAILABLE, "AI 综合建议暂时不可用，请稍后再试")
            }
            log.info("AI 综合建议：模型 {}，输入 {} tokens，输出 {} tokens", client.model, reply.promptTokens, reply.completionTokens)
            db.transaction { addUsage(accountId, day) }
            when (val result = AdviceOutput.parse(reply.content, request, allowedNumbers)) {
                is AdviceOutput.Result.Invalid -> problem = result.reason
                is AdviceOutput.Result.Valid -> {
                    val dto = AdviceDto(
                        summary = result.summary, priorities = result.priorities, habits = result.habits, seeDoctor = result.seeDoctor,
                        sources = sourcesFor(result.refs, chunks), fatigueLevel = request.fatigueLevel, model = client.model, generatedAt = clock(),
                    )
                    db.transaction {
                        if (single("SELECT 1 FROM ai_explanations WHERE cache_key = ?", key) { 1 } == null)
                            update("INSERT INTO ai_explanations (cache_key, output_json, created_at) VALUES (?, ?, ?)",
                                key, json.encodeToString(AdviceDto.serializer(), dto), clock())
                        audit.write(this, AuditAction.AI_ADVICE, accountId, "advice", request.fatigueLevel, "OK", ip)
                    }
                    return dto
                }
            }
        }
        log.warn("AI 综合建议输出两次不合格：{}", problem)
        db.transaction { audit.write(this, AuditAction.AI_ADVICE, accountId, "advice", request.fatigueLevel, "INVALID_OUTPUT", ip) }
        throw ApiException(HttpStatusCode.BadGateway, ErrorCodes.AI_UNAVAILABLE, "这次没能生成合格的建议，请稍后再试")
    }

    private suspend fun cached(key: String): AdviceDto? = db.transaction {
        single("SELECT output_json FROM ai_explanations WHERE cache_key = ?", key) { it.getString(1) }
    }?.let { runCatching { json.decodeFromString(AdviceDto.serializer(), it) }.getOrNull() }

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
            .sortedBy { if (it == "app-protocol") 1 else 0 }
            .mapNotNull(knowledge.sources::get).take(4)
    }

    companion object {
        /** 改动提示词或输出格式时递增，旧缓存随之失效（与解读的缓存键以前缀区分）。 */
        const val PROMPT_VERSION = "advice-a1"
        private const val MAX_ATTEMPTS = 2
        private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}

/** 提示词：系统提示固定不变（利于服务商前缀缓存），用户消息为用眼概况 + 检索到的知识。 */
object AdvicePrompt {
    val SYSTEM = """
        你是眼健康科普助手，根据手机记录的用眼概况，为普通人写「今天的用眼综合建议」。必须遵守：
        1. 用眼负荷等级与主要原因已由应用规则算出，你只能据此给建议，不得改变或质疑等级；不得下诊断，不说「确诊」「患有」「得了」。
        2. 只依据【用眼概况】和【参考知识】写作，不编造信息；只能使用这两部分里出现过的数字。
        3. 口语化、简短、不制造焦虑：全部文字合计不超过 220 字；建议具体、今天就能做到，优先针对主要原因，结合年龄段与职业。
        4. 自测为中风险或高风险，或今天有眼痛、突然重影或看不清时，明确建议去哪类门诊、多久内就诊；否则提醒有不适仍应就医。
        5. 不提具体药品名称和剂量，不推荐具体医院、产品或品牌。
        只输出一个 JSON 对象，格式：
        {"summary":"一句话总结，不超过40字","priorities":["今天最该先做的事，1到3条，每条不超过45字"],"habits":["值得养成的习惯，1到3条，每条不超过45字"],"seeDoctor":"什么情况下、去哪类门诊看医生，不超过50字","refs":["实际用到的参考知识编号"]}
    """.trimIndent()

    private val LEVEL_NAMES = mapOf("LOW" to "轻松", "MEDIUM" to "留意", "HIGH" to "偏累", "UNKNOWN" to "数据不足，无法评估")
    private val KIND_NAMES = mapOf("DRY_EYE" to "干眼自测", "PRESBYOPIA" to "老视自测", "STRABISMUS" to "眼位自测")
    private val RISK_NAMES = mapOf("LOW" to "低风险", "MEDIUM" to "中风险", "HIGH" to "高风险", "UNCERTAIN" to "结果不确定", "UNKNOWN" to "数据无效")

    fun user(r: AdviceRequest, chunks: List<KnowledgeBase.Entry>, knowledge: KnowledgeBase): String = buildString {
        appendLine("【用眼概况】")
        appendLine("今天的用眼负荷：${LEVEL_NAMES[r.fatigueLevel] ?: r.fatigueLevel}" + r.fatigueScore?.let { "，$it 分（满分 100，越高越累）" }.orEmpty())
        if (r.factors.isNotEmpty()) appendLine("主要原因：" + r.factors.joinToString("；"))
        r.ageGroup?.let { appendLine("年龄段：$it") }
        r.occupation?.let { appendLine("职业：$it") }
        r.visionCorrection?.let { appendLine("戴镜方式：$it") }
        r.eyeHistory?.let { appendLine("既往眼病：$it") }
        val usage = listOfNotNull(
            r.averageScreenMinutes?.let { "日均用屏 $it 分钟" },
            r.averageLongestSessionMinutes?.let { "平均每天最长连续用眼 $it 分钟" },
            r.nightUseDays?.let { "晚上 22 点后用屏超过 1 小时的有 $it 天" },
            r.averageBreaks?.let { "平均每天休息 $it 次" },
        )
        if (usage.isNotEmpty()) appendLine("近 7 天：" + usage.joinToString("；"))
        val behavior = listOfNotNull(
            r.blinksPerMinute?.let { "眨眼约每分钟 $it 次" },
            r.viewingDistanceCm?.let { "眼睛离屏幕约 $it 厘米" },
            r.headOffsetDeg?.let { "头部偏离正对屏幕约 $it 度" },
        )
        if (behavior.isNotEmpty()) appendLine("实时检测：" + behavior.joinToString("；"))
        val environment = listOfNotNull(r.ambientLight?.let { "环境光$it" }, r.screenBrightness?.let { "屏幕亮度$it" })
        if (environment.isNotEmpty()) appendLine("环境：" + environment.joinToString("；"))
        if (r.tests.isNotEmpty()) appendLine("最近的自测：" + r.tests.joinToString("；") { "${KIND_NAMES[it.kind] ?: it.kind}${RISK_NAMES[it.riskLevel] ?: it.riskLevel}" })
        appendLine("今天的感受：" + r.symptoms.joinToString("、").ifEmpty { "没有记录不适" })
        appendLine()
        appendLine("【参考知识】")
        chunks.forEach { c ->
            val source = c.sourceIds.mapNotNull { knowledge.sources[it]?.publisher }.distinct().joinToString("、")
            appendLine("[${c.id}] ${c.text}（来源：$source）")
        }
    }
}

/** 模型输出的解析与校验。 */
object AdviceOutput {
    sealed interface Result {
        data class Valid(val summary: String, val priorities: List<String>, val habits: List<String>, val seeDoctor: String,
                         val refs: List<String>) : Result
        data class Invalid(val reason: String) : Result
    }

    /** 与负荷等级矛盾的说法。 */
    private val CONTRADICTIONS = mapOf(
        "LOW" to listOf("负荷很高", "非常疲劳", "严重疲劳", "偏累"),
        "MEDIUM" to listOf("非常轻松", "完全没问题", "严重疲劳"),
        "HIGH" to listOf("很轻松", "非常轻松", "一切正常", "不用担心"),
        "UNKNOWN" to listOf("很轻松", "非常轻松", "偏累", "一切正常"),
    )
    private val SMALL_NUMBERS = setOf("1", "2", "3")
    private const val MAX_ITEM = 80
    private const val MAX_TOTAL = 400

    fun parse(content: String, request: AdviceRequest, allowedNumbers: Set<String>): Result {
        val root = runCatching { SyncContract.json.parseToJsonElement(content.trim()).jsonObject }.getOrNull()
            ?: return Result.Invalid("不是 JSON 对象")
        fun text(key: String) = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::clean).orEmpty()
        fun list(key: String) = (root[key] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(::clean) }
            .filter { it.isNotEmpty() }.take(3)
        val summary = text("summary")
        val priorities = list("priorities")
        val habits = list("habits")
        val seeDoctor = text("seeDoctor")
        val refs = (root["refs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        if (summary.isEmpty()) return Result.Invalid("缺少 summary")
        if (priorities.isEmpty()) return Result.Invalid("priorities 不能为空")
        val all = listOf(summary, seeDoctor) + priorities + habits
        all.firstOrNull { it.length > MAX_ITEM }?.let { return Result.Invalid("有一条超过 $MAX_ITEM 字，请精简") }
        if (all.sumOf { it.length } > MAX_TOTAL) return Result.Invalid("总字数过多，请控制在 220 字左右")
        val joined = all.joinToString("\n")
        ExplanationOutput.FORBIDDEN.firstOrNull { it in joined }?.let { return Result.Invalid("出现了不允许的说法「$it」") }
        CONTRADICTIONS[request.fatigueLevel].orEmpty().firstOrNull { it in joined }?.let {
            return Result.Invalid("「$it」与用眼负荷等级 ${request.fatigueLevel} 矛盾")
        }
        ExplanationPrompt.numbers(joined).firstOrNull { it !in allowedNumbers && it !in SMALL_NUMBERS }?.let {
            return Result.Invalid("出现了输入中没有的数字 $it")
        }
        return Result.Valid(summary, priorities, habits, seeDoctor, refs)
    }

    private fun clean(text: String): String =
        text.trim().replace(Regex("^(?:[-*•·]|\\d+[.、)）])\\s*"), "").replace(Regex("\\s+"), " ").trim()
}
