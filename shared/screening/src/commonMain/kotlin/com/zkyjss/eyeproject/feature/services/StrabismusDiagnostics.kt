package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.model.*
import kotlin.math.abs

/** 不改变判定的逐帧审计：失败帧也保留，眼区为未镜像原像素，注视为全屏归一化坐标。 */
internal class StrabismusDiagnostics(private val platform: ScreeningPlatform) {
    var sessionId: String? = null
        private set
    private var active = false
    private data class PresentedTarget(val step: ScreeningStep, val time: Long, val point: GazePoint)
    private val targets = ArrayDeque<PresentedTarget>()
    fun reportTarget(step: ScreeningStep, x: Float, y: Float, time: Long) {
        if (!active) return
        targets.addLast(PresentedTarget(step, time, GazePoint(x / platform.display.widthPixels, y / platform.display.heightPixels)))
        while (targets.size > 256) targets.removeFirst()
    }
    private val lights = ArrayDeque<Pair<Long, Boolean>>()
    fun reportLight(timeMs: Long, on: Boolean) {
        lights.addLast(timeMs to on)
        while (lights.size > 256) lights.removeFirst()
    }
    private var serial = 0
    private val reasons = mutableMapOf<String, Int>()
    private val phases = mutableMapOf<String, Int>()

    fun start() {
        if (active) return
        sessionId = "strabismus_${platform.epochMs()}_${serial++}"
        active = true; reasons.clear(); phases.clear(); lights.clear(); targets.clear()
        emit(RecordObject().apply {
            put("event", "start"); put("createdAtMs", platform.epochMs()); put("version", 1)
            put("displayWidth", platform.display.widthPixels); put("displayHeight", platform.display.heightPixels)
        })
    }

    fun frame(frame: FaceLandmarkFrame, state: ScreeningState, valid: Boolean, opticalReady: Boolean,
              target: GazePoint?, estimates: Pair<GazePoint?, GazePoint?>?, onTarget: Boolean?,
              calibrated: Boolean, coding: Boolean, sessionTime: Long,
              inputLeft: GazePoint? = null, inputRight: GazePoint? = null, calibrationSamples: Int = 0, sampleAccepted: Boolean = false, targetRenderTimeMs: Long? = null,
              leftMeasurement: OpticalEyeMeasurement? = frame.faces.singleOrNull()?.leftOptical,
              rightMeasurement: OpticalEyeMeasurement? = frame.faces.singleOrNull()?.rightOptical,
              leftBlinking: Boolean = false, rightBlinking: Boolean = false,
              fusedEstimate: GazePoint? = null) {
        if (!active || state.kind != ScreeningKind.STRABISMUS) return
        val presented = targets.lastOrNull { it.step == state.step && it.time <= sessionTime }
        val actualTarget = if (targets.isEmpty()) target else presented?.point
        val face = frame.faces.singleOrNull()
        val pose = face?.headPose
        val rejected = buildList {
            if (face == null) add("未检测到单张人脸")
            if (face?.leftEye == null || face.rightEye == null) add("双眼关键点不完整")
            if (state.distanceCm !in 20f..55f) add("距离不可用或超范围")
            if (pose == null) add("头姿不可用")
            if (pose != null && (abs(pose.yawDeg) > 25f || abs(pose.pitchDeg) > (if (state.step == ScreeningStep.FAR_FIXATION) 30f else 25f) || abs(pose.rollDeg) > 20f)) add("头姿偏离")
            if (!valid) add("采样质量门控未通过")
            if (inputLeft == null && inputRight == null) add("无可用睁眼注视输入")
            if (actualTarget == null) add("目标绘制位置缺失")
            if (state.guidePending) add("方法演示中，不采样")
            if (state.stabilityInterrupted) add("稳定性中断")
            if (state.farWaiting) add("等待看远开始")
            if (coding) add("起始光码段，不计正式映光")
            if (!calibrated) add(if (state.step == ScreeningStep.GAZE_CALIBRATION && !state.gazeCalibrationFailed) "标定进行中，暂未建立映射" else "无可靠注视标定")
            if (onTarget == false) add("估计注视偏离目标")
            if (!opticalReady) add("双眼映光不完整")
        }
        rejected.forEach { reasons[it] = (reasons[it] ?: 0) + 1 }
        phases[state.step.name] = (phases[state.step.name] ?: 0) + 1
        emit(RecordObject().apply {
            put("event", "frame"); put("captureTimeMs", frame.sourceElapsedRealtimeMs ?: frame.analysisElapsedRealtimeMs ?: sessionTime)
            put("inputLeft", point(inputLeft)); put("inputRight", point(inputRight))
            put("calibrationSamples", calibrationSamples); put("calibrationSampleAccepted", sampleAccepted)
            put("calibrationFailed", state.gazeCalibrationFailed)
            put("sessionTimeMs", sessionTime); put("phase", state.step.name)
            put("imageWidth", frame.sourceWidth); put("imageHeight", frame.sourceHeight)
            put("displayWidth", platform.display.widthPixels); put("displayHeight", platform.display.heightPixels)
            put("targetRenderTimeMs", presented?.time ?: targetRenderTimeMs)
            val light = lights.lastOrNull { it.first <= sessionTime }
            put("lightRenderTimeMs", light?.first)
            put("targetVisible", actualTarget != null && !state.guidePending &&
                (state.step !in setOf(ScreeningStep.FIXATION, ScreeningStep.COVER, ScreeningStep.FAR_FIXATION) || light?.second == true))
            put("targetColor", if (state.step in setOf(ScreeningStep.FIXATION, ScreeningStep.COVER, ScreeningStep.FAR_FIXATION)) "white" else "orange")
            put("target", point(actualTarget)); put("leftGaze", point(estimates?.first)); put("rightGaze", point(estimates?.second))
            put("gaze", point(fusedEstimate ?: fuse(estimates?.first, estimates?.second)))
            put("calibrated", calibrated); put("gazeOnTarget", onTarget)
            put("leftBlinking", leftBlinking); put("rightBlinking", rightBlinking)
            put("valid", valid); put("opticalReady", opticalReady); put("coding", coding)
            put("guidePending", state.guidePending); put("calibrationIndex", state.gazeCalibrationIndex)
            put("calibrationRound", state.gazeCalibrationRound); put("calibrationPaused", state.gazeCalibrationPaused)
            put("distanceCm", state.distanceCm); put("fps", frame.fps)
            put("warning", state.gazeWarning ?: state.warning ?: state.stabilityReason)
            put("reasons", RecordArray(rejected)); put("left", eye(face?.leftEye, leftMeasurement))
            put("right", eye(face?.rightEye, rightMeasurement))
            put("coverCount", state.coverMarks.size); put("coverHolding", state.coverHolding)
            put("headYaw", pose?.yawDeg); put("headPitch", pose?.pitchDeg); put("headRoll", pose?.rollDeg)
        })
    }

