package com.zkyjss.eyeproject.feature.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 「我的 → 本机测试记录」的一行：两端共用同一份解析与文案。
 * [level] 为 LOW / MEDIUM / HIGH / UNCERTAIN / UNKNOWN，供界面选择颜色；[lines] 为点开后显示的要点。
 */
data class LocalRecordEntry(
    val fileName: String,
    val kind: String,
    val title: String,
    val testedAtMs: Long?,
    val level: String,
    val badge: String,
    val lines: List<String>,
)

object LocalRecordCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    /** 记录 JSON 无法识别时返回 null（列表中跳过）。 */
    fun entry(fileName: String, record: String): LocalRecordEntry? {
        val root = runCatching { json.parseToJsonElement(record) as? JsonObject }.getOrNull() ?: return null
        val kind = root.string("kind") ?: return null
        val testedAt = fileName.substringAfterLast('_').removeSuffix(".json").toLongOrNull()
        return when (kind) {
            "DRY_EYE" -> {
                val valid = (root["valid"] as? JsonPrimitive)?.booleanOrNull ?: false
                val level = if (valid) root.string("riskLevel") ?: "UNKNOWN" else "UNKNOWN"
                val lines = if (!valid) root.strings("validityReasons").ifEmpty { listOf("数据无效，请重新测试") }
                    else root.strings("abnormal").ifEmpty { listOf("各项指标未见异常") } +
                        (root["evidence"] as? JsonArray).orEmpty().flatMap { (it as? JsonObject)?.strings("details").orEmpty() }
                LocalRecordEntry(fileName, kind, "干眼自测", testedAt, level, badge(kind, level), lines)
            }
            "PRESBYOPIA", "STRABISMUS" -> {
                val assessment = root["assessment"] as? JsonObject
                val level = assessment?.string("riskLevel") ?: "UNKNOWN"
                val lines = root.strings("summary").ifEmpty { assessment?.strings("findings").orEmpty() }
                LocalRecordEntry(fileName, kind, if (kind == "PRESBYOPIA") "老视自测" else "眼位自测",
                    testedAt, level, badge(kind, level), lines)
            }
            else -> null
        }
    }

    /** 苹果端用：与 [entry] 相同内容的 JSON 字符串；无法识别时返回 null。 */
    fun entryJson(fileName: String, record: String): String? = entry(fileName, record)?.let { e ->
        RecordObject().apply {
            put("fileName", e.fileName); put("kind", e.kind); put("title", e.title)
            put("testedAtMs", e.testedAtMs?.toDouble() ?: RecordObject.NULL)
            put("level", e.level); put("badge", e.badge); put("lines", RecordArray(e.lines))
        }.toString()
    }

    /** 与结果页徽标同一说法。 */
    private fun badge(kind: String, level: String): String = when (level) {
        "UNKNOWN" -> "需要重测"
        "UNCERTAIN" -> "结果不确定"
        else -> {
            val label = runCatching { ScreeningRiskLevel.valueOf(level).label }.getOrDefault(level)
            when (kind) {
                "DRY_EYE" -> "干眼$label"
                "PRESBYOPIA" -> "老视$label"
                else -> when (level) { "LOW" -> "未见异常"; "MEDIUM" -> "可疑"; else -> "高度疑似" }
            }
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
}
