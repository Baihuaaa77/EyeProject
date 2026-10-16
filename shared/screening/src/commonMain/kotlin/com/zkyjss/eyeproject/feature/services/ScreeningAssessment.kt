package com.zkyjss.eyeproject.feature.services

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 风险等级仅供研究原型演示；UNKNOWN 不能解释为低风险。
 * UNCERTAIN（rules-2 起）：数据有效，但指标落在测量误差范围内、无法区分正常与异常——建议延长测试或复测，
 * 不给出居中等级，也不能解释为低风险。
 */
enum class ScreeningRiskLevel(val label: String) {
    LOW("低风险"), MEDIUM("中风险"), HIGH("高风险"), UNCERTAIN("结果不确定"), UNKNOWN("无法评估"),
}

/** 按病种的结果用语：斜视按原文输出「未见异常 / 可疑阳性 / 高度疑似斜视」三级。 */
fun ScreeningRiskLevel.labelFor(kind: ScreeningKind): String = when (kind) {
    ScreeningKind.STRABISMUS -> when (this) {
        ScreeningRiskLevel.LOW -> "未见异常"
        ScreeningRiskLevel.MEDIUM -> "可疑阳性"
        ScreeningRiskLevel.HIGH -> "高度疑似斜视"
        ScreeningRiskLevel.UNCERTAIN -> "结果不确定，建议复测"
        ScreeningRiskLevel.UNKNOWN -> "测试质量不佳，请重新测试"
    }
    ScreeningKind.PRESBYOPIA -> label
}

/** 未经临床标定的探索性评估；所有数值都保持原始物理或归一化单位。 */
data class ScreeningAssessment(
    val version: String,
    val quality: String,
    val riskLevel: ScreeningRiskLevel,
    val riskScope: String,
    val category: String,
    val features: Map<String, Double?>,
    val findings: List<String>,
    val limitations: List<String>,
    /** 判定依据（证据族）；结果页「判断依据」与导出记录据此展示。 */
    val evidence: List<Evidence> = emptyList(),
) {
    fun displayLines(): List<String> = buildList {
        add("评估依据：$category（数据质量：$quality；$version）")
        addAll(findings)
        addAll(limitations)
    }
}

object ScreeningAssessmentModel {
    /**
     * rules-2：调节幅度带测距误差区间、日常模式不与同龄比较、远近切换不参与分级；斜视眼位按中位数误差判定、
     * 再注视扣除噪声底、双眼协调受屏幕幅度限制时不评估；新增「结果不确定」。
     */
    const val VERSION = "protocol-9.18-rules-2"

    // region 老视

