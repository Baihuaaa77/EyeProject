package com.zkyjss.eyeproject.feature.services

/*
 * 结果页的展示模型：安卓 Compose 与苹果 SwiftUI 渲染同一份内容，文案与判定只在这里写一次。
 *
 * 排版约定（两端一致）：结论卡（小瞳 + 等级 + 一句结论 + 一句建议）→ 至多 4 个关键指标 →
 * 判断依据（每族一行，点开看明细）→ 操作按钮（延长/复测/重测、查看数据、导出）→ 一行说明。
 * 明细与原始数据全部收在「本次测试数据」里，首屏不出现成段小字。
 */

/** 小瞳在结论卡上的神情。 */
enum class ResultMood { HAPPY, THINKING, CONCERNED, SLEEPY }

data class ResultMetric(val label: String, val value: String, val status: EvidenceStatus)
data class ResultRow(val label: String, val value: String)
data class ResultSection(val title: String, val rows: List<ResultRow>)

data class ResultReport(
    val kind: String,
    val level: ScreeningRiskLevel,
    val badge: String,
    val title: String,
    val advice: String,
    val mood: ResultMood,
    val metrics: List<ResultMetric>,
    val evidence: List<Evidence>,
    /** 数据无效时「没测好的原因」，此时不展示指标与依据。 */
    val problems: List<String>,
    val sections: List<ResultSection>,
    val version: String,
) {
    val valid: Boolean get() = level != ScreeningRiskLevel.UNKNOWN
}

object ResultReports {
    const val FOOTNOTE = "手机初筛结果仅供参考，不能代替医院检查。"

    private fun mood(level: ScreeningRiskLevel) = when (level) {
        ScreeningRiskLevel.LOW -> ResultMood.HAPPY
        ScreeningRiskLevel.UNCERTAIN -> ResultMood.THINKING
        ScreeningRiskLevel.MEDIUM, ScreeningRiskLevel.HIGH -> ResultMood.CONCERNED
        ScreeningRiskLevel.UNKNOWN -> ResultMood.SLEEPY
    }

    // region 干眼

