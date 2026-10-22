package com.zkyjss.eyeproject.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/*
 * AI 解读（设计文档 §5.4）：风险等级由设备端规则给出，大模型只负责用通俗的话解释并给出可做的建议。
 * 输入只含规则已给出的结构化结果与少量去标识背景（年龄段、几项与病种相关的选项答案），
 * 不含姓名、账号、成员编号、问卷全文或逐帧数据。
 */

@Serializable
data class ExplanationItem(val label: String, val value: String, val status: String? = null)

@Serializable
data class ExplanationEvidence(val title: String, val status: String, val summary: String, val details: List<String> = emptyList())

@Serializable
data class ExplanationRequest(
    val kind: String,
    /** 规则给出的等级（LOW / MEDIUM / HIGH / UNCERTAIN / UNKNOWN），解读不得改变。 */
    val riskLevel: String,
    val badge: String,
    val title: String,
    val advice: String = "",
    val metrics: List<ExplanationItem> = emptyList(),
    val evidence: List<ExplanationEvidence> = emptyList(),
    val qualityNotes: List<String> = emptyList(),
    /** 数据无效时「没测好的原因」。 */
    val problems: List<String> = emptyList(),
    /** 年龄段（如「40–49 岁」），不传具体年龄。 */
    val ageGroup: String? = null,
    /** 与病种相关的少量资料答案（键为题目，值为所选项）。 */
    val background: Map<String, String> = emptyMap(),
    val assessmentVersion: String? = null,
)

@Serializable
data class KnowledgeSourceDto(val id: String, val title: String, val publisher: String, val url: String? = null)

@Serializable
data class ExplanationDto(
    /** 一句话总结。 */
    val summary: String,
    /** 结果说明（1–3 条）。 */
    val meaning: List<String>,
    /** 可以怎么做（1–3 条）。 */
    val suggestions: List<String>,
    /** 什么情况下去看眼科。 */
    val seeDoctor: String,
    val sources: List<KnowledgeSourceDto> = emptyList(),
    /** 解读所依据的规则等级（原样回传，便于界面核对）。 */
    val riskLevel: String,
    val model: String,
    val generatedAt: Long,
    val disclaimer: String = Explanations.DISCLAIMER,
)

object Explanations {
    const val DISCLAIMER = "由 AI 根据本次规则判定结果生成，只作通俗解释，不改变风险等级，也不能代替眼科检查。"
    /** 与解读请求一起发送的去标识说明（同意文案，两端一致）。 */
    const val CONSENT_TEXT = "生成解读时，会把本次结果的等级、指标、判断依据、年龄段和几项相关的资料选项发送到云端服务，" +
        "由第三方大模型（DeepSeek）生成通俗解读；不发送姓名、账号、照片或逐帧数据。可随时在「我的 → 云端同步」中关闭。"

    const val MAX_ITEMS = 8
    const val MAX_DETAILS = 6
    const val MAX_TEXT = 200
    private const val MAX_BACKGROUND = 8

    /** 每个病种送给模型的资料题目（只取所选项，不取自由填写）。 */
    private val BACKGROUND_KEYS = mapOf(
        "DRY_EYE" to listOf("干眼症状", "症状频率", "佩戴隐形眼镜史", "每日电子产品使用", "睡眠时长", "近期用眼情况", "近期眼部用药", "眼部手术"),
        "PRESBYOPIA" to listOf("日常矫正方式", "屈光情况", "每日电子产品使用", "职业"),
        "STRABISMUS" to listOf("既往眼病", "眼部手术", "协助测试者"),
    )

