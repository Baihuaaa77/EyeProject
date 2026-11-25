package com.zkyjss.eyeproject.strabismuslab

import com.zkyjss.eyeproject.core.domain.BlinkDetector
import com.zkyjss.eyeproject.core.domain.DepthFromIris
import com.zkyjss.eyeproject.core.model.EyeLandmarks
import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
import com.zkyjss.eyeproject.core.model.FaceMetrics
import com.zkyjss.eyeproject.core.model.OpticalEyeMeasurement
import com.zkyjss.eyeproject.core.model.Point2f
import com.zkyjss.eyeproject.feature.services.CoverDetector
import com.zkyjss.eyeproject.feature.services.ScreeningDistanceTracker
import com.zkyjss.eyeproject.feature.services.StrabismusProtocol
import com.zkyjss.eyeproject.feature.services.VisionEye
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 实时界面渲染状态（预览取景仪与黑屏采集舞台共用）。 */
data class LabLiveState(
    val stage: LabStage = LabStage.IDLE,
    val stageIndex: Int = 0,
    val totalStages: Int = 4,
    val remainingSec: Int = 0,
    val stageProgress: Float = 0f,
    val farWaitingConfirm: Boolean = false,
    val prompt: String = "保持正面平视，距离约 33 cm",
    val warning: String? = null,
    val faceDetected: Boolean = false,
    val distanceCm: Float = 0f,
    val distanceOk: Boolean = false,
    val fps: Float = 0f,
    val yawDeg: Float? = null,
    val pitchDeg: Float? = null,
    val rollDeg: Float? = null,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val leftIrisCenter: Point2f? = null,
    val rightIrisCenter: Point2f? = null,
    val leftIrisDiameterPx: Float = 0f,
    val rightIrisDiameterPx: Float = 0f,
    val leftVisibilityContrast: Float? = null,
    val rightVisibilityContrast: Float? = null,
    val leftCandidates: List<LabCandidateSummary> = emptyList(),
    val rightCandidates: List<LabCandidateSummary> = emptyList(),
    val stimulusIntensity: Float = 1f,
    val stimulusCode: Double = 1.0,
    val pursuitTargetX: Float = 0.5f,
    val pursuitTargetY: Float = 0.5f,
    val coverStepIndex: Int = 0,
    val coverStepTotal: Int = 6,
    val coverInstruction: String = "",
    val coverHolding: Boolean = false,
    val capturedFrames: Int = 0,
    val validFrames: Int = 0,
    val searchRadiusFraction: Float = 0.80f,
)

/**
 * StrabismusLab 双端共用科研采集与分析引擎。
 * 剥离主 App 繁杂的账号、文案、游戏化外壳，专注高精度光学数据采集、v0.8 光码解调与 Debug 可视化输出。
 */
