package com.zkyjss.eyeproject.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** 数据质量归一：老视/斜视的「不足」与干眼的 valid=false 都是 INSUFFICIENT，不能解释为低风险。 */
@Serializable
enum class RecordQuality { SUFFICIENT, INSUFFICIENT }

/**
 * 从测试记录 JSON 抽取的可索引摘要，用于列表、趋势和对比；原文另行加密保存。
 * 记录格式由 shared/screening 定义：老视/斜视写在 `assessment` 下，干眼写在顶层。
 */
data class RecordSummary(
    val kind: String,
    val protocolVersion: Int?,
    val assessmentVersion: String?,
    val riskLevel: String,
    val quality: RecordQuality,
    val features: Map<String, Double?>,
    /** 问卷「年龄」；用于判断是否为未成年人的记录（需监护人同意）。 */
    val ageYears: Int? = null,
)

class InvalidRecordException(message: String) : IllegalArgumentException(message)

object RecordSummaries {
    val KINDS = setOf("DRY_EYE", "PRESBYOPIA", "STRABISMUS")
    /** UNCERTAIN：规则 rules-2 起的「结果不确定」（数据有效但指标在测量误差内），不能当作低风险。 */
    val RISK_LEVELS = setOf("LOW", "MEDIUM", "HIGH", "UNCERTAIN", "UNKNOWN")
    private const val MAX_FEATURES = 64

    fun parse(payload: String): RecordSummary {
        val root = runCatching { SyncContract.json.parseToJsonElement(payload).jsonObject }
            .getOrElse { throw InvalidRecordException("记录不是 JSON 对象") }
        val kind = root.string("kind") ?: throw InvalidRecordException("记录缺少 kind")
        if (kind !in KINDS) throw InvalidRecordException("未知的测试类型：$kind")
        val protocol = (root["protocolVersion"] as? JsonPrimitive)?.intOrNull
        val age = (root["profile"] as? JsonObject)?.string("年龄")?.trim()?.toIntOrNull()?.takeIf { it in 0..130 }
        val summary = if (kind == "DRY_EYE") {
            val valid = (root["valid"] as? JsonPrimitive)?.booleanOrNull
                ?: throw InvalidRecordException("干眼记录缺少 valid")
            summary(kind, protocol, root.string("assessmentVersion"), root.string("riskLevel"),
                if (valid) RecordQuality.SUFFICIENT else RecordQuality.INSUFFICIENT, root["features"])
        } else {
            val assessment = root["assessment"] as? JsonObject ?: throw InvalidRecordException("记录缺少 assessment")
            val quality = assessment.string("quality") ?: throw InvalidRecordException("记录缺少 quality")
            summary(kind, protocol, assessment.string("modelVersion"), assessment.string("riskLevel"),
                if (quality == "不足") RecordQuality.INSUFFICIENT else RecordQuality.SUFFICIENT, assessment["features"])
        }
        return summary.copy(ageYears = age)
    }

    /** 未满 14 周岁属于需监护人同意的范围（《个人信息保护法》第 31 条）。 */
    fun isMinor(ageYears: Int?): Boolean = ageYears != null && ageYears < MINOR_AGE_YEARS
    const val MINOR_AGE_YEARS = 14

    /** 两次同病种记录的特征差；只比较双方都有的数值键，按当前记录的键顺序输出。 */
    fun compare(previous: Map<String, Double?>, current: Map<String, Double?>): List<FeatureDelta> =
        current.keys.filter { it in previous }.map { key ->
            val before = previous[key]
            val now = current[key]
            FeatureDelta(key, before, now, if (before != null && now != null) now - before else null)
        }

    private fun summary(
        kind: String, protocol: Int?, version: String?, risk: String?, quality: RecordQuality, features: JsonElement?,
    ): RecordSummary {
        if (risk == null || risk !in RISK_LEVELS) throw InvalidRecordException("风险等级无效：$risk")
        // 质量不足时设备端已拒判；服务端同样不接受「不足却给出风险」的记录。
        if (quality == RecordQuality.INSUFFICIENT && risk != "UNKNOWN")
            throw InvalidRecordException("质量不足的记录风险等级必须为 UNKNOWN")
        val table = when (features) {
            null, JsonNull -> emptyMap()
            is JsonObject -> features.entries.take(MAX_FEATURES).associate { (key, value) ->
                key to (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull?.takeIf(Double::isFinite)
            }
            else -> throw InvalidRecordException("features 必须是对象")
        }
        return RecordSummary(kind, protocol, version, risk, quality, table)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