    /**
     * 从本机测试记录（shared/screening 写出的 JSON）抽取解读输入。新记录带结果页快照 `report`，
     * 旧记录退回规则写在记录里的依据。无法识别时返回 null。
     */
    fun fromRecord(payload: String): ExplanationRequest? {
        val root = runCatching { SyncContract.json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val kind = root.string("kind")?.takeIf { it in RecordSummaries.KINDS } ?: return null
        val profile = root["profile"] as? JsonObject
        val age = profile?.string("年龄")?.trim()?.toIntOrNull()
        val background = BACKGROUND_KEYS[kind].orEmpty().mapNotNull { key ->
            profile?.string(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { key to it.take(60) }
        }.take(MAX_BACKGROUND).toMap()
        val snapshot = root["report"] as? JsonObject
        val request = if (snapshot != null) ExplanationRequest(
            kind = kind,
            riskLevel = snapshot.string("level") ?: return null,
            badge = snapshot.string("badge").orEmpty(),
            title = snapshot.string("title").orEmpty(),
            advice = snapshot.string("advice").orEmpty(),
            metrics = snapshot.objects("metrics").map { ExplanationItem(it.string("label").orEmpty(), it.string("value").orEmpty(), it.string("status")) },
            evidence = snapshot.objects("evidence").map(::evidence),
            qualityNotes = snapshot.strings("qualityNotes"),
            problems = snapshot.strings("problems"),
            assessmentVersion = snapshot.string("version"),
        ) else legacy(root, kind) ?: return null
        return request.copy(ageGroup = ageGroup(age), background = background).bounded()
    }

    /** 旧记录：没有结果页快照，用记录里的规则等级与依据。 */
    private fun legacy(root: JsonObject, kind: String): ExplanationRequest? {
        val source = if (kind == "DRY_EYE") root else root["assessment"] as? JsonObject ?: return null
        val valid = if (kind == "DRY_EYE") (root["valid"] as? JsonPrimitive)?.booleanOrNull == true else source.string("quality") != "不足"
        val level = if (valid) source.string("riskLevel") ?: return null else "UNKNOWN"
        return ExplanationRequest(
            kind = kind, riskLevel = level, badge = "", title = source.string("category").orEmpty(),
            advice = root.string("advice").orEmpty(),
            evidence = source.objects("evidence").map(::evidence),
            problems = if (valid) emptyList() else root.strings("validityReasons").ifEmpty { source.strings("findings") },
            assessmentVersion = root.string("assessmentVersion") ?: source.string("modelVersion"),
        )
    }

    /** 年龄段：儿童按学龄分段（斜视判断与之相关），成人按十岁分段。 */
    fun ageGroup(age: Int?): String? = when {
        age == null || age !in 0..120 -> null
        age < 3 -> "3 岁以下"
        age <= 5 -> "3–5 岁"
        age <= 8 -> "6–8 岁"
        age <= 12 -> "9–12 岁"
        age <= 17 -> "13–17 岁"
        age < 70 -> "${age / 10 * 10}–${age / 10 * 10 + 9} 岁"
        else -> "70 岁及以上"
    }

    /** 同样的输入得到同样的解读：两端与服务端都以此作为缓存键。 */
    fun cacheKey(request: ExplanationRequest): String =
        Sha256.hex(SyncContract.json.encodeToString(ExplanationRequest.serializer(), request))

    /** 限制条数与长度，控制费用并与服务端校验一致。 */
    fun ExplanationRequest.bounded(): ExplanationRequest = copy(
        badge = badge.take(MAX_TEXT), title = title.take(MAX_TEXT), advice = advice.take(MAX_TEXT),
        metrics = metrics.take(MAX_ITEMS).map { ExplanationItem(it.label.take(MAX_TEXT), it.value.take(MAX_TEXT), it.status?.take(16)) },
        evidence = evidence.take(MAX_ITEMS).map { e ->
            ExplanationEvidence(e.title.take(MAX_TEXT), e.status.take(16), e.summary.take(MAX_TEXT), e.details.take(MAX_DETAILS).map { it.take(MAX_TEXT) })
        },
        qualityNotes = qualityNotes.take(MAX_ITEMS).map { it.take(MAX_TEXT) },
        problems = problems.take(MAX_ITEMS).map { it.take(MAX_TEXT) },
        background = background.entries.take(MAX_BACKGROUND).associate { (k, v) -> k.take(32) to v.take(60) },
        assessmentVersion = assessmentVersion?.take(64),
    )

    private fun evidence(o: JsonObject) =
        ExplanationEvidence(o.string("title").orEmpty(), o.string("status") ?: "UNAVAILABLE", o.string("summary").orEmpty(), o.strings("details"))

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    private fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
}