    fun presbyopia(state: ScreeningState): ScreeningAssessment {
        val near = state.answers.filter { it.step == ScreeningStep.NEAR_VISION }
        val flip = state.answers.filter { it.step == ScreeningStep.FLIPPER }
        val points = state.nearPoints.filter { !it.censored && it.distanceCm > 0f }
        fun threshold(eye: VisionEye): Double? = near.filter { it.eye == eye }.groupBy { it.level }
            .filterValues { it.size >= 4 && it.count(VisionAnswer::correct) >= 3 }.keys.maxOrNull()?.toDouble()
        val right = threshold(VisionEye.RIGHT)
        val left = threshold(VisionEye.LEFT)
        val both = threshold(VisionEye.BOTH)
        val reducedEyes = listOf(VisionEye.RIGHT to right, VisionEye.LEFT to left, VisionEye.BOTH to both)
            .filter { (_, value) -> value == null || value < 0.5 }.map { it.first }
        val pointCm = median(points.map { it.distanceCm.toDouble() })
        val pointMad = pointCm?.let { mid -> median(points.map { abs(it.distanceCm - mid) }) }
        val censoredNear = state.nearPoints.count { it.censored }
        val amplitude = pointCm?.let { 100.0 / it }
        val speed = median(points.mapNotNull { it.speedCmPerSec?.toDouble() })
        val age = state.fields["年龄"]?.toDoubleOrNull()
        val minAmp = age?.let(PresbyopiaProtocol::minimumAmplitude)
        val avgAmp = age?.let(PresbyopiaProtocol::averageAmplitude)
        // 标准模式：测试时摘下老花镜/渐进镜，戴看远的眼镜或不戴镜
        val standardMode = state.mode.startsWith("标准")
        val ampAge = if (standardMode) amplitude?.let(PresbyopiaProtocol::amplitudeAge) else null
        val cycles = flip.chunked(2).count { it.size == 2 && it.all(VisionAnswer::correct) }
        val nearFlip = flip.filter { it.distanceCm < 35f }
        val farFlip = flip.filter { it.distanceCm >= 35f }
        val curve = state.curvePoints
        val features = linkedMapOf<String, Double?>(
            "age_years" to age,
            "near_right_decimal" to right, "near_left_decimal" to left, "near_both_decimal" to both,
            "near_point_cm" to pointCm,
            "near_point_mad_cm" to pointMad,
            "amplitude_d" to amplitude,
            "push_speed_cm_s" to speed,
            "hofstetter_min_d" to minAmp,
            "hofstetter_mean_d" to avgAmp,
            "amplitude_age_years" to ampAge,
            "flipper_cycles_per_min" to cycles.toDouble(),
            "flipper_errors" to flip.count { !it.correct }.toDouble(),
            "curve_60_decimal" to curve.firstOrNull { it.distanceCm == 60f }?.thresholdDecimal?.toDouble(),
            "curve_33_decimal" to curve.firstOrNull { it.distanceCm == 33f }?.thresholdDecimal?.toDouble(),
            "curve_25_decimal" to curve.firstOrNull { it.distanceCm == 25f }?.thresholdDecimal?.toDouble(),
            "daily_correction_mode" to if (standardMode) 0.0 else 1.0,
            "near_correction_in_use" to if (nearCorrectionInUse(state.fields)) 1.0 else 0.0,
            "invalid_trials" to state.invalidTrialCount.toDouble(),
        )
        val qualityReasons = buildList {
            if (VisionEye.entries.any { eye -> near.count { it.eye == eye } < 4 }) add("单眼或双眼近视力题数不足")
            if (points.size < 2 && censoredNear < 2) add("有效移近法测量不足 2 次")
            if (pointMad != null && pointMad > 5.0) add("近点重复测量差异较大（> 5 cm）")
            if (flip.size < 4 || nearFlip.isEmpty() || farFlip.isEmpty()) add("远近切换两种距离的数据不足")
            if (curve.count { it.thresholdDecimal != null } < 2) add("多距离视力有效距离不足 2 个")
            if (age == null) add("缺少年龄，无法与同龄参考对照")
        }
        val riskScope = if (standardMode) "标准远用矫正下的残余调节能力（老视风险）" else "日常矫正条件下的近距离视觉表现"
        if (qualityReasons.isNotEmpty()) return ScreeningAssessment(VERSION, "不足", ScreeningRiskLevel.UNKNOWN,
            riskScope, "暂无法评估，请复测", features, qualityReasons,
            listOf("前置相机测距未经实物校准；近点为主观持续模糊终点。"))

        // 原文：以 15 − 0.25×年龄 为最小调节幅度参考，结合 40 cm 阅读需求与调节储备判断。
        // rules-2：调节幅度带测距误差范围；只有误差范围整体低于参考才算偏低，越线但范围覆盖参考线 → 不确定。
        age!!
        val effectiveAmp = amplitude ?: (100.0 / PresbyopiaProtocol.PUSH_MIN_CM) // 近点 < 12 cm 时按下限计
        val (ampLow, ampHigh) = PresbyopiaProtocol.amplitudeRange(points.map { it.distanceCm.toDouble() }, censoredNear > 0)
        val refractionConfounded = standardMode && uncorrectedRefraction(state.fields)
        val ampStatus = when {
            !standardMode -> EvidenceStatus.UNAVAILABLE
            refractionConfounded -> EvidenceStatus.UNCERTAIN
            else -> guardBand(effectiveAmp, ampLow, ampHigh, normalMin = minAmp!!)
        }
        // 较早老视样下降：45 岁前、可靠地低于同龄最小值且不足舒适阅读所需（误差范围上界都不够）
        val early = ampStatus == EvidenceStatus.ABNORMAL && age < 45 && ampHigh < PresbyopiaProtocol.COMFORT_RESERVE_D
        val nearStatus = if (reducedEyes.isEmpty()) EvidenceStatus.NORMAL else EvidenceStatus.ABNORMAL
        val status = when {
            !standardMode -> if (reducedEyes.isEmpty()) "戴日常眼镜时看近基本够用" else "戴日常眼镜时看近仍较吃力"
            early -> "存在较早老视样功能下降"
            ampStatus == EvidenceStatus.ABNORMAL -> "调节功能较同龄人偏低"
            ampStatus == EvidenceStatus.UNCERTAIN -> "调节能力接近同龄参考线，建议复测"
            else -> "调节能力与实际年龄基本匹配"
        }
        val ampRangeText = "${fmt(ampLow)}–${if (ampHigh >= PresbyopiaProtocol.MAX_AMPLITUDE_D) "${fmt(ampHigh)}+" else fmt(ampHigh)} D"
        val evidence = listOf(
            Evidence("amplitude", "调节能力与同龄人比较", ampStatus,
                when {
                    !standardMode -> "戴看近用的眼镜测试，不与同龄人比较调节能力"
                    refractionConfounded -> "自述有近视或远视但未戴看远的眼镜，调节能力可能被高估或低估"
                    early -> "调节能力明显低于同龄最低参考，也不够舒适阅读"
                    ampStatus == EvidenceStatus.ABNORMAL -> "调节能力明显低于同龄最低参考（${fmt(minAmp!!)} D）"
                    ampStatus == EvidenceStatus.UNCERTAIN -> "调节能力略低于同龄参考，但在测量误差范围内"
                    else -> "调节能力不低于同龄最低参考（${fmt(minAmp!!)} D）"
                },
                buildList {
                    add("看清最近距离 ${pointCm?.let { decimalString(it, 1) + " cm" } ?: "< 12 cm"} → 调节幅度约 ${fmt(effectiveAmp)} D（考虑测距误差 ${ampRangeText}）")
                    minAmp?.let { add("同龄参考：最低 ${fmt(it)} D、平均 ${fmt(avgAmp!!)} D（Hofstetter 公式，${age.toInt()} 岁）") }
                    add("40 cm 阅读需调节 ${fmt(PresbyopiaProtocol.READING_DEMAND_D)} D，舒适阅读约需 ${fmt(PresbyopiaProtocol.COMFORT_RESERVE_D)} D")
                    pointMad?.let { add("近点 ${points.size} 次测量差异（中位绝对偏差）${decimalString(it, 1)} cm") }
                    add("判定：误差范围整体低于同龄最低参考才算偏低")
                }),
            Evidence("near", "40 cm 看近视力", nearStatus,
                if (reducedEyes.isEmpty()) "右眼、左眼、双眼在 40 cm 都能看清 0.5 及以上"
                else "${reducedEyes.joinToString("、") { it.label }}在 40 cm 看近视力低于 0.5",
                listOf("右眼 ${right?.let(::fmt) ?: "未通过最大档"} · 左眼 ${left?.let(::fmt) ?: "未通过最大档"} · 双眼 ${both?.let(::fmt) ?: "未通过最大档"}",
                    "每档 4 题、对 3 题算通过；参考 ≥ 0.5")),
        )
        val findings = buildList {
            if (standardMode) add("$status：调节幅度约 ${fmt(effectiveAmp)} D（近点 ${pointCm?.let { decimalString(it, 1) + " cm" } ?: "< 12 cm"}，误差范围 ${ampRangeText}），同龄最小参考 ${minAmp?.let(::fmt) ?: "—"} D、平均 ${avgAmp?.let(::fmt) ?: "—"} D。")
            else add("$status：戴日常眼镜时调节幅度约 ${fmt(effectiveAmp)} D（含眼镜的近用度数，不与同龄人比较）。")
            add("40 cm 阅读需调节 ${fmt(PresbyopiaProtocol.READING_DEMAND_D)} D；舒适阅读一般需约 ${fmt(PresbyopiaProtocol.COMFORT_RESERVE_D)} D（保留一半储备），本次${if (effectiveAmp >= PresbyopiaProtocol.COMFORT_RESERVE_D) "可满足" else "不足"}。")
            if (reducedEyes.isNotEmpty()) add("${reducedEyes.joinToString("、") { it.label }}在 40 cm 处看近的视力低于 0.5，看小字可能吃力。")
            add("远近切换 1 分钟完成 $cycles 次（含移动手机的时间，仅作参考，不参与判断）。")
            ampAge?.let { add("调节年龄参考约 ${it.toInt()} 岁（按平均调节幅度公式反推，未经验证，仅供参考）。") }
        }
        val riskLevel = when {
            early || (ampStatus == EvidenceStatus.ABNORMAL && nearStatus == EvidenceStatus.ABNORMAL) -> ScreeningRiskLevel.HIGH
            ampStatus == EvidenceStatus.ABNORMAL || nearStatus == EvidenceStatus.ABNORMAL -> ScreeningRiskLevel.MEDIUM
            ampStatus == EvidenceStatus.UNCERTAIN -> ScreeningRiskLevel.UNCERTAIN
            else -> ScreeningRiskLevel.LOW
        }
        features["amplitude_low_d"] = ampLow
        features["amplitude_high_d"] = ampHigh
        return ScreeningAssessment(VERSION, "可用于探索性评估", riskLevel, riskScope, status, features, findings,
            buildList {
                if (!standardMode) add("戴着看近用的眼镜测试，结果反映的是戴镜后的效果，不代表眼睛本身的调节能力；可摘下老花镜再测一次对比。")
                add("前置相机测距及屏幕像素密度未经实物校准；分级规则未经临床验证，需结合验光与调节检查解释。")
            }, evidence)
    }

