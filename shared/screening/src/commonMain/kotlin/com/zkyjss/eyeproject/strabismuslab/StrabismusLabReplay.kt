package com.zkyjss.eyeproject.strabismuslab

import com.zkyjss.eyeproject.core.model.Point2f
import com.zkyjss.eyeproject.feature.services.StrabismusProtocol
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 离线复算覆盖参数：用于在不重新采集的前提下，对同一归档数据包执行受控变量配对消融。
 * - 固定候选点比较 `codingScheme`（例如 `M_SEQUENCE` vs `STEADY`）；
 * - 固定候选点裁剪比较 `searchRadiusFraction`（例如 `0.80f` vs `0.60f`）；
 * - 固定映光坐标比较线性换算 vs 3D 球面反演及离轴参数 `(lightOffsetXMm, lightOffsetYMm)`。
 */
data class LabReplayOptions(
    val codingSchemeOverride: LabCodingScheme? = null,
    val searchRadiusOverride: Float? = null,
    val intrinsicsOverride: LabCameraIntrinsics? = null,
    val lightOffsetXMmOverride: Double? = null,
    val lightOffsetYMmOverride: Double? = null,
    /** 若 true，直接使用归档中已锁定的最终映光坐标，仅重算线性与 3D 球面反演（隔离光学解调差异）。 */
    val reuseArchivedGlints: Boolean = false,
)

data class LabStageReplayResult(
    val stage: LabStage,
    val leftCode: CornealCodeV8Result?,
    val rightCode: CornealCodeV8Result?,
    val metrics: LabAlignmentMetrics?,
    val frames: List<LabFrameLog>,
)

data class LabSessionReplayResult(
    val sessionId: String,
    val options: LabReplayOptions,
    val near: LabStageReplayResult?,
    val far: LabStageReplayResult?,
    val cover: LabStageReplayResult?,
)

/**
 * 科研归档包离线重放与受控消融引擎。
 * 仅依赖归档 `LabSessionReport`（含逐帧完整候选列表、`displayEvents` 与 `auditMeta`），
 * 无需访问相机或原始视频即可精确复现并重跑主实验对照。
 */
object StrabismusLabReplay {

    fun replaySession(
        report: LabSessionReport,
        options: LabReplayOptions = LabReplayOptions(),
    ): LabSessionReplayResult {
        val near = replayStageInternal(report, LabStage.NEAR_FIXATION, options, priorLagSec = null, leftAnchor = null, rightAnchor = null)
        val hardwareLagPrior = listOfNotNull(highConfLag(near?.leftCode), highConfLag(near?.rightCode))
            .takeIf { it.isNotEmpty() }
            ?.average()
        val far = replayStageInternal(report, LabStage.FAR_FIXATION, options, priorLagSec = hardwareLagPrior, leftAnchor = null, rightAnchor = null)
        val cover = replayStageInternal(
            report = report,
            stage = LabStage.COVER_TEST,
            options = options,
            priorLagSec = hardwareLagPrior,
            leftAnchor = verifiedAnchor(near?.leftCode),
            rightAnchor = verifiedAnchor(near?.rightCode),
        )
        return LabSessionReplayResult(
            sessionId = report.sessionId,
            options = options,
            near = near,
            far = far,
            cover = cover,
        )
    }

    fun replayStage(
        report: LabSessionReport,
        stage: LabStage,
        options: LabReplayOptions = LabReplayOptions(),
        priorLagSec: Double? = null,
    ): LabStageReplayResult? = replayStageInternal(report, stage, options, priorLagSec, leftAnchor = null, rightAnchor = null)

