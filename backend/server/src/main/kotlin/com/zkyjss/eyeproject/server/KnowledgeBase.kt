package com.zkyjss.eyeproject.server

import com.zkyjss.eyeproject.core.sync.ExplanationRequest
import com.zkyjss.eyeproject.core.sync.KnowledgeSourceDto
import kotlin.math.ln

/**
 * AI 解读的知识库：`resources/knowledge/eye-knowledge.md`（格式见文件头）。
 * 检索用 BM25：中文按相邻两字切词、英文与数字按词，标签权重加倍；只在本病种与通用条目中检索，
 * 并按等级固定带上「就医 / 不确定 / 数据无效」等必要条目。条目少（几十条），每次全量计算即可。
 */
class KnowledgeBase(val version: String, val sources: Map<String, KnowledgeSourceDto>, val entries: List<Entry>) {

    data class Entry(val id: String, val kinds: Set<String>, val sourceIds: List<String>, val tags: List<String>, val text: String) {
        internal val terms: List<String> = tokenize(tags.joinToString(" ")).let { it + it } + tokenize(text)
    }

    private val documentFrequency: Map<String, Int> =
        entries.flatMap { it.terms.toSet() }.groupingBy { it }.eachCount()
    private val averageLength = entries.map { it.terms.size }.average().takeIf { it > 0 } ?: 1.0

    fun entry(id: String): Entry? = entries.firstOrNull { it.id == id }

