package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.domain.*
import com.zkyjss.eyeproject.core.model.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** 眼位任务状态机：注视校准、看远、相机遮盖与追踪。 */
internal class StrabismusStateMachine(private val host: ScreeningEngine) {
    private var lastHeldEvidenceMs = 0L
    private var actionWaitStartMs = 0L
    private data class CoverCounts(val total: Int, val valid: Int, val eligible: Int, val eligibleValid: Int, val optical: Int)
    private var countsBeforeCover: CoverCounts? = null
    fun startFixation() {
        with(host) {
            gaze = null; gazeCalibration.clear(); renderedTargets.clear()
            stepStartMs = platform.nowMs()
            _state.update { it.copy(step = ScreeningStep.FIXATION, guidePending = true, gazeCalibrated = false,
                gazeWarning = null, secondsLeft = StrabismusProtocol.FIXATION_SECONDS, instruction = "看着屏幕上方闪烁的圆点，头和手机保持不动。", warning = null) }

        }
    }

    fun startFarFixation() {
        with(host) {
            resetGazeWindow()
            stepStartMs = platform.nowMs()
            _state.update { it.copy(step = ScreeningStep.FAR_FIXATION, guidePending = true, farWaiting = false,
                secondsLeft = StrabismusProtocol.FAR_FIXATION_SECONDS, instruction = FAR_WAIT_PROMPT, warning = null) }
        }
    }

    fun startFarCountdown() {
        with(host) {
            val s = _state.value
            if (s.step != ScreeningStep.FAR_FIXATION || s.guidePending || !s.farWaiting) return
            stepStartMs = platform.nowMs()
            faceCenterBaseline = null
            _state.update { it.copy(farWaiting = false, instruction = "眼睛越过手机，看着 6 米外的目标，头和手机保持不动。", warning = null) }
            runTimedStep(StrabismusProtocol.FAR_FIXATION_SECONDS) { startCover() }
        }
    }

    fun startCover() {
        with(host) {
            if (_state.value.step != ScreeningStep.COVER) countsBeforeCover =
                CoverCounts(totalFrames, validFrames, qualityEligibleFrames, qualityEligibleValidFrames, opticalValidFrames)
            resetGazeWindow()
            coverIndex = 0
            coverHoldJob?.cancel(); coverMonitorJob?.cancel()
            coverDetector.resetCandidate()
            coverStageStartMs = platform.nowMs()
            stepStartMs = platform.nowMs()
            _state.update { it.copy(step = ScreeningStep.COVER, guidePending = true, coverMarks = emptyList(), coverHolding = false,
                coverRetryAvailable = false, instruction = coverGuideCue(it.fields), warning = null) }
        }
    }

    fun markCover() = with(host) { Unit
    }

    /** 超时只提供恢复入口，不把等待时间或点击当成真实遮盖动作。 */
    fun monitorCover() = with(host) {
        coverMonitorJob?.cancel()
        actionWaitStartMs = platform.nowMs()
        coverMonitorJob = scope.launch {
            while (isActive && _state.value.step == ScreeningStep.COVER) {
                helpSession.delay(250L)
                val s = _state.value
                if (s.step == ScreeningStep.COVER && !s.guidePending &&
                    coverIndex < coverSequence.size && platform.nowMs() - actionWaitStartMs >= 8_000L && !s.coverRetryAvailable) {
                    _state.update { it.copy(coverRetryAvailable = true, warning =
                        if (!coverDetector.hasBaseline) "请先移开遮盖、睁开双眼约 1 秒，再按提示遮眼。"
                        else "暂未识别到指定动作。请露出另一只眼、保持头和手机不动，不要挡住摄像头；也可以重新识别本段。") }
                }
            }
        }
    }

