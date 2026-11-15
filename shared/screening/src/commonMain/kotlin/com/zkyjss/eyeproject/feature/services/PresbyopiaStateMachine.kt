package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.domain.*
import com.zkyjss.eyeproject.core.model.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** 老视任务状态机：近视力、多距离、移近法与远近切换。 */
internal class PresbyopiaStateMachine(private val host: ScreeningEngine) {
    fun startNearVision() {
        with(host) {
            timer?.cancel()
            // 先确认已按本次戴镜状态戴好或摘下眼镜：同一组测试的矫正状态必须一致
            val check = PresbyopiaIntake.glassesCheck(_state.value.fields)
            _state.update { it.copy(step = ScreeningStep.NEAR_VISION, guidePending = true, visionEye = VisionEye.RIGHT,
                nearAwaitingEyeReady = true, nearDescending = false, trialVisible = false,
                trialIndex = 0, direction = Random.nextInt(4), visionLevel = 0.3f, glassesCheckPending = true,
                answers = emptyList(), nearPoints = emptyList(), curvePoints = emptyList(), flipperCheck = false, flipperIncomplete = null,
                warning = null, instruction = check.prompt) }
        }
    }

    fun readyForNearEye() {
        with(host) {
            val s = _state.value
            if (s.step != ScreeningStep.NEAR_VISION || !s.nearAwaitingEyeReady) return
            platform.feedback(ScreeningFeedbackCue.PHASE)
            _state.update { it.copy(nearAwaitingEyeReady = false, warning = null,
                instruction = "${s.visionEye.label}测试：保持约 40 cm，点按显示视标后选择 E 开口方向。") }
        }
    }

    fun showTrial() {
        with(host) {
            if (helpSession.paused || _state.value.guidePending) return
            val s = _state.value
            if (s.trialVisible || s.nearAwaitingEyeReady) return
            val target = trialTargetCm(s) ?: return
            val tolerance = nearDistanceToleranceCm
            if (!distanceStable(target, tolerance)) {
                _state.update { it.copy(warning = "请在 ${decimalString(target.toDouble(), 1)}±${decimalString(tolerance.toDouble(), 1)} cm 附近停稳约 1 秒后重试") }; return
            }
            val measuredDistance = distanceTracker.estimate(platform.nowMs())?.distanceCm ?: return
            val level = s.visionLevel
            // 近视力、多距离与远近切换按各题实测距离确定角尺寸。
            val spec = OptotypeGeometry.calculate(platform.display, level, measuredDistance)
            if (!spec.displayable) {
                if (s.step == ScreeningStep.NEAR_VISION) {
                    val index = nearLevels.indexOf(s.visionLevel)
                    val passedLower = s.answers.any { it.step == ScreeningStep.NEAR_VISION &&
                        it.eye == s.visionEye && it.level < s.visionLevel }
                    _state.update { it.copy(nearResolutionLimitedEyes = it.nearResolutionLimitedEyes + s.visionEye) }
                    if (!passedLower && index > 0) {
                        _state.update { it.copy(visionLevel = nearLevels[index - 1], nearDescending = true,
                            nearStopReason = "本机无法呈现更小视标，改测较大视标", warning = null) }
                    } else {
                        _state.update { it.copy(nearStopReason = "${s.visionEye.label}达到本机可呈现的最小视标", warning = null) }
                        advanceNearEye()
                    }
                } else if (s.step == ScreeningStep.DISTANCE_CURVE) {
                    // 与近视力一致：起始档在这个距离画不出来时先改测较大视标，而不是整个距离记为空
                    val index = nearLevels.indexOf(s.visionLevel)
                    val passedLower = s.answers.any { it.step == ScreeningStep.DISTANCE_CURVE &&
                        it.targetCm == target && it.level < s.visionLevel }
                    curveResolutionLimited = true
                    if (!passedLower && index > 0) _state.update { it.copy(visionLevel = nearLevels[index - 1],
                        nearDescending = true, trialIndex = 0, nearStopReason = "本机无法呈现更小视标，改测较大视标", warning = null) }
                    else finishCurveDistance(limitedNow = true)
                } else _state.update { it.copy(warning = "当前距离下视标笔画像素不足，不能可靠测量") }
                return
            }
            nextTrialId++
            questionStartMs = 0L
            _state.update { it.copy(trialVisible = true, trialId = nextTrialId,
                trialOptotypePx = spec.sizePx, trialDistanceCm = measuredDistance,
                trialDistanceSpreadCm = distanceSpread(),
                trialMovementMs = if (s.step == ScreeningStep.FLIPPER) platform.nowMs() - flipperMoveStartMs else null,
                warning = null) }
        }
    }

