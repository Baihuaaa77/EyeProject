package com.zkyjss.eyeproject.feature.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 测前快问：每次测试前现问的两三个问题——与这项测试有关、而且会随时间变化（这几天的症状、正在用的眼药水、
 * 这次谁来帮忙）。年龄、性别、手术史等长期资料在「我的 → 家庭成员」维护，不再重复问；
 * 没有这类问题的测试（老视：戴镜方式在开始页单独选）不出现这一步。
 * 答案只写入本次测试的记录，不改长期资料；字段键沿用原资料（评估规则按键读取）。两端共用。
 */
object PreTestCheck {
    private val dryEyeSymptoms = Question("干眼症状", "这几天眼睛有这些感觉吗？",
        listOf("眼睛发干、发涩", "像进了沙子", "有烧灼感", "看东西模糊，时好时坏", "怕光", "容易累", DRY_EYE_NO_SYMPTOM), multiple = true)
    private val symptomFrequency = Question("症状频率", "这些感觉多久出现一次？", listOf("偶尔", "有时", "经常", "几乎总是"),
        visibleWhen = { f -> f["干眼症状"].orEmpty().split("、").any { it.isNotBlank() && it != DRY_EYE_NO_SYMPTOM } })
    private val eyeUse = Question("近期用眼情况", "这几天用眼比平时多吗？", listOf("比平时少", "差不多", "比平时多", "多很多"))
    private val eyeDrops = Question("近期眼部用药", "最近在用眼药水吗？", listOf("没有", "人工泪液", "其他眼药水", "不清楚"))
    private val doubleVision = Question("近期重影", "最近看东西会重影（一个看成两个）吗？", listOf("没有", "偶尔", "经常"))
    private val helper = Question("协助测试者", "这次谁来帮忙遮眼？", listOf("家长", "医生或护士", "自己完成", "其他"))

    /**
     * 某项测试的测前快问（全部题目，含按答案出现的追问）；空表示这项测试没有这一步。
     * 干眼：症状、这几天用眼、眼药水三题（报告了症状再追问频率），均为干眼问卷文档要求的项；眼位：重影、谁来帮忙两题。
     */
    fun questions(kind: String): List<Question> = when (kind) {
        "DRY_EYE" -> listOf(dryEyeSymptoms, symptomFrequency, eyeUse, eyeDrops)
        "STRABISMUS" -> listOf(doubleVision, helper)
        else -> emptyList()
    }

    fun needed(kind: String): Boolean = questions(kind).isNotEmpty()

    /** 按当前答案应显示的题目。 */
    fun visible(kind: String, answers: Map<String, String>): List<Question> = questions(kind).filter { it.visibleWhen(answers) }

    /** 显示的题都答了才能开始。 */
    fun complete(kind: String, answers: Map<String, String>): Boolean = visible(kind, answers).all { answers[it.key].orEmpty().isNotBlank() }

    /** 只保留当前显示的题目的答案（如改成「都没有」后去掉之前选的频率）。 */
    fun clean(kind: String, answers: Map<String, String>): Map<String, String> =
        visible(kind, answers).mapNotNull { q -> answers[q.key]?.takeIf { it.isNotBlank() }?.let { q.key to it } }.toMap()

    /** 会随时间变化、改为测前现问的字段。旧资料里若还存着这些值，带入测试前去掉，避免把几个月前的答案当成这次的情况。 */
    val VOLATILE_KEYS: Set<String> = setOf("干眼症状", "症状频率", "近期眼部用药", "近期重影", "协助测试者", "近期用眼情况")

    // region 苹果端

    /** 苹果端：当前应显示的题目（与资料编辑页 editorJson 同一题目格式）。[answersJson] 为键 → 答案的 JSON 对象。 */
    fun questionsJson(kind: String, answersJson: String): String {
        val answers = decode(answersJson)
        return RecordArray(visible(kind, answers).map { it.editorJson(answers) }).toString()
    }

    fun completeJson(kind: String, answersJson: String): Boolean = complete(kind, decode(answersJson))

    internal fun decode(text: String): Map<String, String> = runCatching {
        (json.parseToJsonElement(text) as JsonObject).mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    private val json = Json { ignoreUnknownKeys = true }

    // endregion
}

/** 题目给苹果端渲染用的 JSON（资料编辑页与测前快问共用）。 */
internal fun Question.editorJson(fields: Map<String, String>): RecordObject = RecordObject().apply {
    put("key", key); put("title", title); put("options", options?.let(::RecordArray))
    put("multiple", multiple); put("numeric", numeric)
    put("inputKind", inputKind.name); put("placeholder", placeholder); put("maxLength", maxLength)
    put("hints", RecordObject(hints)); put("exclusive", RecordArray(exclusive.toList()))
    val value = fields[key].orEmpty()
    put("valid", value.isBlank() || validate(value))
}