    fun dryEye(state: DryEyeTestUiState): ResultReport? {
        val report = state.report ?: return null
        val f = report.features
        val level = report.riskLevel
        val title = when (level) {
            ScreeningRiskLevel.LOW -> "未见明显干眼迹象"
            ScreeningRiskLevel.MEDIUM -> "有一些干眼迹象"
            ScreeningRiskLevel.HIGH -> "干眼迹象比较明显"
            ScreeningRiskLevel.UNCERTAIN -> "结果还不能确定"
            ScreeningRiskLevel.UNKNOWN -> "这次数据无效"
        }
        val fps = f["analysis_fps"]
        val blinkOk = fps == null || fps >= DryEyeProtocol.MIN_BLINK_FPS
        val rate = report.blinkRatePerMin
        val rateStatus = if (!blinkOk || rate == null) EvidenceStatus.UNAVAILABLE else guardBand(rate,
            f["blink_rate_low"] ?: rate, f["blink_rate_high"] ?: rate, DryEyeProtocol.BLINK_RATE_MIN, DryEyeProtocol.BLINK_RATE_MAX)
        val open = report.longestOpenSec
        val openStatus = if (!blinkOk || open == null) EvidenceStatus.UNAVAILABLE else {
            val margin = maxOf(0.3, 2.0 / (fps ?: 30.0))
            guardBand(open, open - margin, open + margin, DryEyeProtocol.OPEN_INTERVAL_MIN_SEC)
        }
        val visual = report.evidence.firstOrNull { it.key == "visual" }
        val change = listOfNotNull(f["natural_change_logmar"], f["open_change_logmar"]).maxOrNull()
        val metrics = listOf(
            ResultMetric("眨眼频率", rate?.let { "${it.toInt()} 次/分" } ?: "—", rateStatus),
            ResultMetric("最长坚持睁眼", open?.let { "${it.toInt()} 秒" } ?: "—", openStatus),
            ResultMetric("看 E 时视力", change?.let(VisualStability::lines) ?: "—", visual?.status ?: EvidenceStatus.UNAVAILABLE),
            ResultMetric("看 E 答对率", pct(listOfNotNull(report.accuracyNatural, report.accuracyOpen).takeIf { it.isNotEmpty() }?.average()), EvidenceStatus.UNAVAILABLE),
        )
        val sections = buildList {
            add(ResultSection("眨眼", listOf(
                ResultRow("自然眨眼时长", "${state.naturalSeconds} 秒"),
                ResultRow("自然眨眼次数", "${state.phase1BlinkCount} 次"),
                ResultRow("眨眼频率", rate?.let { "${fmt1(it)} 次/分（误差 ${fmt1(f["blink_rate_low"])}–${fmt1(f["blink_rate_high"])}）" } ?: "—"),
                ResultRow("坚持睁眼段眨眼", "${state.phase2BlinkCount} 次"),
                ResultRow("最长坚持睁眼", open?.let { "${fmt1(it)} 秒" } ?: "—"),
                ResultRow("相机分析帧率", fps?.let { "${fmt1(it)} 帧/秒" } ?: "—"),
            )))
            add(ResultSection("看 E 作答", listOf(
                ResultRow("答对率（自然眨眼）", pct(report.accuracyNatural)),
                ResultRow("答对率（坚持睁眼）", pct(report.accuracyOpen)),
                ResultRow("正确作答平均反应", report.meanRtMs?.let { "${it.toInt()} 毫秒" } ?: "—"),
                ResultRow("连续未作答比例", pct(f["unanswered_ratio"])),
                ResultRow("起始视力估计", f["theta_start_logmar"]?.let { VisualStability.acuityText(it, f["best_correct_decimal"]) } ?: "—"),
                ResultRow("自然眨眼段视力变化", f["natural_change_logmar"]?.let(VisualStability::lines) ?: "—"),
                ResultRow("坚持睁眼段视力变化", f["open_change_logmar"]?.let(VisualStability::lines) ?: "—"),
                ResultRow("视觉维持率（旧指标）", f["vmr"]?.let { decimalString(it, 2) } ?: "—"),
                ResultRow("眨眼前 → 后 2 秒答对率", if (f["blink_recovery_before"] != null) "${pct(f["blink_recovery_before"])} → ${pct(f["blink_recovery_after"])}" else "—"),
            )))
            add(ResultSection("数据质量", listOf(
                ResultRow("双眼可见的帧", pct(f["valid_frame_ratio"])),
                ResultRow("距离在 40 cm 附近", pct(f["distance_in_range_ratio"])),
                ResultRow("头部明显偏转", pct(f["head_turn_ratio"])),
            )))
            state.previousReport?.let { previous -> add(ResultSection("与上一次对比", listOf(
                ResultRow("眨眼频率", "${fmt1(previous.blinkRatePerMin)} → ${fmt1(report.blinkRatePerMin)} 次/分"),
                ResultRow("最长坚持睁眼", "${fmt1(previous.longestOpenSec)} → ${fmt1(report.longestOpenSec)} 秒"),
                ResultRow("坚持睁眼答对率", "${pct(previous.accuracyOpen)} → ${pct(report.accuracyOpen)}"),
                ResultRow("结论", "${previous.riskLevel.label} → ${report.riskLevel.label}"),
            ))) }
            profileSection(dryEyeQuestions, state.fields, "我的问卷回答")?.let(::add)
            add(ResultSection("完整指标", report.indicators.map { ResultRow("", it) }))
        }
        return ResultReport(
            kind = "DRY_EYE", level = level,
            badge = when (level) {
                ScreeningRiskLevel.UNKNOWN -> "需要重测"
                ScreeningRiskLevel.UNCERTAIN -> "结果不确定"
                else -> "干眼${level.label}"
            },
            title = if (state.afterRest) "休息后复测：$title" else title,
            advice = report.advice, mood = mood(level), metrics = metrics, evidence = report.evidence,
            problems = if (report.valid) emptyList() else report.validityReasons,
            sections = sections, version = DryEyeAssessment.VERSION,
        )
    }

    // endregion

    // region 老视与斜视