    fun markTrialPresented(trialId: Int, timestampMs: Long) {
        with(host) {
            if (_state.value.trialVisible && _state.value.trialId == trialId && questionStartMs == 0L) questionStartMs = timestampMs
        }
    }

    fun answer(direction: Int, byVoice: Boolean = false) {
        with(host) {
            val s = _state.value
            if (helpSession.paused || !s.trialVisible || questionStartMs == 0L ||
                (s.step != ScreeningStep.NEAR_VISION && s.step != ScreeningStep.FLIPPER && s.step != ScreeningStep.DISTANCE_CURVE)) return
            val target = trialTargetCm(s) ?: return
            val now = platform.nowMs()
            if (byVoice && !voiceGuard.accept(now)) return
            val answerDistance = distanceTracker.estimate(now)?.distanceCm
            if (!distanceTracker.acceptableAnswer(target, s.trialDistanceCm, PresbyopiaProtocol.ANSWER_TOLERANCE_CM, now)) {
                _state.update { it.copy(warning = if (answerDistance == null) "暂时测不到距离，请正对屏幕后再次作答"
                    else "距离变化较大，请回到 ${target.toInt()} cm 附近后再次作答") }
                return
            }
            val latency = now - questionStartMs
            if (s.step == ScreeningStep.FLIPPER && s.flipperCheck) {
                platform.feedback(ScreeningFeedbackCue.ANSWER)
                questionStartMs = 0L
                answerFlipperCheck(s, direction == s.direction, now)
                return
            }
            val answer = VisionAnswer(s.step, s.trialDistanceCm, s.visionLevel, s.direction, direction,
                direction == s.direction, latency, s.trialOptotypePx, s.visionEye, questionStartMs,
                s.trialMovementMs, if (s.step == ScreeningStep.FLIPPER) latency else null,
                s.trialDistanceSpreadCm, answerDistance ?: s.trialDistanceCm,
                targetCm = if (s.step == ScreeningStep.DISTANCE_CURVE) target else null, byVoice = byVoice)
            val answers = s.answers + answer
            platform.feedback(ScreeningFeedbackCue.ANSWER)
            questionStartMs = 0L
            if (s.step == ScreeningStep.NEAR_VISION) {
                val next = s.trialIndex + 1
                if (next >= 4) {
                    val passed = answers.count { it.step == ScreeningStep.NEAR_VISION &&
                        it.eye == s.visionEye && it.level == s.visionLevel && it.correct } >= 3
                    val index = nearLevels.indexOf(s.visionLevel)
                    val previousPass = answers.filter { it.step == ScreeningStep.NEAR_VISION &&
                        it.eye == s.visionEye && it.level != s.visionLevel }
                        .groupBy { it.level }.values.any { group -> group.size == 4 && group.count { it.correct } >= 3 }
                    _state.update { it.copy(answers = answers, trialVisible = false, warning = null) }
                    when {
                        passed && !s.nearDescending && index < nearLevels.lastIndex -> {
                            _state.update { it.copy(visionLevel = nearLevels[index + 1], trialIndex = 0,
                                direction = Random.nextInt(4)) }
                        }
                        !passed && !previousPass && index > 0 -> {
                            _state.update { it.copy(visionLevel = nearLevels[index - 1], nearDescending = true,
                                trialIndex = 0, direction = Random.nextInt(4)) }
                        }
                        else -> advanceNearEye()
                    }
                } else {
                    _state.update { it.copy(answers = answers, trialIndex = next, trialVisible = false,
                        direction = Random.nextInt(4), warning = null) }
                }
            } else if (s.step == ScreeningStep.DISTANCE_CURVE) {
                answerCurve(s, answers, target)
            } else {
                val next = s.trialIndex + 1
                flipperMoveStartMs = now
                _state.update { it.copy(answers = answers, trialIndex = next, trialVisible = false,
                    direction = Random.nextInt(4), warning = null) }
            }
        }
    }

    fun advanceNearEye() {
        with(host) {
            val next = when (_state.value.visionEye) {
                VisionEye.RIGHT -> VisionEye.LEFT
                VisionEye.LEFT -> VisionEye.BOTH
                VisionEye.BOTH -> null
            }
            if (next == null) startDistanceCurve()
            else _state.update { it.copy(visionEye = next, nearAwaitingEyeReady = true,
                nearDescending = false, trialVisible = false, trialIndex = 0, visionLevel = 0.3f,
                direction = Random.nextInt(4), nearStopReason = null, instruction = when (next) {
                    VisionEye.LEFT -> "请遮住右眼，露出左眼；准备好后继续。"
                    VisionEye.BOTH -> "请移开遮盖，双眼注视屏幕；准备好后继续。"
                    else -> ""
                }) }
        }
    }

