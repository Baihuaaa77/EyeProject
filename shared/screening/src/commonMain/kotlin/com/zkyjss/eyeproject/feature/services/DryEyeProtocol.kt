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
object DryEyeProtocol {
    const val TARGET_DISTANCE_CM = 40f
    const val DISTANCE_TOLERANCE_CM = 5f
    const val PRACTICE_SECONDS = 12
    const val NATURAL_SECONDS = 60
    const val EXTENDED_NATURAL_SECONDS = 180
    const val OPEN_SECONDS = 20
    const val REST_SECONDS = 60
    const val TRIAL_TIMEOUT_MS = 2_000L
    const val INTER_TRIAL_MS = 300L
    /** 等界面回报「已画出」的上限；超过则以发出显示的时刻起算（不再继续等）。 */
    const val PRESENT_WAIT_MS = 250L
    const val START_LEVEL_INDEX = 5 // 0.3

    /** 小数视力档位（0.1 logMAR 间隔）。下标越大视标越小。 */
    val LEVELS = listOf(0.1f, 0.12f, 0.15f, 0.2f, 0.25f, 0.3f, 0.4f, 0.5f, 0.6f, 0.8f, 1.0f, 1.2f)

    /** 参考范围（原文）。 */
    const val BLINK_RATE_MIN = 10.0
    const val BLINK_RATE_MAX = 16.0
    const val OPEN_INTERVAL_MIN_SEC = 10.0

    /** 候选阈值（未经临床验证）。rules-2 起 VMR 与「下降 2 档」只作描述，判定改用 [VisualStability]。 */
    const val VMR_THRESHOLD = 0.85
    const val DECLINE_STEPS = 2

    /** 眨眼指标可用的最低分析帧率（候选）：低于此值眨眼会被系统性漏记，眨眼类指标改为「无法评估」。 */
    const val MIN_BLINK_FPS = 20.0
    /** 连续 ≥ 3 题超时视为「未作答段」（走神、没点到、语音未生效），不当作看不清。 */
    const val UNANSWERED_RUN = 3
    /** 未作答段占正式题数的比例上限；超过则视力指标无法评估。 */
    const val MAX_UNANSWERED_RATIO = 0.3

    fun logMar(decimal: Float): Double = -log10(decimal.toDouble())
}

/** 练习与正式测试共用的「两次答对缩小、一次错误放大」阶梯。 */
class DryEyeStaircase(startIndex: Int = DryEyeProtocol.START_LEVEL_INDEX) {
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

enum class DryEyeStage(val label: String) { PRACTICE("练习"), NATURAL("自然眨眼"), OPEN("持续睁眼") }

/** 一题的完整记录；[onsetSec] 为相对正式测试开始的秒数（练习题相对练习开始）。 */
data class DryEyeTrial(
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
    /**
     * 从发出显示到界面回报「已画出」的毫秒数；null 表示界面未回报（切后台、测试替身），
     * 此时反应时与 2 秒时限从发出时刻起算。仅说明计时起点，不是屏幕发光时刻的硬件测量。
     */
    val presentDelayMs: Long? = null,
) {
    val correct: Boolean get() = answer == direction
    val timeout: Boolean get() = answer == null
    /** 主动说「看不清 / 跳过」（作答 -1），与答错、超时分开统计。 */
    val unsure: Boolean get() = answer == UNSURE_ANSWER

    companion object { const val UNSURE_ANSWER = -1 }
}

/** 一次正式测试的原始数据（用于评估与复测对比）。 */
data class DryEyeSession(
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
    /** 正式测试期间相机分析帧率（逐秒帧数的中位数）；null 表示未记录（旧数据或测试替身）。 */
    val analysisFps: Double? = null,
    /** 正式阶段每约 500 ms 的距离读数；0 表示没有读数，仅用于描述采集条件。 */
    val distanceSamplesCm: List<Float> = emptyList(),
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
    /** 判定依据（证据族）；结果页「判断依据」与导出记录据此展示。 */
    val evidence: List<Evidence> = emptyList(),
    val analysisFps: Double? = null,
    val qualityNotes: List<String> = emptyList(),
    val qualityRows: List<ResultRow> = emptyList(),
    /** 距上次眨眼的分箱作答（描述性，不参与判断）。 */
    val intervalRows: List<ResultRow> = emptyList(),
)

object DryEyeAssessment {
    /**
     * 规则版本；写入记录，服务端据此区分评估口径。改阈值或分级逻辑必须升版本。
     * rules-2：帧率门控与按时间判定眨眼、未作答段剔除、心理测量模型替代 VMR/下降、误差范围三向判定、按证据族计数。
     */
    const val VERSION = "dryeye-9.18-rules-2"