    fun screening(s: ScreeningState): ResultReport? {
        if (s.step != ScreeningStep.RESULT) return null
        val strabismus = s.kind == ScreeningKind.STRABISMUS
        val level = s.riskLevel
        val valid = level != ScreeningRiskLevel.UNKNOWN
        val badge = when {
            !valid -> "需要重测"
            level == ScreeningRiskLevel.UNCERTAIN -> "结果不确定"
            strabismus -> when (level) {
                ScreeningRiskLevel.LOW -> "未见异常"
                ScreeningRiskLevel.MEDIUM -> "可疑"
                else -> "高度疑似"
            }
            else -> "老视${level.label}"
        }
        val title = when {
            !valid -> "数据不足，暂时无法判断"
            strabismus -> when (level) {
                ScreeningRiskLevel.LOW -> "眼位未见明显异常"
                ScreeningRiskLevel.MEDIUM -> "眼位有可疑表现"
                ScreeningRiskLevel.UNCERTAIN -> "眼位结果还不能确定"
                else -> "高度疑似斜视"
            }
            else -> s.category.ifBlank { level.label }
        }
        val advice = when {
            !valid -> "关键数据不足，请改善测试条件并重新完成测试。"
            level == ScreeningRiskLevel.UNCERTAIN -> if (strabismus) "部分指标在测量误差范围内，建议在光线均匀处、保持 33 cm 再测一次。"
                else "调节能力接近同龄参考线，建议按提示放慢移近再测一次。"
            strabismus && level == ScreeningRiskLevel.HIGH -> "建议尽快到斜视专科就诊，做遮盖试验与三棱镜检查。"
            strabismus && level == ScreeningRiskLevel.MEDIUM -> "建议近期到眼科复查眼位；平时留意孩子是否歪头、眯眼或一只眼往外飘。"
            strabismus -> "本次未见明显眼位异常，建议每半年复测一次。"
            level == ScreeningRiskLevel.HIGH -> "建议到眼科验光并检查调节功能，评估是否需要配老花镜。"
            level == ScreeningRiskLevel.MEDIUM -> "建议用同样的眼镜再测一次；看近处吃力时请到眼科检查。"
            !s.mode.startsWith("标准") -> "戴这副眼镜看近基本够用；如果看近处不舒服，仍建议到眼科检查。"
            else -> "调节能力与年龄基本相符；如果看近处不舒服，仍建议到眼科检查。"
        }
        val f = s.features
        fun status(key: String) = s.evidence.firstOrNull { it.key == key }?.status ?: EvidenceStatus.UNAVAILABLE
        val metrics = if (strabismus) listOf(
            ResultMetric("水平偏斜", f["fixation_hde_prism"]?.let { "${fmt1(kotlin.math.abs(it))} Δ" } ?: "—", status("alignment")),
            ResultMetric("垂直偏斜", f["fixation_vde_prism"]?.let { "${fmt1(kotlin.math.abs(it))} Δ" } ?: "—", status("alignment")),
            ResultMetric("遮盖后眼睛移动", f["cover_refix_median_prism"]?.let { "${fmt1(it)} Δ" } ?: "—", status("cover")),
            ResultMetric("双眼协调", f["bsi"]?.let { decimalString(it, 2) } ?: "—", status("coordination")),
        ) else listOf(
            ResultMetric("调节能力", f["amplitude_d"]?.let { "${fmt1(it)} D" } ?: "≥ ${fmt1(PresbyopiaProtocol.MAX_AMPLITUDE_D)} D", status("amplitude")),
            ResultMetric("看清最近距离", f["near_point_cm"]?.let { "${it.toInt()} cm" } ?: "< 12 cm", status("amplitude")),
            ResultMetric("40 cm 双眼视力", f["near_both_decimal"]?.let { decimalString(it, 1) } ?: "< 0.1", status("near")),
            f["amplitude_age_years"]?.let { ResultMetric("调节年龄参考", "约 ${it.toInt()} 岁", EvidenceStatus.UNAVAILABLE) }
                ?: ResultMetric("远近切换", f["flipper_cycles_per_min"]?.let { "${it.toInt()} 次/分" } ?: "—", EvidenceStatus.UNAVAILABLE),
        )
        val sections = buildList {
            if (strabismus) {
                add(ResultSection("注视眼位", listOf(
                    ResultRow("水平偏斜", "${fmt1(f["fixation_hde_prism"])} Δ（误差 ±${fmt1(f["fixation_hde_ci_half_prism"])}）"),
                    ResultRow("垂直偏斜", "${fmt1(f["fixation_vde_prism"])} Δ（误差 ±${fmt1(f["fixation_vde_ci_half_prism"])}）"),
                    ResultRow("逐帧标准差", "${fmt1(f["fixation_hde_sd_prism"])} / ${fmt1(f["fixation_vde_sd_prism"])} Δ"),
                    ResultRow("有效帧 / 最长连续", "${f["fixation_frames"]?.toInt() ?: 0} 帧 / ${fmt1((f["fixation_continuous_ms"] ?: 0.0) / 1000)} 秒"),
                )))
                add(ResultSection("交替遮盖", listOf(
                    ResultRow("再注视中位偏移", "${fmt1(f["cover_refix_median_prism"])} Δ"),
                    ResultRow("可用事件", "${f["cover_refix_events"]?.toInt() ?: 0} 次"),
                    ResultRow("无遮盖时的抖动", f["cover_noise_floor_prism"]?.let { "${fmt1(it)} Δ" } ?: "—"),
                    ResultRow("协助者点按", "${s.coverMarks.size}/${StrabismusProtocol.COVER_SEQUENCE.size} 个动作"),
                )))
                add(ResultSection("跟随移动目标", listOf(
                    ResultRow("双眼对称性指数 BSI", f["bsi"]?.let { decimalString(it, 2) } ?: "—"),
                    ResultRow("水平 / 垂直速度相关", "${f["pursuit_r_h"]?.let { decimalString(it, 2) } ?: "—"} / ${f["pursuit_r_v"]?.let { decimalString(it, 2) } ?: "—"}"),
                    ResultRow("屏幕可显示幅度", "水平 ±${fmt1(f["pursuit_max_x_deg"])}° · 垂直 ±${fmt1(f["pursuit_max_y_deg"])}°"),
                )))
                add(ResultSection("数据质量", listOf(
                    ResultRow("可测阶段有效帧", pct(f["quality_rate"])),
                    ResultRow("光学测量有效帧", "${s.opticalValidFrames} 帧"),
                    ResultRow("相机帧", "${s.cameraFrames} 帧"),
                )))
            } else {
                val near = s.answers.filter { it.step == ScreeningStep.NEAR_VISION }
                add(ResultSection("40 cm 近视力", listOf(
                    ResultRow("右眼", f["near_right_decimal"]?.let { decimalString(it, 1) } ?: "未通过最大档"),
                    ResultRow("左眼", f["near_left_decimal"]?.let { decimalString(it, 1) } ?: "未通过最大档"),
                    ResultRow("双眼", f["near_both_decimal"]?.let { decimalString(it, 1) } ?: "未通过最大档"),
                    ResultRow("作答", "${near.size} 题，答对 ${near.count { it.correct }} 题"),
                )))
                add(ResultSection("移近法（看清最近距离）", s.nearPoints.mapIndexed { i, p ->
                    ResultRow("第 ${i + 1} 次", if (p.censored) "< ${PresbyopiaProtocol.PUSH_MIN_CM.toInt()} cm 仍清晰" else
                        "${fmt1(p.distanceCm.toDouble())} cm · 速度 ${p.speedCmPerSec?.let { fmt1(it.toDouble()) } ?: "—"} cm/秒")
                } + listOf(
                    ResultRow("调节幅度", "${fmt1(f["amplitude_d"])} D（误差 ${fmt1(f["amplitude_low_d"])}–${fmt1(f["amplitude_high_d"])}）"),
                    ResultRow("同龄参考", "最低 ${fmt1(f["hofstetter_min_d"])} D · 平均 ${fmt1(f["hofstetter_mean_d"])} D"),
                )))
                add(ResultSection("不同距离的视力", s.curvePoints.map { c ->
                    ResultRow("${c.distanceCm.toInt()} cm", c.thresholdDecimal?.let { decimalString(it.toDouble(), 1) } ?: "未通过")
                }))
                add(ResultSection("远近切换（仅供参考）", listOf(
                    ResultRow("1 分钟完成", "${f["flipper_cycles_per_min"]?.toInt() ?: 0} 次"),
                    ResultRow("答错", "${f["flipper_errors"]?.toInt() ?: 0} 题"),
                )))
                add(ResultSection("测试条件", listOf(
                    ResultRow("戴镜方式", if (s.mode.startsWith("标准")) "看远的眼镜或不戴镜" else "看近用的眼镜"),
                    ResultRow("作废的移近", "${s.invalidTrialCount} 次"),
                )))
            }
            if (s.history.isNotEmpty()) add(ResultSection("与上次对比", s.history.map { ResultRow("", it) }))
            profileSection(if (strabismus) strabismusQuestions else presbyopiaQuestions, s.fields,
                if (strabismus) "孩子的基本信息" else "我的问卷回答")?.let(::add)
            add(ResultSection("完整报告", s.result.map { ResultRow("", it) }))
        }
        return ResultReport(
            kind = s.kind.name, level = level, badge = badge, title = title, advice = advice, mood = mood(level),
            metrics = metrics, evidence = s.evidence, problems = if (valid) emptyList() else s.findings,
            sections = sections, version = ScreeningAssessmentModel.VERSION,
        )
    }