    fun trialTargetCm(s: ScreeningState): Float? = with(host) { when (s.step) {
        ScreeningStep.NEAR_VISION -> PresbyopiaProtocol.NEAR_DISTANCE_CM
        ScreeningStep.DISTANCE_CURVE -> PresbyopiaProtocol.CURVE_DISTANCES.getOrNull(s.curveIndex)
        ScreeningStep.FLIPPER -> if (s.trialIndex % 2 == 0) PresbyopiaProtocol.FLIPPER_FAR_CM else PresbyopiaProtocol.FLIPPER_NEAR_CM
        else -> null
    }
    }

    fun bestNearLevel(answers: List<VisionAnswer>, eye: VisionEye): Float? = with(host) {
        answers.filter { it.step == ScreeningStep.NEAR_VISION && it.eye == eye }
            .groupBy { it.level }.filterValues { g -> g.size >= 4 && g.count { it.correct } >= 3 }.keys.maxOrNull()
    }

    fun bestBinocularLevel(answers: List<VisionAnswer>): Float? = with(host) { bestNearLevel(answers, VisionEye.BOTH)
    }

    fun pushLevel(eye: VisionEye): Float = with(host) {
        (bestNearLevel(_state.value.answers, eye) ?: 0.3f).coerceAtMost(PresbyopiaProtocol.PUSH_MAX_LEVEL)
    }

    fun startPushUp() {
        with(host) {
            stepStartMs = platform.nowMs()
            _state.update { it.copy(step = ScreeningStep.PUSH_UP, guidePending = true, nearPoints = emptyList(), trialIndex = 0,
                pushStage = PushStage.READY, trialVisible = false, visionEye = VisionEye.RIGHT, visionLevel = pushLevel(VisionEye.RIGHT),
                pushSpeedCmPerSec = null, instruction = pushInstruction(VisionEye.RIGHT), warning = null) }
        }
    }

    fun pushInstruction(eye: VisionEye) = with(host) {
        "${if (eye == VisionEye.RIGHT) "遮住左眼，用右眼" else "遮住右眼，用左眼"}看 E：从 45 cm 以外慢慢移近，E 模糊后停一下仍看不清就点按。这只眼做 ${PresbyopiaProtocol.PUSH_REPEATS} 次。"
    }

    fun startPushTrial() {
        with(host) {
            val s = _state.value
            if (s.step != ScreeningStep.PUSH_UP || s.pushStage != PushStage.READY || s.guidePending) return
            val now = platform.nowMs()
            val distance = distanceTracker.estimate(now)?.distanceCm
            if (distance == null || distance < PresbyopiaProtocol.PUSH_START_MIN_CM) {
                _state.update { it.copy(warning = "请先把手机移到 ${PresbyopiaProtocol.PUSH_START_MIN_CM.toInt()} cm 以外再开始") }
                return
            }
            // 视标物理尺寸固定：按 40 cm 处的角大小换算一次
            val spec = OptotypeGeometry.calculate(platform.display, s.visionLevel, PresbyopiaProtocol.NEAR_DISTANCE_CM)
            nextTrialId++
            questionStartMs = 0L
            _state.update { it.copy(pushStage = PushStage.APPROACH, trialVisible = true, trialId = nextTrialId,
                trialOptotypePx = spec.sizePx, trialDistanceCm = distance, pushStartCm = distance, direction = 0, warning = null) }
            pushTrace.clear(); pushTraceStartMs = now
            platform.feedback(ScreeningFeedbackCue.START)
        }
    }

    fun markPushBlur() {
        with(host) {
            val s = _state.value
            if (s.step != ScreeningStep.PUSH_UP || s.pushStage != PushStage.APPROACH) return
            val now = platform.nowMs()
            if (questionStartMs == 0L || now - questionStartMs < 1_000L) {
                _state.update { it.copy(warning = "请先看清 E 再慢慢移近") }; return
            }
            val distance = distanceTracker.estimate(now)?.distanceCm
            if (distance == null) {
                _state.update { it.copy(warning = "测距丢失，请保持正对屏幕后重做本次", pushStage = PushStage.READY, trialVisible = false,
                    invalidTrialCount = it.invalidTrialCount + 1) }
                return
            }
            val speed = recentApproachSpeed(now)
            if (speed != null && speed > PresbyopiaProtocol.PUSH_MAX_SPEED_CM_S) {
                _state.update { it.copy(warning = "移动过快（约 ${decimalString(speed.toDouble(), 1)} cm/秒），本次不计，请放慢重做",
                    pushStage = PushStage.READY, trialVisible = false, invalidTrialCount = it.invalidTrialCount + 1) }
                return
            }
            platform.feedback(ScreeningFeedbackCue.ANSWER)
            finishPushTrial(NearPoint(distance, speed, s.pushStartCm, eye = s.visionEye))
        }
    }