    private fun replayStageInternal(
        report: LabSessionReport,
        stage: LabStage,
        options: LabReplayOptions,
        priorLagSec: Double?,
        leftAnchor: Point2f?,
        rightAnchor: Point2f?,
    ): LabStageReplayResult? {
        val stageRows = report.frames.filter { it.stage == stage }
        if (stageRows.isEmpty()) return null

        val medFps = report.medianFps.takeIf { it.isFinite() && it > 1.0 } ?: 30.0
        val fpsInt = medFps.toInt().coerceIn(15, 60)
        val chipSec = report.config.chipDurationMs / 1000.0
        val effectiveStimulusMm = min(report.config.stimulusDiameterMm, 140.0 / 6.3)
        val radiusFraction = options.searchRadiusOverride ?: report.config.searchRadiusFraction
        val scheme = options.codingSchemeOverride
            ?: if (stage.forcesSteadyStimulus) LabCodingScheme.STEADY else report.config.codingScheme

        val baseIntrinsics = options.intrinsicsOverride ?: report.cameraIntrinsics
        val intrinsics = baseIntrinsics?.copy(
            lightOffsetXMm = options.lightOffsetXMmOverride ?: baseIntrinsics.lightOffsetXMm,
            lightOffsetYMm = options.lightOffsetYMmOverride ?: baseIntrinsics.lightOffsetYMm,
        )?.takeIf { it.isValid }

        val nasalSign = inferNasalSign(report.frames)
        val kPrismPerRadius = StrabismusProtocol.PRISM_PER_IRIS_UNIT * 0.5

        val stageEvents = report.displayEvents
            .filter { it.stage == stage }
            .map { it.toDisplayEvent() }
        val startSec = report.auditMeta.stageStartTimesSec[stage.name]
            ?: stageEvents.firstOrNull()?.hostTimeSec
            ?: (stageRows.first().timestampMs / 1000.0)
        val durSec = report.auditMeta.stageDurationsSec[stage.name]
            ?: max(0.5, stageRows.last().timestampMs / 1000.0 - startSec)

        var leftRes: CornealCodeV8Result? = null
        var rightRes: CornealCodeV8Result? = null

        val replayedFrames = if (options.reuseArchivedGlints) {
            stageRows.map { f -> recomputeGeometry(f, intrinsics, nasalSign, kPrismPerRadius) }
        } else {
            val leftObs = buildObservations(stageRows, isLeft = true, radiusFilter = options.searchRadiusOverride)
            val rightObs = buildObservations(stageRows, isLeft = false, radiusFilter = options.searchRadiusOverride)

            fun analyze(eye: String, obs: List<GlintObservationFrame>, lag: Double?, anchor: Point2f?): CornealCodeV8Result? {
                if (obs.isEmpty()) return null
                return CornealCodeV8Analyzer.analyze(
                    eye = eye,
                    frames = obs,
                    displayEvents = stageEvents,
                    startHostTimeSec = startSec,
                    durationSec = durSec,
                    chipSec = chipSec,
                    scheme = scheme,
                    stimulusDiameterMm = effectiveStimulusMm,
                    searchRadiusFraction = radiusFraction,
                    fps = fpsInt,
                    lagPriorSec = lag,
                    anchorPrior = anchor,
                )
            }

            leftRes = analyze("LEFT", leftObs, priorLagSec, leftAnchor)
            rightRes = analyze("RIGHT", rightObs, priorLagSec, rightAnchor)
            val stageLag = highConfLag(leftRes) ?: highConfLag(rightRes) ?: priorLagSec
            if (stageLag != null) {
                if (leftRes != null && !leftRes.codeVerified) {
                    val retried = analyze("LEFT", leftObs, stageLag, leftAnchor)
                    if (retried != null && (retried.codeVerified || (retried.heldOutCorrelation ?: -1.0) > (leftRes.heldOutCorrelation ?: -1.0))) {
                        leftRes = retried
                    }
                }
                if (rightRes != null && !rightRes.codeVerified) {
                    val retried = analyze("RIGHT", rightObs, stageLag, rightAnchor)
                    if (retried != null && (retried.codeVerified || (retried.heldOutCorrelation ?: -1.0) > (rightRes.heldOutCorrelation ?: -1.0))) {
                        rightRes = retried
                    }
                }
            }

            val lByIdx = leftRes?.frames.orEmpty().associateBy { it.frameIndex }
            val rByIdx = rightRes?.frames.orEmpty().associateBy { it.frameIndex }
            stageRows.map { f ->
                val le = lByIdx[f.index]
                val re = rByIdx[f.index]
                val lGx = le?.measurementX ?: le?.imageX
                val lGy = le?.measurementY ?: le?.imageY
                val rGx = re?.measurementX ?: re?.imageX
                val rGy = re?.measurementY ?: re?.imageY
                val lRelX = le?.takeIf { it.source.measured }?.irisRelX?.toFloat()
                val lRelY = le?.takeIf { it.source.measured }?.irisRelY?.toFloat()
                val rRelX = re?.takeIf { it.source.measured }?.irisRelX?.toFloat()
                val rRelY = re?.takeIf { it.source.measured }?.irisRelY?.toFloat()
                val updated = f.copy(
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
                )
                recomputeGeometry(updated, intrinsics, nasalSign, kPrismPerRadius)
            }
        }

        val metrics = computeAlignmentMetrics(
            stage = stage,
            stageRows = replayedFrames,
            lCode = leftRes ?: if (stage == LabStage.NEAR_FIXATION) report.nearLeftCode else if (stage == LabStage.FAR_FIXATION) report.farLeftCode else null,
            rCode = rightRes ?: if (stage == LabStage.NEAR_FIXATION) report.nearRightCode else if (stage == LabStage.FAR_FIXATION) report.farRightCode else null,
            medFps = medFps,
        )

        return LabStageReplayResult(
            stage = stage,
            leftCode = leftRes,
            rightCode = rightRes,
            metrics = metrics,
            frames = replayedFrames,
        )
    }

