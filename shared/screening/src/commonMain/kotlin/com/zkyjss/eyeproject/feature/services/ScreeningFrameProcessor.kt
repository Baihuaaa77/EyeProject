package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.domain.*
import com.zkyjss.eyeproject.core.model.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** 相机帧处理与质量门控，向病种工作流提供观测。 */
internal class ScreeningFrameProcessor(private val host: ScreeningEngine) {
    fun processFrame(time: Long, step: ScreeningStep, frame: FaceLandmarkFrame) {
        with(host) {
            cameraFrameCount++
            lastCameraFrameMs = time
            val face = frame.faces.singleOrNull()
            val left = face?.leftEye?.toNormalized()
            val right = face?.rightEye?.toNormalized()
            val iris = listOfNotNull(face?.leftEye?.irisDiameterPx, face?.rightEye?.irisDiameterPx).filter { it > 0f }
            val calibrationVersion = platform.cameraGeometry(frame.sourceWidth, frame.sourceHeight).version
            if (cachedFocalWidth != frame.sourceWidth || cachedGeometry?.imageHeight != frame.sourceHeight ||
                cachedCalibrationVersion != calibrationVersion) {
                distanceTracker.clear()
                cachedFocalWidth = frame.sourceWidth
                cachedCalibrationVersion = calibrationVersion
                cachedGeometry = platform.cameraGeometry(frame.sourceWidth, frame.sourceHeight)
                cachedFocalPx = cachedGeometry?.focalLengthPx ?: 0f
            }
            val focalPx = cachedFocalPx
            val rawDistance = if (iris.isNotEmpty()) DepthFromIris.distanceCm(iris.average().toFloat(), focalPx) else 0f
            val distance = rawDistance
            if (face != null) distanceTracker.add(time, distance)
            val smoothedDistance = if (face != null) distanceTracker.estimate(time)?.distanceCm ?: 0f else 0f
            val pose = face?.headPose
            val leftOptical = face?.leftOptical
            val rightOptical = face?.rightOptical
            fun opticalQuality(eye: com.zkyjss.eyeproject.core.model.OpticalEyeMeasurement?): String =
                eye?.let {
                    "虹膜 ${decimalString(it.irisDiameterPx.toDouble(), 1)} px；瞳孔对比 ${decimalString(it.pupilContrast.toDouble(), 0)}；反光对比 ${decimalString(it.reflexContrast.toDouble(), 0)}"
                } ?: "眼区像素不可用"
            val opticalReady = leftOptical?.usable == true && rightOptical?.usable == true
            val pitchLimit = if (step == ScreeningStep.FAR_FIXATION) StrabismusProtocol.FAR_PITCH_LIMIT_DEG else 10f
            val poseStable = pose != null && abs(pose.yawDeg) <= 10f &&
                abs(pose.pitchDeg) <= pitchLimit && abs(pose.rollDeg) <= 8f
            val opticalStatus = when {
                face == null -> "等待人脸"
                pose == null -> "头姿不可用"
                leftOptical?.usable != true -> "左眼：${leftOptical?.rejectionReason ?: "眼区不可用"}"
                rightOptical?.usable != true -> "右眼：${rightOptical?.rejectionReason ?: "眼区不可用"}"
                !poseStable -> "头姿偏转过大"
                else -> "双眼光学测量可用"
            }
            if (_state.value.kind == ScreeningKind.STRABISMUS && opticalStatus != "双眼光学测量可用") {
                // 两只眼分别计数：原先只记先失败的左眼，右眼是否可用无从得知
                val reasons = buildList {
                    if (face == null || pose == null || !poseStable) add(opticalStatus)
                    else {
                        if (leftOptical?.usable != true) add("左眼：${leftOptical?.rejectionReason ?: "眼区不可用"}")
                        if (rightOptical?.usable != true) add("右眼：${rightOptical?.rejectionReason ?: "眼区不可用"}")
                    }
                }
                reasons.forEach { opticalRejectionCounts[it] = (opticalRejectionCounts[it] ?: 0) + 1 }
            }
            if (_state.value.kind == ScreeningKind.STRABISMUS)
                opticalIrisReferenceFrames += listOfNotNull(leftOptical, rightOptical).count { it.usable && it.referenceFromIris }
            if (_state.value.kind == ScreeningKind.STRABISMUS && opticalContrasts.size < 20_000) {
                listOfNotNull(leftOptical, rightOptical).forEach {
                    opticalContrasts += it.pupilContrast; opticalIrisPx += it.irisDiameterPx
                }
            }
            val trackingStatus = when {
                face == null -> "相机已工作，未识别人脸"
                left == null || right == null -> "相机已工作，双眼关键点不完整"
                focalPx <= 0f -> "已识别双眼，但前置相机焦距参数不可用"
                smoothedDistance <= 0f -> "已识别双眼，但虹膜测距失败"
                else -> "双眼跟踪与测距正常"
            }
            if (headStep != step) {
                headStep = step
                faceCenterBaseline = null
            }
            val center = face?.faceBox?.let { ((it.left + it.right) / 2f) to ((it.top + it.bottom) / 2f) }
            if (faceCenterBaseline == null && center != null) faceCenterBaseline = center
            val baseline = faceCenterBaseline
            val headStable = center != null && baseline != null &&
                abs(center.first - baseline.first) <= frame.sourceWidth * 0.08f &&
                abs(center.second - baseline.second) <= frame.sourceHeight * 0.08f
            val valid = smoothedDistance in 10f..100f &&
                (_state.value.kind != ScreeningKind.PRESBYOPIA || (left != null || right != null)) &&
                (_state.value.kind != ScreeningKind.STRABISMUS ||
                    ((if (step == ScreeningStep.COVER) left != null || right != null else left != null && right != null) &&
                        abs(smoothedDistance - StrabismusProtocol.DISTANCE_CM) <= StrabismusProtocol.DISTANCE_GATE_CM && headStable && poseStable)) &&
                (_state.value.lux == null || _state.value.lux!! >= 50f)
            if (step == _state.value.step && !_state.value.guidePending) observeCover(time, face, valid && poseStable, step)
            val rendered = renderedTargets.lastOrNull { it.timeMs <= time && it.step == step }
            val covered = if (step == ScreeningStep.COVER) {
                if (coverDetector.hasObservation) coverDetector.coveredEye else _state.value.coverMarks.lastOrNull()?.covered
            } else null
            fun openEye(eye: EyeLandmarks?, position: Pair<Float, Float>?, which: VisionEye): GazePoint? =
                position?.takeIf { covered != which && eye != null && (BlinkDetector.ear(eye.earPoints) ?: 0f) >= 0.16f }
                    ?.let { GazePoint(it.first, it.second) }
            val gazeLeft = openEye(face?.leftEye, left, VisionEye.LEFT)
            val gazeRight = openEye(face?.rightEye, right, VisionEye.RIGHT)
            if (step == ScreeningStep.GAZE_CALIBRATION && !_state.value.gazeCalibrationFailed && !_state.value.gazeCalibrated &&
                step == _state.value.step && time >= calibrationPointStartMs) {
                // 异常（人脸丢失、头偏或移动、距离超出、双眼都闭着）：暂停采样，本点计时随之顺延
                if (!valid || (gazeLeft == null && gazeRight == null)) {
                    if (calibrationPausedSinceMs == null) calibrationPausedSinceMs = time
                    lastCalibrationSampleMs = 0L
                } else {
                    if (calibrationPausedSinceMs != null) { calibrationPausedSinceMs = null; calibrationResumeMs = time }
                    if (rendered != null && rendered.index == _state.value.gazeCalibrationIndex) {
                        // 从本次换点后的首次实际绘制起等待注视稳定；补测重看同一点时不沿用上一轮的绘制时刻。
                        // 异常恢复或目标被挪动后，也要重新稳定再采样。
                        val firstShown = renderedTargets.firstOrNull { it.step == step && it.index == rendered.index &&
                            it.timeMs >= calibrationPointStartMs }?.timeMs
                        val settledAt = firstShown?.let { maxOf(it + StrabismusGaze.SETTLE_MS, calibrationResumeMs + StrabismusGaze.RESETTLE_MS,
                            calibrationTargetMovedMs + StrabismusGaze.SETTLE_MS) }
                        if (settledAt != null && time >= settledAt) {
                            gazeCalibration.add(GazeCalibrationSample(rendered.index, rendered.point, gazeLeft, gazeRight))
                            if (lastCalibrationSampleMs > 0L && time - lastCalibrationSampleMs <= StrabismusGaze.MAX_SAMPLE_GAP_MS)
                                calibrationSampledMs += time - lastCalibrationSampleMs
                            lastCalibrationSampleMs = time
                        } else lastCalibrationSampleMs = 0L
                    }
                }
                val paused = calibrationPausedSinceMs?.let { time - it >= StrabismusGaze.PAUSE_NOTICE_MS } == true
                if (paused != _state.value.gazeCalibrationPaused) _state.update { it.copy(gazeCalibrationPaused = paused,
                    gazeWarning = if (paused) "校准已暂停：请把头摆正、保持约 33 cm，眼睛看着圆点" else null) }
            }
            val estimates = if (valid) gaze?.estimate(gazeLeft, gazeRight) else null
            val gazeOnTarget = rendered?.let { target -> estimates?.let { (l, r) ->
                if (l == null && r == null) null else StrabismusGaze.onTarget(l, target.point) || StrabismusGaze.onTarget(r, target.point)
            } }
            if (!_state.value.guidePending && !_state.value.farWaiting && step == _state.value.step && time >= stepStartMs && step != ScreeningStep.PROFILE && step != ScreeningStep.ENVIRONMENT && step != ScreeningStep.RESULT && step != ScreeningStep.GAZE_CALIBRATION) {
                totalFrames++
                // 看远另行评估（数据不足时只是不评估看远），不拉低整体有效帧率
                val eligible = step != ScreeningStep.FAR_FIXATION && (step != ScreeningStep.COVER ||
                    _state.value.coverMarks.lastOrNull()?.label?.let { it.startsWith("移开") || it.startsWith("完全") } == true)
                if (eligible) qualityEligibleFrames++
                if (valid) {
                    validFrames++
                    if (eligible) qualityEligibleValidFrames++
                    if (_state.value.kind == ScreeningKind.STRABISMUS && opticalReady) opticalValidFrames++
                    val elapsed = (time - stepStartMs) / 1000f
                    val pursuit = if (step == ScreeningStep.PURSUIT) StrabismusProtocol.pursuitTarget(elapsed) else null
                    val target = pursuit?.let { it.first to it.second } ?: (0f to 0f)
                    // 鼻侧坐标：左眼的鼻侧朝向右眼，右眼相反；映光偏心 = (映光 − 瞳孔) / 虹膜直径。
                    // 每只眼单独记录：交替遮盖时总有一只眼被遮，未遮眼的数据仍要保留。
                    val leftCenter = face?.leftEye?.irisPoints?.firstOrNull()?.x
                    val rightCenter = face?.rightEye?.irisPoints?.firstOrNull()?.x
                    if (leftCenter != null && rightCenter != null) nasalSign = if (rightCenter > leftCenter) 1f else -1f
                    val leftNasalSign = nasalSign
                    val leftNasal = leftOptical?.horizontalIrisUnits?.let { -it * leftNasalSign }
                    val rightNasal = rightOptical?.horizontalIrisUnits?.let { -it * -leftNasalSign }
                    val leftVertical = leftOptical?.verticalIrisUnits?.let { -it }
                    val rightVertical = rightOptical?.verticalIrisUnits?.let { -it }
                    if (_state.value.kind == ScreeningKind.STRABISMUS && (left != null || right != null)) {
                        samples.add(EyeMotionSample(time, step, left?.first ?: Float.NaN, left?.second ?: Float.NaN, right?.first ?: Float.NaN, right?.second ?: Float.NaN,
                            smoothedDistance, target.first, target.second,
                            if (opticalReady) leftOptical?.horizontalIrisUnits else null,
                            if (opticalReady) leftOptical?.verticalIrisUnits else null,
                            if (opticalReady) rightOptical?.horizontalIrisUnits else null,
                            if (opticalReady) rightOptical?.verticalIrisUnits else null,
                            pose?.yawDeg, pose?.pitchDeg, pose?.rollDeg,
                            leftOptical?.pupilCenter?.x, leftOptical?.pupilCenter?.y,
                            leftOptical?.cornealReflex?.x, leftOptical?.cornealReflex?.y,
                            rightOptical?.pupilCenter?.x, rightOptical?.pupilCenter?.y,
                            rightOptical?.cornealReflex?.x, rightOptical?.cornealReflex?.y,
                            leftNasal, rightNasal, leftVertical, rightVertical, pursuit?.third ?: -1,
                            rendered?.point?.x, rendered?.point?.y, estimates?.first?.x, estimates?.first?.y,
                            estimates?.second?.x, estimates?.second?.y, gazeOnTarget))
                    }
                }
                if (_state.value.kind == ScreeningKind.STRABISMUS &&
                    step in setOf(ScreeningStep.FIXATION, ScreeningStep.COVER, ScreeningStep.PURSUIT)) {
                    if (time - lastGazeFrameMs > 500L) gazeBadSinceMs = null
                    lastGazeFrameMs = time
                    if (step == ScreeningStep.PURSUIT && time - lastPursuitCheckMs >= 500L) {
                        lastPursuitCheckMs = time
                        pursuitFollowing = PursuitGazeCheck.follows(samples.filter { it.timeMs >= time - 4_000L }, segment =
                            StrabismusProtocol.pursuitTarget((time - stepStartMs) / 1000f).third)
                    }
                    val offTarget = gazeOnTarget != true || pursuitFollowing == false
                    if (offTarget) {
                        if (gazeBadSinceMs == null) gazeBadSinceMs = time
                    } else gazeBadSinceMs = null
                    // 遮盖阶段家长的手在脸前，判断不了注视很正常：只在确实看偏时提醒；其余阶段也放宽等待时间
                    val gazeWarning = when {
                        gazeOnTarget == null && step != ScreeningStep.COVER &&
                            gazeBadSinceMs?.let { time - it >= 3_000L } == true -> "暂时无法确认注视，请保持头部稳定并看着屏幕目标"
                        gazeOnTarget != null && gazeBadSinceMs?.let { time - it >= 2_500L } == true ->
                            if (step == ScreeningStep.PURSUIT) "未检测到有效跟随，请只用眼睛跟随移动的圆点" else "注视明显偏离，请看着屏幕上的圆点"
                        else -> null
                    }
                    _state.update { it.copy(gazeWarning = gazeWarning) }
                }
            }
            if (time - lastUiFrameMs >= 200) {
                lastUiFrameMs = time
                _state.update { it.copy(faceDetected = left != null && right != null, distanceCm = smoothedDistance,
                    frameCount = totalFrames, acceptedFrames = validFrames, cameraFrames = cameraFrameCount,
                    cameraBrightness = frame.imageBrightness, trackingStatus = trackingStatus,
                    opticalStatus = opticalStatus, opticalValidFrames = opticalValidFrames,
                    opticalRejectionCounts = opticalRejectionCounts.toMap(),
                    leftOpticalQuality = opticalQuality(leftOptical),
                    rightOpticalQuality = opticalQuality(rightOptical),
                    leftOpticalX = if (opticalReady && poseStable) leftOptical?.horizontalIrisUnits else null,
                    leftOpticalY = if (opticalReady && poseStable) leftOptical?.verticalIrisUnits else null,
                    rightOpticalX = if (opticalReady && poseStable) rightOptical?.horizontalIrisUnits else null,
                    rightOpticalY = if (opticalReady && poseStable) rightOptical?.verticalIrisUnits else null,
                    headPose = pose,
                    warning = if (it.warning?.startsWith("未收到相机") == true ||
                        it.warning?.startsWith("相机数据已中断") == true ||
                        it.warning?.startsWith("相机错误") == true) null else it.warning,
                ) }
            }
            trackDistanceMove(smoothedDistance)
            val st = _state.value
            // 近视力、多距离与远近切换：容差内距离浮动时视标随实测距离实时缩放，保持视角不变；超出容差提醒回到目标距离。
            // 移近法（PUSH_UP）按方法要求保持视标物理尺寸不变，不在此列。
            if (st.trialVisible && smoothedDistance > 0f &&
                (step == ScreeningStep.NEAR_VISION || step == ScreeningStep.DISTANCE_CURVE || step == ScreeningStep.FLIPPER)) {
                trialTargetCm(st)?.let { target ->
                    if (abs(smoothedDistance - target) <= nearDistanceToleranceCm) {
                        val size = OptotypeGeometry.calculate(platform.display, st.visionLevel, smoothedDistance)
                        if (size.displayable && st.trialOptotypePx > 0f && abs(size.sizePx - st.trialOptotypePx) / st.trialOptotypePx > RESIZE_STEP)
                            _state.update { it.copy(trialOptotypePx = size.sizePx) }
                        if (st.warning?.startsWith(BACK_TO_DISTANCE) == true) _state.update { it.copy(warning = null) }
                    } else if (st.warning == null) {
                        _state.update { it.copy(warning = "$BACK_TO_DISTANCE ${target.toInt()} cm") }
                    }
                }
            }
            // 停稳后视标自动出现：免去点按「显示视标」带动手机
            if ((step == ScreeningStep.FLIPPER && st.secondsLeft > 0 || step == ScreeningStep.DISTANCE_CURVE ||
                    step == ScreeningStep.NEAR_VISION && !st.nearAwaitingEyeReady) &&
                !st.guidePending && !st.trialVisible) {
                trialTargetCm(st)?.let { target -> if (distanceStable(target, nearDistanceToleranceCm)) showTrial() }
            }
            if (step == ScreeningStep.PUSH_UP && st.pushStage == PushStage.APPROACH) {
                if (questionStartMs == 0L && st.trialVisible) questionStartMs = time
                if (lastUiFrameMs == time) _state.update { it.copy(pushSpeedCmPerSec = recentApproachSpeed(time)) }
                val traceSec = (time - pushTraceStartMs) / 1000f
                if (smoothedDistance > 0f && (pushTrace.isEmpty() || traceSec - pushTrace.last().first >= 0.1f) && pushTrace.size < 1_200)
                    pushTrace += traceSec to smoothedDistance
                if (smoothedDistance in 1f..PresbyopiaProtocol.PUSH_MIN_CM) {
                    finishPushTrial(NearPoint(smoothedDistance, recentApproachSpeed(time), st.pushStartCm, censored = true))
                }
            }
        }
    }

    fun EyeLandmarks.toNormalized(): Pair<Float, Float>? {
        with(host) {
            val iris = irisPoints.firstOrNull() ?: return null
            val a = earPoints.getOrNull(0) ?: return null
            val b = earPoints.getOrNull(3) ?: return null
            val width = hypot(b.x - a.x, b.y - a.y)
            if (width < 2f) return null
            return ((iris.x - (a.x + b.x) / 2f) / width) to ((iris.y - (a.y + b.y) / 2f) / width)
        }
    }
}
