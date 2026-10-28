package com.zkyjss.eyeproject.feature.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

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
    val version: String = "未记录（旧版本）",
    val subjectLabel: String = "未选择成员",
    val advice: String = "",
    val qualityNotes: List<String> = emptyList(),
    val sections: List<ResultSection> = emptyList(),
    /** 被测者关系代码（SELF / CHILD …）；旧记录未选成员时为 null */
    val subjectRelation: String? = null,
) {
    /** 只转写保存时的内容，不用当前评估规则重算历史结论。时间由平台按用户时区格式化。 */
    fun reportText(testedAtLabel: String): String = buildString {
        appendLine(title)
        appendLine("测试时间：$testedAtLabel")
        appendLine("被测者：$subjectLabel")
        appendLine("当时结论：$badge")
        appendLine("评估版本：$version")
        if (advice.isNotBlank()) appendLine("当时建议：$advice")
        appendLine()
        lines.forEach { appendLine("· $it") }
        if (qualityNotes.isNotEmpty()) {
            appendLine(); appendLine("数据可靠性说明")
            qualityNotes.forEach { appendLine("· $it") }
        }
        sections.forEach { section ->
            appendLine(); appendLine(section.title)
            section.rows.forEach { appendLine(if (it.label.isBlank()) it.value else "${it.label}：${it.value}") }
        }
        appendLine(); appendLine(ResultReports.FOOTNOTE)
        appendLine("原始记录：$fileName")
    }
}

object LocalRecordCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    /** 记录 JSON 无法识别时返回 null（列表中跳过）。 */
    fun entry(fileName: String, record: String): LocalRecordEntry? {
        val root = runCatching { json.parseToJsonElement(record) as? JsonObject }.getOrNull() ?: return null
        val kind = root.string("kind") ?: return null
        val testedAt = fileName.substringAfterLast('_').removeSuffix(".json").toLongOrNull()
        val entry = when (kind) {
            "COLOR_VISION" -> {
                val valid = (root["valid"] as? JsonPrimitive)?.booleanOrNull ?: false
                val level = if (valid) root.string("riskLevel") ?: "UNKNOWN" else "UNKNOWN"
                val lines = if (!valid) root.strings("validityReasons").ifEmpty { listOf("数据无效，请重新测试") }
                    else (root["evidence"] as? JsonArray).orEmpty().mapNotNull { e ->
                        (e as? JsonObject)?.let { "${it.string("title").orEmpty()}：${it.string("summary").orEmpty()}" } }
                LocalRecordEntry(fileName, kind, "色觉自测", testedAt, level, badge(kind, level), lines)
            }
            "DRY_EYE" -> {
                val valid = (root["valid"] as? JsonPrimitive)?.booleanOrNull ?: false
                val level = if (valid) root.string("riskLevel") ?: "UNKNOWN" else "UNKNOWN"
                val lines = if (!valid) root.strings("validityReasons").ifEmpty { listOf("数据无效，请重新测试") }
                    else root.strings("abnormal").ifEmpty { listOf(when (level) {
                        "LOW" -> "本次未见明显异常"
                        "UNCERTAIN" -> "部分指标尚不能确定，请查看判断依据"
                        else -> "请结合当时保存的判断依据查看"
                    }) } +
                        (root["evidence"] as? JsonArray).orEmpty().flatMap { (it as? JsonObject)?.strings("details").orEmpty() }
                LocalRecordEntry(fileName, kind, "干眼自测", testedAt, level, badge(kind, level), lines)
            }
            "PRESBYOPIA", "STRABISMUS" -> {
                val assessment = root["assessment"] as? JsonObject
                val level = if (assessment?.string("quality") == "不足") "UNKNOWN" else assessment?.string("riskLevel") ?: "UNKNOWN"
                val lines = root.strings("summary").ifEmpty { assessment?.strings("findings").orEmpty() }
                LocalRecordEntry(fileName, kind, if (kind == "PRESBYOPIA") "老视自测" else "眼位自测",
                    testedAt, level, badge(kind, level), lines)
            }
            else -> null
        } ?: return null
        val snapshot = root["report"] as? JsonObject
        val assessment = root["assessment"] as? JsonObject
        val subject = root["subject"] as? JsonObject
        val savedSections = snapshot?.objects("sections").orEmpty().map { section ->
            ResultSection(section.string("title").orEmpty(), section.objects("rows").map {
                ResultRow(it.string("label").orEmpty(), it.string("value") ?: "未记录")
            })
        }
        val notes = if (snapshot != null) snapshot.strings("qualityNotes").ifEmpty { snapshot.strings("problems") }
        else if (entry.level == "UNKNOWN") entry.lines.ifEmpty { listOf("当时记录的数据不足，请重新测试。") }
        else listOf("旧记录未保存分项可靠性说明，以下仅显示当时记录的数据。")
        val sections = buildList {
            if (snapshot != null && entry.level != "UNKNOWN") {
                val metrics = snapshot.objects("metrics").map { ResultRow(it.string("label").orEmpty(), it.string("value") ?: "未记录") }
                if (metrics.isNotEmpty()) add(ResultSection("关键指标", metrics))
            }
            val evidence = (snapshot ?: if (kind == "DRY_EYE" || kind == "COLOR_VISION") root else assessment)?.objects("evidence").orEmpty()
            if (evidence.isNotEmpty()) add(ResultSection("当时的判断依据", evidence.flatMap { e ->
                listOf(ResultRow(e.string("title").orEmpty(), e.string("summary").orEmpty())) + e.strings("details").map { ResultRow("", it) }
            }))
            addAll(savedSections.ifEmpty { legacySections(root, kind) })
            add(ResultSection("记录与设备", listOf(
                ResultRow("测试是否完成", root.booleanText("completed")),
                ResultRow("测试平台", root.string("platform") ?: "未记录"),
                ResultRow("屏幕像素密度", root.number("displayXdpi")?.let { "${decimalString(it, 1)} dpi" } ?: "未记录"),
                ResultRow("成员编号", subject?.string("id") ?: "未记录"),
            )))
        }
        return entry.copy(
            version = snapshot?.string("version") ?: root.string("assessmentVersion") ?: assessment?.string("modelVersion") ?: "未记录（旧版本）",
            subjectLabel = subject?.string("name") ?: subject?.string("relation")?.let(SubjectProfiles::relationLabel) ?: "未选择成员",
            advice = snapshot?.string("advice") ?: root.string("advice").orEmpty(),
            qualityNotes = notes, sections = sections, subjectRelation = subject?.string("relation"),
        )
    }

    /** 苹果端用：与 [entry] 相同内容的 JSON 字符串；无法识别时返回 null。 */
    fun entryJson(fileName: String, record: String): String? = entry(fileName, record)?.let { e ->
        RecordObject().apply {
            put("fileName", e.fileName); put("kind", e.kind); put("title", e.title)
            put("testedAtMs", e.testedAtMs?.toDouble() ?: RecordObject.NULL)
            put("level", e.level); put("badge", e.badge); put("lines", RecordArray(e.lines))
            put("version", e.version); put("subjectLabel", e.subjectLabel); put("advice", e.advice)
            put("qualityNotes", RecordArray(e.qualityNotes))
            put("sections", RecordArray(e.sections.map { s -> RecordObject().apply {
                put("title", s.title); put("rows", RecordArray(s.rows.map { r -> RecordObject().apply { put("label", r.label); put("value", r.value) } }))
            } }))
        }.toString()
    }

    /** 为旧记录补齐可读明细，只读已保存的数值和问卷，不推算缺失值。 */
    private fun legacySections(root: JsonObject, kind: String): List<ResultSection> = buildList {
        val assessment = root["assessment"] as? JsonObject
        val features = (if (kind == "DRY_EYE") root else assessment)?.get("features") as? JsonObject
        val labels = if (kind == "DRY_EYE") listOf(
            "blink_rate_per_min" to "自然眨眼频率（次/分）", "open_longest_sec" to "最长持续睁眼（秒）",
            "accuracy_natural" to "自然眨眼段答对比例", "accuracy_open" to "睁眼段答对比例",
            "mean_rt_ms" to "正确作答平均反应（毫秒）", "valid_frame_ratio" to "双眼可见比例",
            "distance_in_range_ratio" to "距离达标比例", "head_turn_ratio" to "头部偏转比例", "analysis_fps" to "分析帧率（帧/秒）",
        ) else if (kind == "PRESBYOPIA") listOf(
            "near_right_decimal" to "右眼近视力", "near_left_decimal" to "左眼近视力", "near_both_decimal" to "双眼近视力",
            "amplitude_d" to "调节幅度（D）", "near_point_cm" to "近点距离（cm）", "quality_rate" to "有效数据比例",
        ) else listOf("fixation_hde_prism" to "水平偏斜（Δ）", "fixation_vde_prism" to "垂直偏斜（Δ）",
            "cover_refix_median_prism" to "遮盖再注视偏移（Δ）", "bsi" to "双眼对称性指数", "quality_rate" to "有效数据比例")
        add(ResultSection("当时保存的指标", labels.map { (key, label) -> ResultRow(label, features?.number(key)?.let { decimalString(it, 2) } ?: "未记录") }))
        val trials = root.objects("trials")
        if (trials.isNotEmpty()) add(ResultSection("作答记录", listOf(
            ResultRow("总题数", "${trials.size} 题"),
            ResultRow("答对", "${trials.count { (it["correct"] as? JsonPrimitive)?.booleanOrNull == true }} 题"),
        )))
        val profile = root["profile"] as? JsonObject
        if (profile != null && profile.isNotEmpty()) add(ResultSection("测试时的健康资料", profile.entries.map { (key, value) ->
            ResultRow(key, (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "未记录")
        }))
    }

    private fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite)
    private fun JsonObject.booleanText(key: String): String = when ((this[key] as? JsonPrimitive)?.booleanOrNull) { true -> "已完成"; false -> "未完成"; null -> "未记录" }

    /** 与结果页徽标同一说法。 */
    private fun badge(kind: String, level: String): String = when (level) {
        "UNKNOWN" -> "需要重测"
        "UNCERTAIN" -> "结果不确定"
        else -> {
            val label = runCatching { ScreeningRiskLevel.valueOf(level).label }.getOrDefault(level)
            when (kind) {
                "DRY_EYE" -> "干眼$label"
                "PRESBYOPIA" -> "老视$label"
                "COLOR_VISION" -> when (level) { "LOW" -> "色觉未见异常"; "MEDIUM" -> "色觉可疑"; else -> "色觉异常可能" }
                else -> when (level) { "LOW" -> "未见异常"; "MEDIUM" -> "可疑"; else -> "高度疑似" }
            }
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
}