    private fun buildObservations(
        stageRows: List<LabFrameLog>,
        isLeft: Boolean,
        radiusFilter: Float?,
    ): List<GlintObservationFrame> = stageRows.mapNotNull { f ->
        val ix = (if (isLeft) f.leftIrisX else f.rightIrisX)?.toDouble() ?: return@mapNotNull null
        val iy = (if (isLeft) f.leftIrisY else f.rightIrisY)?.toDouble() ?: return@mapNotNull null
        val dPx = (if (isLeft) f.leftIrisDiameterPx else f.rightIrisDiameterPx)?.toDouble() ?: return@mapNotNull null
        if (dPx <= 0.0) return@mapNotNull null
        val radiusPx = dPx * 0.5
        val visContrast = (if (isLeft) f.leftVisibilityContrast else f.rightVisibilityContrast) ?: 0f
        val ear = (if (isLeft) f.leftEar else f.rightEar) ?: 1f
        val visible = dPx >= 12.0 && visContrast >= 8f && ear >= 0.14f
        val rawCands = if (isLeft) f.leftCandidates else f.rightCandidates
        val filtered = if (radiusFilter != null) {
            val maxDist = radiusPx * radiusFilter.toDouble()
            rawCands.filter { hypot(it.x.toDouble() - ix, it.y.toDouble() - iy) <= maxDist }
        } else {
            rawCands
        }
        val exposureSec = f.exposureSec?.takeIf { it.isFinite() && it > 0.0 }
            ?: (1.0 / max(20.0, f.fps.toDouble().takeIf { it.isFinite() && it > 10.0 } ?: 30.0) * 0.6)
        GlintObservationFrame(
            frameIndex = f.index,
            hostTimeSec = f.timestampMs / 1000.0,
            irisCenterX = ix,
            irisCenterY = iy,
            irisRadiusPx = radiusPx,
            distanceCm = f.distanceCm.toDouble().takeIf { it > 10.0 } ?: 33.0,
            exposureSec = exposureSec,
            visible = visible,
            candidates = filtered.map { it.toReflexCandidate() },
        )
    }

    private fun recomputeGeometry(
        f: LabFrameLog,
        intrinsics: LabCameraIntrinsics?,
        nasalSign: Double,
        kPrismPerRadius: Double,
    ): LabFrameLog {
        val lRelX = f.leftRelX?.takeIf { f.leftGlintSource.measured }
        val lRelY = f.leftRelY?.takeIf { f.leftGlintSource.measured }
        val rRelX = f.rightRelX?.takeIf { f.rightGlintSource.measured }
        val rRelY = f.rightRelY?.takeIf { f.rightGlintSource.measured }
        val hdeLinear = if (f.valid && lRelX != null && rRelX != null) {
            val leftNasal = lRelX * nasalSign
            val rightNasal = rRelX * (-nasalSign)
            (leftNasal - rightNasal) * kPrismPerRadius
        } else null
        val vdeLinear = if (f.valid && lRelY != null && rRelY != null) {
            (lRelY - rRelY) * kPrismPerRadius
        } else null

        val optics3d = if (
            f.valid && intrinsics != null &&
            f.leftGlintSource.measured && f.rightGlintSource.measured &&
            f.leftGlintX != null && f.leftGlintY != null &&
            f.rightGlintX != null && f.rightGlintY != null &&
            f.leftIrisX != null && f.leftIrisY != null && f.leftIrisDiameterPx != null &&
            f.rightIrisX != null && f.rightIrisY != null && f.rightIrisDiameterPx != null
        ) {
            CornealOptics3D.solveBinocular(
                leftIrisX = f.leftIrisX.toDouble(),
                leftIrisY = f.leftIrisY.toDouble(),
                leftGlintX = f.leftGlintX.toDouble(),
                leftGlintY = f.leftGlintY.toDouble(),
                leftIrisDiameterPx = f.leftIrisDiameterPx.toDouble(),
                rightIrisX = f.rightIrisX.toDouble(),
                rightIrisY = f.rightIrisY.toDouble(),
                rightGlintX = f.rightGlintX.toDouble(),
                rightGlintY = f.rightGlintY.toDouble(),
                rightIrisDiameterPx = f.rightIrisDiameterPx.toDouble(),
                intrinsics = intrinsics,
                nasalSign = nasalSign,
                farFixation = f.stage == LabStage.FAR_FIXATION,
            )
        } else null

        return f.copy(
            hdePrism = hdeLinear,
            vdePrism = vdeLinear,
            hde3dPrism = optics3d?.hdeAsymmetryPrism,
            vde3dPrism = optics3d?.vdePrism,
            hdeVergence3dPrism = optics3d?.hdeVergencePrism,
            rollCorrectionDeg = optics3d?.rollCorrectionDeg,
        )
    }

