package com.zkyjss.eyeproject.feature.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/*
 * 被测者资料：原先每项测试开头的问卷合并为一份资料，在「我的」页按成员（本人、孩子、父母……）维护，
 * 测试时只选「这次测谁」，不再逐题填写。字段键沿用原问卷（评估代码按键读取），年龄由出生年份换算。
 */

/** 一位被测者。[relation] 取 [SubjectProfiles.RELATIONS] 的代码；[fields] 为问卷键 → 答案（多选用「、」拼接）。 */
data class SubjectProfile(
    val id: String,
    val relation: String,
    val nickname: String,
    val fields: Map<String, String>,
    val updatedAt: Long,
)

/** 本次测试的被测者（写入记录，云端同步时映射到对应成员）。 */
data class TestSubject(val id: String, val name: String, val relation: String)

/** 写入记录的「subject」对象。 */
internal fun TestSubject.toRecordObject(): RecordObject = RecordObject().apply {
    put("id", id); put("relation", relation); put("name", name)
}

data class ProfileSection(val title: String, val questions: List<Question>)

object SubjectProfiles {
    /** 关系代码与显示名；代码与服务端 SubjectRelation 一致。 */
    val RELATIONS = listOf("SELF" to "本人", "CHILD" to "孩子", "PARENT" to "父母", "SPOUSE" to "配偶", "OTHER" to "其他")
    fun relationLabel(code: String) = RELATIONS.firstOrNull { it.first == code }?.second ?: "其他"

    const val BIRTH_YEAR = "出生年份"
    private const val AGE = "年龄"

    /**
     * 资料题目分组（编辑页按组显示）：只放长期不变的信息。全部可选；某项测试必需的见 [requiredKeys]。
     * 症状、用药等会随时间变化的问题改在每次测试前现问，见 [PreTestCheck]。
     */
    val sections: List<ProfileSection> = listOf(
        ProfileSection("基本信息", listOf(
            Question(BIRTH_YEAR, "出生年份", numeric = true, optional = true,
                validate = { it.toIntOrNull()?.let { y -> y in 1900..2100 } == true }),
            Question("性别", "性别", listOf("男", "女", "不愿透露"), optional = true),
        )),
        ProfileSection("视力与戴镜", listOf(
            Question("日常矫正方式", "平时戴什么眼镜？",
                listOf("不戴眼镜", "近视或远视眼镜", "老花镜", "渐进多焦点眼镜", "隐形眼镜", "需要配镜但没戴"),
                multiple = true, optional = true, hints = mapOf("渐进多焦点眼镜" to "一副镜片看远看近都清楚")),
            Question("屈光情况", "知道自己有以下情况吗？", listOf("近视", "远视", "散光", "老花", "都没有或不清楚"),
                multiple = true, optional = true),
            Question("佩戴隐形眼镜史", "戴过隐形眼镜吗？", listOf("没戴过", "以前戴过", "现在在戴"), optional = true),
            Question("基础视力", "平时视力怎么样？", listOf("挺好", "一般", "比较差", "没测过或不清楚"), optional = true),
        )),
        ProfileSection("眼病与手术", listOf(
            Question("既往眼病", "医生诊断过以下眼病吗？",
                listOf("干眼", "白内障", "青光眼", "眼底病", "眼皮（眼睑）疾病", "斜视", "弱视", "其他眼病", "没有"),
                multiple = true, optional = true, hints = mapOf("眼底病" to "如黄斑病变、视网膜病变")),
            Question("眼部手术", "做过眼部手术吗？",
                listOf("近视激光手术", "白内障手术", "ICL 晶体植入", "斜视手术", "其他眼部手术", "没有", "不清楚"),
                multiple = true, optional = true,
                hints = mapOf("近视激光手术" to "全飞秒、半飞秒、表层手术等", "ICL 晶体植入" to "在眼内放一片矫正近视的镜片")),
            Question("激光手术类型", "做的是哪种近视激光手术？", listOf("全飞秒（SMILE）", "半飞秒（FS-LASIK）", "表层手术（PRK）", "不清楚"),
                optional = true, visibleWhen = { f -> f.hasAny("眼部手术", "近视激光手术") }),
            Question("人工晶体类型", "白内障手术换的是哪种人工晶体？", listOf("普通单焦点", "多焦点或景深延长（EDOF）", "不清楚"),
                optional = true, visibleWhen = { f -> f.hasAny("眼部手术", "白内障手术") }),
            Question("手术年份", "手术大约是哪一年做的？", numeric = true, optional = true,
                visibleWhen = { f -> f.hasAny("眼部手术", "近视激光手术", "白内障手术", "ICL 晶体植入", "斜视手术", "其他眼部手术") },
                validate = { it.toIntOrNull()?.let { y -> y in 1950..2100 } == true }),
            Question("眼外伤史", "眼睛受过外伤吗？", listOf("没有", "有", "不清楚"), optional = true),
            Question("全身疾病", "有以下疾病吗？", listOf("糖尿病", "甲状腺疾病", "类风湿", "干燥综合征", "都没有"),
                multiple = true, optional = true),
        )),
        ProfileSection("用眼与生活习惯", listOf(
            Question("每日电子产品使用", "每天看手机、电脑或书本大约多久？", listOf("不到 2 小时", "2–4 小时", "4–8 小时", "8 小时以上"), optional = true),
            Question("职业", "平时主要做什么？", listOf("办公室工作", "户外工作", "精细手工", "老师或学生", "已退休", "其他"), optional = true),
            Question("睡眠时长", "每天大约睡多久？", listOf("不到 6 小时", "6–8 小时", "8 小时以上"), optional = true),
            Question("是否眼部化妆", "平时化眼妆（眼线、睫毛膏）吗？", listOf("不化", "偶尔", "经常"), optional = true),
        )),
        HealthProfileFields.measurements,
        HealthProfileFields.histories,
    )

