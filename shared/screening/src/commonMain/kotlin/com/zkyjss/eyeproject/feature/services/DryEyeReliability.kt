package com.zkyjss.eyeproject.feature.services

import kotlin.math.sqrt

/** 采集质量的描述，不增加风险判据或临床阈值。与当时报告一同保存，历史查看不重新评分。 */
internal object DryEyeReliability {
    fun notes(session: DryEyeSession, validityReasons: List<String>): List<String> = buildList {
        addAll(validityReasons)
        if (validityReasons.isEmpty()) add("本次通过现有采集质量检查；这表示数据可用于本次分析，不代表没有干眼。")
        when {
            session.analysisFps == null -> add("未记录分析帧率，无法核实本次眨眼采样是否充分。")
            session.analysisFps < DryEyeProtocol.MIN_BLINK_FPS -> add("分析帧率不足 ${DryEyeProtocol.MIN_BLINK_FPS.toInt()} 帧/秒，可能漏记眨眼，眨眼指标不参与判断。")
        }
        if (session.resolutionLimited) add("部分小视标受屏幕像素限制，已使用本机可显示的最小档。")
        val openBlinks = session.blinks.count { it >= session.naturalSeconds && it < session.naturalSeconds + DryEyeProtocol.OPEN_SECONDS }
        if (openBlinks > 0) add("持续睁眼阶段记录到 $openBlinks 次眨眼；眨眼会打断持续睁眼，应结合最长睁眼时间解读。")
        if (session.trials.any { it.byVoice }) add("本次包含语音作答，反应时间也包含语音识别延迟。")
        add("距离为前摄估计值；距离波动和环境光等明细用于解释采集条件，不单独作为干眼结论。")
    }.distinct()

    fun rows(session: DryEyeSession): List<ResultRow> = buildList {
        add(ResultRow("测试完整性", if (session.completed) "已完成" else "未完整完成"))
        add(ResultRow("双眼可见比例", percent(session.validFrameRatio)))
        add(ResultRow("约 40 cm 距离达标比例", percent(session.distanceInRangeRatio)))
        add(ResultRow("头部明显偏转比例", percent(session.headTurnRatio)))
        add(ResultRow("分析帧率", session.analysisFps?.let { "${decimalString(it, 1)} 帧/秒" } ?: "未记录"))
        val samples = session.distanceSamplesCm
        val valid = samples.filter { it.isFinite() && it > 0f }.map(Float::toDouble)
        val mean = valid.takeIf { it.isNotEmpty() }?.average()
        val sd = mean?.let { m -> sqrt(valid.sumOf { (it - m) * (it - m) } / valid.size) }
        add(ResultRow("平均估计距离", mean?.let { "${decimalString(it, 1)} cm" } ?: "未记录"))
        add(ResultRow("距离波动（标准差）", sd?.let { "${decimalString(it, 1)} cm" } ?: "未记录"))
        add(ResultRow("无距离读数比例", if (samples.isEmpty()) "未记录" else percent(1.0 - valid.size.toDouble() / samples.size)))
        for (stage in listOf(DryEyeStage.NATURAL, DryEyeStage.OPEN)) {
            val trials = session.trials.filter { it.stage == stage }
            add(ResultRow("${stage.label}段作答", "${trials.size} 题 · 答对 ${trials.count { it.correct }} · " +
                "答错 ${trials.count { !it.correct && !it.timeout && !it.unsure }} · 看不清 ${trials.count { it.unsure }} · 超时 ${trials.count { it.timeout }}"))
        }
        val confirmed = session.trials.mapNotNull { it.presentDelayMs }.sorted()
        add(ResultRow("视标呈现确认", when {
            session.trials.isEmpty() -> "未记录"
            confirmed.isEmpty() -> "界面未回报，反应时从发出显示时起算"
            else -> "${confirmed.size}/${session.trials.size} 题由界面确认已画出（中位延迟 ${confirmed[confirmed.size / 2]} 毫秒）"
        }))
        add(ResultRow("连续未作答", "${DryEyeAssessment.unansweredTrials(session.trials).size} 题（连续至少 ${DryEyeProtocol.UNANSWERED_RUN} 题超时）"))
        add(ResultRow("屏幕显示限制", if (session.resolutionLimited) "已达到本机最小视标" else "未触及像素下限"))
    }

    /**
     * 按「距上次眨眼多久出现视标」分箱的作答（描述性，不参与判断）：题数、答对率、正确作答平均反应。
     * 对应原文「眨眼间期」思路——若泪膜不稳，越久不眨眼越容易看错、反应越慢。空箱写「无数据」，不当作零；
     * 每箱样本往往只有几题，只用于观察趋势。
     */
    fun blinkIntervalRows(session: DryEyeSession): List<ResultRow> {
        val trials = session.trials.filter { it.stage != DryEyeStage.PRACTICE }
        val blinks = session.blinks.sorted()
        if (trials.isEmpty()) return emptyList()
        val beforeFirst = trials.filter { t -> blinks.none { it <= t.onsetSec } }
        val bins = INTERVAL_EDGES.zipWithNext() + (INTERVAL_EDGES.last() to Float.POSITIVE_INFINITY)
        return bins.map { (from, to) ->
            val inBin = trials.filter { t ->
                val last = blinks.lastOrNull { it <= t.onsetSec } ?: return@filter false
                (t.onsetSec - last).let { it >= from && it < to }
            }
            val label = if (to.isInfinite()) "距上次眨眼 ≥ ${from.toInt()} 秒" else "距上次眨眼 ${from.toInt()}–${to.toInt()} 秒"
            ResultRow(label, describe(inBin))
        } + ResultRow("首次眨眼前", describe(beforeFirst))
    }

    private fun describe(trials: List<DryEyeTrial>): String {
        if (trials.isEmpty()) return "无数据"
        val correctRt = trials.filter { it.correct }.mapNotNull { it.rtMs }
        return "${trials.size} 题 · 答对 ${percent(trials.count { it.correct }.toDouble() / trials.size)}" +
            (correctRt.takeIf { it.isNotEmpty() }?.let { " · 正确作答平均 ${it.average().toInt()} 毫秒" } ?: "")
    }

    private val INTERVAL_EDGES = listOf(0f, 1f, 2f, 3f, 4f)

    private fun percent(value: Double) = if (value.isFinite()) "${(value * 100).toInt()}%" else "未记录"
}