    private fun computeAlignmentMetrics(
        stage: LabStage,
        stageRows: List<LabFrameLog>,
        lCode: CornealCodeV8Result?,
        rCode: CornealCodeV8Result?,
        medFps: Double,
    ): LabAlignmentMetrics? {
        if (stageRows.isEmpty()) return null
        val settleMs = if (stage == LabStage.FAR_FIXATION) StrabismusProtocol.FAR_SETTLE_MS else 0L
        val firstMs = stageRows.first().timestampMs
        val allMeasured = stageRows.filter { it.timestampMs >= firstMs + settleMs && it.hdePrism != null && it.vdePrism != null }
        val observedBinocular = allMeasured.filter {
            it.leftGlintSource == LabGlintSource.OBSERVED && it.rightGlintSource == LabGlintSource.OBSERVED
        }
        val codeAnyVerified = (lCode?.codeVerified == true) || (rCode?.codeVerified == true)
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
            val madSigma = (median(values.map { abs(it - med) }) ?: 0.0) * 1.4826
            if (madSigma < 0.5) return values
            val limit = max(4.0, 2.5 * madSigma)
            val kept = values.filter { abs(it - med) <= limit }
            return if (kept.size >= 6) kept else values
        }

        val hVals = robustTrim(usable.map { it.hdePrism!! })
        val vVals = robustTrim(usable.map { it.vdePrism!! })
        val asym3dVals = robustTrim(usable.mapNotNull { it.hde3dPrism })
        val verg3dVals = robustTrim(usable.mapNotNull { it.hdeVergence3dPrism })
        val v3dVals = robustTrim(usable.mapNotNull { it.vde3dPrism })

        val effN = max(1.0, usable.size / max(1.0, medFps * 0.2))
        val hSd = stdDev(hVals)
        val vSd = stdDev(vVals)
        val h3dSd = stdDev(asym3dVals)
        val v3dSd = stdDev(v3dVals)
        return LabAlignmentMetrics(
            stage = stage,
            hdePrism = median(hVals),
            vdePrism = median(vVals),
            hdeSdPrism = hSd,
            vdeSdPrism = vSd,
            hdeCi95HalfPrism = hSd?.let { 1.96 * it / sqrt(effN) },
            vdeCi95HalfPrism = vSd?.let { 1.96 * it / sqrt(effN) },
            hde3dPrism = median(asym3dVals),
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

    private fun inferNasalSign(frames: List<LabFrameLog>): Double {
        val sample = frames.firstOrNull { it.leftIrisX != null && it.rightIrisX != null } ?: return 1.0
        return if (sample.rightIrisX!! > sample.leftIrisX!!) 1.0 else -1.0
    }

    private fun highConfLag(res: CornealCodeV8Result?): Double? {
        if (res == null || !res.codeVerified) return null
        if ((res.trainCorrelation ?: 0.0) < 0.45 || (res.heldOutCorrelation ?: 0.0) < 0.40) return null
        return res.lagMs?.div(1000.0)?.takeIf { it in -0.05..0.22 }
    }

    private fun verifiedAnchor(res: CornealCodeV8Result?): Point2f? {
        if (res == null || !res.codeVerified) return null
        val rx = res.referenceRelX ?: return null
        val ry = res.referenceRelY ?: return null
        return Point2f(rx.toFloat(), ry.toFloat())
    }

    private fun stdDev(values: List<Double>): Double? {
        if (values.size < 2) return null
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    }
}