    fun recentApproachSpeed(now: Long): Float? = with(host) { distanceTracker.velocityCmPerSec(now)?.let { -it }
    }

    fun finishPushTrial(point: NearPoint) {
        with(host) {
            val eye = _state.value.visionEye
            val points = _state.value.nearPoints + point.copy(trace = pushTrace.toList(), eye = eye)
            val done = points.count { it.eye == eye }
            _state.update { it.copy(nearPoints = points, trialIndex = done,
                pushStage = PushStage.READY, trialVisible = false, pushSpeedCmPerSec = null,
                warning = if (point.censored) "移近到 ${PresbyopiaProtocol.PUSH_MIN_CM.toInt()} cm 仍未模糊，已记为「近点 < ${PresbyopiaProtocol.PUSH_MIN_CM.toInt()} cm」" else null) }
            if (done < PresbyopiaProtocol.PUSH_REPEATS) return
            val next = PresbyopiaProtocol.PUSH_EYES.getOrNull(PresbyopiaProtocol.PUSH_EYES.indexOf(eye) + 1)
            if (next == null) startFlipper()
            else _state.update { it.copy(visionEye = next, trialIndex = 0, visionLevel = pushLevel(next), instruction = pushInstruction(next)) }
        }
    }

    fun startDistanceCurve() {
        with(host) {
            stepStartMs = platform.nowMs()
            questionStartMs = 0L
            val start = bestBinocularLevel(_state.value.answers) ?: 0.3f
            curveResolutionLimited = false
            _state.update { it.copy(step = ScreeningStep.DISTANCE_CURVE, guidePending = true, curveIndex = 0, curvePoints = emptyList(),
                trialIndex = 0, visionLevel = start, nearDescending = false, visionEye = VisionEye.BOTH, trialVisible = false,
                direction = Random.nextInt(4), nearStopReason = null,
                instruction = "依次在 67、50、33 cm 停稳，视标会自动出现，点选开口方向。", warning = null) }
        }
    }

    fun answerCurve(s: ScreeningState, answers: List<VisionAnswer>, target: Float) {
        with(host) {
            val next = s.trialIndex + 1
            if (next < PresbyopiaProtocol.CURVE_TRIALS_PER_LEVEL) {
                _state.update { it.copy(answers = answers, trialIndex = next, trialVisible = false, direction = Random.nextInt(4), warning = null) }
                return
            }
            val atDistance = answers.filter { it.step == ScreeningStep.DISTANCE_CURVE && it.targetCm == target }
            val passed = atDistance.count { it.level == s.visionLevel && it.correct } >= PresbyopiaProtocol.CURVE_PASS
            val passedOther = atDistance.filter { it.level != s.visionLevel }.groupBy { it.level }
                .values.any { g -> g.size >= PresbyopiaProtocol.CURVE_TRIALS_PER_LEVEL && g.count { it.correct } >= PresbyopiaProtocol.CURVE_PASS }
            val index = nearLevels.indexOf(s.visionLevel)
            _state.update { it.copy(answers = answers, trialVisible = false, warning = null) }
            when {
                passed && !s.nearDescending && index < nearLevels.lastIndex ->
                    _state.update { it.copy(visionLevel = nearLevels[index + 1], trialIndex = 0, direction = Random.nextInt(4)) }
                !passed && !passedOther && index > 0 ->
                    _state.update { it.copy(visionLevel = nearLevels[index - 1], nearDescending = true, trialIndex = 0, direction = Random.nextInt(4)) }
                else -> finishCurveDistance(limitedNow = false)
            }
        }
    }