    fun retryCoverDetection() = with(host) {
        val s = _state.value
        if (s.step != ScreeningStep.COVER || s.guidePending || !s.coverRetryAvailable) return
        coverDetector.clear()
        samples.removeAll { it.step == ScreeningStep.COVER }
        countsBeforeCover?.let { counts ->
            totalFrames = counts.total; validFrames = counts.valid
            qualityEligibleFrames = counts.eligible; qualityEligibleValidFrames = counts.eligibleValid
            opticalValidFrames = counts.optical
        }
        startCover()
        host.confirmGuide()
        _state.update { it.copy(warning = "请先移开遮盖、睁开双眼约 1 秒，再按提示遮眼。") }
    }

    private fun retryHeldAction(message: String, time: Long) = with(host) {
        coverIndex--
        coverStageStartMs = time
        coverDetector.resetCandidate()
        _state.update { it.copy(coverMarks = it.coverMarks.dropLast(1), coverHolding = false,
            warning = message, instruction = "等待相机识别：${coverSequence[coverIndex].label}") }
    }

    private fun completeHold(deadline: Long) = with(host) {
        // 保留已开始的下一动作证据，不能因提示/定时任务晚一帧运行而永远拒绝它。
        coverStageStartMs = deadline
        actionWaitStartMs = platform.nowMs()
        _state.update { it.copy(coverHolding = false, coverRetryAvailable = false, warning = null,
            instruction = "下一步：${coverSequence[coverIndex].label}") }
    }

    fun observeCover(time: Long, face: FaceMetrics?, quality: Boolean, step: ScreeningStep) {
        with(host) {
            var current = _state.value
            val learn = step != ScreeningStep.COVER || coverIndex == 0 || coverSequence.getOrNull(coverIndex)?.covered == null
            val observation = coverDetector.observe(time, face, quality, learn) ?: return
            if (step != ScreeningStep.COVER || current.step != step || current.guidePending) return
            if (current.coverHolding) {
                val held = current.coverMarks.lastOrNull() ?: return
                val deadline = coverStageStartMs + coverSequence[coverIndex - 1].holdMs
                if (observation.covered == held.covered) {
                    if (observation.sinceMs - lastHeldEvidenceMs > CoverDetector.MAX_FRAME_GAP_MS) {
                        coverHoldJob?.cancel()
                        retryHeldAction("识别曾中断，请重新保持：${held.label}", time)
                    } else lastHeldEvidenceMs = time
                    return
                }
                if (observation.sinceMs < deadline || deadline - lastHeldEvidenceMs > CoverDetector.MAX_FRAME_GAP_MS) {
                    coverHoldJob?.cancel()
                    retryHeldAction("保持时间不足，请重新${coverSequence[coverIndex - 1].label}", time)
                    return
                }
                coverHoldJob?.cancel()
                completeHold(deadline)
                current = _state.value
            }
            val action = coverSequence.getOrNull(coverIndex) ?: return
            if (observation.covered != action.covered || observation.sinceMs < coverStageStartMs) return
            val release = action.uncovered?.let { observation.releasedAtMs[it] }
            val eventTime = release?.takeIf { it >= coverStageStartMs } ?: observation.sinceMs
            val marks = current.coverMarks + CoverMark(action.label, eventTime, action.uncovered, action.covered,
                source = "CAMERA", confirmedAtMs = observation.confirmedAtMs)
            platform.feedback(ScreeningFeedbackCue.ANSWER)
            coverIndex++
            coverStageStartMs = observation.sinceMs
            lastHeldEvidenceMs = observation.confirmedAtMs
            _state.update { it.copy(coverMarks = marks, coverHolding = action.holdMs > 0,
                instruction = "保持：${action.label}", warning = null) }
            coverHoldJob?.cancel()
            coverHoldJob = scope.launch {
                if (coverIndex >= coverSequence.size) {
                    helpSession.delay(StrabismusProtocol.REFIX_SEARCH_MS + 100L)
                    if (_state.value.step == ScreeningStep.COVER) startPursuit()
                } else {
                    helpSession.delay((action.holdMs - (platform.nowMs() - observation.sinceMs)).coerceAtLeast(0L))
                    val deadline = observation.sinceMs + action.holdMs
                    // 保持结束时恰逢眨眼/坏帧，先等新证据；无新证据绝不按墙钟直接放行。
                    while (_state.value.step == ScreeningStep.COVER && _state.value.coverMarks.size == marks.size && _state.value.coverHolding &&
                        !coverDetector.matchesCover(action.covered, platform.nowMs()) && platform.nowMs() - deadline < 1_200L) {
                        helpSession.delay(100L)
                    }
                    if (_state.value.step == ScreeningStep.COVER && _state.value.coverMarks.size == marks.size && _state.value.coverHolding) {
                        if (coverDetector.matchesCover(action.covered, platform.nowMs())) completeHold(deadline)
                        else retryHeldAction("暂未看清保持动作，请重新${action.label}", platform.nowMs())
                    }
                }
            }
        }
    }