    /** 按本次结果检索最相关的 [limit] 条。 */
    fun search(request: ExplanationRequest, limit: Int = 6): List<Entry> {
        val candidates = entries.filter { request.kind in it.kinds || GENERAL in it.kinds }
        val pinned = pinnedIds(request).mapNotNull(::entry)
        val query = tokenize(queryText(request))
        val ranked = candidates.filter { it !in pinned }
            .map { it to score(it, query) * (if (request.kind in it.kinds) 1.3 else 1.0) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
        return (pinned + ranked).take(limit)
    }

    /** 各等级必须带上的条目：就医时机、不确定与无效的含义。 */
    private fun pinnedIds(request: ExplanationRequest): List<String> {
        val prefix = when (request.kind) { "DRY_EYE" -> "dry"; "PRESBYOPIA" -> "pres"; else -> "stra" }
        return when (request.riskLevel) {
            "UNKNOWN" -> listOf("gen-invalid", "gen-retest")
            "UNCERTAIN" -> listOf("gen-uncertain", "gen-retest")
            "MEDIUM", "HIGH" -> listOf("$prefix-doctor")
            else -> emptyList()
        }
    }

    private fun queryText(r: ExplanationRequest): String = buildList {
        add(KIND_WORDS[r.kind].orEmpty())
        add(LEVEL_WORDS[r.riskLevel].orEmpty())
        add(r.title)
        r.evidence.filter { it.status != "NORMAL" }.forEach { add(it.title); add(it.summary) }
        r.metrics.forEach { add(it.label) }
        addAll(r.problems)
        addAll(r.qualityNotes.take(2))
        addAll(r.background.values)
        // 资料题目的说法与知识库用词不同（如「电子产品」对「屏幕」），按题目补上检索词
        r.background.forEach { (key, value) -> BACKGROUND_WORDS[key]?.takeIf { value !in NEGATIVE_ANSWERS }?.let(::add) }
        r.ageGroup?.let { group ->
            val start = group.takeWhile { it.isDigit() }.toIntOrNull()
            if (start != null && start < 13) add("儿童 家长")
            if (start != null && start in 18..34 && r.kind == "PRESBYOPIA") add("年轻 调节不足")
        }
    }.joinToString(" ")

    private fun score(entry: Entry, query: List<String>): Double {
        val counts = entry.terms.groupingBy { it }.eachCount()
        val length = entry.terms.size
        return query.toSet().sumOf { term ->
            val tf = counts[term] ?: return@sumOf 0.0
            val df = documentFrequency[term] ?: 0
            val idf = ln(1 + (entries.size - df + 0.5) / (df + 0.5))
            idf * tf * (K1 + 1) / (tf + K1 * (1 - B + B * length / averageLength))
        }
    }

    companion object {
        const val GENERAL = "GENERAL"
        private const val K1 = 1.2
        private const val B = 0.75
        private const val RESOURCE = "/knowledge/eye-knowledge.md"

        private val KIND_WORDS = mapOf(
            "DRY_EYE" to "干眼 泪膜 眨眼",
            "PRESBYOPIA" to "老视 老花 调节 看近",
            "STRABISMUS" to "斜视 眼位 偏斜",
        )
        private val BACKGROUND_WORDS = mapOf(
            "每日电子产品使用" to "屏幕 手机 电脑",
            "近期用眼情况" to "屏幕 用眼 疲劳",
            "佩戴隐形眼镜史" to "隐形眼镜",
            "眼部手术" to "激光手术 手术",
            "近期眼部用药" to "人工泪液 眼药水",
            "日常矫正方式" to "眼镜 老花镜",
            "屈光情况" to "近视 远视 散光",
            "既往眼病" to "弱视 斜视",
        )
        /** 否定或未知的答案不补检索词。 */
        private val NEGATIVE_ANSWERS = setOf("没戴过", "没有", "不清楚", "不戴眼镜", "都没有或不清楚", "不到 2 小时", "比平时少", "差不多")
        private val LEVEL_WORDS = mapOf(
            "LOW" to "习惯 建议 复测",
            "MEDIUM" to "建议 就医 检查",
            "HIGH" to "就医 尽快 检查 危险信号",
            "UNCERTAIN" to "结果不确定 误差 复测",
            "UNKNOWN" to "数据无效 没测好 重测",
        )

        fun load(): KnowledgeBase = parse(
            KnowledgeBase::class.java.getResource(RESOURCE)?.readText(Charsets.UTF_8) ?: error("缺少知识库 $RESOURCE"))

        fun parse(text: String): KnowledgeBase {
            val lines = text.lines()
            val version = lines.firstOrNull { it.startsWith("版本:") }?.substringAfter(':')?.trim() ?: "unknown"
            val sources = linkedMapOf<String, KnowledgeSourceDto>()
            val entries = mutableListOf<Entry>()
            var section = ""
            var current: MutableMap<String, String>? = null
            val body = StringBuilder()
            fun flush() {
                val head = current ?: return
                val id = head.getValue("id")
                val sourceIds = head["来源"].orEmpty().split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
                sourceIds.forEach { require(it in sources) { "知识条目 $id 引用了未登记的来源 $it" } }
                entries += Entry(
                    id = id,
                    kinds = head["病种"].orEmpty().split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                    sourceIds = sourceIds,
                    tags = head["标签"].orEmpty().split(' ').filter { it.isNotBlank() },
                    text = body.toString().lines().joinToString("") { it.trim() }.trim(),
                )
                current = null
                body.clear()
            }
            for (line in lines) {
                when {
                    line.startsWith("## ") -> { flush(); section = line.removePrefix("## ").trim() }
                    section == "来源" && line.startsWith("- ") -> {
                        val parts = line.removePrefix("- ").split('|').map { it.trim() }
                        require(parts.size >= 3) { "来源格式应为「编号 | 名称 | 机构 | 链接」：$line" }
                        sources[parts[0]] = KnowledgeSourceDto(parts[0], parts[1], parts[2], parts.getOrNull(3)?.takeIf { it.isNotEmpty() })
                    }
                    section == "条目" && line.startsWith("### ") -> { flush(); current = mutableMapOf("id" to line.removePrefix("### ").trim()) }
                    current != null && body.isEmpty() && Regex("^(病种|来源|标签):").containsMatchIn(line) ->
                        current!![line.substringBefore(':')] = line.substringAfter(':').trim()
                    current != null -> body.appendLine(line)
                }
            }
            flush()
            require(entries.map { it.id }.toSet().size == entries.size) { "知识条目编号重复" }
            return KnowledgeBase(version, sources, entries)
        }

        /** 中文取相邻两字（单字段落取单字），英文小写成词，数字成词。 */
        fun tokenize(text: String): List<String> = buildList {
            Regex("[\\u4e00-\\u9fff]+|[A-Za-z]+|\\d+(?:\\.\\d+)?").findAll(text).forEach { match ->
                val token = match.value
                if (token[0] in '一'..'鿿') {
                    if (token.length == 1) add(token) else for (i in 0 until token.length - 1) add(token.substring(i, i + 2))
                } else add(token.lowercase())
            }
        }
    }
}
