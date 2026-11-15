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
