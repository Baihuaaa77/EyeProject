package com.zkyjss.eyeproject.feature.services

import kotlin.math.log10
import kotlin.math.pow

/*
 * 干眼功能性连续视力测试协议（对齐《智能眼科-整合9.18》研究内容一）。
 *
 * 原文明确：40 cm、提示佩戴最佳矫正眼镜、练习 10–15 秒（0.3 起，错/无反应增大一档，
 * 答对后同大小再显示一次、再次答对缩小一档）、正式 60 秒自然眨眼 + 紧接 20 秒睁眼（中间不休息），
 * 每个视标显示 2 秒、2 秒内无反应记错；可延长至 3–5 分钟；可闭眼休息 1 分钟后复测；
 * 平均眨眼 10–16 次/分钟为正常、正常眨眼间隔 > 10 秒。
 *
 * 原文未定、本原型的实现约定（待专家确认，均集中在此处便于修改）：
 * - 正式测试沿用练习的阶梯规则，从练习结束时的档位开始；双眼同时观看
 * - 档位为小数视力 0.1–1.2 的 0.1 logMAR 等级；超出本机像素能力的小视标自动封顶
 * - 作答后间隔 300 ms 出下一题（不强制占满 2 秒）
 * - 「正常眨眼间隔 > 10 秒」解释为 20 秒睁眼阶段的最长持续睁眼时间
 * - 视觉维持率 = 正式测试平均视力（按呈现时长加权的 logMAR 换算）/ 练习结束视力
 * - 视力下降时间 = 睁眼阶段视标较该阶段起始下降 ≥ 2 档的时刻
 */
internal object DryEyeProtocol {
    const val TARGET_DISTANCE_CM = 40f
    const val DISTANCE_TOLERANCE_CM = 5f
    const val PRACTICE_SECONDS = 12
    const val NATURAL_SECONDS = 60
    const val EXTENDED_NATURAL_SECONDS = 180
    const val OPEN_SECONDS = 20
    const val REST_SECONDS = 60
    const val TRIAL_TIMEOUT_MS = 2_000L
    const val INTER_TRIAL_MS = 300L
    const val START_LEVEL_INDEX = 5 // 0.3

    /** 小数视力档位（0.1 logMAR 间隔）。下标越大视标越小。 */
    val LEVELS = listOf(0.1f, 0.12f, 0.15f, 0.2f, 0.25f, 0.3f, 0.4f, 0.5f, 0.6f, 0.8f, 1.0f, 1.2f)

    /** 参考范围（原文）。 */
    const val BLINK_RATE_MIN = 10.0
    const val BLINK_RATE_MAX = 16.0
    const val OPEN_INTERVAL_MIN_SEC = 10.0

    /** 候选阈值（未经临床验证）。 */
    const val VMR_THRESHOLD = 0.85
    const val DECLINE_STEPS = 2

    fun logMar(decimal: Float): Double = -log10(decimal.toDouble())
}

/** 练习与正式测试共用的「两次答对缩小、一次错误放大」阶梯。 */
internal class DryEyeStaircase(startIndex: Int = DryEyeProtocol.START_LEVEL_INDEX) {
    var index: Int = startIndex
        private set
    private var streak = 0

    val level: Float get() = DryEyeProtocol.LEVELS[index]

    /** [maxIndex] 为本机当前距离下可显示的最小视标档位。 */
    fun record(correct: Boolean, maxIndex: Int = DryEyeProtocol.LEVELS.lastIndex) {
        if (correct) {
            streak++
            if (streak >= 2) {
                index = (index + 1).coerceAtMost(maxIndex)
                streak = 0
            }
        } else {
            index = (index - 1).coerceAtLeast(0)
            streak = 0
        }
    }

    fun clampTo(maxIndex: Int) {
        if (index > maxIndex) index = maxIndex.coerceAtLeast(0)
    }
}

internal enum class DryEyeStage(val label: String) { PRACTICE("练习"), NATURAL("自然眨眼"), OPEN("持续睁眼") }

/** 一题的完整记录；[onsetSec] 为相对正式测试开始的秒数（练习题相对练习开始）。 */
internal data class DryEyeTrial(
    val stage: DryEyeStage,
    val onsetSec: Float,
    val level: Float,
    val direction: Int,
    val answer: Int?,
    val rtMs: Long?,
    val distanceCm: Float,
    val optotypePx: Float,
    /** true 表示语音作答（反应时包含语音识别延迟）。 */
    val byVoice: Boolean = false,
) {
    val correct: Boolean get() = answer == direction
    val timeout: Boolean get() = answer == null
}