    val questions: List<Question> get() = sections.flatMap { it.questions }

    /** 两端共用校验；填写了才校验，新增选填项不会阻止旧资料开始测试。 */
    fun validationError(fields: Map<String, String>): String? {
        val invalid = questions.firstOrNull { q ->
            q.visibleWhen(fields) && fields[q.key].orEmpty().let { it.isNotBlank() && (it.length > q.maxLength || !q.validate(it)) }
        }
        if (invalid != null) return "「${invalid.title}」填写有误，请按输入提示填写"
        val start = fields["眼药水开始日期"].orEmpty()
        val end = fields["眼药水结束日期"].orEmpty()
        if (start.isNotBlank() && end.isNotBlank() && end < start)
            return "眼药水结束日期不能早于开始日期"
        return null
    }

    fun validationErrorJson(fieldsJson: String): String? = validationError(decodeFields(fieldsJson))

    private fun decodeFields(text: String): Map<String, String> = runCatching {
        (json.parseToJsonElement(text) as JsonObject).mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    /** 各项测试开始前资料里必须填好的项（评估规则会用到的长期信息）；症状等测前现问，见 [PreTestCheck]。 */
    fun requiredKeys(kind: String, @Suppress("UNUSED_PARAMETER") fields: Map<String, String>): List<String> = buildList {
        add(BIRTH_YEAR)
        if (kind == "PRESBYOPIA") { add("日常矫正方式"); add("屈光情况") }
    }

    /** 各项测试的适用年龄（与原问卷一致）。 */
    fun ageRange(kind: String): IntRange = when (kind) {
        "DRY_EYE" -> 6..100
        "PRESBYOPIA" -> 18..100
        // 色觉图要认得数字
        "COLOR_VISION" -> 5..100
        else -> 3..100
    }

    /** 由出生年份换算年龄（只存年份，可能比实际年龄多 1 岁）。 */
    fun age(fields: Map<String, String>, nowMs: Long): Int? =
        fields[BIRTH_YEAR]?.toIntOrNull()?.let { yearOf(nowMs) - it }

    private fun yearOf(ms: Long): Int = (1970 + ms / 31_556_952_000L).toInt()

    /**
     * 这位成员做 [kind] 测试还缺什么：返回需要补充的题目标题；年龄不在适用范围时给出说明。空表示可以开始。
     */
    fun missing(kind: String, profile: SubjectProfile, nowMs: Long): List<String> {
        val f = profile.fields
        val gaps = requiredKeys(kind, f).filter { key ->
            val value = f[key].orEmpty()
            val question = questions.first { it.key == key }
            value.isBlank() || !question.validate(value)
        }.map { key -> if (key == BIRTH_YEAR) "出生年份" else questions.first { it.key == key }.title }
        val age = age(f, nowMs)
        val range = ageRange(kind)
        return if (gaps.isEmpty() && age != null && age !in range) listOf("年龄 $age 岁不在本测试适用范围（${range.first} 岁以上）") else gaps
    }

    /** 送进测试与记录的字段：资料原样 + 换算出的「年龄」；旧资料里存的随时变化项不带入（改为测前现问）。 */
    fun testFields(profile: SubjectProfile, nowMs: Long): Map<String, String> =
        profile.fields.filter { (key, value) -> value.isNotBlank() && key !in PreTestCheck.VOLATILE_KEYS } +
            (age(profile.fields, nowMs)?.let { mapOf(AGE to it.toString()) } ?: emptyMap())

    // region JSON（苹果端本机存储、资料编辑页与跨端传递）

    /**
     * 苹果端资料编辑页：按分组给出当前可见的题目（随已填答案动态变化）。[fieldsJson] 为键 → 答案的 JSON 对象。
     */
    fun editorJson(fieldsJson: String): String {
        val fields = runCatching { (json.parseToJsonElement(fieldsJson) as JsonObject).mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it } }.toMap() }.getOrDefault(emptyMap())
        return RecordArray(sections.map { section -> RecordObject().apply {
            put("title", section.title)
            put("questions", RecordArray(section.questions.filter { it.visibleWhen(fields) }.map { it.editorJson(fields) }))
        } }).toString()
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun toJson(profiles: List<SubjectProfile>): String = RecordArray(profiles.map { p ->
        RecordObject().apply {
            put("id", p.id); put("relation", p.relation); put("nickname", p.nickname)
            put("fields", RecordObject(p.fields)); put("updatedAt", p.updatedAt.toDouble())
        }
    }).toString()

    fun fromJson(text: String): List<SubjectProfile> = runCatching {
        (json.parseToJsonElement(text) as JsonArray).mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            fun str(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            SubjectProfile(
                id = str("id") ?: return@mapNotNull null,
                relation = str("relation") ?: "OTHER",
                nickname = str("nickname").orEmpty(),
                fields = (o["fields"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                    (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it }
                }.toMap(),
                updatedAt = (o["updatedAt"] as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toDoubleOrNull()?.toLong() } ?: 0L,
            )
        }
    }.getOrDefault(emptyList())

    // endregion
}