    fun startPursuit() {
        with(host) {
            resetGazeWindow()
            stepStartMs = platform.nowMs()
            _state.update { it.copy(step = ScreeningStep.PURSUIT, guidePending = true, secondsLeft = StrabismusProtocol.PURSUIT_SECONDS,
                instruction = "只用眼睛跟随移动的圆点：水平 8 秒、垂直 8 秒、斜向 6 秒。", warning = null) }

        }
    }

    fun retryGazeCalibration() {
        with(host) {
            if (_state.value.step != ScreeningStep.FIXATION && _state.value.step != ScreeningStep.GAZE_CALIBRATION) return
            timer?.cancel(); gaze = null; gazeCalibration.clear(); renderedTargets.clear(); resetGazeWindow()
            faceCenterBaseline = null
            stepStartMs = platform.nowMs()
            _state.update { it.copy(step = ScreeningStep.GAZE_CALIBRATION, guidePending = false,
                gazeCalibrationIndex = 0, gazeCalibrationRound = 0, gazeCalibrationFailed = false, gazeCalibrated = false, gazeCalibrationPaused = false,
                instruction = "只用眼睛依次看着圆点，头和手机保持不动。", warning = null) }
            timer = scope.launch {
                // 每点需累计 [StrabismusGaze.SAMPLE_MS] 的有效采样才换下一点：异常期间暂停计时、自动延长，
                // 最长 [StrabismusGaze.CALIBRATION_POINT_MAX_MS]；仍不够的点交给补测或整体重试。
                suspend fun show(points: List<Int>) = points.forEach { index ->
                    calibrationPointStartMs = platform.nowMs()
                    calibrationPausedSinceMs = null; calibrationResumeMs = 0L; calibrationTargetMovedMs = 0L
                    calibrationSampledMs = 0L; lastCalibrationSampleMs = 0L
                    _state.update { it.copy(gazeCalibrationIndex = index) }
                    while (true) {
                        helpSession.delay(100)
                        val elapsed = platform.nowMs() - calibrationPointStartMs
                        if (calibrationSampledMs >= StrabismusGaze.SAMPLE_MS && elapsed >= StrabismusGaze.CALIBRATION_POINT_MS) break
                        if (elapsed >= StrabismusGaze.CALIBRATION_POINT_MAX_MS) break
                    }
                }
                show(StrabismusGaze.calibrationTargets.indices.toList())
                gaze = StrabismusGaze.calibrate(gazeCalibration)
                // 少数点没看稳（眨眼、看晚了）时只重看这几个点，而不是整轮约 18 秒重来。
                val retake = if (gaze == null) StrabismusGaze.pointsToRetake(gazeCalibration) else emptyList()
                if (retake.isNotEmpty()) {
                    gazeCalibration.removeAll { it.index in retake }
                    _state.update { it.copy(gazeCalibrationRound = 1, instruction = "再看一次刚才没看稳的 ${retake.size} 个圆点。") }
                    show(retake)
                    gaze = StrabismusGaze.calibrate(gazeCalibration)
                }
                _state.update { it.copy(gazeCalibrationPaused = false, gazeWarning = null) }
                if (gaze == null) {
                    _state.update { it.copy(gazeCalibrationFailed = true,
                        warning = "未获得可靠注视校准。" + StrabismusGaze.failureReason(gazeCalibration)) }
                } else {
                    stepStartMs = platform.nowMs(); resetGazeWindow(); faceCenterBaseline = null
                    _state.update { it.copy(step = ScreeningStep.FIXATION, gazeCalibrated = true,
                        instruction = "看着屏幕上方闪烁的圆点，头和手机保持不动。", targetX = 1f, targetY = 0f) }
                    runTimedStep(StrabismusProtocol.FIXATION_SECONDS) { startFarFixation() }
                }
            }
        }
    }

