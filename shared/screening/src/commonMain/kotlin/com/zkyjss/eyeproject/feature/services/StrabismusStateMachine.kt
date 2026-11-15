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
            resetGazeWindow()
            coverIndex = 0
            coverDetector.resetCandidate()
            coverStageStartMs = platform.nowMs()
            stepStartMs = platform.nowMs()
            _state.update { it.copy(step = ScreeningStep.COVER, guidePending = true, coverMarks = emptyList(), coverHolding = false, instruction = coverGuideCue(it.fields), warning = null) }
        }
    }

    fun markCover() = with(host) { Unit
    }

    fun observeCover(time: Long, face: FaceMetrics?, quality: Boolean, step: ScreeningStep) {
        with(host) {
            val current = _state.value
            val observation = coverDetector.observe(time, face, quality, learn = step != ScreeningStep.COVER || coverIndex == 0) ?: return
            if (step != ScreeningStep.COVER || current.step != step || current.guidePending) return
            if (current.coverHolding) {
                val held = current.coverMarks.lastOrNull() ?: return
                if (observation.covered != held.covered && time - coverStageStartMs < coverSequence[coverIndex - 1].holdMs) {
                    coverHoldJob?.cancel()
                    coverIndex--
                    coverStageStartMs = time
                    coverDetector.resetCandidate()
                    _state.update { it.copy(coverMarks = it.coverMarks.dropLast(1), coverHolding = false,
                        warning = "保持时间不足，请重新${coverSequence[coverIndex].label}", instruction = "等待相机识别：${coverSequence[coverIndex].label}") }
                }
                return
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
            _state.update { it.copy(coverMarks = marks, coverHolding = action.holdMs > 0,
                instruction = "保持：${action.label}", warning = null) }
            coverHoldJob?.cancel()
            coverHoldJob = scope.launch {
                if (coverIndex >= coverSequence.size) {
                    helpSession.delay(StrabismusProtocol.REFIX_SEARCH_MS + 100L)
                    if (_state.value.step == ScreeningStep.COVER) startPursuit()
                } else {
                    helpSession.delay((action.holdMs - (platform.nowMs() - observation.sinceMs)).coerceAtLeast(0L))
                    if (_state.value.step == ScreeningStep.COVER && _state.value.coverMarks.size == marks.size) {
                        coverStageStartMs = platform.nowMs()
                        if (!coverDetector.matchesCover(action.covered, coverStageStartMs)) {
                            coverIndex--
                            _state.update { it.copy(coverMarks = it.coverMarks.dropLast(1), coverHolding = false,
                                warning = "动作保持不稳定，请重新${action.label}", instruction = "等待相机识别：${action.label}") }
                        } else {
                            _state.update { it.copy(coverHolding = false, instruction = "下一步：${coverSequence[coverIndex].label}") }
                        }
                        coverDetector.resetCandidate()
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
