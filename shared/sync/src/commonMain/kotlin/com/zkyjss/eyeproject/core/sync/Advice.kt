package com.zkyjss.eyeproject.core.sync

import kotlinx.serialization.Serializable

/*
 * AI 综合建议：设备端规则（共用 EyeCareEngine）先算出用眼负荷等级与主要原因，大模型只结合这些概况给出
 * 通俗、可执行的综合建议，不改变等级、不下诊断。输入为用户已确认的去标识清单：
 * 年龄段、职业类别、视力矫正方式、既往眼病选项；近 7 天日均用屏、最长连续用眼、夜间使用天数、平均休息次数（系统提供时）；
 * 实时检测的眨眼频率、观看距离、头部偏转；环境光与亮度等级；三项自测各自最近一次的等级；负荷等级与主要原因；当天自评症状。
 * 不含姓名、邮箱、成员编号、具体日期时间、逐帧数据、照片或视频。
 */

@Serializable
data class AdviceTest(val kind: String, val riskLevel: String)

@Serializable
data class AdviceRequest(
    /** 规则给出的用眼负荷等级（LOW / MEDIUM / HIGH / UNKNOWN），建议不得改变。 */
    val fatigueLevel: String,
    val fatigueScore: Int? = null,
    /** 主要原因（规则归因的标题，按分值排序） */
    val factors: List<String> = emptyList(),
    val ageGroup: String? = null,
    val occupation: String? = null,
    val visionCorrection: String? = null,
    val eyeHistory: String? = null,
    val averageScreenMinutes: Int? = null,
    val averageLongestSessionMinutes: Int? = null,
    val nightUseDays: Int? = null,
    val averageBreaks: Int? = null,
    val blinksPerMinute: Int? = null,
    val viewingDistanceCm: Int? = null,
    val headOffsetDeg: Int? = null,
    /** 偏暗 / 适中 / 偏亮 */
    val ambientLight: String? = null,
    val screenBrightness: String? = null,
    val tests: List<AdviceTest> = emptyList(),
    /** 当天自评症状 */
    val symptoms: List<String> = emptyList(),
)

@Serializable
data class AdviceDto(
    /** 一句话总结。 */
    val summary: String,
    /** 最该先做的事（1–3 条）。 */
    val priorities: List<String>,
    /** 长期习惯（1–3 条）。 */
    val habits: List<String>,
    /** 什么情况下去看眼科。 */
    val seeDoctor: String,
    val sources: List<KnowledgeSourceDto> = emptyList(),
    /** 建议所依据的负荷等级（原样回传，便于界面核对）。 */
    val fatigueLevel: String,
    val model: String,
    val generatedAt: Long,
    val disclaimer: String = Advices.DISCLAIMER,
)

object Advices {
    const val DISCLAIMER = "由 AI 根据本机规则算出的用眼概况生成，只作生活建议，不是诊断，也不能代替眼科检查。"
    /** 同意说明（两端一致）。 */
    const val CONSENT_TEXT = "生成综合建议时，会把年龄段、职业、戴镜方式、既往眼病选项，近 7 天的用屏与休息概况，" +
        "实时检测的眨眼与观看距离，环境光与亮度等级，三项自测最近的等级，今天的用眼负荷与主要原因，以及你记录的感受" +
        "发送到云端服务，由第三方大模型（DeepSeek）生成建议；不发送姓名、账号、具体日期、照片或视频。可随时在「我的 → 云端同步」中关闭。"

    val LEVELS = setOf("LOW", "MEDIUM", "HIGH", "UNKNOWN")
    private const val MAX_ITEMS = 6
    private const val MAX_TEXT = 60

    /** 同样的概况得到同样的建议：两端与服务端都以此作为缓存键。 */
    fun cacheKey(request: AdviceRequest): String =
        Sha256.hex(SyncContract.json.encodeToString(AdviceRequest.serializer(), request))

    /** 限制条数、长度与数值范围，控制费用并与服务端校验一致。 */
    fun AdviceRequest.bounded(): AdviceRequest = copy(
        fatigueScore = fatigueScore?.coerceIn(0, 100),
        factors = factors.take(MAX_ITEMS).map { it.take(MAX_TEXT) },
        ageGroup = ageGroup?.take(16), occupation = occupation?.take(MAX_TEXT), visionCorrection = visionCorrection?.take(MAX_TEXT),
        eyeHistory = eyeHistory?.take(MAX_TEXT),
        averageScreenMinutes = averageScreenMinutes?.coerceIn(0, 24 * 60),
        averageLongestSessionMinutes = averageLongestSessionMinutes?.coerceIn(0, 24 * 60),
        nightUseDays = nightUseDays?.coerceIn(0, 7), averageBreaks = averageBreaks?.coerceIn(0, 200),
        blinksPerMinute = blinksPerMinute?.coerceIn(0, 120), viewingDistanceCm = viewingDistanceCm?.coerceIn(0, 200),
        headOffsetDeg = headOffsetDeg?.coerceIn(0, 90),
        ambientLight = ambientLight?.take(8), screenBrightness = screenBrightness?.take(8),
        tests = tests.take(3).map { AdviceTest(it.kind.take(16), it.riskLevel.take(16)) },
        symptoms = symptoms.take(MAX_ITEMS).map { it.take(MAX_TEXT) },
    )
}
