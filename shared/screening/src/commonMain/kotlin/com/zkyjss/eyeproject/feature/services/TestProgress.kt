package com.zkyjss.eyeproject.feature.services

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 整个测试流程的大致进度（0–1）与剩余秒数，只用于提示，不参与计时或评分。
 * 固定时长的阶段按实际倒计时推进；作答次数不定的阶段（视力阶梯、移近、遮盖）按经验时长估算。
 * 引导页不计时长：停留时进度不动。问卷、环境检查和结果页不显示进度。
 */
data class TestProgress(val fraction: Float, val remainingSeconds: Int) {
    /** 「剩余约 3 分钟」「剩余约 40 秒」：一分钟以上按分钟取整，以下按 5 秒向上取整。 */
    val remainingText: String get() {
        val rounded = ceil(remainingSeconds / 5.0).toInt() * 5
        return when {
            rounded >= 60 -> "剩余约 ${(remainingSeconds / 60.0).roundToInt().coerceAtLeast(1)} 分钟"
            rounded > 0 -> "剩余约 $rounded 秒"
            else -> "即将完成"
        }
    }
}

/** 估算用的阶段经验时长（秒）。 */
object TestProgressEstimate {
    /** 每只眼约 3 级 × 4 题，含停稳等待。 */
    const val NEAR_VISION_PER_EYE = 45f
    const val PUSH_UP_PER_TRIAL = 15f
    /** 每个距离约 2 级 × 3 题，含移动到位。 */
    const val CURVE_PER_DISTANCE = 25f
    /** 9 个遮盖动作的保持时长约 12 秒，加上协助者操作。 */
    const val COVER_TOTAL = 30f
    /** 单题的大致耗时，用于在可变阶段内平滑推进。 */
    const val SECONDS_PER_ANSWER = 3.5f
}

private class Stage(val seconds: Float, val doneSeconds: Float)

private fun progressOf(stages: List<Stage>): TestProgress {
    val total = stages.sumOf { it.seconds.toDouble() }.toFloat().coerceAtLeast(1f)
    val done = stages.sumOf { it.doneSeconds.coerceIn(0f, it.seconds).toDouble() }.toFloat()
    return TestProgress((done / total).coerceIn(0f, 1f), ceil(total - done).toInt().coerceAtLeast(0))
}

/** [order] 为当前所处阶段序号：之前的阶段记满，之后的记零，当前阶段记 [elapsed]。 */
private fun stagesAt(durations: List<Float>, order: Int, elapsed: Float) = durations.mapIndexed { i, seconds ->
    Stage(seconds, when { i < order -> seconds; i == order -> elapsed.coerceIn(0f, seconds * 0.98f); else -> 0f })
}

fun dryEyeProgress(s: DryEyeTestUiState): TestProgress? {
    if (s.phase in setOf(DryEyePhase.Profile, DryEyePhase.Setup, DryEyePhase.Result)) return null
    // 休息复测从休息开始；延长测试与复测不重复练习。
    val withRest = s.afterRest
    val withPractice = !s.afterRest && s.naturalSeconds == DryEyeProtocol.NATURAL_SECONDS
    val keys = buildList {
        if (withRest) add(DryEyePhase.Rest)
        if (withPractice) add(DryEyePhase.Practice)
        add(DryEyePhase.Countdown); add(DryEyePhase.Natural); add(DryEyePhase.Open)
    }
    val durations = keys.map { when (it) {
        DryEyePhase.Rest -> DryEyeProtocol.REST_SECONDS.toFloat()
        DryEyePhase.Practice -> DryEyeProtocol.PRACTICE_SECONDS.toFloat()
        DryEyePhase.Countdown -> 3f
        DryEyePhase.Natural -> s.naturalSeconds.toFloat()
        else -> DryEyeProtocol.OPEN_SECONDS.toFloat()
    } }
    // 引导页停在下一个计时阶段的起点。
    val current = when (s.phase) {
        DryEyePhase.PracticeGuide -> DryEyePhase.Practice
        DryEyePhase.FormalGuide -> DryEyePhase.Countdown
        else -> s.phase
    }
    val order = keys.indexOf(current).takeIf { it >= 0 } ?: return null
    val elapsed = when (s.phase) {
        DryEyePhase.PracticeGuide, DryEyePhase.FormalGuide -> 0f
        DryEyePhase.Countdown -> (3 - s.remainingSeconds).toFloat()
        else -> s.progressFraction * durations[order]
    }
    return progressOf(stagesAt(durations, order, elapsed))
}