class StrabismusLabEngine(
    private val platformName: String = "Android",
    private val deviceModel: String = "",
) {
    private val _live = MutableStateFlow(LabLiveState())
    val live: StateFlow<LabLiveState> = _live.asStateFlow()

    private val distanceTracker = ScreeningDistanceTracker()
    private val coverDetector = CoverDetector()

    private var config = LabProtocolConfig()
    private var stages: List<LabStage> = config.activeStages
    private var currentStageIdx = -1
    private var sessionId = ""
    private var sessionStartMs = 0L
    private var stageStartMs = 0L
    private var farWaiting = false
    private var frameCounter = 0
    private var validFrameCounter = 0
    private var nasalSign = 1f
    private var lastSourceW = 0
    private var lastSourceH = 0
    private val fpsSamples = mutableListOf<Double>()

    private data class CoverActionSpec(
        val label: String,
        val covered: VisionEye?,
        val uncovered: VisionEye?,
        val holdMs: Long,
    )

    private val coverSteps = listOf(
        CoverActionSpec("请遮住右眼", VisionEye.RIGHT, null, 2_000L),
        CoverActionSpec("请移开遮盖（双眼注视）", null, VisionEye.RIGHT, 1_800L),
        CoverActionSpec("请遮住左眼", VisionEye.LEFT, null, 2_000L),
        CoverActionSpec("请移开遮盖（双眼注视）", null, VisionEye.LEFT, 1_800L),
        CoverActionSpec("请再次遮住右眼", VisionEye.RIGHT, null, 1_500L),
        CoverActionSpec("请移开遮盖（完成遮盖）", null, VisionEye.RIGHT, 1_500L),
    )

    private data class RecordedCoverMark(
        val index: Int,
        val label: String,
        val covered: VisionEye?,
        val uncovered: VisionEye?,
        val eventTimeMs: Long,
        val source: String,
    )

    private var coverIndex = 0
    private var coverHoldStartMs = 0L
    private var coverHolding = false
    private var coverActionWaitStartMs = 0L
    private val coverMarks = mutableListOf<RecordedCoverMark>()

    private val stageDisplayEvents = mutableMapOf<LabStage, MutableList<LabDisplayEvent>>()
    private val stageLeftObs = mutableMapOf<LabStage, MutableList<GlintObservationFrame>>()
    private val stageRightObs = mutableMapOf<LabStage, MutableList<GlintObservationFrame>>()
    private val stageStartTimesSec = mutableMapOf<LabStage, Double>()
    private val stageDurationsSec = mutableMapOf<LabStage, Double>()
    private val frameLogs = mutableListOf<LabFrameLog>()
    private val rejectionCounts = mutableMapOf<String, Int>()
    private val pursuitSamples = mutableListOf<PursuitSample>()

    private data class PursuitSample(
        val timeMs: Long,
        val segment: Int,
        val targetX: Double,
        val targetY: Double,
        val leftX: Double,
        val leftY: Double,
        val rightX: Double,
        val rightY: Double,
    )

    fun updatePreviewConfig(newConfig: LabProtocolConfig) {
        if (_live.value.stage != LabStage.IDLE) return
        config = newConfig
        _live.value = _live.value.copy(
            searchRadiusFraction = newConfig.searchRadiusFraction,
            totalStages = max(1, newConfig.activeStages.size),
        )
    }

    fun startSession(newConfig: LabProtocolConfig, nowMs: Long): String {
        config = newConfig
        stages = newConfig.activeStages.ifEmpty { listOf(LabStage.NEAR_FIXATION) }
        sessionId = "${newConfig.subjectId.ifBlank { "LAB" }}_$nowMs"
        sessionStartMs = nowMs
        currentStageIdx = 0
        frameCounter = 0
        validFrameCounter = 0
        fpsSamples.clear()
        stageDisplayEvents.clear()
        stageLeftObs.clear()
        stageRightObs.clear()
        stageStartTimesSec.clear()
        stageDurationsSec.clear()
        frameLogs.clear()
        rejectionCounts.clear()
        pursuitSamples.clear()
        coverMarks.clear()
        distanceTracker.clear()
        coverDetector.clear()

        enterStage(stages[0], nowMs)
        return sessionId
    }

    fun confirmFarReady(nowMs: Long) {
        if (_live.value.stage != LabStage.FAR_FIXATION || !farWaiting) return
        farWaiting = false
        stageStartMs = nowMs
        stageStartTimesSec[LabStage.FAR_FIXATION] = nowMs / 1000.0
        _live.value = _live.value.copy(
            farWaitingConfirm = false,
            prompt = "越过手机注视 6 m 远目标，保持稳定",
        )
    }

    /** 遮盖识别超时或实验需要手动标记下一步时使用，明确记录 source = MANUAL。 */
    fun manualAdvanceCover(nowMs: Long) {
        if (_live.value.stage != LabStage.COVER_TEST) return
        if (coverIndex >= coverSteps.size) return
        val spec = coverSteps[coverIndex]
        coverMarks += RecordedCoverMark(coverIndex, spec.label, spec.covered, spec.uncovered, nowMs, "MANUAL")
        coverIndex++
        coverHolding = false
        coverActionWaitStartMs = nowMs
        if (coverIndex >= coverSteps.size) {
            advanceStage(nowMs)
        } else {
            _live.value = _live.value.copy(
                coverStepIndex = coverIndex,
                coverInstruction = coverSteps[coverIndex].label,
                coverHolding = false,
            )
        }
    }

    fun skipCurrentStage(nowMs: Long) {
        if (_live.value.stage == LabStage.IDLE || _live.value.stage == LabStage.COMPLETED) return
        advanceStage(nowMs)
    }

    fun stopAndBuildReport(nowMs: Long): LabSessionReport {
        val current = _live.value.stage
        if (current != LabStage.IDLE && current != LabStage.COMPLETED) {
            val startSec = stageStartTimesSec[current] ?: (stageStartMs / 1000.0)
            stageDurationsSec[current] = max(0.5, nowMs / 1000.0 - startSec)
        }
        val report = buildReport(nowMs)
        _live.value = _live.value.copy(
            stage = LabStage.COMPLETED,
            remainingSec = 0,
            stageProgress = 1f,
            prompt = "采集完成，正在呈现分析工作台",
            warning = null,
        )
        return report
    }

    fun resetToIdle() {
        currentStageIdx = -1
        farWaiting = false
        coverHolding = false
        _live.value = LabLiveState(searchRadiusFraction = config.searchRadiusFraction)
    }

    /** 显示刷新帧回调（由 Compose / SwiftUI 60Hz 帧时钟触发），写入真实绘制时间戳供 v0.8 解调。 */
    fun recordDisplayTick(hostTimeMs: Long, screenBrightness: Double = 1.0): Pair<Float, Double> {
        val stage = _live.value.stage
        if (!stage.usesOpticalStimulus || (stage == LabStage.FAR_FIXATION && farWaiting)) {
            return 1f to 1.0
        }
        val elapsedSec = max(0.0, (hostTimeMs - stageStartMs) / 1000.0)
        val chipSec = config.chipDurationMs / 1000.0
        val chip = (elapsedSec / chipSec).toInt()
        val code = StimulusCodeV8.codeValue(elapsedSec, chipSec, config.codingScheme)
        val intensity = StimulusCodeV8.intensity(elapsedSec, chipSec, config.codingScheme)
        val events = stageDisplayEvents.getOrPut(stage) { mutableListOf() }
        val hostSec = hostTimeMs / 1000.0
        if (events.isEmpty() || events.last().hostTimeSec < hostSec - 0.004) {
            events += LabDisplayEvent(
                hostTimeSec = hostSec,
                callbackHostTimeSec = hostSec,
                intensity = intensity,
                code = code,
                chip = chip,
                elapsedSec = elapsedSec,
                screenBrightness = screenBrightness,
            )
        }
        return intensity.toFloat() to code
    }

    /** 每帧相机关键点+光学候选输入（可在主线程或串行调度器调用）。返回若阶段自动完成则给出完整报告。 */
    fun onFrame(frame: FaceLandmarkFrame, focalLengthPx: Float, nowMs: Long): LabSessionReport? {
        val face = frame.faces.singleOrNull()
        lastSourceW = frame.sourceWidth
        lastSourceH = frame.sourceHeight
        if (frame.fps.isFinite() && frame.fps > 1f) fpsSamples += frame.fps.toDouble()

        val leftEye = face?.leftEye
        val rightEye = face?.rightEye
        val leftOpt = face?.leftOptical
        val rightOpt = face?.rightOptical
        val pose = face?.headPose
        val leftEar = leftEye?.let { BlinkDetector.ear(it.earPoints) }
        val rightEar = rightEye?.let { BlinkDetector.ear(it.earPoints) }

        val stage = _live.value.stage
        val irisPxList = if (stage == LabStage.COVER_TEST) {
            listOf(Triple(leftEye, leftOpt, leftEar), Triple(rightEye, rightOpt, rightEar)).mapNotNull { (e, o, ear) ->
                val vis = (o?.irisVisibilityContrast ?: 10f) >= 8f && (ear ?: 1f) >= 0.16f
                e?.irisDiameterPx?.takeIf { it > 0f && vis }
            }
        } else {
            listOfNotNull(leftEye?.irisDiameterPx, rightEye?.irisDiameterPx).filter { it > 0f }
        }
        val rawDist = if (irisPxList.isNotEmpty() && focalLengthPx > 0f) {
            DepthFromIris.distanceCm(irisPxList.average().toFloat(), focalLengthPx)
        } else 0f
        if (face != null && rawDist > 0f) distanceTracker.add(nowMs, rawDist)
        val distCm = if (face != null) distanceTracker.estimate(nowMs)?.distanceCm ?: rawDist else 0f
        val targetDist = if (stage == LabStage.PURSUIT) 25f else 33f
        val distOk = distCm in (targetDist - 6f)..(targetDist + 6f)

        val leftCands = leftOpt?.reflexCandidates.orEmpty().map {
            LabCandidateSummary(it.point.x, it.point.y, it.contrast, it.snr, it.diameterPx, it.quality)
        }
        val rightCands = rightOpt?.reflexCandidates.orEmpty().map {
            LabCandidateSummary(it.point.x, it.point.y, it.contrast, it.snr, it.diameterPx, it.quality)
        }

        if (stage == LabStage.IDLE || stage == LabStage.COMPLETED) {
            _live.value = _live.value.copy(
                faceDetected = leftEye != null && rightEye != null,
                distanceCm = distCm,
                distanceOk = distOk,
                fps = frame.fps,
                yawDeg = pose?.yawDeg,
                pitchDeg = pose?.pitchDeg,
                rollDeg = pose?.rollDeg,
                sourceWidth = frame.sourceWidth,
                sourceHeight = frame.sourceHeight,
                leftIrisCenter = leftEye?.irisPoints?.firstOrNull(),
                rightIrisCenter = rightEye?.irisPoints?.firstOrNull(),
                leftIrisDiameterPx = leftEye?.irisDiameterPx ?: 0f,
                rightIrisDiameterPx = rightEye?.irisDiameterPx ?: 0f,
                leftVisibilityContrast = leftOpt?.irisVisibilityContrast,
                rightVisibilityContrast = rightOpt?.irisVisibilityContrast,
                leftCandidates = leftCands,
                rightCandidates = rightCands,
                searchRadiusFraction = config.searchRadiusFraction,
            )
            return null
        }

        // 等待看远确认期间只更新距离与姿态 HUD，不采样也不推进计时。
        if (stage == LabStage.FAR_FIXATION && farWaiting) {
            _live.value = _live.value.copy(
                faceDetected = leftEye != null && rightEye != null,
                distanceCm = distCm,
                distanceOk = distOk,
                fps = frame.fps,
                yawDeg = pose?.yawDeg,
                pitchDeg = pose?.pitchDeg,
                rollDeg = pose?.rollDeg,
                leftIrisDiameterPx = leftEye?.irisDiameterPx ?: 0f,
                rightIrisDiameterPx = rightEye?.irisDiameterPx ?: 0f,
                leftCandidates = leftCands,
                rightCandidates = rightCands,
            )
            return null
        }

        val elapsedStageSec = max(0.0, (nowMs - stageStartMs) / 1000.0)
        val (stimIntensity, stimCode) = if (stage.usesOpticalStimulus) {
            val chipSec = config.chipDurationMs / 1000.0
            StimulusCodeV8.intensity(elapsedStageSec, chipSec, config.codingScheme) to
                StimulusCodeV8.codeValue(elapsedStageSec, chipSec, config.codingScheme)
        } else {
            0.0 to 0.0
        }

        val pitchLimit = if (stage == LabStage.FAR_FIXATION) 30f else 25f
        val poseOk = pose == null || (abs(pose.yawDeg) <= 25f && abs(pose.pitchDeg) <= pitchLimit && abs(pose.rollDeg) <= 20f)
        val rejection = when {
            face == null -> "未检测到人脸"
            stage != LabStage.COVER_TEST && (leftEye == null || rightEye == null) -> "双眼关键点不完整"
            distCm !in 18f..60f -> "距离越界（${fmt1(distCm.toDouble())} cm）"
            !poseOk -> "头姿偏转过大"
            else -> null
        }
        val frameValid = rejection == null
        frameCounter++
        if (frameValid) validFrameCounter++ else rejection?.let { rejectionCounts[it] = (rejectionCounts[it] ?: 0) + 1 }

        val leftCenter = leftEye?.irisPoints?.firstOrNull()
        val rightCenter = rightEye?.irisPoints?.firstOrNull()
        if (leftCenter != null && rightCenter != null) {
            nasalSign = if (rightCenter.x > leftCenter.x) 1f else -1f
        }

        // 记录光学观测帧供结束时跑 CornealCodeV8 双向解调
        if (stage.usesOpticalStimulus) {
            val hostSec = nowMs / 1000.0
            val exposureSec = 1.0 / max(20.0, frame.fps.toDouble().takeIf { it.isFinite() && it > 10.0 } ?: 30.0) * 0.6
            if (leftEye != null && leftCenter != null && leftOpt != null) {
                val visible = leftOpt.irisDiameterPx >= 12f && (leftOpt.irisVisibilityContrast ?: 0f) >= 8f && (leftEar ?: 1f) >= 0.14f
                stageLeftObs.getOrPut(stage) { mutableListOf() } += GlintObservationFrame(
                    frameIndex = frameCounter,
                    hostTimeSec = hostSec,
                    irisCenterX = leftCenter.x.toDouble(),
                    irisCenterY = leftCenter.y.toDouble(),
                    irisRadiusPx = leftOpt.irisDiameterPx * 0.5,
                    distanceCm = distCm.toDouble().takeIf { it > 10.0 } ?: 33.0,
                    exposureSec = exposureSec,
                    visible = visible,
                    candidates = leftOpt.reflexCandidates,
                )
            }
            if (rightEye != null && rightCenter != null && rightOpt != null) {
                val visible = rightOpt.irisDiameterPx >= 12f && (rightOpt.irisVisibilityContrast ?: 0f) >= 8f && (rightEar ?: 1f) >= 0.14f
                stageRightObs.getOrPut(stage) { mutableListOf() } += GlintObservationFrame(
                    frameIndex = frameCounter,
                    hostTimeSec = hostSec,
                    irisCenterX = rightCenter.x.toDouble(),
                    irisCenterY = rightCenter.y.toDouble(),
                    irisRadiusPx = rightOpt.irisDiameterPx * 0.5,
                    distanceCm = distCm.toDouble().takeIf { it > 10.0 } ?: 33.0,
                    exposureSec = exposureSec,
                    visible = visible,
                    candidates = rightOpt.reflexCandidates,
                )
            }
        }

        // 遮盖阶段：复用主核心 CoverDetector 实时判定遮盖/去遮盖动作并记录精确事件时间
        var stageFinished = false
        if (stage == LabStage.COVER_TEST) {
            val coverQuality = face != null && poseOk
            val learn = coverIndex == 0 || coverSteps.getOrNull(coverIndex)?.covered == null
            val obs = coverDetector.observe(nowMs, face, coverQuality, learn, tolerateSingleFlicker = coverHolding)
            if (coverHolding && coverIndex > 0) {
                val prevSpec = coverSteps[coverIndex - 1]
                val elapsedHold = nowMs - coverHoldStartMs
                if (elapsedHold >= prevSpec.holdMs) {
                    coverHolding = false
                    coverActionWaitStartMs = nowMs
                    if (coverIndex >= coverSteps.size) {
                        stageFinished = true
                    }
                }
            } else if (obs != null && coverIndex < coverSteps.size) {
                val spec = coverSteps[coverIndex]
                if (obs.covered == spec.covered) {
                    val exactTime = spec.uncovered?.let { obs.releasedAtMs[it] } ?: obs.sinceMs
                    coverMarks += RecordedCoverMark(coverIndex, spec.label, spec.covered, spec.uncovered, exactTime, "CAMERA")
                    coverIndex++
                    coverHolding = true
                    coverHoldStartMs = exactTime
                }
            }
        }

        // 追踪阶段：计算 60Hz 平滑轨迹与双眼相对虹膜坐标
        var targetX = 0.5f
        var targetY = 0.5f
        if (stage == LabStage.PURSUIT) {
            val (tx, ty, seg) = pursuitTargetNorm(elapsedStageSec, config.pursuitDurationSec.toDouble())
            targetX = tx
            targetY = ty
            val ln = leftEye?.toNormalized()
            val rn = rightEye?.toNormalized()
            if (frameValid && ln != null && rn != null && (leftEar ?: 1f) >= 0.16f && (rightEar ?: 1f) >= 0.16f) {
                pursuitSamples += PursuitSample(
                    timeMs = nowMs,
                    segment = seg,
                    targetX = tx.toDouble(),
                    targetY = ty.toDouble(),
                    leftX = (ln.first * nasalSign).toDouble(),
                    leftY = ln.second.toDouble(),
                    rightX = (rn.first * nasalSign).toDouble(),
                    rightY = rn.second.toDouble(),
                )
            }
        }

        // 写入实时帧日志（后续 v0.8 双向解调完成后会回填精准的 glint 坐标、相位与 HDE/VDE）
        val lBest = leftOpt?.cornealReflex
        val rBest = rightOpt?.cornealReflex
        val lRelX = if (lBest != null && leftCenter != null && (leftOpt?.irisDiameterPx ?: 0f) > 0f) {
            (lBest.x - leftCenter.x) / (leftOpt!!.irisDiameterPx * 0.5f)
        } else null
        val lRelY = if (lBest != null && leftCenter != null && (leftOpt?.irisDiameterPx ?: 0f) > 0f) {
            (lBest.y - leftCenter.y) / (leftOpt!!.irisDiameterPx * 0.5f)
        } else null
        val rRelX = if (rBest != null && rightCenter != null && (rightOpt?.irisDiameterPx ?: 0f) > 0f) {
            (rBest.x - rightCenter.x) / (rightOpt!!.irisDiameterPx * 0.5f)
        } else null
        val rRelY = if (rBest != null && rightCenter != null && (rightOpt?.irisDiameterPx ?: 0f) > 0f) {
            (rBest.y - rightCenter.y) / (rightOpt!!.irisDiameterPx * 0.5f)
        } else null

        frameLogs += LabFrameLog(
            index = frameCounter,
            timestampMs = nowMs,
            elapsedSec = max(0.0, (nowMs - sessionStartMs) / 1000.0),
            stage = stage,
            valid = frameValid,
            rejectionReason = rejection,
            distanceCm = distCm,
            fps = frame.fps,
            yawDeg = pose?.yawDeg,
            pitchDeg = pose?.pitchDeg,
            rollDeg = pose?.rollDeg,
            stimulusIntensity = stimIntensity,
            stimulusCode = stimCode,
            targetNormX = if (stage == LabStage.PURSUIT) targetX else null,
            targetNormY = if (stage == LabStage.PURSUIT) targetY else null,
            leftIrisX = leftCenter?.x,
            leftIrisY = leftCenter?.y,
            leftIrisDiameterPx = leftEye?.irisDiameterPx,
            leftEar = leftEar,
            leftVisibilityContrast = leftOpt?.irisVisibilityContrast,
            leftGlintX = lBest?.x,
            leftGlintY = lBest?.y,
            leftGlintConfidence = leftOpt?.reflexConfidence,
            leftGlintSource = if (lBest != null) LabGlintSource.OBSERVED else LabGlintSource.UNAVAILABLE,
            leftRelX = lRelX,
            leftRelY = lRelY,
            rightIrisX = rightCenter?.x,
            rightIrisY = rightCenter?.y,
            rightIrisDiameterPx = rightEye?.irisDiameterPx,
            rightEar = rightEar,
            rightVisibilityContrast = rightOpt?.irisVisibilityContrast,
            rightGlintX = rBest?.x,
            rightGlintY = rBest?.y,
            rightGlintConfidence = rightOpt?.reflexConfidence,
            rightGlintSource = if (rBest != null) LabGlintSource.OBSERVED else LabGlintSource.UNAVAILABLE,
            rightRelX = rRelX,
            rightRelY = rRelY,
            coveredEye = coverDetector.coveredEye?.name,
            leftCandidateCount = leftCands.size,
            rightCandidateCount = rightCands.size,
        )

        val stageTotalSec = when (stage) {
            LabStage.NEAR_FIXATION -> config.nearDurationSec.toDouble()
            LabStage.FAR_FIXATION -> config.farDurationSec.toDouble()
            LabStage.PURSUIT -> config.pursuitDurationSec.toDouble()
            else -> 0.0
        }
        if (stageTotalSec > 0.0 && elapsedStageSec >= stageTotalSec) {
            stageFinished = true
        }

        if (stageFinished) {
            val done = advanceStage(nowMs)
            if (done) return buildReport(nowMs)
        } else {
            val progress = when (stage) {
                LabStage.COVER_TEST -> (coverIndex.toFloat() / coverSteps.size).coerceIn(0f, 1f)
                else -> if (stageTotalSec > 0.0) (elapsedStageSec / stageTotalSec).toFloat().coerceIn(0f, 1f) else 0f
            }
            val remSec = if (stageTotalSec > 0.0) max(0, (stageTotalSec - elapsedStageSec).toInt() + 1) else 0
            val coverCue = if (stage == LabStage.COVER_TEST) {
                if (coverHolding && coverIndex > 0) "保持：${coverSteps[coverIndex - 1].label}"
                else coverSteps.getOrNull(coverIndex)?.label ?: "完成"
            } else ""
            _live.value = _live.value.copy(
                remainingSec = remSec,
                stageProgress = progress,
                warning = rejection,
                faceDetected = leftEye != null && rightEye != null,
                distanceCm = distCm,
                distanceOk = distOk,
                fps = frame.fps,
                yawDeg = pose?.yawDeg,
                pitchDeg = pose?.pitchDeg,
                rollDeg = pose?.rollDeg,
                sourceWidth = frame.sourceWidth,
                sourceHeight = frame.sourceHeight,
                leftIrisCenter = leftCenter,
                rightIrisCenter = rightCenter,
                leftIrisDiameterPx = leftEye?.irisDiameterPx ?: 0f,
                rightIrisDiameterPx = rightEye?.irisDiameterPx ?: 0f,
                leftVisibilityContrast = leftOpt?.irisVisibilityContrast,
                rightVisibilityContrast = rightOpt?.irisVisibilityContrast,
                leftCandidates = leftCands,
                rightCandidates = rightCands,
                stimulusIntensity = stimIntensity.toFloat(),
                stimulusCode = stimCode,
                pursuitTargetX = targetX,
                pursuitTargetY = targetY,
                coverStepIndex = coverIndex,
                coverStepTotal = coverSteps.size,
                coverInstruction = coverCue,
                coverHolding = coverHolding,
                capturedFrames = frameCounter,
                validFrames = validFrameCounter,
            )
        }
        return null
    }

    private fun enterStage(stage: LabStage, nowMs: Long) {
        stageStartMs = nowMs
        stageStartTimesSec[stage] = nowMs / 1000.0
        farWaiting = stage == LabStage.FAR_FIXATION
        coverIndex = 0
        coverHolding = false
        coverActionWaitStartMs = nowMs
        if (stage == LabStage.COVER_TEST) coverDetector.clear()

        val prompt = when (stage) {
            LabStage.NEAR_FIXATION -> "注视顶部编码白点，保持头与手机稳定"
            LabStage.FAR_FIXATION -> "准备好后点「开始看远」，越过手机看 6 m 远目标"
            LabStage.COVER_TEST -> coverSteps.first().label
            LabStage.PURSUIT -> "头部不动，双眼平滑跟随移动圆点"
            else -> ""
        }
        _live.value = _live.value.copy(
            stage = stage,
            stageIndex = currentStageIdx + 1,
            totalStages = stages.size,
            stageProgress = 0f,
            farWaitingConfirm = farWaiting,
            prompt = prompt,
            warning = null,
            coverStepIndex = 0,
            coverStepTotal = coverSteps.size,
            coverInstruction = if (stage == LabStage.COVER_TEST) coverSteps.first().label else "",
            coverHolding = false,
            searchRadiusFraction = config.searchRadiusFraction,
        )
    }

    private fun advanceStage(nowMs: Long): Boolean {
        val prev = stages.getOrNull(currentStageIdx)
        if (prev != null) {
            val startSec = stageStartTimesSec[prev] ?: (stageStartMs / 1000.0)
            stageDurationsSec[prev] = max(0.5, nowMs / 1000.0 - startSec)
        }
        currentStageIdx++
        if (currentStageIdx >= stages.size) {
            _live.value = _live.value.copy(
                stage = LabStage.COMPLETED,
                stageProgress = 1f,
                remainingSec = 0,
                prompt = "采集完成",
                warning = null,
            )
            return true
        }
        enterStage(stages[currentStageIdx], nowMs)
        return false
    }

    private fun buildReport(endMs: Long): LabSessionReport {
        val medFps = median(fpsSamples) ?: 30.0
        val fpsInt = medFps.toInt().coerceIn(15, 60)
        val chipSec = config.chipDurationMs / 1000.0

        fun analyzeStageCode(stage: LabStage): Pair<CornealCodeV8Result?, CornealCodeV8Result?> {
            val lObs = stageLeftObs[stage].orEmpty()
            val rObs = stageRightObs[stage].orEmpty()
            if (lObs.isEmpty() && rObs.isEmpty()) return null to null
            val startSec = stageStartTimesSec[stage] ?: (lObs.firstOrNull()?.hostTimeSec ?: 0.0)
            val durSec = stageDurationsSec[stage] ?: max(2.0, ((lObs.lastOrNull()?.hostTimeSec ?: startSec) - startSec))
            val events = stageDisplayEvents[stage].orEmpty()
            val lRes = CornealCodeV8Analyzer.analyze(
                eye = "LEFT",
                frames = lObs,
                displayEvents = events,
                startHostTimeSec = startSec,
                durationSec = durSec,
                chipSec = chipSec,
                scheme = config.codingScheme,
                stimulusDiameterMm = config.stimulusDiameterMm,
                searchRadiusFraction = config.searchRadiusFraction,
                fps = fpsInt,
            )
            val rRes = CornealCodeV8Analyzer.analyze(
                eye = "RIGHT",
                frames = rObs,
                displayEvents = events,
                startHostTimeSec = startSec,
                durationSec = durSec,
                chipSec = chipSec,
                scheme = config.codingScheme,
                stimulusDiameterMm = config.stimulusDiameterMm,
                searchRadiusFraction = config.searchRadiusFraction,
                fps = fpsInt,
            )
            return lRes to rRes
        }

        val (nearL, nearR) = analyzeStageCode(LabStage.NEAR_FIXATION)
        val (farL, farR) = analyzeStageCode(LabStage.FAR_FIXATION)
        val (coverL, coverR) = analyzeStageCode(LabStage.COVER_TEST)

        val leftEstimateByFrame = buildMap<Int, LabGlintFrameEstimate> {
            listOfNotNull(nearL, farL, coverL).forEach { r -> r.frames.forEach { put(it.frameIndex, it) } }
        }
        val rightEstimateByFrame = buildMap<Int, LabGlintFrameEstimate> {
            listOfNotNull(nearR, farR, coverR).forEach { r -> r.frames.forEach { put(it.frameIndex, it) } }
        }

        // 用 v0.8 双向解调后的精确坐标回填逐帧日志，并计算每帧 HDE / VDE（Δ）
        val kPrismPerRadius = StrabismusProtocol.PRISM_PER_IRIS_UNIT * 0.5 // irisRel 基于半径归一化，1 直径 = 2 半径
        val enrichedFrames = frameLogs.map { f ->
            val le = leftEstimateByFrame[f.index]
            val re = rightEstimateByFrame[f.index]
            val lRelX = le?.takeIf { it.source.measured }?.irisRelX?.toFloat()
            val lRelY = le?.takeIf { it.source.measured }?.irisRelY?.toFloat()
            val rRelX = re?.takeIf { it.source.measured }?.irisRelX?.toFloat()
            val rRelY = re?.takeIf { it.source.measured }?.irisRelY?.toFloat()
            val hde = if (f.valid && lRelX != null && rRelX != null) {
                val leftNasal = lRelX * nasalSign
                val rightNasal = rRelX * (-nasalSign)
                (leftNasal - rightNasal) * kPrismPerRadius
            } else null
            val vde = if (f.valid && lRelY != null && rRelY != null) {
                (lRelY - rRelY) * kPrismPerRadius
            } else null
            f.copy(
                leftGlintX = le?.measurementX?.toFloat() ?: le?.imageX?.toFloat(),
                leftGlintY = le?.measurementY?.toFloat() ?: le?.imageY?.toFloat(),
                leftGlintConfidence = le?.confidence?.toFloat(),
                leftGlintSource = le?.source ?: f.leftGlintSource,
                leftEmissionPhase = le?.emissionPhase ?: f.leftEmissionPhase,
                leftRelX = lRelX,
                leftRelY = lRelY,
                rightGlintX = re?.measurementX?.toFloat() ?: re?.imageX?.toFloat(),
                rightGlintY = re?.measurementY?.toFloat() ?: re?.imageY?.toFloat(),
                rightGlintConfidence = re?.confidence?.toFloat(),
                rightGlintSource = re?.source ?: f.rightGlintSource,
                rightEmissionPhase = re?.emissionPhase ?: f.rightEmissionPhase,
                rightRelX = rRelX,
                rightRelY = rRelY,
                hdePrism = hde,
                vdePrism = vde,
            )
        }

        fun computeAlignment(stage: LabStage, lCode: CornealCodeV8Result?, rCode: CornealCodeV8Result?): LabAlignmentMetrics? {
            val stageRows = enrichedFrames.filter { it.stage == stage }
            if (stageRows.isEmpty()) return null
            val settleMs = if (stage == LabStage.FAR_FIXATION) StrabismusProtocol.FAR_SETTLE_MS else 0L
            val firstMs = stageRows.first().timestampMs
            val usable = stageRows.filter { it.timestampMs >= firstMs + settleMs && it.hdePrism != null && it.vdePrism != null }
            val hVals = usable.map { it.hdePrism!! }
            val vVals = usable.map { it.vdePrism!! }
            val hMed = median(hVals)
            val vMed = median(vVals)
            val hSd = stdDev(hVals)
            val vSd = stdDev(vVals)
            // 按 200ms 自相关时间折算有效独立样本数，计算 95% 置信区间半宽
            val effN = max(1.0, usable.size / max(1.0, medFps * 0.2))
            val hCi = hSd?.let { 1.96 * it / sqrt(effN) }
            val vCi = vSd?.let { 1.96 * it / sqrt(effN) }
            return LabAlignmentMetrics(
                stage = stage,
                hdePrism = hMed,
                vdePrism = vMed,
                hdeSdPrism = hSd,
                vdeSdPrism = vSd,
                hdeCi95HalfPrism = hCi,
                vdeCi95HalfPrism = vCi,
                validBinocularFrames = usable.size,
                totalStageFrames = stageRows.size,
                leftCodeVerified = lCode?.codeVerified == true,
                rightCodeVerified = rCode?.codeVerified == true,
            )
        }

        val nearMetrics = computeAlignment(LabStage.NEAR_FIXATION, nearL, nearR)
        val farMetrics = computeAlignment(LabStage.FAR_FIXATION, farL, farR)
        val (coverEvents, coverNoise) = computeCoverRefixations(
            enrichedFrames = enrichedFrames,
            kPrismPerRadius = kPrismPerRadius,
            codeConfirmed = (coverL?.codeVerified == true || coverR?.codeVerified == true),
        )
        val coverMedian = median(coverEvents.map { it.amplitudePrism })
        val pursuitMetrics = computePursuit()

        val leftScatter = buildScatter(enrichedFrames, isLeft = true)
        val rightScatter = buildScatter(enrichedFrames, isLeft = false)

        // 综合分级与科研摘要
        val nearH = abs(nearMetrics?.hdePrism ?: 0.0)
        val nearV = abs(nearMetrics?.vdePrism ?: 0.0)
        val nearReady = (nearMetrics?.validBinocularFrames ?: 0) >= 12
        val nearVerified = nearMetrics?.let { it.leftCodeVerified && it.rightCodeVerified } == true
        val nearAbnormal = nearReady && (nearH >= StrabismusProtocol.HIGH_H_PRISM || nearV >= StrabismusProtocol.HIGH_V_PRISM)
        val coverAbnormal = coverMedian != null && coverMedian >= StrabismusProtocol.REFIX_PRISM &&
            (coverNoise == null || coverMedian > coverNoise)
        val bsiAbnormal = (pursuitMetrics?.bsi ?: 1.0) < StrabismusProtocol.BSI_ABNORMAL

        val riskGrade = when {
            nearAbnormal && (nearVerified || !config.codingScheme.usesTemporalCode) -> LabRiskGrade.HIGH_RISK
            nearAbnormal || coverAbnormal || bsiAbnormal -> LabRiskGrade.SUSPECT
            nearReady && (nearVerified || !config.codingScheme.usesTemporalCode) -> LabRiskGrade.NORMAL
            else -> LabRiskGrade.INCONCLUSIVE
        }

        val codeScore = listOfNotNull(nearL, nearR, farL, farR).let { list ->
            if (list.isEmpty()) 0.0 else list.count { it.codeVerified }.toDouble() / list.size
        }
        val validRatio = if (frameCounter > 0) validFrameCounter.toDouble() / frameCounter else 0.0
        val confidenceScore = (0.45 * validRatio + 0.40 * codeScore + 0.15 * (if (nearReady) 1.0 else 0.0)).coerceIn(0.0, 1.0)

        val notes = buildList {
            add("搜索半径：${fmt2(config.searchRadiusFraction.toDouble())} R；光码方案：${config.codingScheme.label}（码元 ${config.chipDurationMs} ms）")
            nearMetrics?.hdePrism?.let {
                val ver = if (nearVerified) "双眼光码验证通过" else "光码未完全通过"
                add("看近眼位：HDE ${fmt1(it)}Δ (±${fmt1(nearMetrics.hdeCi95HalfPrism ?: 0.0)}) / VDE ${fmt1(nearMetrics.vdePrism ?: 0.0)}Δ，有效 ${nearMetrics.validBinocularFrames} 帧（$ver）")
            }
            farMetrics?.hdePrism?.let {
                add("看远眼位：HDE ${fmt1(it)}Δ / VDE ${fmt1(farMetrics.vdePrism ?: 0.0)}Δ，有效 ${farMetrics.validBinocularFrames} 帧")
            }
            if (coverEvents.isNotEmpty()) {
                add("去遮盖再注视：中位 ${fmt1(coverMedian ?: 0.0)}Δ（${coverEvents.size} 次事件，噪声底 ${coverNoise?.let { fmt1(it) } ?: "—"}Δ）")
            }
            pursuitMetrics?.bsi?.let {
                add("平滑追踪 BSI：${fmt2(it)}（r_h ${pursuitMetrics.rHorizontal?.let(::fmt2) ?: "—"}，r_v ${pursuitMetrics.rVertical?.let(::fmt2) ?: "—"}）")
            }
        }

        return LabSessionReport(
            sessionId = sessionId,
            createdAtMs = sessionStartMs,
            platform = platformName,
            deviceModel = deviceModel,
            sourceWidth = lastSourceW,
            sourceHeight = lastSourceH,
            medianFps = medFps,
            durationSec = max(0.0, (endMs - sessionStartMs) / 1000.0),
            config = config,
            riskGrade = riskGrade,
            confidenceScore = confidenceScore,
            headline = when (riskGrade) {
                LabRiskGrade.HIGH_RISK -> "检测到持续性眼位不对称（看近 HDE ${fmt1(nearH)}Δ）"
                LabRiskGrade.SUSPECT -> "存在可疑眼位或去遮盖再注视信号，建议复测与对照"
                LabRiskGrade.NORMAL -> "本次科研采集未见显著眼位偏斜"
                LabRiskGrade.INCONCLUSIVE -> "有效光码或双眼帧不足，请在 Debug 页核查拒判原因"
            },
            summaryNotes = notes,
            nearMetrics = nearMetrics,
            farMetrics = farMetrics,
            coverRefixations = coverEvents,
            coverMedianPrism = coverMedian,
            coverNoiseFloorPrism = coverNoise,
            pursuitMetrics = pursuitMetrics,
            nearLeftCode = nearL,
            nearRightCode = nearR,
            farLeftCode = farL,
            farRightCode = farR,
            leftScatter = leftScatter,
            rightScatter = rightScatter,
            rejectionCounts = rejectionCounts.toMap(),
            totalFrames = frameCounter,
            validFrames = validFrameCounter,
            frames = enrichedFrames,
        )
    }

    private fun computeCoverRefixations(
        enrichedFrames: List<LabFrameLog>,
        kPrismPerRadius: Double,
        codeConfirmed: Boolean,
    ): Pair<List<LabCoverRefixation>, Double?> {
        val coverFrames = enrichedFrames.filter { it.stage == LabStage.COVER_TEST }
        val nearFrames = enrichedFrames.filter { it.stage == LabStage.NEAR_FIXATION && it.valid }
        // 从看近稳定段抽样 550ms 窗口的位移 95% 分位作为测量噪声底
        val noiseSamples = mutableListOf<Double>()
        for (i in nearFrames.indices) {
            val a = nearFrames[i]
            val b = nearFrames.firstOrNull { it.timestampMs >= a.timestampMs + 500L } ?: break
            val alx = a.leftRelX; val aly = a.leftRelY; val blx = b.leftRelX; val bly = b.leftRelY
            if (alx != null && aly != null && blx != null && bly != null) {
                noiseSamples += hypot((blx - alx).toDouble(), (bly - aly).toDouble()) * kPrismPerRadius
            }
        }
        val noiseFloor = noiseSamples.sorted().takeIf { it.size >= 5 }?.let { it[(it.size * 0.95).toInt().coerceAtMost(it.lastIndex)] }

        val events = mutableListOf<LabCoverRefixation>()
        val nearLeftBaseX = median(nearFrames.mapNotNull { it.leftRelX?.toDouble() })
        val nearRightBaseX = median(nearFrames.mapNotNull { it.rightRelX?.toDouble() })
        for (mark in coverMarks) {
            val eye = mark.uncovered ?: continue
            val window = coverFrames.filter {
                it.timestampMs in mark.eventTimeMs..(mark.eventTimeMs + 750L) &&
                    if (eye == VisionEye.LEFT) (it.leftRelX != null && it.leftRelY != null)
                    else (it.rightRelX != null && it.rightRelY != null)
            }
            if (window.size < 3) continue
            val first = window.first()
            val last = window.last()
            val dx = if (eye == VisionEye.LEFT) (last.leftRelX!! - first.leftRelX!!).toDouble() else (last.rightRelX!! - first.rightRelX!!).toDouble()
            val dy = if (eye == VisionEye.LEFT) (last.leftRelY!! - first.leftRelY!!).toDouble() else (last.rightRelY!! - first.rightRelY!!).toDouble()
            val ampPrism = hypot(dx, dy) * kPrismPerRadius
            val dtSec = max(0.05, (last.timestampMs - first.timestampMs) / 1000.0)
            val speedDeg = (ampPrism * 0.57) / dtSec
            val baseX = if (eye == VisionEye.LEFT) nearLeftBaseX else nearRightBaseX
            val endX = if (eye == VisionEye.LEFT) last.leftRelX!!.toDouble() else last.rightRelX!!.toDouble()
            val residual = baseX?.let { abs(endX - it) * kPrismPerRadius } ?: 0.0
            events += LabCoverRefixation(
                index = mark.index,
                eye = eye.name,
                label = mark.label,
                source = mark.source,
                uncoveredAtSec = max(0.0, (mark.eventTimeMs - sessionStartMs) / 1000.0),
                amplitudePrism = ampPrism,
                speedDegPerSec = speedDeg,
                residualPrism = residual,
                codeConfirmed = codeConfirmed,
            )
        }
        return events to noiseFloor
    }

    private fun computePursuit(): LabPursuitMetrics? {
        if (pursuitSamples.size < 12) return null
        fun velCorr(rows: List<PursuitSample>, axisX: Boolean): Double? {
            if (rows.size < 8) return null
            val lv = mutableListOf<Double>()
            val rv = mutableListOf<Double>()
            for (i in 1 until rows.size) {
                val dt = (rows[i].timeMs - rows[i - 1].timeMs) / 1000.0
                if (dt !in 0.015..0.25) continue
                lv += ((if (axisX) rows[i].leftX - rows[i - 1].leftX else rows[i].leftY - rows[i - 1].leftY) / dt)
                rv += ((if (axisX) rows[i].rightX - rows[i - 1].rightX else rows[i].rightY - rows[i - 1].rightY) / dt)
            }
            return CornealCodeV8Analyzer.pearson(lv, rv)
        }
        val horiz = pursuitSamples.filter { it.segment == 0 || it.segment == 2 }
        val vert = pursuitSamples.filter { it.segment == 1 || it.segment == 2 }
        val rH = velCorr(horiz, axisX = true)
        val rV = velCorr(vert, axisX = false)
        val diffs = horiz.map { it.leftX - it.rightX }
        val diffMean = diffs.average()
        val rms = if (diffs.size >= 4) sqrt(diffs.map { (it - diffMean) * (it - diffMean) }.average()) else null
        val span = horiz.map { (it.leftX + it.rightX) * 0.5 }.let { xs ->
            if (xs.size >= 4) (xs.max() - xs.min()).coerceAtLeast(1e-4) else null
        }
        val dNorm = if (rms != null && span != null) (rms / span).coerceIn(0.0, 1.0) else null
        val bsi = if (rH != null && rV != null && dNorm != null) 0.5 * rH + 0.3 * rV + 0.2 * (1.0 - dNorm) else null
        val followed = (0..2).count { seg ->
            val segRows = pursuitSamples.filter { it.segment == seg }
            if (segRows.size < 8) false else {
                val tx = segRows.map { it.targetX }
                val ex = segRows.map { (it.leftX + it.rightX) * 0.5 }
                val ty = segRows.map { it.targetY }
                val ey = segRows.map { (it.leftY + it.rightY) * 0.5 }
                val rx = abs(CornealCodeV8Analyzer.pearson(tx, ex) ?: 0.0)
                val ry = abs(CornealCodeV8Analyzer.pearson(ty, ey) ?: 0.0)
                max(rx, ry) >= 0.30
            }
        }
        return LabPursuitMetrics(rH, rV, dNorm, bsi, followed, pursuitSamples.size)
    }

    private fun buildScatter(frames: List<LabFrameLog>, isLeft: Boolean): List<LabGlintScatterPoint> {
        val raw = frames.mapNotNull { f ->
            val rx = if (isLeft) f.leftRelX else f.rightRelX
            val ry = if (isLeft) f.leftRelY else f.rightRelY
            val conf = if (isLeft) f.leftGlintConfidence else f.rightGlintConfidence
            val src = if (isLeft) f.leftGlintSource else f.rightGlintSource
            if (rx != null && ry != null && src.measured) {
                LabGlintScatterPoint(rx, ry, conf ?: 0.5f, f.stage, src)
            } else null
        }
        if (raw.size <= 240) return raw
        val stride = max(1, raw.size / 240)
        return raw.filterIndexed { i, _ -> i % stride == 0 }
    }

    private fun pursuitTargetNorm(elapsedSec: Double, totalSec: Double): Triple<Float, Float, Int> {
        val segDur = max(3.0, totalSec / 3.0)
        val seg = min(2, (elapsedSec / segDur).toInt())
        val local = elapsedSec - seg * segDur
        val phase = 2.0 * PI * (local / 3.5)
        return when (seg) {
            0 -> Triple((0.5 + 0.36 * sin(phase)).toFloat(), 0.5f, 0)
            1 -> Triple(0.5f, (0.5 + 0.28 * sin(phase)).toFloat(), 1)
            else -> Triple((0.5 + 0.32 * sin(phase)).toFloat(), (0.5 + 0.24 * sin(phase)).toFloat(), 2)
        }
    }

    private fun EyeLandmarks.toNormalized(): Pair<Float, Float>? {
        val iris = irisPoints.firstOrNull() ?: return null
        val a = earPoints.getOrNull(0) ?: return null
        val b = earPoints.getOrNull(3) ?: return null
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len < 4f) return null
        val mx = (a.x + b.x) * 0.5f
        val my = (a.y + b.y) * 0.5f
        val ux = dx / len
        val uy = dy / len
        val rx = ((iris.x - mx) * ux + (iris.y - my) * uy) / len
        val ry = (-(iris.x - mx) * uy + (iris.y - my) * ux) / len
        return rx to ry
    }

    private fun stdDev(values: List<Double>): Double? {
        if (values.size < 2) return null
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    }
}