/** 一次正式测试的原始数据（用于评估与复测对比）。 */
internal data class DryEyeSession(
    val naturalSeconds: Int,
    val baselineLevel: Float,
    val trials: List<DryEyeTrial>,
    /** 眨眼时刻（相对正式测试开始的秒数）。 */
    val blinks: List<Float>,
    val validFrameRatio: Double,
    val distanceInRangeRatio: Double,
    val headTurnRatio: Double,
    val resolutionLimited: Boolean,
    val completed: Boolean,
    val afterRest: Boolean = false,
)

data class DryEyeReport(
    val valid: Boolean,
    val validityReasons: List<String>,
    val riskLevel: ScreeningRiskLevel,
    val indicators: List<String>,
    val abnormal: List<String>,
    val advice: String,
    val borderline: Boolean,
    val blinkRatePerMin: Double?,
    val longestOpenSec: Double?,
    val vmr: Double?,
    val accuracyNatural: Double?,
    val accuracyOpen: Double?,
    val meanRtMs: Double?,
    val declineSec: Double?,
    val features: Map<String, Double?>,
)

internal object DryEyeAssessment {

    fun evaluate(session: DryEyeSession, fields: Map<String, String>): DryEyeReport {
        val natural = session.trials.filter { it.stage == DryEyeStage.NATURAL }
        val open = session.trials.filter { it.stage == DryEyeStage.OPEN }
        val naturalEnd = session.naturalSeconds.toFloat()
        val openEnd = naturalEnd + DryEyeProtocol.OPEN_SECONDS
        val naturalBlinks = session.blinks.filter { it < naturalEnd }
        val openBlinks = session.blinks.filter { it >= naturalEnd && it < openEnd }.map { it - naturalEnd }

        val blinkRate = naturalBlinks.size * 60.0 / session.naturalSeconds
        val longestOpen = longestOpenInterval(openBlinks, DryEyeProtocol.OPEN_SECONDS.toDouble())
        val formal = natural + open
        val meanLogMar = timeWeightedLogMar(formal, openEnd)
        val baseLogMar = DryEyeProtocol.logMar(session.baselineLevel)
        val vmr = meanLogMar?.let { 10.0.pow(-(it - baseLogMar)) }
        val accNatural = natural.takeIf { it.isNotEmpty() }?.let { list -> list.count { it.correct }.toDouble() / list.size }
        val accOpen = open.takeIf { it.isNotEmpty() }?.let { list -> list.count { it.correct }.toDouble() / list.size }
        val rts = formal.mapNotNull { it.rtMs?.takeIf { _ -> it.correct } }
        val meanRt = rts.takeIf { it.isNotEmpty() }?.average()
        val declineSec = declineTime(open, naturalEnd)
        val blinkRecovery = blinkRecovery(natural, naturalBlinks)

        val reasons = buildList {
            if (!session.completed) add("测试未完整完成")
            if (natural.size < 10) add("自然眨眼阶段有效视标题数不足（${natural.size} 题）")
            if (open.size < 4) add("睁眼阶段有效视标题数不足（${open.size} 题）")
            if (session.validFrameRatio < 0.8) add("双眼可见的有效帧不足 80%")
            if (session.distanceInRangeRatio < 0.7) add("约 40 cm 距离内的时间不足 70%")
            if (session.headTurnRatio > 0.3) add("头部明显偏转的时间超过 30%")
        }

        val symptoms = fields["干眼症状"].orEmpty().split("、").filter { it.isNotBlank() && it != DRY_EYE_NO_SYMPTOM }
        val symptomFrequent = fields["症状频率"] in setOf("经常", "几乎总是")
        val abnormal = buildList {
            if (blinkRate < DryEyeProtocol.BLINK_RATE_MIN || blinkRate > DryEyeProtocol.BLINK_RATE_MAX)
                add("每分钟眨眼约 ${blinkRate.toInt()} 次，${if (blinkRate > DryEyeProtocol.BLINK_RATE_MAX) "比正常多" else "比正常少"}（正常 10–16 次）")
            if (longestOpen < DryEyeProtocol.OPEN_INTERVAL_MIN_SEC)
                add("最多只能坚持睁眼约 ${longestOpen.toInt()} 秒（正常 10 秒以上）")
            if (vmr != null && vmr < DryEyeProtocol.VMR_THRESHOLD)
                add("看 E 时视力保持度只有 ${(vmr * 100).toInt()}%，看久了容易模糊")
            if (declineSec != null)
                add("坚持睁眼约 ${declineSec.toInt()} 秒后，需要更大的 E 才能看清")
        }
        val borderline = (blinkRate in 9.0..10.0 || blinkRate in 16.0..18.0) ||
            (longestOpen in 8.0..12.0) || (vmr != null && vmr in 0.8..0.9) || abnormal.size == 1

        val risk = when {
            reasons.isNotEmpty() -> ScreeningRiskLevel.UNKNOWN
            abnormal.size >= 3 || (abnormal.size >= 2 && symptomFrequent) -> ScreeningRiskLevel.HIGH
            abnormal.isNotEmpty() -> ScreeningRiskLevel.MEDIUM
            else -> ScreeningRiskLevel.LOW
        }
        val advice = when (risk) {
            ScreeningRiskLevel.HIGH -> "多项指标提示泪膜稳定性可能不足，建议到眼科做泪膜破裂时间、泪液分泌及睑板腺等检查。"
            ScreeningRiskLevel.MEDIUM -> if (borderline) "结果接近临界，建议延长测试或闭眼休息后复测；症状持续请就诊。"
                else "部分指标异常，建议注意用眼休息；症状持续或加重请到眼科检查。"
            ScreeningRiskLevel.LOW -> if (symptoms.isNotEmpty()) "本次未见明显异常，但你报告了眼部不适，建议到眼科评估。"
                else "本次未见明显异常，保持良好用眼习惯。"
            ScreeningRiskLevel.UNKNOWN -> "检测数据无效，请按提示调整距离、光线和姿势后重新测试。"
        }

        val indicators = buildList {
            add("练习结束视力（基线）：${session.baselineLevel}")
            add("自然眨眼：${naturalBlinks.size} 次（${fmt1(blinkRate)} 次/分钟，参考 10–16）")
            add("睁眼阶段眨眼 ${openBlinks.size} 次；最长持续睁眼 ${fmt1(longestOpen)} 秒（参考 ≥10 秒）")
            add("视标正确率：自然眨眼 ${pct(accNatural)}（${natural.size} 题）· 持续睁眼 ${pct(accOpen)}（${open.size} 题）")
            add("超时未答：${formal.count { it.timeout }} 题；正确作答平均反应 ${meanRt?.let { "${it.toInt()} ms" } ?: "—"}")
            add("功能性视力（时间加权平均）：${meanLogMar?.let { fmt2(10.0.pow(-it)) } ?: "—"}；视觉维持率：${vmr?.let(::fmt2) ?: "—"}")
            add("视力下降时间：${declineSec?.let { "${fmt1(it)} 秒" } ?: "睁眼阶段未出现 2 档以上下降"}")
            blinkRecovery?.let { (before, after) -> add("眨眼前 2 秒正确率 ${pct(before)} → 眨眼后 2 秒 ${pct(after)}") }
            add("数据质量：有效帧 ${pct(session.validFrameRatio)} · 距离达标 ${pct(session.distanceInRangeRatio)} · 头部偏转 ${pct(session.headTurnRatio)}")
            if (session.resolutionLimited) add("部分小视标受屏幕像素限制，已封顶为本机可显示的最小档")
        }
        val features = linkedMapOf<String, Double?>(
            "natural_seconds" to session.naturalSeconds.toDouble(),
            "baseline_decimal" to session.baselineLevel.toDouble(),
            "blink_rate_per_min" to blinkRate,
            "open_longest_sec" to longestOpen,
            "fva_mean_logmar" to meanLogMar,
            "vmr" to vmr,
            "accuracy_natural" to accNatural,
            "accuracy_open" to accOpen,
            "mean_rt_ms" to meanRt,
            "decline_sec" to declineSec,
            "valid_frame_ratio" to session.validFrameRatio,
            "distance_in_range_ratio" to session.distanceInRangeRatio,
            "head_turn_ratio" to session.headTurnRatio,
        )
        return DryEyeReport(
            valid = reasons.isEmpty(), validityReasons = reasons, riskLevel = risk,
            indicators = indicators, abnormal = abnormal, advice = advice,
            borderline = risk != ScreeningRiskLevel.UNKNOWN && borderline,
            blinkRatePerMin = blinkRate, longestOpenSec = longestOpen, vmr = vmr,
            accuracyNatural = accNatural, accuracyOpen = accOpen, meanRtMs = meanRt,
            declineSec = declineSec, features = features,
        )
    }