    // endregion

    private fun profileSection(questions: List<Question>, fields: Map<String, String>, title: String): ResultSection? =
        questions.filter { !fields[it.key].isNullOrBlank() }.map { ResultRow(it.title, fields[it.key].orEmpty()) }
            .takeIf { it.isNotEmpty() }?.let { ResultSection(title, it) }

    private fun fmt1(v: Double?) = v?.let { decimalString(it, 1) } ?: "—"
    private fun pct(v: Double?) = v?.let { "${(it * 100).toInt()}%" } ?: "—"
}

/** 苹果端经快照 JSON 读取结果页。 */
internal fun ResultReport.toRecordObject(): RecordObject = RecordObject().apply {
    put("kind", kind); put("level", level.name); put("badge", badge); put("title", title); put("advice", advice)
    put("mood", mood.name); put("valid", valid); put("version", version); put("footnote", ResultReports.FOOTNOTE)
    put("metrics", RecordArray(metrics.map { m -> RecordObject().apply {
        put("label", m.label); put("value", m.value); put("status", m.status.name)
    } }))
    put("evidence", evidence.toRecordArray())
    put("problems", RecordArray(problems))
    put("sections", RecordArray(sections.map { section -> RecordObject().apply {
        put("title", section.title)
        put("rows", RecordArray(section.rows.map { r -> RecordObject().apply { put("label", r.label); put("value", r.value) } }))
    } }))
}