    fun evaluate(session: DryEyeSession, fields: Map<String, String>): DryEyeReport {
        val natural = session.trials.filter { it.stage == DryEyeStage.NATURAL }
        val open = session.trials.filter { it.stage == DryEyeStage.OPEN }
        val naturalEnd = session.naturalSeconds.toFloat()
        val openEnd = naturalEnd + DryEyeProtocol.OPEN_SECONDS
        val naturalBlinks = session.blinks.filter { it < naturalEnd }
        val openBlinks = session.blinks.filter { it >= naturalEnd && it < openEnd }.map { it - naturalEnd }

        val blinkRate = naturalBlinks.size * 60.0 / session.naturalSeconds
        val longestOpen = longestOpenInterval(openBlinks, DryEyeProtocol.OPEN_SECONDS.toDouble())
        val formal = (natural + open).sortedBy { it.onsetSec }
        val meanLogMar = timeWeightedLogMar(formal, openEnd)
        val baseLogMar = DryEyeProtocol.logMar(session.baselineLevel)
        val vmr = meanLogMar?.let { 10.0.pow(-(it - baseLogMar)) }
        val accNatural = natural.takeIf { it.isNotEmpty() }?.let { list -> list.count { it.correct }.toDouble() / list.size }
        val accOpen = open.takeIf { it.isNotEmpty() }?.let { list -> list.count { it.correct }.toDouble() / list.size }
        val rts = formal.mapNotNull { it.rtMs?.takeIf { _ -> it.correct } }
        val meanRt = rts.takeIf { it.isNotEmpty() }?.average()
        val declineSec = declineTime(open, naturalEnd)
        val blinkRecovery = blinkRecovery(natural, naturalBlinks)
        val unanswered = unansweredTrials(formal)
        val unansweredRatio = if (formal.isEmpty()) 0.0 else unanswered.size.toDouble() / formal.size
        val answered = formal.filterNot { it in unanswered }
        val fit = VisualStability.fit(answered.map { VisualStability.Response(it.onsetSec.toDouble(), it.level.toDouble(), it.correct) },
            session.naturalSeconds.toDouble())

        val reasons = buildList {
            if (!session.completed) add("测试未完整完成")
            if (natural.size < 10) add("自然眨眼阶段有效视标题数不足（${natural.size} 题）")
            if (open.size < 4) add("睁眼阶段有效视标题数不足（${open.size} 题）")
            if (session.validFrameRatio < 0.8) add("双眼可见的有效帧不足 80%")
            if (session.distanceInRangeRatio < 0.7) add("约 40 cm 距离内的时间不足 70%")
            if (session.headTurnRatio > 0.3) add("头部明显偏转的时间超过 30%")
        }

        // 证据族一：眨眼行为（同一检测器的两项指标合为一族）
        val fps = session.analysisFps
        val blinkReliable = fps == null || fps >= DryEyeProtocol.MIN_BLINK_FPS
        val (rateLow, rateHigh) = poissonInterval(naturalBlinks.size).let { (a, b) ->
            a * 60.0 / session.naturalSeconds to b * 60.0 / session.naturalSeconds
        }
        val rateStatus = if (!blinkReliable) EvidenceStatus.UNAVAILABLE else
            guardBand(blinkRate, rateLow, rateHigh, DryEyeProtocol.BLINK_RATE_MIN, DryEyeProtocol.BLINK_RATE_MAX)
        // 眨眼时刻的误差约 2 帧，最长睁眼按此留保护带
        val openMargin = maxOf(0.3, 2.0 / (fps ?: 30.0))
        val openStatus = if (!blinkReliable) EvidenceStatus.UNAVAILABLE else
            guardBand(longestOpen, longestOpen - openMargin, longestOpen + openMargin, DryEyeProtocol.OPEN_INTERVAL_MIN_SEC)
        val blinkStatus = combineStatus(listOf(rateStatus, openStatus))
        val blinkEvidence = Evidence(
            key = "blink", title = "眨眼情况", status = blinkStatus,
            summary = when {
                !blinkReliable -> "相机每秒只分析约 ${fps!!.toInt()} 帧，眨眼可能漏记，本次不评估眨眼"
                rateStatus == EvidenceStatus.ABNORMAL && blinkRate < DryEyeProtocol.BLINK_RATE_MIN -> "眨眼明显偏少（每分钟约 ${blinkRate.toInt()} 次）"
                rateStatus == EvidenceStatus.ABNORMAL -> "眨眼明显偏多（每分钟约 ${blinkRate.toInt()} 次）"
                openStatus == EvidenceStatus.ABNORMAL -> "最多只能坚持睁眼约 ${longestOpen.toInt()} 秒"
                blinkStatus == EvidenceStatus.UNCERTAIN -> "眨眼指标接近参考线，测试时间短、还不能确定"
                else -> "眨眼频率与坚持睁眼时间在参考范围内"
            },
            details = buildList {
                add("自然眨眼 ${naturalBlinks.size} 次 / ${session.naturalSeconds} 秒 = 每分钟 ${fmt1(blinkRate)} 次（误差范围 ${fmt1(rateLow)}–${fmt1(rateHigh)}，参考 10–16）：${rateStatus.label}")
                add("坚持睁眼段最长不眨眼 ${fmt1(longestOpen)} 秒（±${fmt1(openMargin)}，参考 ≥ 10 秒）：${openStatus.label}")
                fps?.let { add("相机分析帧率约 ${fmt1(it)} 帧/秒（眨眼评估需 ≥ ${DryEyeProtocol.MIN_BLINK_FPS.toInt()}）") }
                add("参考值来自文献，看屏任务本身会减少眨眼，仅作初筛参考")
            },
        )

        // 证据族二：看 E 时视力是否随时间变差
        val openAnswered = answered.count { it.stage == DryEyeStage.OPEN }
        val visualUnavailable = when {
            unansweredRatio > DryEyeProtocol.MAX_UNANSWERED_RATIO -> "有 ${unanswered.size} 题连续未作答（${pct(unansweredRatio)}），无法判断视力变化"
            fit == null -> "有效作答太少，无法判断视力变化"
            else -> null
        }
        val naturalVisual = if (visualUnavailable != null) EvidenceStatus.UNAVAILABLE else fit!!.naturalStatus
        val openVisual = if (visualUnavailable != null || openAnswered < 4) EvidenceStatus.UNAVAILABLE else fit!!.openStatus
        val visualStatus = combineStatus(listOf(naturalVisual, openVisual))
        val visualEvidence = Evidence(
            key = "visual", title = "看 E 时视力是否变差", status = visualStatus,
            summary = when {
                visualUnavailable != null -> visualUnavailable
                openVisual == EvidenceStatus.ABNORMAL -> "坚持睁眼时视力明显变差（${VisualStability.lines(fit!!.openChange)}）"
                naturalVisual == EvidenceStatus.ABNORMAL -> "看久了视力明显变差（${VisualStability.lines(fit!!.naturalChange)}）"
                visualStatus == EvidenceStatus.UNCERTAIN -> "视力可能有所波动，但作答数据还不足以确定"
                else -> "整个测试中视力基本稳定"
            },
            details = buildList {
                fit?.let {
                    add("起始视力估计约 ${VisualStability.acuityText(it.thetaLogMar, answered.filter { t -> t.correct }.maxOfOrNull { t -> t.level.toDouble() })}")
                    add("自然眨眼 ${session.naturalSeconds} 秒内：${VisualStability.lines(it.naturalChange)}，明显变差的把握 ${pct(it.naturalDeclineProbability)}：${naturalVisual.label}")
                    add("坚持睁眼 20 秒内：${VisualStability.lines(it.openChange)}，明显变差的把握 ${pct(it.openDeclineProbability)}：${openVisual.label}")
                }
                if (unanswered.isNotEmpty()) add("连续未作答 ${unanswered.size} 题，已从视力分析中剔除（不当作看不清）")
                add("判定：变差超过约 1 行且把握 ≥ 90% 为异常，60%–90% 为不确定")
            },
        )

        // 证据族三：症状（问卷）
        val symptoms = fields["干眼症状"].orEmpty().split("、").filter { it.isNotBlank() && it != DRY_EYE_NO_SYMPTOM }
        val symptomFrequent = fields["症状频率"] in setOf("经常", "几乎总是")
        val profileAnswered = fields.containsKey("干眼症状")
        val symptomEvidence = Evidence(
            key = "symptom", title = "眼部不适", status = when {
                !profileAnswered -> EvidenceStatus.UNAVAILABLE
                symptomFrequent -> EvidenceStatus.ABNORMAL
                else -> EvidenceStatus.NORMAL
            },
            summary = when {
                !profileAnswered -> "未填写问卷"
                symptoms.isEmpty() -> "没有报告眼部不适"
                symptomFrequent -> "经常有${symptoms.first().removePrefix("眼睛")}等不适"
                else -> "偶尔有${symptoms.first().removePrefix("眼睛")}等不适"
            },
            details = if (symptoms.isEmpty()) emptyList() else listOf("报告的不适：${symptoms.joinToString("、")}", "出现频率：${fields["症状频率"] ?: "—"}"),
        )
        val evidence = listOf(blinkEvidence, visualEvidence, symptomEvidence)

        val objective = listOf(blinkStatus, visualStatus)
        val abnormalFamilies = objective.count { it == EvidenceStatus.ABNORMAL }
        val risk = when {
            reasons.isNotEmpty() -> ScreeningRiskLevel.UNKNOWN
            objective.all { it == EvidenceStatus.UNAVAILABLE } -> ScreeningRiskLevel.UNKNOWN
            abnormalFamilies >= 2 || (abnormalFamilies == 1 && symptomFrequent) -> ScreeningRiskLevel.HIGH
            abnormalFamilies == 1 -> ScreeningRiskLevel.MEDIUM
            objective.any { it != EvidenceStatus.NORMAL } -> ScreeningRiskLevel.UNCERTAIN
            else -> ScreeningRiskLevel.LOW
        }
        val validity = if (reasons.isEmpty() && risk == ScreeningRiskLevel.UNKNOWN)
            listOf(blinkEvidence.summary, visualEvidence.summary) else reasons
        val abnormal = evidence.filter { it.status == EvidenceStatus.ABNORMAL && it.key != "symptom" }.map { it.summary }
        val borderline = risk != ScreeningRiskLevel.UNKNOWN && objective.any { it == EvidenceStatus.UNCERTAIN }
        val advice = when (risk) {
            ScreeningRiskLevel.HIGH -> "多项指标提示泪膜稳定性可能不足，建议到眼科做泪膜破裂时间、泪液分泌及睑板腺等检查。"
            ScreeningRiskLevel.MEDIUM -> if (borderline) "有一项指标异常，另一项接近参考线；建议延长测试或闭眼休息后复测，症状持续请就诊。"
                else "有一项指标异常，建议注意用眼休息；症状持续或加重请到眼科检查。"
            ScreeningRiskLevel.UNCERTAIN -> "这次数据还不足以下结论，建议延长测试到 3 分钟或闭眼休息后复测。"
            ScreeningRiskLevel.LOW -> if (symptoms.isNotEmpty()) "本次未见明显异常，但你报告了眼部不适，建议到眼科评估。"
                else "本次未见明显异常，保持良好用眼习惯。"
            ScreeningRiskLevel.UNKNOWN -> "检测数据无效，请按提示调整距离、光线和姿势后重新测试。"
        }

        val indicators = buildList {
            add("练习结束视力（基线）：${session.baselineLevel}")
            add("自然眨眼：${naturalBlinks.size} 次（${fmt1(blinkRate)} 次/分钟，参考 10–16）")
            add("睁眼阶段眨眼 ${openBlinks.size} 次；最长持续睁眼 ${fmt1(longestOpen)} 秒（参考 ≥10 秒）")
            add("视标正确率：自然眨眼 ${pct(accNatural)}（${natural.size} 题）· 持续睁眼 ${pct(accOpen)}（${open.size} 题）")
            add("超时未答：${formal.count { it.timeout }} 题（其中连续未作答 ${unanswered.size} 题）；正确作答平均反应 ${meanRt?.let { "${it.toInt()} ms" } ?: "—"}")
            add("功能性视力（时间加权平均）：${meanLogMar?.let { fmt2(10.0.pow(-it)) } ?: "—"}；视觉维持率（旧指标，仅供参考）：${vmr?.let(::fmt2) ?: "—"}")
            add("视力下降时间（旧指标）：${declineSec?.let { "${fmt1(it)} 秒" } ?: "睁眼阶段未出现 2 档以上下降"}")
            blinkRecovery?.let { (before, after) -> add("眨眼前 2 秒正确率 ${pct(before)} → 眨眼后 2 秒 ${pct(after)}") }
            add("数据质量：有效帧 ${pct(session.validFrameRatio)} · 距离达标 ${pct(session.distanceInRangeRatio)} · 头部偏转 ${pct(session.headTurnRatio)}${fps?.let { " · 分析帧率 ${fmt1(it)} 帧/秒" } ?: ""}")
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
            "analysis_fps" to fps,
            "blink_rate_low" to rateLow,
            "blink_rate_high" to rateHigh,
            "unanswered_ratio" to unansweredRatio,
            "theta_start_logmar" to fit?.thetaLogMar,
            "natural_change_logmar" to fit?.naturalChange,
            "open_change_logmar" to fit?.openChange,
            "natural_decline_probability" to fit?.naturalDeclineProbability,
            "open_decline_probability" to fit?.openDeclineProbability,
            "best_correct_decimal" to answered.filter { it.correct }.maxOfOrNull { it.level.toDouble() },
            "blink_recovery_before" to blinkRecovery?.first,
            "blink_recovery_after" to blinkRecovery?.second,
        )
        return DryEyeReport(
            valid = risk != ScreeningRiskLevel.UNKNOWN, validityReasons = validity, riskLevel = risk,
            indicators = indicators, abnormal = abnormal, advice = advice,
            borderline = borderline,
            blinkRatePerMin = blinkRate, longestOpenSec = longestOpen, vmr = vmr,
            accuracyNatural = accNatural, accuracyOpen = accOpen, meanRtMs = meanRt,
            declineSec = declineSec, features = features, evidence = evidence, analysisFps = fps,
            qualityNotes = DryEyeReliability.notes(session, validity), qualityRows = DryEyeReliability.rows(session),
            intervalRows = DryEyeReliability.blinkIntervalRows(session),
        )
    }

    /** 连续 ≥ [DryEyeProtocol.UNANSWERED_RUN] 题超时的题目（未作答段）。孤立的超时仍按「看不清」计。 */
    fun unansweredTrials(formal: List<DryEyeTrial>): Set<DryEyeTrial> {
        val result = mutableSetOf<DryEyeTrial>()
        var run = mutableListOf<DryEyeTrial>()
        for (trial in formal.sortedBy { it.onsetSec } + listOf(null)) {
            if (trial != null && trial.timeout) { run += trial; continue }
            if (run.size >= DryEyeProtocol.UNANSWERED_RUN) result += run
            run = mutableListOf()
        }
        return result
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

    private fun fmt1(v: Double) = decimalString(v, 1)
    private fun fmt2(v: Double) = decimalString(v, 2)
    private fun pct(v: Double?) = v?.let { "${(it * 100).toInt()}%" } ?: "—"
}

/** 干眼症状里表示「没有不适」的选项。 */
const val DRY_EYE_NO_SYMPTOM = "都没有"