    /** 睁眼阶段最长不眨眼区间（含阶段起点到首次眨眼、末次眨眼到阶段结束）。 */
    fun longestOpenInterval(blinks: List<Float>, durationSec: Double): Double {
        val points = listOf(0.0) + blinks.map { it.toDouble() }.sorted() + durationSec
        return points.zipWithNext { a, b -> b - a }.maxOrNull() ?: durationSec
    }

    /** 按呈现时长加权的平均 logMAR（每题档位持续到下一题出现）。 */
    private fun timeWeightedLogMar(trials: List<DryEyeTrial>, endSec: Float): Double? {
        if (trials.isEmpty()) return null
        val sorted = trials.sortedBy { it.onsetSec }
        var weighted = 0.0
        var total = 0.0
        sorted.forEachIndexed { i, t ->
            val next = sorted.getOrNull(i + 1)?.onsetSec ?: endSec
            val w = (next - t.onsetSec).coerceAtLeast(0f).toDouble()
            weighted += DryEyeProtocol.logMar(t.level) * w
            total += w
        }
        return if (total > 0) weighted / total else null
    }

    /** 睁眼阶段视标较起始档放大 ≥ 2 档的首个时刻（相对睁眼阶段开始）。 */
    private fun declineTime(open: List<DryEyeTrial>, openStartSec: Float): Double? {
        val first = open.minByOrNull { it.onsetSec } ?: return null
        val startIdx = DryEyeProtocol.LEVELS.indexOf(first.level)
        return open.sortedBy { it.onsetSec }.firstOrNull {
            startIdx - DryEyeProtocol.LEVELS.indexOf(it.level) >= DryEyeProtocol.DECLINE_STEPS
        }?.let { (it.onsetSec - openStartSec).toDouble() }
    }