    fun reportGazeTarget(xPx: Float, yPx: Float) {
        with(host) {
            val s = _state.value
            if (s.guidePending || s.step !in setOf(ScreeningStep.GAZE_CALIBRATION, ScreeningStep.FIXATION, ScreeningStep.COVER, ScreeningStep.PURSUIT)) return
            val display = platform.display
            val now = platform.nowMs()
            val point = GazePoint(xPx / display.widthPixels, yPx / display.heightPixels)
            // 同一校准点在屏幕上被挪动（布局变化）：此前看的位置已不是现在记录的位置，需重新稳定后再采样
            renderedTargets.lastOrNull()?.takeIf { it.step == s.step && it.index == s.gazeCalibrationIndex }?.let { last ->
                if (s.step == ScreeningStep.GAZE_CALIBRATION && (abs(last.point.x - point.x) > 0.01f || abs(last.point.y - point.y) > 0.01f))
                    calibrationTargetMovedMs = now
            }
            renderedTargets.addLast(ScreeningEngine.RenderedTarget(now, s.step, s.gazeCalibrationIndex, point))
            while (renderedTargets.isNotEmpty() && renderedTargets.first().timeMs < now - 2_000L) renderedTargets.removeFirst()
        }
    }

    fun resetGazeWindow() {
        with(host) {
            gazeBadSinceMs = null; lastGazeFrameMs = 0L; lastPursuitCheckMs = 0L; pursuitFollowing = null
            _state.update { it.copy(gazeWarning = null) }
        }
    }

    fun runTimedStep(seconds: Int, after: () -> Unit) {
        with(host) {
            timer?.cancel()
            timer = scope.launch {
                while (isActive) {
                    val elapsed = ((platform.nowMs() - stepStartMs).coerceAtLeast(0L) / 1_000f).coerceAtMost(seconds.toFloat())
                    val target = if (_state.value.step == ScreeningStep.PURSUIT) StrabismusProtocol.pursuitTarget(elapsed).let { it.first to it.second }
                        else (if ((elapsed * StrabismusProtocol.FLASH_HZ * 2f).toInt() % 2 == 0) 1f else -1f) to 0f
                    _state.update { it.copy(secondsLeft = kotlin.math.ceil(seconds - elapsed).toInt().coerceAtLeast(0), targetX = target.first, targetY = target.second) }
                    if (elapsed >= seconds) break
                    helpSession.delay(50)
                }
                if (isActive) after()
            }
        }
    }

    fun reportPursuitCapacity(maxXDeg: Float, maxYDeg: Float) {
        with(host) {
            if (!maxXDeg.isFinite() || !maxYDeg.isFinite() || maxXDeg <= 0f || maxYDeg <= 0f) return
            val previous = _state.value.pursuitMaxDeg
            if (previous == null || abs(previous.first - maxXDeg) > 0.1f || abs(previous.second - maxYDeg) > 0.1f)
                _state.update { it.copy(pursuitMaxDeg = maxXDeg to maxYDeg) }
        }
    }

    fun coverRemainingMs(): Long = with(host) { if (_state.value.step != ScreeningStep.COVER) 0L else
        ((coverSequence.getOrNull(coverIndex - 1)?.holdMs ?: 0L) - (platform.nowMs() - coverStageStartMs)).coerceAtLeast(0L)
    }
}