    /** 标准模式下自述近视/远视却不戴眼镜：未矫正屈光不正会让移近法的调节幅度失真。 */
    private fun uncorrectedRefraction(fields: Map<String, String>): Boolean =
        fields.hasAny("屈光情况", "近视", "远视") && fields.hasAny("日常矫正方式", "不戴眼镜")

    // endregion

    // region 斜视

    fun strabismus(state: ScreeningState, samples: List<EyeMotionSample>, eligible: Int, eligibleValid: Int): ScreeningAssessment {
        val m = StrabismusMeasures.compute(samples, state.coverMarks)
        val qualityRate = if (eligible > 0) eligibleValid.toDouble() / eligible else 0.0
        val features = linkedMapOf<String, Double?>(
            "age_years" to state.fields["年龄"]?.toDoubleOrNull(),
            "quality_rate" to qualityRate,
            "fixation_hde_prism" to m.hdePrism,
            "fixation_vde_prism" to m.vdePrism,
            "fixation_hde_sd_prism" to m.hdeSdPrism,
            "fixation_vde_sd_prism" to m.vdeSdPrism,
            "fixation_frames" to m.fixationFrames.toDouble(),
            "fixation_continuous_ms" to m.fixationContinuousMs.toDouble(),
            "cover_refix_median_prism" to median(m.refixations.map { it.amplitudePrism }),
            "cover_refix_events" to m.refixations.size.toDouble(),
            "bsi" to m.bsi,
            "pursuit_r_h" to m.rH,
            "pursuit_r_v" to m.rV,
            "pursuit_d_norm" to m.dNorm,
            "pursuit_max_x_deg" to state.pursuitMaxDeg?.first?.toDouble(),
            "pursuit_max_y_deg" to state.pursuitMaxDeg?.second?.toDouble(),
        )
        val qualityReasons = buildList {
            addAll(PursuitGazeCheck.qualityReasons(samples, state.gazeCalibrated))
            if (qualityRate < 0.5) add("可测阶段有效帧率低于 50%")
            if (m.fixationFrames < 20 || m.fixationContinuousMs < 3_000L) add("注视任务缺少连续 3 秒的有效映光测量")
            if (m.refixations.size < 2) add("解除遮盖后的可用事件不足 2 次")
        }
        val riskScope = "近距离（33 cm）眼位偏斜及双眼协调"
        if (qualityReasons.isNotEmpty()) return ScreeningAssessment(VERSION, "不足", ScreeningRiskLevel.UNKNOWN,
            riskScope, "测试质量不佳，请重新测试", features, qualityReasons,
            listOf("请在光线均匀处、保持 33 cm、头部正对后重新测试。"))

        val h = abs(m.hdePrism ?: 0.0)
        val v = abs(m.vdePrism ?: 0.0)
        val hHalf = 1.96 * (m.hdeSePrism ?: 0.0)
        val vHalf = 1.96 * (m.vdeSePrism ?: 0.0)
        val stable = (m.hdeSdPrism ?: Double.MAX_VALUE) <= StrabismusProtocol.STABLE_SD_H_PRISM &&
            (m.vdeSdPrism ?: Double.MAX_VALUE) <= StrabismusProtocol.STABLE_SD_V_PRISM
        // 原文红旗：注视稳定且 ≥ 10Δ / 5Δ。rules-2 增加：即使逐帧抖动大（多为测量噪声），中位数的误差范围下界仍越线也算——
        // 噪声不能把高度疑似压低。测得值越线但误差范围覆盖参考线 → 不确定。
        val eyeStatus = when {
            stable && (h >= StrabismusProtocol.HIGH_H_PRISM || v >= StrabismusProtocol.HIGH_V_PRISM) -> EvidenceStatus.ABNORMAL
            h - hHalf >= StrabismusProtocol.HIGH_H_PRISM || v - vHalf >= StrabismusProtocol.HIGH_V_PRISM -> EvidenceStatus.ABNORMAL
            h >= StrabismusProtocol.HIGH_H_PRISM || v >= StrabismusProtocol.HIGH_V_PRISM -> EvidenceStatus.UNCERTAIN
            else -> EvidenceStatus.NORMAL
        }
        val refixMedian = median(m.refixations.map { it.amplitudePrism }) ?: 0.0
        val noiseFloor = m.refixNoisePrism
        val coverStatus = when {
            refixMedian < StrabismusProtocol.REFIX_PRISM -> EvidenceStatus.NORMAL
            noiseFloor != null && refixMedian <= noiseFloor -> EvidenceStatus.UNCERTAIN
            else -> EvidenceStatus.ABNORMAL
        }
        val bsi = m.bsi
        val amplitudeOk = state.pursuitMaxDeg?.let { (x, y) -> minOf(x, y) >= StrabismusProtocol.BSI_MIN_AMPLITUDE_DEG } ?: true
        val coordinationStatus = when {
            bsi == null || !amplitudeOk -> EvidenceStatus.UNAVAILABLE
            bsi < StrabismusProtocol.BSI_ABNORMAL -> EvidenceStatus.ABNORMAL
            bsi < StrabismusProtocol.BSI_SUSPECT -> EvidenceStatus.UNCERTAIN
            else -> EvidenceStatus.NORMAL
        }
        val coordination = when (coordinationStatus) {
            EvidenceStatus.ABNORMAL -> "明显异常"
            EvidenceStatus.UNCERTAIN -> "可疑协调障碍"
            EvidenceStatus.UNAVAILABLE -> "本次无法评估"
            EvidenceStatus.NORMAL -> "协调性良好"
        }
        val evidence = listOf(
            Evidence("alignment", "注视时眼位", eyeStatus,
                when (eyeStatus) {
                    EvidenceStatus.ABNORMAL -> "注视时两眼位置持续不对称（水平约 ${fmt(h)}Δ、垂直约 ${fmt(v)}Δ）"
                    EvidenceStatus.UNCERTAIN -> "眼位偏差接近判定线，但测量抖动较大，建议复测"
                    else -> "注视时两眼位置基本对称"
                },
                listOf("水平偏斜 ${fmt(h)}Δ（误差 ±${fmt(hHalf)}，判定线 ${StrabismusProtocol.HIGH_H_PRISM.toInt()}Δ）",
                    "垂直偏斜 ${fmt(v)}Δ（误差 ±${fmt(vHalf)}，判定线 ${StrabismusProtocol.HIGH_V_PRISM.toInt()}Δ）",
                    "逐帧标准差 ${fmt(m.hdeSdPrism ?: 0.0)}/${fmt(m.vdeSdPrism ?: 0.0)}Δ（${if (stable) "注视稳定" else "抖动较大"}），有效 ${m.fixationFrames} 帧",
                    "误差范围下界越过判定线，或注视稳定且测得值越线，才判异常")),
            Evidence("cover", "遮盖后眼睛是否移动", coverStatus,
                when (coverStatus) {
                    EvidenceStatus.ABNORMAL -> "移开遮盖后眼睛明显移动（中位约 ${fmt(refixMedian)}Δ）"
                    EvidenceStatus.UNCERTAIN -> "移开遮盖后有移动，但与平时的测量抖动差不多大"
                    else -> "移开遮盖后眼睛基本不动"
                },
                buildList {
                    add("再注视中位偏移 ${fmt(refixMedian)}Δ（${m.refixations.size} 次，判定线 ${StrabismusProtocol.REFIX_PRISM.toInt()}Δ）")
                    m.refixations.forEach { add("${it.eye.label}：${fmt(it.amplitudePrism)}Δ、${fmt(it.speedDegPerSec)}°/s、残余 ${fmt(it.residualPrism)}Δ") }
                    add("没有遮盖动作时同样窗口的抖动（95% 分位）：${noiseFloor?.let { "${fmt(it)}Δ" } ?: "数据不足"}")
                }),
            Evidence("coordination", "双眼跟随协调", coordinationStatus,
                when (coordinationStatus) {
                    EvidenceStatus.ABNORMAL -> "跟随移动目标时两眼配合明显不一致"
                    EvidenceStatus.UNCERTAIN -> "跟随时两眼配合略有不一致，建议复测"
                    EvidenceStatus.UNAVAILABLE -> if (!amplitudeOk) "本机屏幕能显示的追踪幅度太小，不评估双眼协调" else "追踪数据不足，不评估双眼协调"
                    EvidenceStatus.NORMAL -> "跟随移动目标时两眼配合良好"
                },
                buildList {
                    add("双眼对称性指数 BSI ${bsi?.let(::fmt) ?: "—"}（r_h ${m.rH?.let(::fmt) ?: "—"}、r_v ${m.rV?.let(::fmt) ?: "—"}、d_norm ${m.dNorm?.let(::fmt) ?: "—"}），参考 ≥ 0.7")
                    state.pursuitMaxDeg?.let { (x, y) -> add("屏幕可显示追踪幅度 水平 ±${fmt(x.toDouble())}°、垂直 ±${fmt(y.toDouble())}°（评估需 ≥ ±${StrabismusProtocol.BSI_MIN_AMPLITUDE_DEG.toInt()}°）") }
                }),
        )
        val findings = buildList {
            add("注视眼位：水平偏斜约 ${fmt(h)}Δ、垂直约 ${fmt(v)}Δ（标准差 ${fmt(m.hdeSdPrism ?: 0.0)}/${fmt(m.vdeSdPrism ?: 0.0)}Δ，${if (stable) "注视稳定" else "注视不稳定"}）。")
            add("遮盖解除后再注视：中位偏移 ${fmt(refixMedian)}Δ（${m.refixations.size} 次事件）；" +
                m.refixations.joinToString("；") { "${it.eye.label} ${fmt(it.amplitudePrism)}Δ、${fmt(it.speedDegPerSec)}°/s、残余 ${fmt(it.residualPrism)}Δ" })
            add("双眼对称性指数 BSI ${bsi?.let(::fmt) ?: "—"}（r_h ${m.rH?.let(::fmt) ?: "—"}、r_v ${m.rV?.let(::fmt) ?: "—"}、d_norm ${m.dNorm?.let(::fmt) ?: "—"}）：$coordination。")
            if (eyeStatus == EvidenceStatus.ABNORMAL) add("注视偏差持续 ≥ ${StrabismusProtocol.HIGH_H_PRISM.toInt()}Δ（水平）或 ≥ ${StrabismusProtocol.HIGH_V_PRISM.toInt()}Δ（垂直），建议尽快到斜视专科检查。")
            else if (coverStatus == EvidenceStatus.ABNORMAL) add("遮盖诱发再注视偏移 ≥ ${StrabismusProtocol.REFIX_PRISM.toInt()}Δ，提示可能存在隐斜或间歇性斜视，建议眼科复查。")
        }
        val riskLevel = when {
            eyeStatus == EvidenceStatus.ABNORMAL -> ScreeningRiskLevel.HIGH
            coverStatus == EvidenceStatus.ABNORMAL || coordinationStatus == EvidenceStatus.ABNORMAL -> ScreeningRiskLevel.MEDIUM
            listOf(eyeStatus, coverStatus, coordinationStatus).contains(EvidenceStatus.UNCERTAIN) -> ScreeningRiskLevel.UNCERTAIN
            else -> ScreeningRiskLevel.LOW
        }
        features["fixation_hde_ci_half_prism"] = hHalf
        features["fixation_vde_ci_half_prism"] = vHalf
        features["cover_noise_floor_prism"] = noiseFloor
        return ScreeningAssessment(VERSION, "可用于探索性评估", riskLevel, riskScope, riskLevel.labelFor(ScreeningKind.STRABISMUS),
            features, findings,
            listOf("棱镜度为估算值：以平均角膜直径 ${StrabismusProtocol.CORNEA_DIAMETER_MM} mm 作像素尺度、按 Hirschberg ${StrabismusProtocol.PRISM_PER_MM.toInt()}Δ/mm 换算，未针对本机标定；阈值待临床金标准验证。"),
            evidence)
    }