    fun finishCurveDistance(limitedNow: Boolean) {
        with(host) {
            val s = _state.value
            val target = PresbyopiaProtocol.CURVE_DISTANCES.getOrNull(s.curveIndex) ?: return
            val resolutionLimited = limitedNow || curveResolutionLimited
            curveResolutionLimited = false
            val threshold = s.answers.filter { it.step == ScreeningStep.DISTANCE_CURVE && it.targetCm == target }
                .groupBy { it.level }
                .filterValues { g -> g.size >= PresbyopiaProtocol.CURVE_TRIALS_PER_LEVEL && g.count { it.correct } >= PresbyopiaProtocol.CURVE_PASS }
                .keys.maxOrNull()
            val points = s.curvePoints + CurvePoint(target, threshold, resolutionLimited)
            val nextIndex = s.curveIndex + 1
            if (nextIndex >= PresbyopiaProtocol.CURVE_DISTANCES.size) {
                _state.update { it.copy(curvePoints = points) }
                // 只有满足标准调节幅度条件（A1 / A2）才做单眼移近法与远近切换，其余路径到此结束
                if (s.presbyopiaPath?.standardAmplitude == true) startPushUp() else finish()
            } else {
                _state.update { it.copy(curvePoints = points, curveIndex = nextIndex, trialIndex = 0, nearDescending = false,
                    visionLevel = bestBinocularLevel(it.answers) ?: 0.3f, direction = Random.nextInt(4), trialVisible = false,
                    nearStopReason = if (resolutionLimited) "上一距离更小视标受屏幕像素限制" else null,
                    warning = null) }
            }
        }
    }

    fun startFlipper() {
        with(host) {
            stepStartMs = platform.nowMs()
            flipperMoveStartMs = stepStartMs
            questionStartMs = 0L
            flipperCheckMisses = 0
            _state.update { it.copy(step = ScreeningStep.FLIPPER, guidePending = true, trialIndex = 0, direction = Random.nextInt(4),
                visionLevel = PresbyopiaProtocol.FLIPPER_LEVEL, trialVisible = false, visionEye = VisionEye.BOTH,
                secondsLeft = PresbyopiaProtocol.FLIPPER_SECONDS, flipperCheck = false, flipperIncomplete = null,
                instruction = "可选：在 50 cm 与 25 cm 之间来回移动手机，停稳后点选 E 的开口方向，限时 60 秒。", warning = null) }
        }
    }

    fun skipFlipper() {
        with(host) {
            val s = _state.value
            if (s.step != ScreeningStep.FLIPPER || !s.guidePending) return
            _state.update { it.copy(guidePending = false, flipperIncomplete = "选择跳过") }
            finish()
        }
    }

    fun answerFlipperCheck(s: ScreeningState, correct: Boolean, now: Long) {
        with(host) {
            flipperMoveStartMs = now
            if (!correct && ++flipperCheckMisses < PresbyopiaProtocol.FLIPPER_CHECK_TRIES) {
                _state.update { it.copy(trialVisible = false, direction = Random.nextInt(4), warning = null) }
                return
            }
            if (!correct) {
                val target = trialTargetCm(s)?.toInt()
                _state.update { it.copy(trialVisible = false, flipperCheck = false,
                    flipperIncomplete = "未达到测试条件：$target cm 处看不清视标") }
                finish(); return
            }
            flipperCheckMisses = 0
            if (s.trialIndex == 0) {
                _state.update { it.copy(trialIndex = 1, trialVisible = false, direction = Random.nextInt(4), warning = null) }
                return
            }
            // 两个距离都看得清：回到 50 cm 开始计时
            platform.feedback(ScreeningFeedbackCue.PHASE)
            _state.update { it.copy(flipperCheck = false, trialIndex = 0, trialVisible = false, direction = Random.nextInt(4), warning = null) }
            timer?.cancel()
            timer = scope.launch {
                repeat(PresbyopiaProtocol.FLIPPER_SECONDS) { index -> helpSession.delay(1000); _state.update { it.copy(secondsLeft = PresbyopiaProtocol.FLIPPER_SECONDS - 1 - index) } }
                finish()
            }
        }
    }

    fun confirmGlasses() {
        with(host) {
            if (!_state.value.glassesCheckPending) return
            platform.feedback(ScreeningFeedbackCue.ANSWER)
            _state.update { it.copy(glassesCheckPending = false, glassesConfirmed = true,
                instruction = "先遮住左眼，露出右眼；每级 4 题，停稳后再显示视标。") }
        }
    }

    fun retestWithOtherGlasses() {
        with(host) {
            val current = _state.value
            if (current.kind != ScreeningKind.PRESBYOPIA || current.subject == null) { restart(); return }
            timer?.cancel(); coverHoldJob?.cancel(); stopCamera(); samples.clear(); distanceTracker.clear(); coverDetector.clear()
            _state.value = ScreeningState(kind = current.kind, subject = current.subject,
                fields = current.fields - PresbyopiaIntake.TEST_STATE)
        }
    }
}
