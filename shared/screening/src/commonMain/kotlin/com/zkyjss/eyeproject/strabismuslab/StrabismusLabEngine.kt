package com.zkyjss.eyeproject.strabismuslab

import com.zkyjss.eyeproject.core.domain.BlinkDetector
import com.zkyjss.eyeproject.core.domain.DepthFromIris
import com.zkyjss.eyeproject.core.model.EyeLandmarks
import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
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
    /** 进入任意测试阶段前先展示准备引导页，点击确认后再开始采集（避免还没准备好就开跑）。 */
    val stageWaitingConfirm: Boolean = false,
    val farWaitingConfirm: Boolean = false,
    val stageBriefingTitle: String = "",
    val stageBriefingBody: String = "",
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
    val coverStepTotal: Int = 7,
    val coverInstruction: String = "",
    val coverHolding: Boolean = false,
    val capturedFrames: Int = 0,
    val validFrames: Int = 0,
    val searchRadiusFraction: Float = 0.80f,
)

/**
 * StrabismusLab 双端共用科研采集与分析引擎（v0.9）。
 * 集成：
 * - v0.8 双向主动光码解调 + 双眼/跨阶段硬件延迟共享先验 + 稳定 ON 帧门控过滤；
 * - 3D 球面角膜镜光学反演（离轴光源物理偏置补偿 + 大角度球面非线性 + 虹膜连线 Roll 旋正）；
 * - 遮盖/去遮盖双向毫秒级拐点回溯 + 显斜（遮盖对侧眼）/ 隐斜·IXT（去遮盖同侧眼）动力学曲线；
 * - 400ms 窗口平滑追踪速度相关（抗关键点 33ms 帧间抖动）。
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
    private var stageWaiting = false
    private var frameCounter = 0
    private var validFrameCounter = 0
    private var nasalSign = 1f
    private var lastSourceW = 0
    private var lastSourceH = 0
    private var lastIntrinsics: LabCameraIntrinsics? = null
    private val fpsSamples = mutableListOf<Double>()

    private data class CoverActionSpec(
        val label: String,
        val covered: VisionEye?,
        val uncovered: VisionEye?,
        val holdMs: Long,
    )

    /** 完整临床遮盖序列：单眼 Cover-Uncover（区分显斜/隐斜）+ 快速交替遮盖（打破融合测总偏斜与 IXT 恢复）。 */
    private val coverSteps = listOf(
        CoverActionSpec("请遮住右眼", VisionEye.RIGHT, null, 2_000L),
        CoverActionSpec("请移开遮盖（双眼注视）", null, VisionEye.RIGHT, 1_800L),
        CoverActionSpec("请遮住左眼", VisionEye.LEFT, null, 2_000L),
        CoverActionSpec("请移开遮盖（双眼注视）", null, VisionEye.LEFT, 1_800L),
        CoverActionSpec("交替遮盖：请遮住右眼", VisionEye.RIGHT, null, 1_500L),
        CoverActionSpec("立即换遮左眼", VisionEye.LEFT, VisionEye.RIGHT, 1_500L),
        CoverActionSpec("完全移开遮盖（完成）", null, VisionEye.LEFT, 1_500L),
    )

    private data class RecordedCoverMark(
        val index: Int,
        val label: String,
        val covered: VisionEye?,
        val uncovered: VisionEye?,
        val eventTimeMs: Long,
        val confirmedAtMs: Long,
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

    /** 最近一次写入帧日志的帧号（供视频录制器把视频帧与 report.frames 精确一一对应）。 */
    val lastLoggedFrameIndex: Int get() = frameCounter

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
        lastIntrinsics = null
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

    /** 通用阶段确认入口：受试者阅读完当前环节说明并摆好距离后点击确认，正式开始本阶段采集。 */
    fun confirmStageReady(nowMs: Long) {
        val stage = _live.value.stage
        if (!stageWaiting || stage == LabStage.IDLE || stage == LabStage.COMPLETED) return
        stageWaiting = false
        stageStartMs = nowMs
        stageStartTimesSec[stage] = nowMs / 1000.0
        coverActionWaitStartMs = nowMs
        if (stage == LabStage.COVER_TEST) coverDetector.clear()
        val runningPrompt = when (stage) {
            LabStage.NEAR_FIXATION -> "注视顶部编码白点中心，保持头与手机约 33 cm"
            LabStage.FAR_FIXATION -> "越过手机注视 6 m 远目标，保持稳定"
            LabStage.COVER_TEST -> coverSteps.first().label
            LabStage.PURSUIT -> "头部不动，双眼平滑跟随移动圆点"
            else -> ""
        }
        _live.value = _live.value.copy(
            stageWaitingConfirm = false,
            farWaitingConfirm = false,
            prompt = runningPrompt,
        )
    }

    /** 兼容旧看远确认入口。 */
    fun confirmFarReady(nowMs: Long) = confirmStageReady(nowMs)

    /** 遮盖识别超时或实验需要手动标记下一步时使用，明确记录 source = MANUAL。 */
    fun manualAdvanceCover(nowMs: Long) {
        if (_live.value.stage != LabStage.COVER_TEST) return
        if (coverIndex >= coverSteps.size) return
        val spec = coverSteps[coverIndex]
        coverMarks += RecordedCoverMark(coverIndex, spec.label, spec.covered, spec.uncovered, nowMs, nowMs, "MANUAL")
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
        stageWaiting = false
        coverHolding = false
        _live.value = LabLiveState(searchRadiusFraction = config.searchRadiusFraction)
    }

    private fun activeCodingScheme(stage: LabStage): LabCodingScheme =
        if (stage.forcesSteadyStimulus) LabCodingScheme.STEADY else config.codingScheme

    /** 显示刷新帧回调（由 Compose / SwiftUI 60Hz 帧时钟触发），写入真实绘制时间戳供 v0.8 解调。 */
    fun recordDisplayTick(hostTimeMs: Long, screenBrightness: Double = 1.0): Pair<Float, Double> {
        val stage = _live.value.stage
        if (!stage.usesOpticalStimulus || stageWaiting) {
            return 1f to 1.0
        }
        val elapsedSec = max(0.0, (hostTimeMs - stageStartMs) / 1000.0)
        val chipSec = config.chipDurationMs / 1000.0
        val chip = (elapsedSec / chipSec).toInt()
        val scheme = activeCodingScheme(stage)
        val code = StimulusCodeV8.codeValue(elapsedSec, chipSec, scheme)
        val intensity = StimulusCodeV8.intensity(elapsedSec, chipSec, scheme)
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

    /** 兼容旧单焦距签名：自动构造主点在图像中心的近似内参，并叠加配置中的光源物理偏置。 */
    fun onFrame(frame: FaceLandmarkFrame, focalLengthPx: Float, nowMs: Long): LabSessionReport? {
        val f = focalLengthPx.toDouble()
        val intrinsics = LabCameraIntrinsics(
            fx = f,
            fy = f,
            cx = frame.sourceWidth * 0.5,
            cy = frame.sourceHeight * 0.5,
            lightOffsetXMm = config.lightOffsetXMm,
            lightOffsetYMm = config.lightOffsetYMm,
        )
        return onFrameWithIntrinsics(frame, intrinsics, nowMs)
    }

    /** 完整内参入口：传入 (fx, fy, cx, cy) 与光源相对前摄物理偏置，供 3D 球面光学反演使用。 */
    fun onFrameWithIntrinsics(frame: FaceLandmarkFrame, intrinsics: LabCameraIntrinsics, nowMs: Long): LabSessionReport? {
        val mergedIntrinsics = intrinsics.copy(
            lightOffsetXMm = config.lightOffsetXMm,
            lightOffsetYMm = config.lightOffsetYMm,
        )
        if (mergedIntrinsics.isValid) lastIntrinsics = mergedIntrinsics
        val focalLengthPx = if (mergedIntrinsics.isValid) {
            ((mergedIntrinsics.fx + mergedIntrinsics.fy) * 0.5).toFloat()
        } else 0f

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

        if (stageWaiting) {
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
        val stageScheme = activeCodingScheme(stage)
        val (stimIntensity, stimCode) = if (stage.usesOpticalStimulus) {
            val chipSec = config.chipDurationMs / 1000.0
            StimulusCodeV8.intensity(elapsedStageSec, chipSec, stageScheme) to
                StimulusCodeV8.codeValue(elapsedStageSec, chipSec, stageScheme)
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

        // 遮盖阶段：CoverDetector 负责实时稳健状态机；精确毫秒级拐点在 buildReport() 双向回溯锁定
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
                    coverMarks += RecordedCoverMark(
                        index = coverIndex,
                        label = spec.label,
                        covered = spec.covered,
                        uncovered = spec.uncovered,
                        eventTimeMs = exactTime,
                        confirmedAtMs = obs.confirmedAtMs,
                        source = "CAMERA",
                    )
                    coverIndex++
                    coverHolding = true
                    coverHoldStartMs = exactTime
                }
            }
        }

        val leftNorm = leftEye?.toNormalized()
        val rightNorm = rightEye?.toNormalized()

        // 追踪阶段：计算 60Hz 平滑轨迹与双眼相对虹膜坐标
        var targetX = 0.5f
        var targetY = 0.5f
        if (stage == LabStage.PURSUIT) {
            val (tx, ty, seg) = pursuitTargetNorm(elapsedStageSec, config.pursuitDurationSec.toDouble())
            targetX = tx
            targetY = ty
            if (frameValid && leftNorm != null && rightNorm != null && (leftEar ?: 1f) >= 0.16f && (rightEar ?: 1f) >= 0.16f) {
                pursuitSamples += PursuitSample(
                    timeMs = nowMs,
                    segment = seg,
                    targetX = tx.toDouble(),
                    targetY = ty.toDouble(),
                    leftX = (leftNorm.first * nasalSign).toDouble(),
                    leftY = leftNorm.second.toDouble(),
                    rightX = (rightNorm.first * nasalSign).toDouble(),
                    rightY = rightNorm.second.toDouble(),
                )
            }
        }

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
            leftNormX = leftNorm?.first,
            leftNormY = leftNorm?.second,
            rightNormX = rightNorm?.first,
            rightNormY = rightNorm?.second,
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
        stageWaiting = stage != LabStage.IDLE && stage != LabStage.COMPLETED
        coverIndex = 0
        coverHolding = false
        coverActionWaitStartMs = nowMs
        if (stage == LabStage.COVER_TEST) coverDetector.clear()

        val (briefTitle, briefBody, prompt) = when (stage) {
            LabStage.NEAR_FIXATION -> Triple(
                "准备：看近光码映光（${config.nearDurationSec} 秒）",
                "将手机举在眼前约 33 厘米处，头部摆正。点击开始后，双眼自然注视屏幕顶部闪烁的白点中央，尽量保持头部和手机稳定。",
                "调整至约 33 cm，准备好后点击开始看近",
            )
            LabStage.FAR_FIXATION -> Triple(
                "准备：看远越屏光码（${config.farDurationSec} 秒）",
                "手机保持在眼前约 33 厘米、略低于视线。点击开始后，越过手机顶部注视 6 米外远处固定目标，不要看屏幕上的白点。",
                "越过手机找好 6 m 远目标，准备好后点击开始",
            )
            LabStage.COVER_TEST -> Triple(
                "准备：动态交替遮盖（共 ${coverSteps.size} 步）",
                "保持约 33 厘米注视顶部常亮白点，按语音提示用手掌完全遮住指定眼睛并保持，听到提示音后再快速移开或换遮另一眼。",
                "准备好单手遮眼，点击开始动态遮盖",
            )
            LabStage.PURSUIT -> Triple(
                "准备：双眼平滑追踪（${config.pursuitDurationSec} 秒）",
                "手机距离约 25 厘米，头部保持完全不动，仅用双眼平滑跟随屏幕上移动的橙色圆点（依次水平、垂直与斜向运动）。",
                "调整至约 25 cm，头部固定，准备好后点击开始",
            )
            else -> Triple("", "", "")
        }
        _live.value = _live.value.copy(
            stage = stage,
            stageIndex = currentStageIdx + 1,
            totalStages = stages.size,
            remainingSec = 0,
            stageProgress = 0f,
            stageWaitingConfirm = stageWaiting,
            farWaitingConfirm = stage == LabStage.FAR_FIXATION && stageWaiting,
            stageBriefingTitle = briefTitle,
            stageBriefingBody = briefBody,
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

        fun analyzeEye(stage: LabStage, eye: String, obs: List<GlintObservationFrame>, lagPriorSec: Double?): CornealCodeV8Result? {
            if (obs.isEmpty()) return null
            val startSec = stageStartTimesSec[stage] ?: obs.first().hostTimeSec
            val durSec = stageDurationsSec[stage] ?: max(2.0, obs.last().hostTimeSec - startSec)
            val events = stageDisplayEvents[stage].orEmpty()
            return CornealCodeV8Analyzer.analyze(
                eye = eye,
                frames = obs,
                displayEvents = events,
                startHostTimeSec = startSec,
                durationSec = durSec,
                chipSec = chipSec,
                scheme = activeCodingScheme(stage),
                stimulusDiameterMm = config.stimulusDiameterMm,
                searchRadiusFraction = config.searchRadiusFraction,
                fps = fpsInt,
                lagPriorSec = lagPriorSec,
            )
        }

        fun highConfLag(res: CornealCodeV8Result?): Double? {
            if (res == null || !res.codeVerified) return null
            if ((res.trainCorrelation ?: 0.0) < 0.55 || (res.heldOutCorrelation ?: 0.0) < 0.45) return null
            return res.lagMs?.div(1000.0)?.takeIf { it in -0.05..0.22 }
        }

        /** 同阶段双眼共享硬件延迟先验：单眼被强环境反光拉偏延迟时，以高置信眼（或前阶段）硬件延迟重跑校正。 */
        fun analyzeStagePair(stage: LabStage, priorLagSec: Double?): Pair<CornealCodeV8Result?, CornealCodeV8Result?> {
            val lObs = stageLeftObs[stage].orEmpty()
            val rObs = stageRightObs[stage].orEmpty()
            if (lObs.isEmpty() && rObs.isEmpty()) return null to null
            var lRes = analyzeEye(stage, "LEFT", lObs, priorLagSec)
            var rRes = analyzeEye(stage, "RIGHT", rObs, priorLagSec)
            val stageLag = highConfLag(lRes) ?: highConfLag(rRes) ?: priorLagSec
            if (stageLag != null) {
                if (lRes != null && !lRes.codeVerified) {
                    val retried = analyzeEye(stage, "LEFT", lObs, stageLag)
                    if (retried != null && (retried.codeVerified || (retried.heldOutCorrelation ?: -1.0) > (lRes.heldOutCorrelation ?: -1.0))) {
                        lRes = retried
                    }
                }
                if (rRes != null && !rRes.codeVerified) {
                    val retried = analyzeEye(stage, "RIGHT", rObs, stageLag)
                    if (retried != null && (retried.codeVerified || (retried.heldOutCorrelation ?: -1.0) > (rRes.heldOutCorrelation ?: -1.0))) {
                        rRes = retried
                    }
                }
            }
            return lRes to rRes
        }

        val (nearL, nearR) = analyzeStagePair(LabStage.NEAR_FIXATION, priorLagSec = null)
        val hardwareLagPrior = listOfNotNull(highConfLag(nearL), highConfLag(nearR)).takeIf { it.isNotEmpty() }?.average()
        val (farL, farR) = analyzeStagePair(LabStage.FAR_FIXATION, priorLagSec = hardwareLagPrior)
        val (coverL, coverR) = analyzeStagePair(LabStage.COVER_TEST, priorLagSec = hardwareLagPrior)

        val leftEstimateByFrame = buildMap<Int, LabGlintFrameEstimate> {
            listOfNotNull(nearL, farL, coverL).forEach { r -> r.frames.forEach { put(it.frameIndex, it) } }
        }
        val rightEstimateByFrame = buildMap<Int, LabGlintFrameEstimate> {
            listOfNotNull(nearR, farR, coverR).forEach { r -> r.frames.forEach { put(it.frameIndex, it) } }
        }

        val intrinsics = lastIntrinsics?.takeIf { it.isValid }
        val kPrismPerRadius = StrabismusProtocol.PRISM_PER_IRIS_UNIT * 0.5
        val enrichedFrames = frameLogs.map { f ->
            val le = leftEstimateByFrame[f.index]
            val re = rightEstimateByFrame[f.index]
            val lRelX = le?.takeIf { it.source.measured }?.irisRelX?.toFloat()
            val lRelY = le?.takeIf { it.source.measured }?.irisRelY?.toFloat()
            val rRelX = re?.takeIf { it.source.measured }?.irisRelX?.toFloat()
            val rRelY = re?.takeIf { it.source.measured }?.irisRelY?.toFloat()
            val lGx = le?.measurementX ?: le?.imageX
            val lGy = le?.measurementY ?: le?.imageY
            val rGx = re?.measurementX ?: re?.imageX
            val rGy = re?.measurementY ?: re?.imageY

            // 1) 旧版线性 175.5 估算（保留作论文消融对照）
            val hdeLinear = if (f.valid && lRelX != null && rRelX != null) {
                val leftNasal = lRelX * nasalSign
                val rightNasal = rRelX * (-nasalSign)
                (leftNasal - rightNasal) * kPrismPerRadius
            } else null
            val vdeLinear = if (f.valid && lRelY != null && rRelY != null) {
                (lRelY - rRelY) * kPrismPerRadius
            } else null

            // 2) v0.9 球面角膜镜 3D 光学反演 + 虹膜连线 Roll 旋正
            val optics3d = if (
                f.valid && intrinsics != null &&
                le?.source?.measured == true && re?.source?.measured == true &&
                lGx != null && lGy != null && rGx != null && rGy != null &&
                f.leftIrisX != null && f.leftIrisY != null && f.leftIrisDiameterPx != null &&
                f.rightIrisX != null && f.rightIrisY != null && f.rightIrisDiameterPx != null
            ) {
                CornealOptics3D.solveBinocular(
                    leftIrisX = f.leftIrisX.toDouble(),
                    leftIrisY = f.leftIrisY.toDouble(),
                    leftGlintX = lGx,
                    leftGlintY = lGy,
                    leftIrisDiameterPx = f.leftIrisDiameterPx.toDouble(),
                    rightIrisX = f.rightIrisX.toDouble(),
                    rightIrisY = f.rightIrisY.toDouble(),
                    rightGlintX = rGx,
                    rightGlintY = rGy,
                    rightIrisDiameterPx = f.rightIrisDiameterPx.toDouble(),
                    intrinsics = intrinsics,
                    nasalSign = nasalSign.toDouble(),
                    farFixation = f.stage == LabStage.FAR_FIXATION,
                )
            } else null

            f.copy(
                leftGlintX = lGx?.toFloat(),
                leftGlintY = lGy?.toFloat(),
                leftGlintConfidence = le?.confidence?.toFloat(),
                leftGlintSource = le?.source ?: f.leftGlintSource,
                leftEmissionPhase = le?.emissionPhase ?: f.leftEmissionPhase,
                leftRelX = lRelX,
                leftRelY = lRelY,
                rightGlintX = rGx?.toFloat(),
                rightGlintY = rGy?.toFloat(),
                rightGlintConfidence = re?.confidence?.toFloat(),
                rightGlintSource = re?.source ?: f.rightGlintSource,
                rightEmissionPhase = re?.emissionPhase ?: f.rightEmissionPhase,
                rightRelX = rRelX,
                rightRelY = rRelY,
                hdePrism = hdeLinear,
                vdePrism = vdeLinear,
                hde3dPrism = optics3d?.hdeAsymmetryPrism,
                vde3dPrism = optics3d?.vdePrism,
                hdeVergence3dPrism = optics3d?.hdeVergencePrism,
                rollCorrectionDeg = optics3d?.rollCorrectionDeg,
            )
        }

        fun computeAlignment(stage: LabStage, lCode: CornealCodeV8Result?, rCode: CornealCodeV8Result?): LabAlignmentMetrics? {
            val stageRows = enrichedFrames.filter { it.stage == stage }
            if (stageRows.isEmpty()) return null
            val settleMs = if (stage == LabStage.FAR_FIXATION) StrabismusProtocol.FAR_SETTLE_MS else 0L
            val firstMs = stageRows.first().timestampMs
            val allMeasured = stageRows.filter { it.timestampMs >= firstMs + settleMs && it.hdePrism != null && it.vdePrism != null }
            val observedBinocular = allMeasured.filter {
                it.leftGlintSource == LabGlintSource.OBSERVED && it.rightGlintSource == LabGlintSource.OBSERVED
            }
            val codeAnyVerified = (lCode?.codeVerified == true) || (rCode?.codeVerified == true)
            // 可信度门控：当某阶段双眼均未通过光码验证且几乎没有高置信 OBSERVED 帧（全是低置信 WEAK 噪点）时，
            // 拒绝输出荒谬的极值偏斜（如 223Δ），显式返回空值提示复测或核查视频。
            if (!codeAnyVerified && observedBinocular.size < 6) {
                return LabAlignmentMetrics(
                    stage = stage,
                    stableOnOnly = false,
                    validBinocularFrames = 0,
                    totalStageFrames = stageRows.size,
                    leftCodeVerified = lCode?.codeVerified == true,
                    rightCodeVerified = rCode?.codeVerified == true,
                )
            }
            // 锚点空间一致性过滤：若已有光码锚点，剔除偏离锚点 > 0.20R 的短时跳点帧（防止偶发环境反光拖偏中位数与 SD）
            val lRefX = lCode?.referenceRelX
            val lRefY = lCode?.referenceRelY
            val rRefX = rCode?.referenceRelX
            val rRefY = rCode?.referenceRelY
            fun withinAnchor(f: LabFrameLog): Boolean {
                val lOk = if (lRefX != null && lRefY != null && f.leftRelX != null && f.leftRelY != null) {
                    hypot(f.leftRelX - lRefX, f.leftRelY - lRefY) <= 0.20
                } else true
                val rOk = if (rRefX != null && rRefY != null && f.rightRelX != null && f.rightRelY != null) {
                    hypot(f.rightRelX - rRefX, f.rightRelY - rRefY) <= 0.20
                } else true
                return lOk && rOk
            }
            // 优先仅保留稳定 ON 且 OBSERVED 的双目帧，剔除 TRANSITION 过渡帧与弱观测噪点
            val stableOnObserved = observedBinocular.filter {
                it.leftEmissionPhase == LabEmissionPhase.ON && it.rightEmissionPhase == LabEmissionPhase.ON
            }
            val stableOnInlier = stableOnObserved.filter(::withinAnchor)
            val observedInlier = observedBinocular.filter(::withinAnchor)
            val stableOnOnly = stableOnInlier.size >= 12 || stableOnObserved.size >= 12
            val usable = when {
                stableOnInlier.size >= 12 -> stableOnInlier
                stableOnObserved.size >= 12 -> stableOnObserved
                observedInlier.size >= 12 -> observedInlier
                observedBinocular.size >= 12 -> observedBinocular
                else -> allMeasured
            }

            fun robustTrim(values: List<Double>): List<Double> {
                if (values.size < 8) return values
                val med = median(values) ?: return values
                val absDevs = values.map { abs(it - med) }
                val madSigma = (median(absDevs) ?: 0.0) * 1.4826
                if (madSigma < 0.5) return values
                val limit = max(4.0, 2.5 * madSigma)
                val kept = values.filter { abs(it - med) <= limit }
                return if (kept.size >= 6) kept else values
            }

            val hVals = robustTrim(usable.map { it.hdePrism!! })
            val vVals = robustTrim(usable.map { it.vdePrism!! })
            // 主 3D 水平指标优先采用对共轭偏转与人脸偏移免疫的 3D 聚散分量（Vergence 3D），并保留不对称与线性对照
            val verg3dVals = robustTrim(usable.mapNotNull { it.hdeVergence3dPrism })
            val asym3dVals = robustTrim(usable.mapNotNull { it.hde3dPrism })
            val v3dVals = robustTrim(usable.mapNotNull { it.vde3dPrism })

            val primaryH3d = verg3dVals.ifEmpty { asym3dVals }
            val effN = max(1.0, usable.size / max(1.0, medFps * 0.2))
            val hSd = stdDev(hVals)
            val vSd = stdDev(vVals)
            val h3dSd = stdDev(primaryH3d)
            val v3dSd = stdDev(v3dVals)
            return LabAlignmentMetrics(
                stage = stage,
                hdePrism = median(hVals),
                vdePrism = median(vVals),
                hdeSdPrism = hSd,
                vdeSdPrism = vSd,
                hdeCi95HalfPrism = hSd?.let { 1.96 * it / sqrt(effN) },
                vdeCi95HalfPrism = vSd?.let { 1.96 * it / sqrt(effN) },
                hde3dPrism = median(primaryH3d),
                vde3dPrism = median(v3dVals),
                hde3dSdPrism = h3dSd,
                vde3dSdPrism = v3dSd,
                hde3dCi95HalfPrism = h3dSd?.let { 1.96 * it / sqrt(effN) },
                vde3dCi95HalfPrism = v3dSd?.let { 1.96 * it / sqrt(effN) },
                hdeVergence3dPrism = median(verg3dVals),
                stableOnOnly = stableOnOnly,
                validBinocularFrames = usable.size,
                totalStageFrames = stageRows.size,
                leftCodeVerified = lCode?.codeVerified == true,
                rightCodeVerified = rCode?.codeVerified == true,
            )
        }

        val nearMetrics = computeAlignment(LabStage.NEAR_FIXATION, nearL, nearR)
        val farMetrics = computeAlignment(LabStage.FAR_FIXATION, farL, farR)
        val coverSummary = computeCoverKinematics(
            enrichedFrames = enrichedFrames,
            kPrismPerRadius = kPrismPerRadius,
            codeConfirmed = (nearL?.codeVerified == true && nearR?.codeVerified == true),
        )
        val pursuitMetrics = computePursuit()

        val leftScatter = buildScatter(enrichedFrames, isLeft = true)
        val rightScatter = buildScatter(enrichedFrames, isLeft = false)

        // 综合分级（对齐 ScreeningAssessment 严格统计门控）：
        // 仅当「测量稳定且越线」或「95% 置信区间下界仍越线」才判显著异常；
        // 越线但抖动大导致误差区间覆盖判定线时，判为 INCONCLUSIVE（证据不足/波动大），绝不误报高度斜视。
        val nearHVal = nearMetrics?.hde3dPrism ?: nearMetrics?.hdePrism
        val nearVVal = nearMetrics?.vde3dPrism ?: nearMetrics?.vdePrism
        val nearH = abs(nearHVal ?: 0.0)
        val nearV = abs(nearVVal ?: 0.0)
        val nearHSd = nearMetrics?.hde3dSdPrism ?: nearMetrics?.hdeSdPrism ?: Double.MAX_VALUE
        val nearVSd = nearMetrics?.vde3dSdPrism ?: nearMetrics?.vdeSdPrism ?: Double.MAX_VALUE
        val nearHCi = nearMetrics?.hde3dCi95HalfPrism ?: nearMetrics?.hdeCi95HalfPrism ?: 0.0
        val nearVCi = nearMetrics?.vde3dCi95HalfPrism ?: nearMetrics?.vdeCi95HalfPrism ?: 0.0
        val nearReady = (nearMetrics?.validBinocularFrames ?: 0) >= 12 && nearHVal != null && nearVVal != null
        val nearVerified = nearMetrics?.let { it.leftCodeVerified && it.rightCodeVerified } == true

        val nearHStable = nearHSd <= StrabismusProtocol.STABLE_SD_H_PRISM
        val nearVStable = nearVSd <= 3.0 // 3D 球面垂直灵敏度略高于线性，稳定 SD 上限取 3.0Δ
        val nearHAbnormal = nearReady && (
            (nearHStable && nearH >= StrabismusProtocol.HIGH_H_PRISM) ||
                (nearH - nearHCi >= StrabismusProtocol.HIGH_H_PRISM)
            )
        // 无头托前置相机下 MediaPipe 上下眼睑微遮可能引入 ~2–3px 垂直微差，垂直显斜门槛需满足 ≥ 6.0Δ 且统计显著
        val nearVThreshold = 6.0
        val nearVAbnormal = nearReady && (
            (nearVStable && nearV >= nearVThreshold) ||
                (nearV - nearVCi >= nearVThreshold)
            )
        val nearAbnormal = nearHAbnormal || nearVAbnormal
        val nearUncertain = nearReady && !nearAbnormal && (nearH >= StrabismusProtocol.HIGH_H_PRISM || nearV >= nearVThreshold)

        val coverMedian = coverSummary.overallMedianPrism
        val coverNoise = coverSummary.noiseFloorPrism
        val coverAbnormal = coverMedian != null && coverMedian >= StrabismusProtocol.REFIX_PRISM &&
            (coverNoise == null || coverMedian > coverNoise)
        val bsiAbnormal = (pursuitMetrics?.bsi ?: 1.0) < StrabismusProtocol.BSI_ABNORMAL

        val riskGrade = when {
            nearAbnormal && (nearVerified || !config.codingScheme.usesTemporalCode) -> LabRiskGrade.HIGH_RISK
            nearAbnormal || coverAbnormal || bsiAbnormal -> LabRiskGrade.SUSPECT
            nearUncertain -> LabRiskGrade.INCONCLUSIVE
            nearReady && (nearVerified || !config.codingScheme.usesTemporalCode) -> LabRiskGrade.NORMAL
            else -> LabRiskGrade.INCONCLUSIVE
        }

        val triggerDesc = when {
            nearHAbnormal && nearVAbnormal -> "看近 3D HDE ${fmt1(nearH)}Δ / VDE ${fmt1(nearV)}Δ"
            nearHAbnormal -> "看近 3D HDE ${fmt1(nearH)}Δ (±${fmt1(nearHCi)})"
            nearVAbnormal -> "看近 3D VDE ${fmt1(nearV)}Δ (±${fmt1(nearVCi)})"
            nearUncertain -> "看近 3D VDE ${fmt1(nearV)}Δ ± ${fmt1(nearVCi)}Δ 波动较大"
            else -> "看近 3D HDE ${fmt1(nearH)}Δ"
        }

        val codeScore = listOfNotNull(nearL, nearR, farL, farR).let { list ->
            if (list.isEmpty()) 0.0 else list.count { it.codeVerified }.toDouble() / list.size
        }
        val validRatio = if (frameCounter > 0) validFrameCounter.toDouble() / frameCounter else 0.0
        val confidenceScore = (0.45 * validRatio + 0.40 * codeScore + 0.15 * (if (nearReady) 1.0 else 0.0)).coerceIn(0.0, 1.0)

        val notes = buildList {
            add("搜索半径：${fmt2(config.searchRadiusFraction.toDouble())} R；光码方案：${config.codingScheme.label}（码元 ${config.chipDurationMs} ms，遮盖阶段常亮）")
            nearMetrics?.let { nm ->
                val ver = if (nearVerified) "双眼光码通过" else "光码未全过"
                val onTag = if (nm.stableOnOnly) "稳定ON帧" else "全测帧"
                val h3d = nm.hde3dPrism?.let { "3D HDE ${fmt1(it)}Δ / VDE ${fmt1(nm.vde3dPrism ?: 0.0)}Δ" }
                val hLin = nm.hdePrism?.let { "线性 ${fmt1(it)}Δ" }
                add("看近眼位：${listOfNotNull(h3d, hLin).joinToString("（对照 ") + if (h3d != null && hLin != null) "）" else ""}，有效 ${nm.validBinocularFrames} 帧（$onTag · $ver）")
            }
            farMetrics?.let { fm ->
                val h3d = fm.hde3dPrism?.let { "3D HDE ${fmt1(it)}Δ / VDE ${fmt1(fm.vde3dPrism ?: 0.0)}Δ" }
                val hLin = fm.hdePrism?.let { "线性 ${fmt1(it)}Δ" }
                add("看远眼位：${listOfNotNull(h3d, hLin).joinToString(" / ")}，有效 ${fm.validBinocularFrames} 帧")
            }
            if (coverSummary.events.isNotEmpty()) {
                val tropiaTxt = coverSummary.tropiaMedianPrism?.let { "显斜对侧 ${fmt1(it)}Δ" } ?: "显斜 —"
                val phoriaTxt = coverSummary.phoriaMedianPrism?.let { "隐斜/IXT 同侧 ${fmt1(it)}Δ" } ?: "隐斜 —"
                add("遮盖动力学：${coverSummary.classification}（$tropiaTxt · $phoriaTxt · 噪声底 ${coverNoise?.let(::fmt1) ?: "—"}Δ）")
            }
            pursuitMetrics?.bsi?.let {
                add("平滑追踪 BSI（400ms 窗）：${fmt2(it)}（r_h ${pursuitMetrics.rHorizontal?.let(::fmt2) ?: "—"}，r_v ${pursuitMetrics.rVertical?.let(::fmt2) ?: "—"}）")
            }
        }

        return LabSessionReport(
            sessionId = sessionId,
            createdAtMs = sessionStartMs,
            platform = platformName,
            deviceModel = deviceModel,
            sourceWidth = lastSourceW,
            sourceHeight = lastSourceH,
            cameraIntrinsics = intrinsics,
            medianFps = medFps,
            durationSec = max(0.0, (endMs - sessionStartMs) / 1000.0),
            config = config,
            riskGrade = riskGrade,
            confidenceScore = confidenceScore,
            headline = when (riskGrade) {
                LabRiskGrade.HIGH_RISK -> "检测到持续且显著的眼位偏斜（$triggerDesc）"
                LabRiskGrade.SUSPECT -> "存在可疑眼位或遮盖再注视信号（${coverSummary.classification ?: triggerDesc}）"
                LabRiskGrade.NORMAL -> "本次科研采集未见显著眼位偏斜（看近 3D HDE ${fmt1(nearH)}Δ）"
                LabRiskGrade.INCONCLUSIVE -> if (nearUncertain) "测得眼位接近门槛但抖动较大（$triggerDesc），建议复测" else "有效光码或双眼帧不足，请在 Debug 页核查拒判原因"
            },
            summaryNotes = notes,
            nearMetrics = nearMetrics,
            farMetrics = farMetrics,
            coverRefixations = coverSummary.events,
            coverMedianPrism = coverMedian,
            coverTropiaMedianPrism = coverSummary.tropiaMedianPrism,
            coverPhoriaMedianPrism = coverSummary.phoriaMedianPrism,
            coverClassification = coverSummary.classification,
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

    private data class CoverKinematicsSummary(
        val events: List<LabCoverRefixation>,
        val overallMedianPrism: Double?,
        val tropiaMedianPrism: Double?,
        val phoriaMedianPrism: Double?,
        val classification: String?,
        val noiseFloorPrism: Double?,
    )

    /**
     * 瓶颈 4 核心实现：
     * 1. 仅用看近稳定 ON + OBSERVED 帧计算 500ms 稳态窗位移 95% 分位噪声底（剔除 TRANSITION 污染）；
     * 2. 对每次 COVER（遮盖）和 UNCOVER（去遮盖）动作，沿逐帧 visibilityContrast 缓存向前回溯 1200ms 锁定精确拐点；
     * 3. COVER 动作观测对侧未遮眼（显斜 Tropia 标志），UNCOVER 动作观测刚露出同侧眼（隐斜 Phoria / IXT 标志）；
     * 4. 融合稳定角膜映光与连续归一化虹膜轨迹，提取稳态前后净幅度、峰值速度、潜伏期、90% 复位耗时与毫秒级轨迹曲线。
     */
    private fun computeCoverKinematics(
        enrichedFrames: List<LabFrameLog>,
        kPrismPerRadius: Double,
        codeConfirmed: Boolean,
    ): CoverKinematicsSummary {
        val coverFrames = enrichedFrames.filter { it.stage == LabStage.COVER_TEST }
        val nearAll = enrichedFrames.filter { it.stage == LabStage.NEAR_FIXATION && it.valid }
        val nearStable = nearAll.filter {
            it.leftEmissionPhase == LabEmissionPhase.ON && it.leftGlintSource == LabGlintSource.OBSERVED &&
                it.rightEmissionPhase == LabEmissionPhase.ON && it.rightGlintSource == LabGlintSource.OBSERVED
        }.ifEmpty { nearAll }

        // 使用「前 100ms 稳态中位 → 500ms 后 150ms 稳态中位」的差值算噪声底，与去遮盖口径一致
        val noiseSamples = mutableListOf<Double>()
        for (i in nearStable.indices) {
            val t0 = nearStable[i].timestampMs
            val early = nearStable.filter { it.timestampMs in t0..(t0 + 120L) && it.leftRelX != null && it.leftRelY != null }
            val late = nearStable.filter { it.timestampMs in (t0 + 450L)..(t0 + 620L) && it.leftRelX != null && it.leftRelY != null }
            if (early.size >= 2 && late.size >= 2) {
                val dx = median(late.map { it.leftRelX!!.toDouble() })!! - median(early.map { it.leftRelX!!.toDouble() })!!
                val dy = median(late.map { it.leftRelY!!.toDouble() })!! - median(early.map { it.leftRelY!!.toDouble() })!!
                noiseSamples += hypot(dx, dy) * kPrismPerRadius
            }
        }
        val noiseFloor = noiseSamples.sorted().takeIf { it.size >= 5 }?.let { it[(it.size * 0.95).toInt().coerceAtMost(it.lastIndex)] }

        val nearLeftBaseX = median(nearStable.mapNotNull { it.leftRelX?.toDouble() })
        val nearRightBaseX = median(nearStable.mapNotNull { it.rightRelX?.toDouble() })
        val baseLeftVis = median(nearAll.mapNotNull { it.leftVisibilityContrast?.toDouble() })
            ?: median(coverFrames.mapNotNull { it.leftVisibilityContrast?.toDouble()?.takeIf { v -> v >= 20.0 } }) ?: 50.0
        val baseRightVis = median(nearAll.mapNotNull { it.rightVisibilityContrast?.toDouble() })
            ?: median(coverFrames.mapNotNull { it.rightVisibilityContrast?.toDouble()?.takeIf { v -> v >= 20.0 } }) ?: 50.0

        fun visOf(f: LabFrameLog, eye: VisionEye): Double? =
            if (eye == VisionEye.LEFT) f.leftVisibilityContrast?.toDouble() else f.rightVisibilityContrast?.toDouble()

        fun baseVisOf(eye: VisionEye): Double = if (eye == VisionEye.LEFT) baseLeftVis else baseRightVis

        /** 向前回溯精确去遮盖拐点（对比度从遮盖低谷首次跨过 35% 阶跃并持续上升的毫秒首帧）。 */
        fun refineUncoverOnsetMs(eye: VisionEye, markTimeMs: Long, confirmedMs: Long): Long {
            val searchEnd = max(markTimeMs, confirmedMs)
            val searchStart = markTimeMs - 1_200L
            val seg = coverFrames.filter { it.timestampMs in searchStart..searchEnd }
            if (seg.size < 4) return markTimeMs
            val visVals = seg.mapNotNull { visOf(it, eye) }
            if (visVals.isEmpty()) return markTimeMs
            val vMin = visVals.min()
            val vHigh = max(baseVisOf(eye), visVals.max())
            if (vHigh - vMin < 10.0) return markTimeMs
            val thresh = vMin + (vHigh - vMin) * 0.35
            // 从确认点向前回扫，找到最后一个仍处于低谷（< thresh）的帧，其紧邻下一帧即为精确去遮盖首帧
            var lastLowIdx = -1
            for (i in seg.indices.reversed()) {
                val v = visOf(seg[i], eye) ?: continue
                if (v < thresh) {
                    lastLowIdx = i
                    break
                }
            }
            return if (lastLowIdx in 0 until seg.lastIndex) seg[lastLowIdx + 1].timestampMs else markTimeMs
        }

        /** 向前回溯精确遮盖生效拐点（被遮眼对比度首次跌破 65% 基线的下降沿首帧）。 */
        fun refineCoverOnsetMs(coveredEye: VisionEye, markTimeMs: Long, confirmedMs: Long): Long {
            val searchEnd = max(markTimeMs, confirmedMs)
            val searchStart = markTimeMs - 1_200L
            val seg = coverFrames.filter { it.timestampMs in searchStart..searchEnd }
            if (seg.size < 4) return markTimeMs
            val vBase = baseVisOf(coveredEye)
            val thresh = vBase * 0.65
            var lastHighIdx = -1
            for (i in seg.indices.reversed()) {
                val v = visOf(seg[i], coveredEye) ?: continue
                if (v >= thresh) {
                    lastHighIdx = i
                    break
                }
            }
            return if (lastHighIdx in 0 until seg.lastIndex) seg[lastHighIdx + 1].timestampMs else markTimeMs
        }

        data class RawSample(val timeMs: Long, val x: Double, val y: Double, val fromGlint: Boolean)

        fun analyzeEyeWindow(
            mark: RecordedCoverMark,
            observedEye: VisionEye,
            eventType: String,
            onsetMs: Long,
        ): LabCoverRefixation? {
            val window = coverFrames.filter { it.timestampMs in (onsetMs - 120L)..(onsetMs + 820L) }
            if (window.size < 5) return null

            // 提取稳定角膜映光（排除 OFF 与极低置信噪点），并与归一化虹膜轨迹做偏移对齐融合
            val glintPoints = mutableListOf<Triple<Long, Double, Double>>()
            val irisPoints = mutableListOf<Triple<Long, Double, Double>>()
            // 归一化眼裂坐标到虹膜半径单位的经验换算（眼裂宽约 2.6× 虹膜直径 ≈ 5.2× 虹膜半径）
            val normToRelScale = 5.2
            for (f in window) {
                val gx = if (observedEye == VisionEye.LEFT) f.leftRelX?.toDouble() else f.rightRelX?.toDouble()
                val gy = if (observedEye == VisionEye.LEFT) f.leftRelY?.toDouble() else f.rightRelY?.toDouble()
                val src = if (observedEye == VisionEye.LEFT) f.leftGlintSource else f.rightGlintSource
                val ph = if (observedEye == VisionEye.LEFT) f.leftEmissionPhase else f.rightEmissionPhase
                val conf = (if (observedEye == VisionEye.LEFT) f.leftGlintConfidence else f.rightGlintConfidence)?.toDouble() ?: 0.0
                if (gx != null && gy != null && src.measured && ph != LabEmissionPhase.OFF &&
                    (src == LabGlintSource.OBSERVED || (ph != LabEmissionPhase.TRANSITION && conf >= 0.35))
                ) {
                    glintPoints += Triple(f.timestampMs, gx, gy)
                }
                val nx = if (observedEye == VisionEye.LEFT) f.leftNormX?.toDouble() else f.rightNormX?.toDouble()
                val ny = if (observedEye == VisionEye.LEFT) f.leftNormY?.toDouble() else f.rightNormY?.toDouble()
                val ear = (if (observedEye == VisionEye.LEFT) f.leftEar else f.rightEar)?.toDouble() ?: 1.0
                val vis = visOf(f, observedEye) ?: 0.0
                if (nx != null && ny != null && ear >= 0.14 && vis >= 15.0) {
                    // 虹膜向右移相当于映光相对虹膜向左移，取负号保持与 glintRel 方向一致
                    irisPoints += Triple(f.timestampMs, -nx * normToRelScale, -ny * normToRelScale)
                }
            }

            // 用重合帧对齐虹膜轨迹与角膜映光的零点偏置
            val glintByT = glintPoints.associateBy { it.first }
            val offsetXs = mutableListOf<Double>()
            val offsetYs = mutableListOf<Double>()
            for ((t, ix, iy) in irisPoints) {
                val g = glintByT[t] ?: continue
                offsetXs += g.second - ix
                offsetYs += g.third - iy
            }
            val offX = median(offsetXs) ?: 0.0
            val offY = median(offsetYs) ?: 0.0

            val irisByT = irisPoints.associateBy { it.first }
            val merged = window.mapNotNull { f ->
                val t = f.timestampMs
                val g = glintByT[t]
                if (g != null) {
                    RawSample(t, g.second, g.third, fromGlint = true)
                } else {
                    val ir = irisByT[t]
                    if (ir != null && (glintPoints.isEmpty() || offsetXs.size >= 2)) {
                        RawSample(t, ir.second + offX, ir.third + offY, fromGlint = false)
                    } else null
                }
            }
            if (merged.size < 4) return null

            // 3 帧滑动中值滤波消除单帧跳点
            val smoothed = merged.indices.map { i ->
                val lo = max(0, i - 1)
                val hi = min(merged.lastIndex, i + 1)
                val sx = median((lo..hi).map { merged[it].x })!!
                val sy = median((lo..hi).map { merged[it].y })!!
                RawSample(merged[i].timeMs, sx, sy, merged[i].fromGlint)
            }

            val postOnset = smoothed.filter { it.timeMs >= onsetMs }
            if (postOnset.size < 3) return null

            // 起始参考点：COVER 动作取遮盖前 [-120, +40]ms 稳态中位；UNCOVER 动作取露出后前 100ms 稳态中位
            val earlyWindow = if (eventType == "COVER_CONTRALATERAL") {
                smoothed.filter { it.timeMs in (onsetMs - 120L)..(onsetMs + 40L) }.ifEmpty { postOnset.take(3) }
            } else {
                val tFirst = postOnset.first().timeMs
                postOnset.filter { it.timeMs <= tFirst + 100L }.ifEmpty { postOnset.take(2) }
            }
            val lateWindow = postOnset.filter { it.timeMs >= onsetMs + 420L }.ifEmpty { postOnset.takeLast(min(4, postOnset.size)) }
            if (earlyWindow.isEmpty() || lateWindow.isEmpty()) return null

            val x0 = median(earlyWindow.map { it.x })!!
            val y0 = median(earlyWindow.map { it.y })!!
            val x1 = median(lateWindow.map { it.x })!!
            val y1 = median(lateWindow.map { it.y })!!
            val ampPrism = hypot(x1 - x0, y1 - y0) * kPrismPerRadius

            // 构建毫秒级位移与瞬时速度曲线
            val dispSeries = smoothed.map { s ->
                val d = hypot(s.x - x0, s.y - y0) * kPrismPerRadius
                Triple(s.timeMs - onsetMs, d, s.fromGlint)
            }
            val trace = mutableListOf<LabRefixationTracePoint>()
            var peakVelDeg = 0.0
            for (i in dispSeries.indices) {
                val (dtMs, dPrism, fromGlint) = dispSeries[i]
                val vel = if (i > 0) {
                    val prev = dispSeries[i - 1]
                    val dtSec = (dtMs - prev.first) / 1000.0
                    if (dtSec in 0.015..0.15) abs(dPrism - prev.second) * StrabismusProtocol.DEG_PER_PRISM / dtSec else 0.0
                } else 0.0
                if (dtMs in 0L..700L && vel > peakVelDeg) peakVelDeg = vel
                trace += LabRefixationTracePoint(
                    dtMs = dtMs,
                    displacementPrism = dPrism,
                    velocityDegPerSec = vel,
                    fromGlint = fromGlint,
                )
            }

            val startThresh = max(2.0, ampPrism * 0.15)
            val endThresh = ampPrism * 0.90
            val latencyMs = if (ampPrism >= 3.0) {
                trace.firstOrNull { it.dtMs >= 0L && it.displacementPrism >= startThresh }?.dtMs
            } else null
            val recoveryMs = if (ampPrism >= 3.0 && latencyMs != null) {
                trace.firstOrNull { it.dtMs >= latencyMs && it.displacementPrism >= endThresh }?.let { it.dtMs - latencyMs }
            } else null

            val baseX = if (observedEye == VisionEye.LEFT) nearLeftBaseX else nearRightBaseX
            val residual = baseX?.let { abs(x1 - it) * kPrismPerRadius } ?: 0.0
            val shiftMs = mark.eventTimeMs - onsetMs

            return LabCoverRefixation(
                index = mark.index,
                eye = observedEye.name,
                label = mark.label,
                source = mark.source,
                eventType = eventType,
                uncoveredAtSec = max(0.0, (onsetMs - sessionStartMs) / 1000.0),
                onsetShiftMs = shiftMs,
                amplitudePrism = ampPrism,
                speedDegPerSec = peakVelDeg,
                latencyMs = latencyMs,
                recoveryDurationMs = recoveryMs,
                residualPrism = residual,
                codeConfirmed = codeConfirmed,
                trace = trace,
            )
        }

        val events = mutableListOf<LabCoverRefixation>()
        for (mark in coverMarks) {
            // 1) 遮盖瞬间 → 观察对侧未遮眼（显斜 Tropia 关键信号）
            val coveredEye = mark.covered
            if (coveredEye != null) {
                val contralateral = if (coveredEye == VisionEye.LEFT) VisionEye.RIGHT else VisionEye.LEFT
                val coverOnset = refineCoverOnsetMs(coveredEye, mark.eventTimeMs, mark.confirmedAtMs)
                analyzeEyeWindow(mark, contralateral, "COVER_CONTRALATERAL", coverOnset)?.let { events += it }
            }
            // 2) 去遮盖瞬间 → 观察被解除遮盖的同侧眼（隐斜 Phoria / IXT 关键信号）
            val uncoveredEye = mark.uncovered
            if (uncoveredEye != null) {
                val uncoverOnset = refineUncoverOnsetMs(uncoveredEye, mark.eventTimeMs, mark.confirmedAtMs)
                analyzeEyeWindow(mark, uncoveredEye, "UNCOVER_IPSILATERAL", uncoverOnset)?.let { events += it }
            }
        }

        val tropiaAmps = events.filter { it.eventType == "COVER_CONTRALATERAL" }.map { it.amplitudePrism }
        val phoriaAmps = events.filter { it.eventType == "UNCOVER_IPSILATERAL" }.map { it.amplitudePrism }
        val tropiaMed = median(tropiaAmps)
        val phoriaMed = median(phoriaAmps)
        val overallMed = phoriaMed ?: tropiaMed
        val sigThresh = max(StrabismusProtocol.REFIX_PRISM, (noiseFloor ?: 0.0))
        val classification = when {
            events.isEmpty() -> null
            tropiaMed != null && tropiaMed >= sigThresh -> "显斜倾向（Tropia：遮盖瞬间对侧眼明显复位）"
            phoriaMed != null && phoriaMed >= sigThresh -> "隐斜 / 间歇性斜视倾向（Phoria / IXT：去遮盖同侧眼复位）"
            else -> "未见显著遮盖复位"
        }

        return CoverKinematicsSummary(
            events = events,
            overallMedianPrism = overallMed,
            tropiaMedianPrism = tropiaMed,
            phoriaMedianPrism = phoriaMed,
            classification = classification,
            noiseFloorPrism = noiseFloor,
        )
    }

    /**
     * 平滑追踪 BSI：改用 400 ms 滑动窗口（350–500 ms 配对）计算速度相关，
     * 并先做 3 帧中值滤波抑制 MediaPipe 逐帧关键点抖动（修复 v0.8 1 帧 33ms 差分导致 r_h 崩溃的问题）。
     */
    private fun computePursuit(): LabPursuitMetrics? {
        if (pursuitSamples.size < 12) return null

        fun smoothSegment(rows: List<PursuitSample>): List<PursuitSample> {
            if (rows.size < 3) return rows
            return rows.indices.map { i ->
                val lo = max(0, i - 1)
                val hi = min(rows.lastIndex, i + 1)
                rows[i].copy(
                    leftX = median((lo..hi).map { rows[it].leftX })!!,
                    leftY = median((lo..hi).map { rows[it].leftY })!!,
                    rightX = median((lo..hi).map { rows[it].rightX })!!,
                    rightY = median((lo..hi).map { rows[it].rightY })!!,
                )
            }
        }

        fun velCorr400ms(segments: Set<Int>, axisX: Boolean): Double? {
            val lv = mutableListOf<Double>()
            val rv = mutableListOf<Double>()
            for (seg in segments) {
                val frames = smoothSegment(pursuitSamples.filter { it.segment == seg })
                var start = 0
                for (end in frames.indices) {
                    if (end > 0 && frames[end].timeMs - frames[end - 1].timeMs !in 1L..200L) start = end
                    while (start < end && frames[end].timeMs - frames[start].timeMs > 500L) start++
                    val dtMs = frames[end].timeMs - frames[start].timeMs
                    if (dtMs in 350L..500L) {
                        val dtSec = dtMs / 1000.0
                        lv += (if (axisX) frames[end].leftX - frames[start].leftX else frames[end].leftY - frames[start].leftY) / dtSec
                        rv += (if (axisX) frames[end].rightX - frames[start].rightX else frames[end].rightY - frames[start].rightY) / dtSec
                    }
                }
            }
            if (lv.size < 10) return null
            return CornealCodeV8Analyzer.pearson(lv, rv)
        }

        val rH = velCorr400ms(setOf(0, 2), axisX = true)
        val rV = velCorr400ms(setOf(1, 2), axisX = false)
        val horiz = pursuitSamples.filter { it.segment == 0 || it.segment == 2 }
        val diffs = horiz.map { it.leftX - it.rightX }
        val diffMean = if (diffs.isNotEmpty()) diffs.average() else 0.0
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