fun screeningProgress(s: ScreeningState): TestProgress? {
    if (s.step in setOf(ScreeningStep.PROFILE, ScreeningStep.ENVIRONMENT, ScreeningStep.RESULT)) return null
    val est = TestProgressEstimate
    return if (s.kind == ScreeningKind.PRESBYOPIA) {
        val durations = listOf(est.NEAR_VISION_PER_EYE * 3, est.PUSH_UP_PER_TRIAL * PresbyopiaProtocol.PUSH_REPEATS,
            est.CURVE_PER_DISTANCE * PresbyopiaProtocol.CURVE_DISTANCES.size, 60f)
        val (order, elapsed) = when (s.step) {
            ScreeningStep.NEAR_VISION -> 0 to (s.visionEye.ordinal * est.NEAR_VISION_PER_EYE +
                (s.answers.count { it.step == ScreeningStep.NEAR_VISION && it.eye == s.visionEye } * est.SECONDS_PER_ANSWER)
                    .coerceAtMost(est.NEAR_VISION_PER_EYE * 0.95f))
            ScreeningStep.PUSH_UP -> 1 to (s.nearPoints.size * est.PUSH_UP_PER_TRIAL +
                if (s.pushStage == PushStage.APPROACH) est.PUSH_UP_PER_TRIAL / 2 else 0f)
            ScreeningStep.DISTANCE_CURVE -> 2 to (s.curveIndex * est.CURVE_PER_DISTANCE + (s.answers.count {
                it.step == ScreeningStep.DISTANCE_CURVE && it.targetCm == PresbyopiaProtocol.CURVE_DISTANCES.getOrNull(s.curveIndex)
            } * est.SECONDS_PER_ANSWER).coerceAtMost(est.CURVE_PER_DISTANCE * 0.95f))
            else -> 3 to if (s.guidePending) 0f else (60 - s.secondsLeft).toFloat()
        }
        progressOf(stagesAt(durations, order, elapsed))
    } else {
        val calibration = StrabismusGaze.calibrationTargets.size * StrabismusGaze.CALIBRATION_POINT_MS / 1000f
        val durations = listOf(calibration, StrabismusProtocol.FIXATION_SECONDS.toFloat(),
            StrabismusProtocol.FAR_FIXATION_SECONDS.toFloat(), est.COVER_TOTAL, StrabismusProtocol.PURSUIT_SECONDS.toFloat())
        val (order, elapsed) = when (s.step) {
            // 首个引导页仍处于注视步骤，但校准尚未开始。
            ScreeningStep.FIXATION -> if (!s.gazeCalibrated) 0 to 0f
                else 1 to (StrabismusProtocol.FIXATION_SECONDS - s.secondsLeft).toFloat()
            ScreeningStep.GAZE_CALIBRATION -> 0 to s.gazeCalibrationIndex * StrabismusGaze.CALIBRATION_POINT_MS / 1000f
            ScreeningStep.FAR_FIXATION -> 2 to if (s.guidePending) 0f else (StrabismusProtocol.FAR_FIXATION_SECONDS - s.secondsLeft).toFloat()
            ScreeningStep.COVER -> 3 to s.coverMarks.size * est.COVER_TOTAL / StrabismusProtocol.COVER_SEQUENCE.size
            else -> 4 to if (s.guidePending) 0f else (StrabismusProtocol.PURSUIT_SECONDS - s.secondsLeft).toFloat()
        }
        progressOf(stagesAt(durations, order, elapsed))
    }
}
