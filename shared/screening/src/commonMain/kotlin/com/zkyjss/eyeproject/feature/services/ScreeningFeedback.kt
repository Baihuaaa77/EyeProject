package com.zkyjss.eyeproject.feature.services

/** 两端使用同一事件语义、音效文件与防抖规则；作答反馈不包含正确与否。 */
enum class ScreeningFeedbackCue(val sound: String, val priority: Int) {
    TICK("eye_tick", 1), RECOVER("eye_phase", 2), PHASE("eye_phase", 3),
    ANSWER("eye_answer", 4), TARGET("eye_target", 5), START("eye_start", 6),
    WARNING("eye_warning", 7), RETRY("eye_warning", 8), COMPLETE("eye_complete", 9),
    ;
    /** WAV 长度加约 60 ms 尾音，只短暂停用收听，避免固定长暂停拖慢连续作答。 */
    val microphonePauseMs: Int get() = when (this) {
        ANSWER, TICK -> 110
        TARGET -> 140
        PHASE, RECOVER -> 230
        WARNING, RETRY -> 250
        START -> 310
        COMPLETE -> 320
    }
}

data class ScreeningFeedbackFrame(
    val stage: String, val taskActive: Boolean = false, val complete: Boolean = false,
    val calibrationPoint: Int = -1, val calibrationFailed: Boolean = false,
    val countdown: Int = 0,
    val coverReady: Boolean = false,
    /** -1 过近、1 过远、0 合适、2 未知、3 当前任务不要求固定距离。 */
    val distanceZone: Int = 3,
)

fun screeningFeedbackFrame(s: ScreeningState, coverRemainingMs: Long = -1): ScreeningFeedbackFrame {
    val active = !s.guidePending && s.step !in setOf(ScreeningStep.PROFILE, ScreeningStep.ENVIRONMENT, ScreeningStep.RESULT)
    val suffix = when (s.step) {
        ScreeningStep.NEAR_VISION -> ":${s.visionEye}"
        ScreeningStep.DISTANCE_CURVE -> ":${s.curveIndex}"
        ScreeningStep.PUSH_UP -> ":${s.nearPoints.size}:${s.pushStage}"
        else -> ""
    }
    // 移动到新目标距离途中不算偏离，不发距离警示音
    val target = if (s.guidePending || s.distanceMoveToCm != null) null else screeningDistanceTarget(s)
    return ScreeningFeedbackFrame(s.step.name + suffix, active, s.step == ScreeningStep.RESULT,
        if (s.step == ScreeningStep.GAZE_CALIBRATION && !s.gazeCalibrationFailed) s.gazeCalibrationIndex else -1,
        s.step == ScreeningStep.GAZE_CALIBRATION && s.gazeCalibrationFailed,
        coverReady = s.step == ScreeningStep.COVER && !s.guidePending && s.coverMarks.isNotEmpty() &&
            s.coverMarks.size < StrabismusProtocol.COVER_SEQUENCE.size && coverRemainingMs == 0L,
        distanceZone = feedbackDistanceZone(s.distanceCm,
            s.faceDetected || (s.step == ScreeningStep.NEAR_VISION && s.distanceCm > 0f), target))
}

fun screeningFeedbackFrame(s: DryEyeTestUiState): ScreeningFeedbackFrame {
    val active = s.phase in setOf(DryEyePhase.Practice, DryEyePhase.Countdown, DryEyePhase.Natural, DryEyePhase.Open, DryEyePhase.Rest)
    val target = if (s.phase in setOf(DryEyePhase.Setup, DryEyePhase.FormalGuide, DryEyePhase.Practice,
        DryEyePhase.Countdown, DryEyePhase.Natural, DryEyePhase.Open))
        DryEyeProtocol.TARGET_DISTANCE_CM to DryEyeProtocol.DISTANCE_TOLERANCE_CM else null
    return ScreeningFeedbackFrame(s.phase.name, active, s.phase == DryEyePhase.Result,
        countdown = if (s.phase == DryEyePhase.Countdown) s.remainingSeconds else 0,
        distanceZone = feedbackDistanceZone(s.distanceCm, s.faceDetected, target))
}

private fun feedbackDistanceZone(distance: Float, face: Boolean, target: Pair<Float, Float>?): Int = when {
    target == null -> 3
    !face || !distance.isFinite() || distance <= 0f -> 2
    distance < target.first - target.second -> -1
    distance > target.first + target.second -> 1
    else -> 0
}

/** 由前台 UI 轮询。后台、设置菜单期间丢弃事件，恢复时不重播过期反馈。 */
class ScreeningFeedbackSession {
    private var previous: ScreeningFeedbackFrame? = null
    private val pending = mutableListOf<ScreeningFeedbackCue>()
    private var candidate = 3
    private var candidateSince = 0L
    private var alertedZone: Int? = null
    private var lastWarning = -10_000L
    private var lastCue = -10_000L

    fun offer(cue: ScreeningFeedbackCue) {
        if (pending.size < 8) pending += cue
    }

    fun poll(frame: ScreeningFeedbackFrame, nowMs: Long, enabled: Boolean): List<ScreeningFeedbackCue> {
        val old = previous
        previous = frame
        if (!enabled) {
            pending.clear(); candidate = frame.distanceZone; candidateSince = nowMs; alertedZone = null
            return emptyList()
        }
        val cues = pending.toMutableList()
        pending.clear()
        if (old != null) {
            when {
                frame.complete && !old.complete -> cues += ScreeningFeedbackCue.COMPLETE
                frame.calibrationFailed && !old.calibrationFailed -> cues += ScreeningFeedbackCue.RETRY
                // 倒计时 3、2、1 只用节拍音，归零进入任务时才给开始音。
                frame.countdown > 0 -> Unit
                frame.taskActive && (!old.taskActive || old.calibrationFailed && !frame.calibrationFailed || old.countdown > 0) -> cues += ScreeningFeedbackCue.START
                frame.calibrationPoint >= 0 && frame.calibrationPoint != old.calibrationPoint -> cues += ScreeningFeedbackCue.TARGET
                frame.coverReady && !old.coverReady -> cues += ScreeningFeedbackCue.PHASE
                frame.stage != old.stage && (frame.taskActive || old.taskActive) -> cues += ScreeningFeedbackCue.PHASE
            }
            if (frame.countdown in 1..3 && frame.countdown != old.countdown) cues += ScreeningFeedbackCue.TICK
        }
        if (old == null || candidate != frame.distanceZone) {
            candidate = frame.distanceZone; candidateSince = nowMs
        }
        val stable = nowMs - candidateSince >= 800
        if (candidate == 3) alertedZone = null
        if (stable && candidate in listOf(-1, 1) && alertedZone != candidate && nowMs - lastWarning >= 5_000)
            cues += ScreeningFeedbackCue.WARNING
        if (stable && candidate == 0 && alertedZone != null) cues += ScreeningFeedbackCue.RECOVER
        val cue = cues.maxByOrNull { it.priority } ?: return emptyList()
        if (nowMs - lastCue < 150) {
            pending += cues.filter { it != ScreeningFeedbackCue.WARNING && it != ScreeningFeedbackCue.RECOVER }.distinct().take(8)
            return emptyList()
        }
        if (cue == ScreeningFeedbackCue.WARNING) { alertedZone = candidate; lastWarning = nowMs }
        if (cue == ScreeningFeedbackCue.RECOVER) alertedZone = null
        lastCue = nowMs
        return listOf(cue)
    }
}
