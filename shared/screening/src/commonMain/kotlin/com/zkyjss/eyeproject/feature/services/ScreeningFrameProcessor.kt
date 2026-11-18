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
    private val leftSmoother = GazeSmoother()
    private val rightSmoother = GazeSmoother()
    private val leftBlink = GazeBlinkGate()
    private val rightBlink = GazeBlinkGate()
    private var smoothingStep: ScreeningStep? = null


    fun processFrame(time: Long, step: ScreeningStep, frame: FaceLandmarkFrame) {
        with(host) {
            cameraFrameCount++
            lastCameraFrameMs = time
            val face = frame.faces.singleOrNull()
            val left = face?.leftEye?.toNormalized()
            val right = face?.rightEye?.toNormalized()
            val iris = if (step == ScreeningStep.COVER) {
                // 手掌下的虹膜是关键点预测，直径可能失真；遮盖期间只让仍可见的睁眼参与测距。
                val knownCovered = _state.value.coverMarks.lastOrNull()?.covered
                listOf(Triple(face?.leftEye, face?.leftOptical, VisionEye.LEFT),
                    Triple(face?.rightEye, face?.rightOptical, VisionEye.RIGHT)).mapNotNull { (eye, optical, which) ->
                    // 可见性像素偶尔缺测时，已确认动作的未遮眼仍用于注视监督；缺测本身不成为新的遮盖证据。
                    val visible = optical?.irisVisibilityContrast?.let { it >= 8f } ?: (knownCovered != null && knownCovered != which)
                    eye?.irisDiameterPx?.takeIf { it > 0f && visible &&
                        (BlinkDetector.ear(eye.earPoints) ?: 0f) >= 0.16f }
                }
            } else listOfNotNull(face?.leftEye?.irisDiameterPx, face?.rightEye?.irisDiameterPx).filter { it > 0f }
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
            val coding = reflectionCoding(step, time)
            if (coding) reflectionCode.observe(step, time, face)
            val opticalState = _state.value
            val applyCode = !coding && opticalState.step == step && !opticalState.guidePending &&
                !opticalState.farWaiting && !opticalState.stabilityInterrupted && time >= stepStartMs
            val leftOptical = if (applyCode) reflectionCode.measure(step, time, face?.leftEye, face?.leftOptical, true) else face?.leftOptical
            val rightOptical = if (applyCode) reflectionCode.measure(step, time, face?.rightEye, face?.rightOptical, false) else face?.rightOptical
            fun opticalQuality(eye: com.zkyjss.eyeproject.core.model.OpticalEyeMeasurement?): String =
                eye?.let {
                    "虹膜 ${decimalString(it.irisDiameterPx.toDouble(), 1)} px；瞳孔对比 ${decimalString(it.pupilContrast.toDouble(), 0)}；反光对比 ${decimalString(it.reflexContrast.toDouble(), 0)}"
                } ?: "眼区像素不可用"
            val opticalReady = leftOptical?.usable == true && rightOptical?.usable == true
            val pitchLimit = if (step == ScreeningStep.FAR_FIXATION) 30f else 25f
            val poseStable = pose != null && abs(pose.yawDeg) <= 25f &&
                abs(pose.pitchDeg) <= pitchLimit && abs(pose.rollDeg) <= 20f
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
            var stabilityFrameGood = true
            val before = _state.value
            if (before.kind == ScreeningKind.STRABISMUS && before.step == step && !before.guidePending && !before.farWaiting &&
                step in setOf(ScreeningStep.FIXATION,ScreeningStep.FAR_FIXATION,ScreeningStep.COVER,ScreeningStep.PURSUIT)) {
                if (stabilityStep != step) {
                    stabilityStep = step; stability.clear()
                    stabilityCounts = ScreeningEngine.StabilityCounts(totalFrames,validFrames,qualityEligibleFrames,qualityEligibleValidFrames,opticalValidFrames)
                }
                val knownCovered = if (step == ScreeningStep.COVER) before.coverMarks.lastOrNull()?.covered else null
                val positions = listOf(Triple(face?.leftEye,face?.leftOptical,VisionEye.LEFT),Triple(face?.rightEye,face?.rightOptical,VisionEye.RIGHT))
                    .mapNotNull { (eye,optical,which) ->
                        val visibleEye = eye ?: return@mapNotNull null
                        if ((which == knownCovered && optical?.irisVisibilityContrast == null) || (BlinkDetector.ear(visibleEye.earPoints) ?: 0f)<0.16f ||
                            optical?.irisVisibilityContrast?.let { it<8f } == true) null
                        else eyeFramePosition(visibleEye,frame.sourceWidth,frame.sourceHeight)
                    }
                val stabilityReading = stability.observe(time,step,positions,smoothedDistance,poseStable && headStable,before.stabilityInterrupted)
                // 稳定性只作质量记录，不作为连续采集或动作识别的阻断条件。
                stabilityFrameGood = true
                if (before.stabilityInterrupted) _state.update {
                    val message = if (stabilityReading.ready) "距离与位置已稳定，请重新开始本段" else stabilityReading.reason ?: "正在确认稳定，请保持手机和注视稳定"
                    it.copy(stabilityReady = stabilityReading.ready,stabilityReason = message,instruction = message,gazeWarning = message)
                }
                // 轻微姿态、距离或手机移动仅记录质量提示，不再中断当前阶段。
                else stabilityReading.reason?.let { _state.update { state -> state.copy(stabilityReason = it) } }
            }
            val valid = smoothedDistance in 10f..100f &&
                !_state.value.stabilityInterrupted && stabilityFrameGood &&
                (_state.value.kind != ScreeningKind.PRESBYOPIA || (left != null || right != null)) &&
                (_state.value.kind != ScreeningKind.STRABISMUS ||
                    ((if (step == ScreeningStep.COVER) left != null || right != null else left != null && right != null) &&
                        smoothedDistance in 20f..55f && poseStable)) &&
                (_state.value.kind == ScreeningKind.STRABISMUS || _state.value.lux == null || _state.value.lux!! >= 50f)
            if (step == _state.value.step && !_state.value.guidePending && !_state.value.stabilityInterrupted)
                observeCover(time, face, face != null && (pose == null || abs(pose.yawDeg) < 40f && abs(pose.pitchDeg) < 40f), step)
            val rendered = renderedTargets.lastOrNull { it.timeMs <= time && it.step == step }
            val covered = if (step == ScreeningStep.COVER) {
                if (coverDetector.hasObservation) coverDetector.coveredEye else _state.value.coverMarks.lastOrNull()?.covered
            } else null
            if (smoothingStep != step) {
                leftSmoother.clear(); rightSmoother.clear()
                leftBlink.clear(); rightBlink.clear(); smoothingStep = step
            }
            fun ear(eye: EyeLandmarks?, which: VisionEye) =
                eye?.takeUnless { covered == which }?.let { BlinkDetector.ear(it.earPoints) }
            val leftEar = ear(face?.leftEye, VisionEye.LEFT)
            val rightEar = ear(face?.rightEye, VisionEye.RIGHT)
            val leftBlinking = leftBlink.isBlinking(time,leftEar)
            val rightBlinking = rightBlink.isBlinking(time,rightEar)
            val gazeLeft = left?.takeIf { !leftBlinking && leftEar != null }?.let { GazePoint(it.first,it.second) }
            val gazeRight = right?.takeIf { !rightBlinking && rightEar != null }?.let { GazePoint(it.first,it.second) }
            if (leftBlinking) leftSmoother.heldDuringBlink(time)
            if (rightBlinking) rightSmoother.heldDuringBlink(time)
            // 追踪用相对眼动的时序信号，映光偏移单独从当前帧测量。
            val smoothLeft = if (leftBlinking) null else leftSmoother.update(time,gazeLeft)
            val smoothRight = if (rightBlinking) null else rightSmoother.update(time,gazeRight)
            if (_state.value.kind == ScreeningKind.STRABISMUS) diagnostics.frame(frame,
                _state.value.copy(distanceCm = smoothedDistance), valid, opticalReady,
                coding, time, gazeLeft, gazeRight, leftOptical, rightOptical, leftBlinking, rightBlinking)
            if (!coding && !_state.value.stabilityInterrupted && !_state.value.guidePending && !_state.value.farWaiting && step == _state.value.step && time >= stepStartMs && step != ScreeningStep.PROFILE && step != ScreeningStep.ENVIRONMENT && step != ScreeningStep.RESULT) {
                totalFrames++
                // 看远与追踪单独评估，两者的帧率分别于各自证据中反映。
                val eligible = step != ScreeningStep.FAR_FIXATION && step != ScreeningStep.PURSUIT && (step != ScreeningStep.COVER ||
                    _state.value.coverMarks.lastOrNull()?.label?.let { it.startsWith("移开") || it.startsWith("完全") } == true)
                if (eligible) qualityEligibleFrames++
                if (valid) {
                    validFrames++
                    if (eligible) qualityEligibleValidFrames++
                    if (_state.value.kind == ScreeningKind.STRABISMUS && opticalReady) opticalValidFrames++
                    val elapsed = (time - stepStartMs) / 1000f
                    val pursuit = if (step == ScreeningStep.PURSUIT) StrabismusProtocol.pursuitTarget(elapsed) else null
                    val target = pursuit?.let { it.first to it.second } ?: (0f to 0f)
                    val leftCenter = face?.leftEye?.irisPoints?.firstOrNull()?.x
                    val rightCenter = face?.rightEye?.irisPoints?.firstOrNull()?.x
                    if (leftCenter != null && rightCenter != null) nasalSign = if (rightCenter > leftCenter) 1f else -1f
                    // 鼻侧轴左右相反。解除遮盖要多帧确认，按当前眼区像素和眼睑开度判断可见性。
                    val leftOffset = reflectionOffset(face?.leftEye, leftOptical, step, leftBlinking)
                    val rightOffset = reflectionOffset(face?.rightEye, rightOptical, step, rightBlinking)
                    val leftNasal = leftOffset?.x?.times(nasalSign)
                    val rightNasal = rightOffset?.x?.times(-nasalSign)
                    val leftVertical = leftOffset?.y
                    val rightVertical = rightOffset?.y
                    if (_state.value.kind == ScreeningKind.STRABISMUS && (left != null || right != null)) {
                        samples.add(EyeMotionSample(time, step, smoothLeft?.x?.times(nasalSign) ?: Float.NaN, smoothLeft?.y ?: Float.NaN,
                            smoothRight?.x?.times(nasalSign) ?: Float.NaN, smoothRight?.y ?: Float.NaN,
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
                            rendered?.point?.x, rendered?.point?.y,
                            reflectionCodeMatched = leftOffset != null && rightOffset != null &&
                                leftOptical?.reflexCodeMatched == true && rightOptical?.reflexCodeMatched == true))
                    }
                }
                if (_state.value.kind == ScreeningKind.STRABISMUS &&
                    step == ScreeningStep.PURSUIT) {
                    if (time - lastGazeFrameMs > 500L) gazeBadSinceMs = null
                    lastGazeFrameMs = time
                    if (time - lastPursuitCheckMs >= 500L) {
                        lastPursuitCheckMs = time
                        pursuitFollowing = PursuitMotionCheck.follows(samples.filter { it.timeMs >= time - 4_000L }, segment =
                            StrabismusProtocol.pursuitTarget((time - stepStartMs) / 1000f).third)
                    }
                    val offTarget = pursuitFollowing == false
                    if (offTarget) {
                        if (gazeBadSinceMs == null) gazeBadSinceMs = time
                    } else gazeBadSinceMs = null
                    val gazeWarning = when {
                        offTarget && gazeBadSinceMs?.let { time - it >= 2_500L } == true ->
                            "尚未检测到跟随，请用眼睛跟着圆点移动"
                        else -> null
                    }
                    _state.update { it.copy(gazeWarning = gazeWarning) }
                }
            }
            if (time - lastUiFrameMs >= 200) {
                lastUiFrameMs = time
                _state.update { it.copy(faceDetected = left != null && right != null, distanceCm = smoothedDistance,
                    frameCount = totalFrames, acceptedFrames = validFrames, cameraFrames = cameraFrameCount,
                    cameraSourceWidth = frame.sourceWidth,cameraSourceHeight = frame.sourceHeight,analysisFps = frame.fps,
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
            // 投影到每眼的眼角坐标轴，抵消图像平移、缩放和轻微手机滚转。
            val direction = if (b.x >= a.x) 1f else -1f
            val ux = direction * (b.x - a.x) / width
            val uy = direction * (b.y - a.y) / width
            val dx = (iris.x - (a.x + b.x) / 2f) / width
            val dy = (iris.y - (a.y + b.y) / 2f) / width
            return (dx * ux + dy * uy) to (dy * ux - dx * uy)
        }
    }
}

/** 以当前帧的虹膜中心为原点，直径作尺度，保留光码或连续像素跟踪找到的真实反光。 */
internal fun reflectionOffset(
    eye: EyeLandmarks?, optical: OpticalEyeMeasurement?, step: ScreeningStep, blinking: Boolean,
): GazePoint? {
    val iris = eye?.irisPoints?.firstOrNull() ?: return null
    val glint = optical?.cornealReflex ?: return null
    val diameter = optical.irisDiameterPx
    if (blinking || optical.rejectionReason != null || !diameter.isFinite() || diameter <= 0f ||
        (BlinkDetector.ear(eye.earPoints) ?: 0f) < 0.16f ||
        (step == ScreeningStep.COVER && (optical.irisVisibilityContrast ?: 0f) < 8f)) return null
    val x = (glint.x - iris.x) / diameter
    val y = (glint.y - iris.y) / diameter
    return if (x.isFinite() && y.isFinite()) GazePoint(x, y) else null
}