    // endregion

    fun median(values: List<Double>): Double? = values.takeIf { it.isNotEmpty() }
        ?.sorted()?.let { if (it.size % 2 == 0) (it[it.size / 2 - 1] + it[it.size / 2]) / 2 else it[it.size / 2] }

    private fun fmt(value: Double): String = decimalString(value, 1)
}

/** 一次遮盖解除后的再注视测量。 */
data class Refixation(val eye: VisionEye, val amplitudePrism: Double, val speedDegPerSec: Double, val residualPrism: Double)

/** 斜视任务的客观测量（原文研究内容三（2）②③④）。 */
data class StrabismusMeasures(
    val hdePrism: Double?,
    val vdePrism: Double?,
    val hdeSdPrism: Double?,
    val vdeSdPrism: Double?,
    val fixationFrames: Int,
    val fixationContinuousMs: Long,
    val refixations: List<Refixation>,
    val rH: Double?,
    val rV: Double?,
    val dNorm: Double?,
    val bsi: Double?,
    /** 注视偏斜中位数的标准误（Δ），按约 200 ms 的相关时间折算有效独立帧数。 */
    val hdeSePrism: Double? = null,
    val vdeSePrism: Double? = null,
    /** 无遮盖时同长度窗口内「首末帧位移」的 95% 分位（Δ）：再注视幅度须超过这一测量噪声底。 */
    val refixNoisePrism: Double? = null,
) {
    companion object {
        /**
         * - 注视：每帧 HDE = 左右眼映光鼻侧偏心之差，VDE = 垂直偏心之差（共同的 κ 角与共轭注视相互抵消）
         * - 遮盖：被解除遮盖的眼在点按后 [StrabismusProtocol.REFIX_SEARCH_MS] 内的偏心变化幅度、速度，以及相对注视基线的残余；
         *   同长度的注视段窗口给出噪声底
         * - 追踪：BSI = 0.5·r_h + 0.3·r_v + 0.2·(1 − d_norm)（原文初版公式）；r_h 取水平与斜向段、
         *   r_v 取垂直与斜向段的双眼速度相关；d_norm 为双眼相对位置波动 RMS / 眼动幅度，截断到 [0,1]
         */
        fun compute(samples: List<EyeMotionSample>, marks: List<CoverMark>): StrabismusMeasures {
            val k = StrabismusProtocol.PRISM_PER_IRIS_UNIT
            val fixed = samples.filter {
                it.step == ScreeningStep.FIXATION && it.gazeOnTarget == true && it.leftNasal != null && it.rightNasal != null &&
                    it.leftVertical != null && it.rightVertical != null
            }
            val hde = fixed.map { (it.leftNasal!! - it.rightNasal!!).toDouble() * k }
            val vde = fixed.map { (it.leftVertical!! - it.rightVertical!!).toDouble() * k }
            val baselineL = ScreeningAssessmentModel.median(fixed.map { it.leftNasal!!.toDouble() })
            val baselineR = ScreeningAssessmentModel.median(fixed.map { it.rightNasal!!.toDouble() })

            val cover = samples.filter { it.step == ScreeningStep.COVER && it.gazeOnTarget == true }
            val refix = marks.mapNotNull { mark ->
                val eye = mark.uncovered ?: return@mapNotNull null
                val window = cover.filter { it.timeMs in mark.timeMs..(mark.timeMs + StrabismusProtocol.REFIX_SEARCH_MS) }
                    .mapNotNull { s ->
                        val n = if (eye == VisionEye.LEFT) s.leftNasal else s.rightNasal
                        val v = if (eye == VisionEye.LEFT) s.leftVertical else s.rightVertical
                        if (n != null && v != null) Triple(s.timeMs, n.toDouble(), v.toDouble()) else null
                    }
                if (window.size < 2) return@mapNotNull null
                val first = window.first()
                val last = window.last()
                val amp = hypot(last.second - first.second, last.third - first.third) * k
                val dt = (last.first - first.first) / 1000.0
                val baseline = if (eye == VisionEye.LEFT) baselineL else baselineR
                Refixation(
                    eye, amp,
                    if (dt > 0) amp * StrabismusProtocol.DEG_PER_PRISM / dt else 0.0,
                    baseline?.let { abs(last.second - it) * k } ?: 0.0,
                )
            }

            val pursuit = samples.filter { it.step == ScreeningStep.PURSUIT && it.gazeOnTarget == true }
            fun velocityCorr(segments: Set<Int>, horizontal: Boolean): Double? {
                val pairs = pursuit.filter { it.pursuitSegment in segments }.zipWithNext()
                    .filter { (a, b) -> b.timeMs > a.timeMs && b.timeMs - a.timeMs <= 200L && a.pursuitSegment == b.pursuitSegment }
                val l = pairs.map { (a, b) -> ((if (horizontal) b.leftX - a.leftX else b.leftY - a.leftY) / (b.timeMs - a.timeMs)).toDouble() }
                val r = pairs.map { (a, b) -> ((if (horizontal) b.rightX - a.rightX else b.rightY - a.rightY) / (b.timeMs - a.timeMs)).toDouble() }
                return correlation(l, r)
            }
            val rH = velocityCorr(setOf(0, 2), horizontal = true)
            val rV = velocityCorr(setOf(1, 2), horizontal = false)
            val diffs = pursuit.map { (it.leftX - it.rightX).toDouble() to (it.leftY - it.rightY).toDouble() }
            val dNorm = if (diffs.size >= 10) {
                val mx = diffs.map { it.first }.average()
                val my = diffs.map { it.second }.average()
                val rms = sqrt(diffs.map { (it.first - mx) * (it.first - mx) + (it.second - my) * (it.second - my) }.average())
                val horizontalSeg = pursuit.filter { it.pursuitSegment == 0 }.map { ((it.leftX + it.rightX) / 2f).toDouble() }
                val amplitude = if (horizontalSeg.size >= 5) (horizontalSeg.max() - horizontalSeg.min()) / 2 else null
                amplitude?.takeIf { it > 0.01 }?.let { (rms / it).coerceIn(0.0, 1.0) }
            } else null
            val bsi = if (rH != null && rV != null && dNorm != null) 0.5 * rH + 0.3 * rV + 0.2 * (1 - dNorm) else null

            // 中位数标准误 ≈ 1.2533·SD/√n_eff；相邻帧高度相关，按每 200 ms 一个独立样本折算
            val spanMs = if (fixed.size >= 2) fixed.last().timeMs - fixed.first().timeMs else 0L
            val effective = minOf(fixed.size.toDouble(), maxOf(1.0, spanMs / 200.0))
            fun se(values: List<Double>) = sd(values)?.let { 1.2533 * it / sqrt(effective) }
            return StrabismusMeasures(
                hdePrism = ScreeningAssessmentModel.median(hde),
                vdePrism = ScreeningAssessmentModel.median(vde),
                hdeSdPrism = sd(hde), vdeSdPrism = sd(vde),
                hdeSePrism = se(hde), vdeSePrism = se(vde),
                refixNoisePrism = noiseFloor(fixed, k),
                fixationFrames = fixed.size,
                fixationContinuousMs = longestContinuousMs(fixed),
                refixations = refix,
                rH = rH, rV = rV, dNorm = dNorm, bsi = bsi,
            )
        }

        /** 注视段内每只眼、每个 [StrabismusProtocol.REFIX_SEARCH_MS] 滑动窗口的首末帧位移，取 95% 分位；窗口不足 10 个时为 null。 */
        private fun noiseFloor(fixed: List<EyeMotionSample>, k: Double): Double? {
            val displacements = mutableListOf<Double>()
            for (left in listOf(true, false)) {
                var start = 0
                while (start < fixed.size) {
                    val first = fixed[start]
                    val end = fixed.indexOfLast { it.timeMs <= first.timeMs + StrabismusProtocol.REFIX_SEARCH_MS }
                    if (end > start) {
                        val last = fixed[end]
                        val dx = (if (left) last.leftNasal!! - first.leftNasal!! else last.rightNasal!! - first.rightNasal!!).toDouble()
                        val dy = (if (left) last.leftVertical!! - first.leftVertical!! else last.rightVertical!! - first.rightVertical!!).toDouble()
                        displacements += hypot(dx, dy) * k
                    }
                    start = maxOf(start + 1, fixed.indexOfFirst { it.timeMs > first.timeMs + StrabismusProtocol.REFIX_SEARCH_MS / 2 }.takeIf { it > 0 } ?: fixed.size)
                }
            }
            if (displacements.size < 10) return null
            return displacements.sorted()[((displacements.size - 1) * 0.95).toInt()]
        }

        private fun sd(values: List<Double>): Double? {
            if (values.size < 2) return null
            val m = values.average()
            return sqrt(values.sumOf { (it - m) * (it - m) } / values.size)
        }

        private fun longestContinuousMs(samples: List<EyeMotionSample>): Long {
            var best = 0L
            var start = 0L
            var previous = 0L
            for (sample in samples) {
                if (start == 0L || sample.timeMs - previous > 500L) start = sample.timeMs
                previous = sample.timeMs
                best = maxOf(best, previous - start)
            }
            return best
        }

        fun correlation(a: List<Double>, b: List<Double>): Double? {
            if (a.size < 3 || a.size != b.size) return null
            val ma = a.average(); val mb = b.average()
            val va = a.sumOf { (it - ma) * (it - ma) }; val vb = b.sumOf { (it - mb) * (it - mb) }
            if (va < 1e-12 || vb < 1e-12) return null
            return a.indices.sumOf { (a[it] - ma) * (b[it] - mb) } / sqrt(va * vb)
        }
    }
}