    fun finish(reason: String) {
        if (!active) return
        emit(RecordObject().apply {
            put("event", "end"); put("reason", reason); put("endedAtMs", platform.epochMs())
            put("rejectionCounts", RecordObject(reasons)); put("phaseFrames", RecordObject(phases))
        })
        active = false
    }

    private fun emit(data: RecordObject) {
        data.put("sessionId", sessionId)
        // 诊断存储失败不得中断采集或改变疾病结论；平台保存自己的错误状态供回看。
        runCatching { platform.diagnosticEvent(data.toString()) }
    }
    private fun point(p: GazePoint?) = p?.let { RecordArray(listOf(it.x, it.y)) }
    private fun fuse(left: GazePoint?, right: GazePoint?): GazePoint? = when {
        left != null && right != null -> GazePoint((left.x + right.x) * 0.5f, (left.y + right.y) * 0.5f)
        else -> left ?: right
    }
    private fun eye(eye: EyeLandmarks?, optical: OpticalEyeMeasurement?) = RecordObject().apply {
        put("lid", RecordArray(eye?.earPoints.orEmpty().map { RecordArray(listOf(it.x, it.y)) }))
        put("iris", RecordArray(eye?.irisPoints.orEmpty().map { RecordArray(listOf(it.x, it.y)) }))
        put("irisDiameter", eye?.irisDiameterPx); put("pupil", optical?.pupilCenter?.let { RecordArray(listOf(it.x, it.y)) })
        put("glint", optical?.cornealReflex?.let { RecordArray(listOf(it.x, it.y)) })
        put("glintConfidence", optical?.reflexConfidence); put("glintCodeMatched", optical?.reflexCodeMatched)
        put("usable", optical?.usable == true); put("rejection", optical?.rejectionReason ?: if (optical?.usable == true) "检测通过" else "无光学观测")
        put("pupilContrast", optical?.pupilContrast); put("glintContrast", optical?.reflexContrast)
        put("visibility", optical?.irisVisibilityContrast); put("referenceFromIris", optical?.referenceFromIris)
    }
}
