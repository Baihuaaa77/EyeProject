package com.zkyjss.eyeproject.feature.services

/**
 * 干眼流程阶段：问卷 → 准备（40 cm 门禁）→ 练习说明 → 练习 → 正式说明 → 倒计时 →
 * 自然眨眼（60 秒或延长 180 秒）→ 紧接持续睁眼 20 秒 → 结果；可选「闭眼休息 1 分钟」后复测。
 */
enum class DryEyePhase { Profile, Setup, PracticeGuide, Practice, FormalGuide, Countdown, Natural, Open, Rest, Result }

/** 单个距离采样：相对正式测试开始的秒数 + 距离。 */
data class DryEyeDistanceSample(val tSec: Float, val cm: Float)

data class DryEyeTestUiState(
    val phase: DryEyePhase = DryEyePhase.Profile,
    val fields: Map<String, String> = emptyMap(),
    /** 用户选择先跳过问卷；记录中注明。 */
    val profileSkipped: Boolean = false,
    val instruction: String = "",
    val remainingSeconds: Int = 0,
    val progressFraction: Float = 0f,
    // 门禁与质量
    val faceDetected: Boolean = false,
    val distanceCm: Float = 0f,
    val gateReady: Boolean = false,
    val headTurned: Boolean = false,
    val lux: Float? = null,
    val qualityWarning: String? = null,
    // 视标
    val trialVisible: Boolean = false,
    val trialId: Int = 0,
    val direction: Int = 0,
    val level: Float = DryEyeProtocol.LEVELS[DryEyeProtocol.START_LEVEL_INDEX],
    val optotypePx: Float = 0f,
    val lastAnswerCorrect: Boolean? = null,
    // 数据
    val phase1Blinks: List<Float> = emptyList(),
    val phase2Blinks: List<Float> = emptyList(),
    val phase1BlinkCount: Int = 0,
    val phase2BlinkCount: Int = 0,
    val trialCount: Int = 0,
    val distanceSamples: List<DryEyeDistanceSample> = emptyList(),
    val naturalSeconds: Int = DryEyeProtocol.NATURAL_SECONDS,
    val afterRest: Boolean = false,
    val report: DryEyeReport? = null,
    val previousReport: DryEyeReport? = null,
    val savedFile: String? = null,
    val error: String? = null,
) {
    /** 临界或中风险的标准时长结果才可延长自然眨眼；两端按钮与引擎共用同一判定。 */
    val canExtend: Boolean get() = phase == DryEyePhase.Result && report?.valid == true &&
        (report.borderline || report.riskLevel == ScreeningRiskLevel.MEDIUM ||
            report.riskLevel == ScreeningRiskLevel.UNCERTAIN) && naturalSeconds == DryEyeProtocol.NATURAL_SECONDS
    /** 有效结果且尚未做过休息复测。 */
    val canRestRetest: Boolean get() = phase == DryEyePhase.Result && report?.valid == true && !afterRest
}