    /** 眨眼间期分析：每次眨眼前/后 2 秒内出现的视标正确率。 */
    private fun blinkRecovery(trials: List<DryEyeTrial>, blinks: List<Float>): Pair<Double, Double>? {
        if (blinks.isEmpty()) return null
        val before = trials.filter { t -> blinks.any { b -> t.onsetSec in (b - 2f)..b } }
        val after = trials.filter { t -> blinks.any { b -> t.onsetSec in b..(b + 2f) } }
        if (before.size < 3 || after.size < 3) return null
        return before.count { it.correct }.toDouble() / before.size to after.count { it.correct }.toDouble() / after.size
    }

    private fun fmt1(v: Double) = "%.1f".format(v)
    private fun fmt2(v: Double) = "%.2f".format(v)
    private fun pct(v: Double?) = v?.let { "${(it * 100).toInt()}%" } ?: "—"
}

/** 干眼症状里表示「没有不适」的选项。 */
internal const val DRY_EYE_NO_SYMPTOM = "都没有"

/** 干眼问卷：覆盖原文研究内容一（1）的全部条目，问法改为患者容易理解的大白话。key 保持稳定供评估读取。 */
internal val dryEyeQuestions = listOf(
    ageQuestion(6..100),
    Question("性别", "你的性别", listOf("男", "女", "不愿透露")),
    Question("干眼症状", "最近眼睛有这些感觉吗？",
        listOf("眼睛发干、发涩", "像进了沙子", "有烧灼感", "看东西模糊，时好时坏", "怕光", "容易累", DRY_EYE_NO_SYMPTOM),
        multiple = true),
    Question("症状频率", "这些感觉多久出现一次？", listOf("偶尔", "有时", "经常", "几乎总是"),
        visibleWhen = { f -> f["干眼症状"].orEmpty().split("、").any { it.isNotBlank() && it != DRY_EYE_NO_SYMPTOM } }),
    Question("每日电子产品使用", "每天看手机、电脑大约多久？", listOf("不到 2 小时", "2–4 小时", "4–8 小时", "8 小时以上")),
    Question("近期用眼情况", "最近用眼比平时多吗？", listOf("比平时少", "差不多", "比平时多", "多很多")),
    Question("屈光矫正状态", "平时怎么看清东西？",
        listOf("不用戴眼镜", "戴框架眼镜", "戴隐形眼镜", "做过近视手术", "需要但没戴", "不清楚")),
    Question("佩戴隐形眼镜史", "戴过隐形眼镜吗？", listOf("没戴过", "以前戴过", "现在在戴")),
    Question("基础视力", "你觉得自己视力怎么样？", listOf("挺好", "一般", "比较差", "不清楚")),
    Question("眼部疾病", "医生诊断过以下眼病吗？",
        listOf("近视、远视或散光", "干眼", "白内障", "青光眼", "眼底病", "眼皮（眼睑）疾病", "都没有"),
        multiple = true, hints = mapOf("眼底病" to "如黄斑病变、视网膜病变")),
    Question("眼手术或外伤史", "眼睛做过手术或受过伤吗？", listOf("没有", "有", "不清楚")),
    Question("近期眼部用药", "最近在用眼药水吗？", listOf("没有", "人工泪液", "其他眼药水", "不清楚")),
    Question("全身疾病", "有以下疾病吗？", listOf("糖尿病", "甲状腺疾病", "类风湿", "干燥综合征", "都没有"), multiple = true),
    Question("是否眼部化妆", "平时化眼妆（眼线、睫毛膏）吗？", listOf("不化", "偶尔", "经常")),
    Question("睡眠时长", "每天大约睡多久？", listOf("不到 6 小时", "6–8 小时", "8 小时以上")),
)
