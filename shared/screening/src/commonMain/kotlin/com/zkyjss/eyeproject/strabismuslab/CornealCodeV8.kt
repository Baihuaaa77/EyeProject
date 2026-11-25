package com.zkyjss.eyeproject.strabismuslab

import com.zkyjss.eyeproject.core.model.Point2f
import com.zkyjss.eyeproject.core.model.ReflexCandidate
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 对齐 CornealCodeLab v0.8 的屏幕主动光码定义与解调引擎（KMP 双端共用）。
 *
 * 核心特性：
 * 1. 64 码元平衡 6 位 m-序列，支持平衡随机明灭 / 曼彻斯特双相 / 稀疏短脉冲 / 常亮对照 / 全黑对照；
 * 2. 前半段训练期使用亮/暗样本数平衡加权差分建立亮点身份锚点，抑制恒定窗面/镜片反光；
 * 3. 前向 + 后向双向关联融合，分歧帧降级为预测态，防止暗帧跳点到环境反光；
 * 4. 编码验证严格读取亮灭门控前的独立轨迹：前半段自适应拟合延迟，后半段计算留出相关与全部 63 个错位码对照；
 * 5. EmissionTimeline 按曝光与延迟容差严格区分稳定亮/低电平/灭灯/过渡帧，输出 EmissionAudit；
 * 6. TemporalGlintSupport 仅增强亮灯帧已有像素峰且前后邻帧均支持的弱候选，不凭空造点。
 */
@Serializable
enum class LabCodingScheme(val label: String, val detail: String) {
    M_SEQUENCE("平衡随机明灭", "64 码元平衡 m-序列，结合 63 个错位码核验光源身份"),
    MANCHESTER("曼彻斯特双相", "每码元前后半程交换亮灭，直流均衡抗缓慢光漂"),
    SPARSE_PULSE("稀疏短脉冲", "正码元前 35% 发光（占空约 17.5%），提供大量灭灯阴性检查"),
    STEADY("常亮白点对照", "恒定亮灯白点基线，用于无编码消融对照"),
    DARK("全黑阴性对照", "零刺激阴性对照，用于核查环境反光误检底数");

    val usesTemporalCode: Boolean
        get() = this == M_SEQUENCE || this == MANCHESTER || this == SPARSE_PULSE

    fun value(bit: Double, phase: Double): Double = when (this) {
        M_SEQUENCE -> bit
        MANCHESTER -> if (phase < 0.5) bit else -bit
        SPARSE_PULSE -> if (bit > 0 && phase < 0.35) 1.0 else -1.0
        STEADY -> 1.0
        DARK -> -1.0
    }
}

object StimulusCodeV8 {
    /** 6 位最大长度伪随机序列 + 1 平衡位 = 64 码元（正负各 32）。 */
    val bits: DoubleArray = DoubleArray(64).also { arr ->
        var state = 0x3F
        var sum = 0.0
        for (i in 0 until 63) {
            val bit = if ((state and 1) == 1) 1.0 else -1.0
            arr[i] = bit
            sum += bit
            val feedback = (state xor (state ushr 1)) and 1
            state = (state ushr 1) or (feedback shl 5)
        }
        arr[63] = -sum
    }

    fun codeValue(
        elapsedSec: Double,
        chipSec: Double,
        scheme: LabCodingScheme,
        shift: Int = 0,
    ): Double {
        if (!elapsedSec.isFinite() || !chipSec.isFinite() || chipSec <= 0.0 || elapsedSec < 0.0) return 0.0
        val index = (elapsedSec / chipSec).toInt()
        val wrapped = ((index + (shift % bits.size)) % bits.size + bits.size) % bits.size
        val phase = (elapsedSec / chipSec) - index
        return scheme.value(bits[wrapped], phase)
    }

    fun intensity(
        elapsedSec: Double,
        chipSec: Double,
        scheme: LabCodingScheme,
        mean: Double = 0.5,
        modulation: Double = 0.5,
    ): Double = when (scheme) {
        LabCodingScheme.DARK -> 0.0
        LabCodingScheme.STEADY -> 1.0
        LabCodingScheme.M_SEQUENCE,
        LabCodingScheme.MANCHESTER,
        LabCodingScheme.SPARSE_PULSE -> (mean + modulation * codeValue(elapsedSec, chipSec, scheme)).coerceIn(0.0, 1.0)
    }
}

@Serializable
enum class LabEmissionPhase(val label: String) {
    ON("亮灯"),
    DIM("低电平"),
    OFF("灭灯"),
    TRANSITION("切换/曝光混合"),
    UNKNOWN("时序未知");

    val inhibitsMeasurement: Boolean
        get() = this == OFF || this == DIM
}

@Serializable
enum class LabGlintSource(val label: String) {
    OBSERVED("图像观测"),
    WEAK("弱观测"),
    PREDICTED("短时预测"),
    UNAVAILABLE("无可靠观测");

    val measured: Boolean
        get() = this == OBSERVED || this == WEAK
}

@Serializable
data class LabDisplayEvent(
    val hostTimeSec: Double,
    val callbackHostTimeSec: Double,
    val intensity: Double,
    val code: Double,
    val chip: Int,
    val elapsedSec: Double,
    val screenBrightness: Double = 1.0,
)

@Serializable
data class OpticalBudgetEstimate(
    val distanceCm: Double,
    val irisDiameterPx: Double,
    val stimulusDiameterMm: Double,
    val cornealRadiusMm: Double = 7.8,
    val expectedDiameterPx: Double,
    val expectedMinPx: Double,
    val expectedMaxPx: Double,
    val observedMedianDiameterPx: Double? = null,
    val observedToExpectedRatio: Double? = null,
) {
    companion object {
        /** 角膜凸面镜近轴缩率 m = (R/2) / (d + R/2)，虹膜尺度 11.7 mm。 */
        fun expectedDiameterPx(
            distanceCm: Double,
            irisPixels: Double,
            sourceMm: Double,
            radiusMm: Double = 7.8,
        ): Double? {
            if (!distanceCm.isFinite() || !irisPixels.isFinite() || !sourceMm.isFinite() || !radiusMm.isFinite()) return null
            if (distanceCm <= 0.0 || irisPixels <= 0.0 || sourceMm <= 0.0 || radiusMm <= 0.0) return null
            val focalMm = radiusMm * 0.5
            return sourceMm * focalMm / (distanceCm * 10.0 + focalMm) * irisPixels / 11.7
        }

        fun build(
            distancesCm: List<Double>,
            irisDiametersPx: List<Double>,
            observedDiametersPx: List<Double>,
            sourceMm: Double,
            radiusMm: Double = 7.8,
        ): OpticalBudgetEstimate? {
            val dMed = median(distancesCm) ?: return null
            val irisMed = median(irisDiametersPx) ?: return null
            val dMin = distancesCm.filter { it.isFinite() && it > 0.0 }.minOrNull() ?: dMed
            val dMax = distancesCm.filter { it.isFinite() && it > 0.0 }.maxOrNull() ?: dMed
            val expected = expectedDiameterPx(dMed, irisMed, sourceMm, radiusMm) ?: return null
            val minPx = expectedDiameterPx(dMax, irisMed, sourceMm, (radiusMm - 0.6).coerceAtLeast(6.0)) ?: expected
            val maxPx = expectedDiameterPx(dMin, irisMed, sourceMm, radiusMm + 0.6) ?: expected
            val obs = median(observedDiametersPx)
            return OpticalBudgetEstimate(
                distanceCm = dMed,
                irisDiameterPx = irisMed,
                stimulusDiameterMm = sourceMm,
                cornealRadiusMm = radiusMm,
                expectedDiameterPx = expected,
                expectedMinPx = minPx,
                expectedMaxPx = maxPx,
                observedMedianDiameterPx = obs,
                observedToExpectedRatio = obs?.let { it / expected },
            )
        }
    }
}

@Serializable
data class LabEmissionAudit(
    val stableOnFrames: Int,
    val stableDimFrames: Int,
    val stableOffFrames: Int,
    val transitionFrames: Int,
    val unknownFrames: Int,
    val independentDimMeasurements: Int,
    val independentOffMeasurements: Int,
    val suppressedMeasurements: Int,
    val finalDimMeasurements: Int,
    val finalOffMeasurements: Int,
    val lagUsedMs: Double? = null,
)

@Serializable
data class LabGlintFrameEstimate(
    val frameIndex: Int,
    val hostTimeSec: Double,
    val imageX: Double? = null,
    val imageY: Double? = null,
    val measurementX: Double? = null,
    val measurementY: Double? = null,
    val irisRelX: Double? = null,
    val irisRelY: Double? = null,
    val confidence: Double = 0.0,
    val source: LabGlintSource = LabGlintSource.UNAVAILABLE,
    val uncertaintyPx: Double = 100.0,
    val signal: Double? = null,
    val snr: Double? = null,
    val observedDiameterPx: Double? = null,
    val expectedDiameterPx: Double? = null,
    val ambiguity: Double = 1.0,
    val emissionPhase: LabEmissionPhase = LabEmissionPhase.UNKNOWN,
    val independentSource: LabGlintSource? = null,
    val independentSignal: Double? = null,
    val timingSuppressed: Boolean = false,
    val temporalSupportFrames: Int = 0,
    val candidateX: Double? = null,
    val candidateY: Double? = null,
    val candidateRelX: Double? = null,
    val candidateRelY: Double? = null,
    val candidateConfidence: Double? = null,
    val candidateDiameterPx: Double? = null,
)

@Serializable
data class LabWaveformPoint(
    val tSec: Double,
    val signal: Double,
    val alignedCode: Double,
    val phase: LabEmissionPhase,
)

@Serializable
data class CornealCodeV8Result(
    val eye: String,
    val totalFrames: Int,
    val observedFrames: Int,
    val weakFrames: Int,
    val predictedFrames: Int,
    val unavailableFrames: Int,
    val medianConfidence: Double,
    val trainCorrelation: Double? = null,
    val heldOutCorrelation: Double? = null,
    val maxWrongCodeCorrelation: Double? = null,
    val codeMargin: Double? = null,
    val lagMs: Double? = null,
    val codeVerified: Boolean = false,
    val referenceRelX: Double? = null,
    val referenceRelY: Double? = null,
    val searchRadiusFraction: Float = 0.80f,
    val shiftNullCorrelations: List<Double> = emptyList(),
    val waveform: List<LabWaveformPoint> = emptyList(),
    val emissionAudit: LabEmissionAudit? = null,
    val opticalBudget: OpticalBudgetEstimate? = null,
    val frames: List<LabGlintFrameEstimate> = emptyList(),
)

/** 单帧送入 v0.8 追踪器的候选快照（来自原像素 ScreenGlintDetection）。 */
data class GlintObservationFrame(
    val frameIndex: Int,
    val hostTimeSec: Double,
    val irisCenterX: Double,
    val irisCenterY: Double,
    val irisRadiusPx: Double,
    val distanceCm: Double,
    val exposureSec: Double,
    val visible: Boolean,
    val candidates: List<ReflexCandidate>,
)

/** 发射时间线：只在整个曝光与延迟容差窗口同状态时判定稳定亮/灭，不把边界帧当作灭灯阴性。 */
internal class EmissionTimelineV8(events: List<LabDisplayEvent>) {
    private val sorted: List<Pair<Double, Double>> = events
        .filter { it.hostTimeSec.isFinite() && it.callbackHostTimeSec.isFinite() && it.intensity.isFinite() }
        .map { max(it.hostTimeSec, it.callbackHostTimeSec) to it.intensity }
        .sortedBy { it.first }

    private fun indexAtOrBefore(timeSec: Double): Int {
        var low = 0
        var high = sorted.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sorted[mid].first <= timeSec) low = mid + 1 else high = mid
        }
        return low - 1
    }

    fun phaseAt(
        timeSec: Double,
        exposureSec: Double,
        fps: Int,
        lagSec: Double?,
        chipSec: Double,
    ): LabEmissionPhase {
        if (sorted.isEmpty()) return LabEmissionPhase.UNKNOWN
        val frame = 1.0 / max(1, fps)
        val margin = max(0.0, exposureSec) * 0.5 + frame * 0.5 + 1.0 / 60.0
        val lowLag = lagSec?.let { it - frame * 0.5 } ?: 0.0
        val highLag = lagSec?.let { it + frame * 0.5 } ?: 0.15
        val from = timeSec - highLag - margin
        val to = timeSec - lowLag + margin
        val first = indexAtOrBefore(from)
        val last = indexAtOrBefore(to)
        if (first < 0 || last < first || to > sorted.last().first + chipSec + frame) return LabEmissionPhase.UNKNOWN
        fun classify(v: Double): LabEmissionPhase = when {
            v <= 0.001 -> LabEmissionPhase.OFF
            v <= 0.10 -> LabEmissionPhase.DIM
            else -> LabEmissionPhase.ON
        }
        val state = classify(sorted[first].second)
        for (i in first..last) {
            if (classify(sorted[i].second) != state) return LabEmissionPhase.TRANSITION
        }
        return state
    }
}

object CornealCodeV8Analyzer {
    fun analyze(
        eye: String,
        frames: List<GlintObservationFrame>,
        displayEvents: List<LabDisplayEvent>,
        startHostTimeSec: Double,
        durationSec: Double,
        chipSec: Double,
        scheme: LabCodingScheme,
        stimulusDiameterMm: Double,
        searchRadiusFraction: Float = 0.80f,
        fps: Int = 30,
        cornealRadiusMm: Double = 7.8,
    ): CornealCodeV8Result {
        val sorted = frames.sortedBy { it.hostTimeSec }
        if (sorted.isEmpty()) {
            return CornealCodeV8Result(
                eye = eye,
                totalFrames = 0,
                observedFrames = 0,
                weakFrames = 0,
                predictedFrames = 0,
                unavailableFrames = 0,
                medianConfidence = 0.0,
                searchRadiusFraction = searchRadiusFraction,
            )
        }
        val sortedEvents = displayEvents
            .filter { it.hostTimeSec.isFinite() && it.code.isFinite() && it.chip >= 0 }
            .sortedBy { it.hostTimeSec }

        val seedReference = estimateReferenceAnchor(
            sorted = sorted,
            events = sortedEvents,
            startSec = startHostTimeSec,
            durationSec = durationSec,
            scheme = scheme,
            stimulusDiameterMm = stimulusDiameterMm,
            cornealRadiusMm = cornealRadiusMm,
        )

        fun runBidirectional(phasesByFrame: Map<Int, LabEmissionPhase>): List<LabGlintFrameEstimate> {
            val forward = runDirectional(
                rows = sorted,
                anchor = seedReference,
                stimulusDiameterMm = stimulusDiameterMm,
                cornealRadiusMm = cornealRadiusMm,
                phasesByFrame = phasesByFrame,
                reverse = false,
            )
            val backward = runDirectional(
                rows = sorted.asReversed(),
                anchor = seedReference,
                stimulusDiameterMm = stimulusDiameterMm,
                cornealRadiusMm = cornealRadiusMm,
                phasesByFrame = phasesByFrame,
                reverse = true,
            ).associateBy { it.frameIndex }
            return forward.map { f -> backward[f.frameIndex]?.let { fuse(f, it) } ?: f }
        }

        // 1) 始终先跑不受亮灭门控的独立图像轨迹，并用独立轨迹验证编码相关性（绝不借门控抬高相关）。
        val independent = runBidirectional(emptyMap())
        val validation = validateCode(
            estimates = independent,
            events = sortedEvents,
            startSec = startHostTimeSec,
            durationSec = durationSec,
            chipSec = chipSec,
            scheme = scheme,
        )

        // 2) 建立曝光-延迟时间线；前半段相关 >= 0.6 且延迟在 0..150ms 才启用拟合延迟，否则走保守范围。
        val timeline = EmissionTimelineV8(sortedEvents)
        val lagForTimeline = validation.lagSec?.takeIf {
            (validation.trainR ?: 0.0) >= 0.6 && it in 0.0..0.15
        }
        val phaseMap = sorted.associate { row ->
            row.frameIndex to timeline.phaseAt(
                timeSec = row.hostTimeSec,
                exposureSec = row.exposureSec,
                fps = fps,
                lagSec = lagForTimeline,
                chipSec = chipSec,
            )
        }

        val conditionedRaw = if (scheme.usesTemporalCode || scheme == LabCodingScheme.DARK) {
            runBidirectional(phaseMap)
        } else {
            independent
        }
        val independentByFrame = independent.associateBy { it.frameIndex }
        val merged = conditionedRaw.map { est ->
            val base = independentByFrame[est.frameIndex]
            val phase = phaseMap[est.frameIndex] ?: LabEmissionPhase.UNKNOWN
            val suppressed = phase.inhibitsMeasurement && (base?.source?.measured == true)
            if (phase.inhibitsMeasurement) {
                est.copy(
                    source = if (est.confidence > 0.0) LabGlintSource.PREDICTED else LabGlintSource.UNAVAILABLE,
                    confidence = min(0.2, est.confidence),
                    measurementX = null,
                    measurementY = null,
                    observedDiameterPx = null,
                    emissionPhase = phase,
                    independentSource = base?.source,
                    independentSignal = base?.signal,
                    timingSuppressed = suppressed,
                )
            } else {
                est.copy(
                    emissionPhase = phase,
                    independentSource = base?.source,
                    independentSignal = base?.signal,
                    timingSuppressed = false,
                )
            }
        }
        val refined = refineTemporalSupport(merged)

        val confidences = refined.map { it.confidence }.sorted()
        val medianConf = if (confidences.isEmpty()) 0.0 else confidences[confidences.size / 2]
        val audit = LabEmissionAudit(
            stableOnFrames = refined.count { it.emissionPhase == LabEmissionPhase.ON },
            stableDimFrames = refined.count { it.emissionPhase == LabEmissionPhase.DIM },
            stableOffFrames = refined.count { it.emissionPhase == LabEmissionPhase.OFF },
            transitionFrames = refined.count { it.emissionPhase == LabEmissionPhase.TRANSITION },
            unknownFrames = refined.count { it.emissionPhase == LabEmissionPhase.UNKNOWN },
            independentDimMeasurements = refined.count { it.emissionPhase == LabEmissionPhase.DIM && it.independentSource?.measured == true },
            independentOffMeasurements = refined.count { it.emissionPhase == LabEmissionPhase.OFF && it.independentSource?.measured == true },
            suppressedMeasurements = refined.count { it.timingSuppressed },
            finalDimMeasurements = refined.count { it.emissionPhase == LabEmissionPhase.DIM && it.source.measured },
            finalOffMeasurements = refined.count { it.emissionPhase == LabEmissionPhase.OFF && it.source.measured },
            lagUsedMs = lagForTimeline?.times(1000.0),
        )

        val opticalBudget = OpticalBudgetEstimate.build(
            distancesCm = sorted.filter { it.visible && it.distanceCm > 0.0 }.map { it.distanceCm },
            irisDiametersPx = sorted.filter { it.visible && it.irisRadiusPx > 0.0 }.map { it.irisRadiusPx * 2.0 },
            observedDiametersPx = refined.filter { it.source == LabGlintSource.OBSERVED }.mapNotNull { it.observedDiameterPx },
            sourceMm = stimulusDiameterMm,
            radiusMm = cornealRadiusMm,
        )

        val waveform = buildWaveform(
            estimates = refined,
            events = sortedEvents,
            startSec = startHostTimeSec,
            lagSec = validation.lagSec ?: 0.0,
            chipSec = chipSec,
            scheme = scheme,
        )

        val margin = if (validation.heldOutR != null && validation.maxWrongR != null) {
            validation.heldOutR - validation.maxWrongR
        } else null

        return CornealCodeV8Result(
            eye = eye,
            totalFrames = refined.size,
            observedFrames = refined.count { it.source == LabGlintSource.OBSERVED },
            weakFrames = refined.count { it.source == LabGlintSource.WEAK },
            predictedFrames = refined.count { it.source == LabGlintSource.PREDICTED },
            unavailableFrames = refined.count { it.source == LabGlintSource.UNAVAILABLE },
            medianConfidence = medianConf,
            trainCorrelation = validation.trainR,
            heldOutCorrelation = validation.heldOutR,
            maxWrongCodeCorrelation = validation.maxWrongR,
            codeMargin = margin,
            lagMs = validation.lagSec?.times(1000.0),
            codeVerified = validation.verified,
            referenceRelX = seedReference?.x?.toDouble(),
            referenceRelY = seedReference?.y?.toDouble(),
            searchRadiusFraction = searchRadiusFraction,
            shiftNullCorrelations = validation.shiftNulls,
            waveform = waveform,
            emissionAudit = audit,
            opticalBudget = opticalBudget,
            frames = refined,
        )
    }

    private data class Seed(val relX: Double, val relY: Double, val weight: Double)

    /**
     * 对齐 CornealCodeLab v0.8：前半段稀疏采样，正负码元按样本数分别归一，
     * 以亮暗均值差抵消恒定窗面/镜片反光；后半段不参与选点。
     */
    private fun estimateReferenceAnchor(
        sorted: List<GlintObservationFrame>,
        events: List<LabDisplayEvent>,
        startSec: Double,
        durationSec: Double,
        scheme: LabCodingScheme,
        stimulusDiameterMm: Double,
        cornealRadiusMm: Double,
    ): Point2f? {
        val trainEnd = min(8.0, durationSec * 0.5).coerceAtLeast(2.0)
        val seedRows = sorted.filterIndexed { idx, row ->
            idx % 2 == 0 && row.visible && row.irisRadiusPx >= 6.0 &&
                (row.hostTimeSec - startSec) in 0.25..trainEnd
        }
        if (seedRows.isEmpty()) return null

        var positiveCount = 0
        var negativeCount = 0
        if (scheme.usesTemporalCode) {
            for (row in seedRows) {
                val c = events.lastOrNull { it.hostTimeSec <= row.hostTimeSec - 1.0 / 30.0 }?.code ?: 0.0
                if (c > 0.0) positiveCount++ else if (c < 0.0) negativeCount++
            }
            if (positiveCount == 0 || negativeCount == 0) return null
        }

        val seeds = mutableListOf<Seed>()
        for (row in seedRows) {
            val expected = OpticalBudgetEstimate.expectedDiameterPx(
                distanceCm = row.distanceCm,
                irisPixels = row.irisRadiusPx * 2.0,
                sourceMm = stimulusDiameterMm,
                radiusMm = cornealRadiusMm,
            ) ?: 3.0
            val code = events.lastOrNull { it.hostTimeSec <= row.hostTimeSec - 1.0 / 30.0 }?.code ?: 0.0
            for (cand in row.candidates) {
                val snr = if (cand.snr > 0f) cand.snr.toDouble() else (cand.contrast / 3.0)
                val shape = if (cand.shapeScore > 0f) cand.shapeScore.toDouble() else cand.quality.toDouble().coerceIn(0.2, 1.0)
                if (snr < 4.5 || shape < 0.35) continue
                val relX = (cand.point.x - row.irisCenterX) / row.irisRadiusPx
                val relY = (cand.point.y - row.irisCenterY) / row.irisRadiusPx
                val diam = if (cand.diameterPx > 0f) cand.diameterPx.toDouble() else expected
                val sizeScore = exp(-ln(max(0.5, diam) / max(1.0, expected)).pow(2) / 0.8)
                val prior = exp(-0.5 * (relX * relX / 0.64 + ((relY + 0.25) / 0.55).pow(2)))
                var weight = min(1.0, snr / 15.0) * shape * shape * sizeScore * prior
                if (scheme.usesTemporalCode) {
                    val halfTotal = (positiveCount + negativeCount) * 0.5
                    val balanced = if (code > 0.0) halfTotal / positiveCount else -halfTotal / negativeCount
                    weight *= balanced * min(2.0, cand.contrast / 60.0)
                }
                if (abs(weight) > 0.12) seeds += Seed(relX, relY, weight)
            }
        }
        if (seeds.size < 6) return null

        var bestSum = 0.0
        var bestSeed: Seed? = null
        for (seed in seeds) {
            var sum = 0.0
            for (other in seeds) {
                val d = hypot(seed.relX - other.relX, seed.relY - other.relY)
                sum += other.weight * exp(-0.5 * (d / 0.075).pow(2))
            }
            if (sum > bestSum) {
                bestSum = sum
                bestSeed = seed
            }
        }
        val ref = bestSeed ?: return null
        if (bestSum < 3.0) return null
        val cluster = seeds.filter { it.weight > 0.0 && hypot(it.relX - ref.relX, it.relY - ref.relY) < 0.12 }
        val totalW = cluster.sumOf { it.weight }
        if (totalW <= 0.0) return null
        val cx = cluster.sumOf { it.relX * it.weight } / totalW
        val cy = cluster.sumOf { it.relY * it.weight } / totalW
        return Point2f(cx.toFloat(), cy.toFloat())
    }

    private fun runDirectional(
        rows: List<GlintObservationFrame>,
        anchor: Point2f?,
        stimulusDiameterMm: Double,
        cornealRadiusMm: Double,
        phasesByFrame: Map<Int, LabEmissionPhase>,
        reverse: Boolean,
    ): List<LabGlintFrameEstimate> {
        var prevPointX: Double? = null
        var prevPointY: Double? = null
        var prevIrisX: Double? = null
        var prevIrisY: Double? = null
        var prevRadius = 1.0
        var lastObsTime = Double.NEGATIVE_INFINITY
        var lastConfidence = 0.0

        return rows.map { row ->
            val t = if (reverse) -row.hostTimeSec else row.hostTimeSec
            val expected = OpticalBudgetEstimate.expectedDiameterPx(
                distanceCm = row.distanceCm,
                irisPixels = row.irisRadiusPx * 2.0,
                sourceMm = stimulusDiameterMm,
                radiusMm = cornealRadiusMm,
            ) ?: 3.0
            val phase = phasesByFrame[row.frameIndex] ?: LabEmissionPhase.UNKNOWN
            val measurementAllowed = !phase.inhibitsMeasurement

            if (!row.visible || row.irisRadiusPx < 6.0) {
                return@map LabGlintFrameEstimate(
                    frameIndex = row.frameIndex,
                    hostTimeSec = row.hostTimeSec,
                    confidence = 0.0,
                    source = LabGlintSource.UNAVAILABLE,
                    expectedDiameterPx = expected,
                    emissionPhase = phase,
                )
            }

            val anchoredX = anchor?.let { row.irisCenterX + it.x * row.irisRadiusPx }
            val anchoredY = anchor?.let { row.irisCenterY + it.y * row.irisRadiusPx }

            var predX: Double? = prevPointX?.let { oldX ->
                val originX = prevIrisX ?: row.irisCenterX
                val scale = (row.irisRadiusPx / max(1.0, prevRadius)).coerceIn(0.8, 1.25)
                val dx = (row.irisCenterX - originX).coerceIn(-row.irisRadiusPx * 0.14, row.irisRadiusPx * 0.14)
                oldX * scale + dx + (1.0 - scale) * originX
            }
            var predY: Double? = prevPointY?.let { oldY ->
                val originY = prevIrisY ?: row.irisCenterY
                val scale = (row.irisRadiusPx / max(1.0, prevRadius)).coerceIn(0.8, 1.25)
                val dy = (row.irisCenterY - originY).coerceIn(-row.irisRadiusPx * 0.14, row.irisRadiusPx * 0.14)
                oldY * scale + dy + (1.0 - scale) * originY
            }
            if (t - lastObsTime > 0.6 && anchoredX != null && anchoredY != null) {
                predX = anchoredX
                predY = anchoredY
            }

            val gate = max(4.0, max(expected * 1.3, row.irisRadiusPx * 0.09))
            data class Scored(val cand: ReflexCandidate, val rank: Double, val snr: Double, val shape: Double)

            val scored = row.candidates.map { cand ->
                val px = cand.point.x.toDouble()
                val py = cand.point.y.toDouble()
                val snr = if (cand.snr > 0f) cand.snr.toDouble() else (cand.contrast / 3.0)
                val shape = if (cand.shapeScore > 0f) cand.shapeScore.toDouble() else cand.quality.toDouble().coerceIn(0.25, 1.0)
                var rank = max(0.05, cand.quality.toDouble()) * (0.25 + 0.75 * shape) * (1.0 - exp(-max(0.0, snr - 1.5) / 5.0))
                if (predX != null && predY != null) {
                    val d = hypot(px - predX, py - predY)
                    rank *= exp(-d * d / (2.0 * gate * gate))
                } else {
                    val rx = (px - row.irisCenterX) / row.irisRadiusPx
                    val ry = (py - row.irisCenterY) / row.irisRadiusPx
                    rank *= exp(-0.5 * (rx * rx / 0.64 + ((ry + 0.25) / 0.55).pow(2)))
                }
                if (anchoredX != null && anchoredY != null) {
                    val da = hypot(px - anchoredX, py - anchoredY)
                    rank *= exp(-0.5 * (da / max(5.0, row.irisRadiusPx * 0.16)).pow(2))
                }
                Scored(cand, rank, snr, shape)
            }.sortedByDescending { it.rank }

            val best = scored.firstOrNull()
            val second = scored.drop(1).firstOrNull { s ->
                best != null && hypot(s.cand.point.x - best.cand.point.x, s.cand.point.y - best.cand.point.y) > 2f
            }
            val ambiguity = if (best != null && best.rank > 1e-8) {
                min(1.0, (second?.rank ?: 0.0) / best.rank)
            } else 1.0

            val strength = best?.let { 1.0 - exp(-max(0.0, it.snr - 1.5) / 5.0) } ?: 0.0
            val rawConf = if (best != null) {
                (strength * (0.45 + 0.55 * best.shape) * (1.0 - 0.55 * ambiguity)).coerceIn(0.0, 1.0)
            } else 0.0
            val candRelX = best?.let { (it.cand.point.x - row.irisCenterX) / row.irisRadiusPx }
            val candRelY = best?.let { (it.cand.point.y - row.irisCenterY) / row.irisRadiusPx }
            val candDiam = best?.let { if (it.cand.diameterPx > 0f) it.cand.diameterPx.toDouble() else expected }

            val accepted = measurementAllowed && best != null && rawConf >= 0.25 && best.snr >= 3.5 &&
                (predX == null || predY == null || hypot(best.cand.point.x - predX, best.cand.point.y - predY) <= gate * 2.4)

            val est = if (accepted && best != null) {
                val px = best.cand.point.x.toDouble()
                val py = best.cand.point.y.toDouble()
                prevPointX = px
                prevPointY = py
                prevIrisX = row.irisCenterX
                prevIrisY = row.irisCenterY
                prevRadius = row.irisRadiusPx
                lastObsTime = t
                lastConfidence = rawConf
                LabGlintFrameEstimate(
                    frameIndex = row.frameIndex,
                    hostTimeSec = row.hostTimeSec,
                    imageX = px,
                    imageY = py,
                    measurementX = px,
                    measurementY = py,
                    irisRelX = candRelX,
                    irisRelY = candRelY,
                    confidence = rawConf,
                    source = if (rawConf >= 0.55) LabGlintSource.OBSERVED else LabGlintSource.WEAK,
                    uncertaintyPx = max(0.35, expected * (0.6 - 0.4 * rawConf)),
                    signal = best.cand.contrast.toDouble(),
                    snr = best.snr,
                    observedDiameterPx = candDiam,
                    expectedDiameterPx = expected,
                    ambiguity = ambiguity,
                    emissionPhase = phase,
                    candidateX = px,
                    candidateY = py,
                    candidateRelX = candRelX,
                    candidateRelY = candRelY,
                    candidateConfidence = rawConf,
                    candidateDiameterPx = candDiam,
                )
            } else {
                val dt = (t - lastObsTime).coerceAtLeast(0.0)
                val decayed = if (predX != null && predY != null && dt <= 0.45) {
                    (lastConfidence * exp(-dt / 0.18) * 0.5).coerceAtMost(0.24)
                } else 0.0
                prevIrisX = row.irisCenterX
                prevIrisY = row.irisCenterY
                prevRadius = row.irisRadiusPx
                // 即使未接受观测，仍记录最靠近预测/锚点处的局部信号用于编码相关性计算（避免亮灭门控缺失信号）。
                val nearSignal = best?.takeIf { b ->
                    val refX = predX ?: anchoredX
                    val refY = predY ?: anchoredY
                    refX == null || refY == null || hypot(b.cand.point.x - refX, b.cand.point.y - refY) <= gate * 2.2
                }?.cand?.contrast?.toDouble() ?: 0.0
                LabGlintFrameEstimate(
                    frameIndex = row.frameIndex,
                    hostTimeSec = row.hostTimeSec,
                    imageX = predX ?: anchoredX,
                    imageY = predY ?: anchoredY,
                    measurementX = null,
                    measurementY = null,
                    irisRelX = (predX ?: anchoredX)?.let { (it - row.irisCenterX) / row.irisRadiusPx },
                    irisRelY = (predY ?: anchoredY)?.let { (it - row.irisCenterY) / row.irisRadiusPx },
                    confidence = decayed,
                    source = if (decayed > 0.0) LabGlintSource.PREDICTED else LabGlintSource.UNAVAILABLE,
                    uncertaintyPx = if (decayed > 0.0) max(1.5, dt * 12.0) else 100.0,
                    signal = nearSignal,
                    snr = best?.snr,
                    observedDiameterPx = null,
                    expectedDiameterPx = expected,
                    ambiguity = ambiguity,
                    emissionPhase = phase,
                    candidateX = best?.cand?.point?.x?.toDouble(),
                    candidateY = best?.cand?.point?.y?.toDouble(),
                    candidateRelX = candRelX,
                    candidateRelY = candRelY,
                    candidateConfidence = rawConf,
                    candidateDiameterPx = candDiam,
                )
            }
            est
        }
    }

    /** 前后向关联一致时加权融合坐标；明显分歧降级为预测，避免暗帧单向跳点。 */
    private fun fuse(a: LabGlintFrameEstimate, b: LabGlintFrameEstimate): LabGlintFrameEstimate {
        if (a.imageX == null || a.imageY == null) return b
        if (b.imageX == null || b.imageY == null) return a
        val dist = hypot(a.imageX - b.imageX, a.imageY - b.imageY)
        val gate = max(3.0, (a.expectedDiameterPx ?: 3.0) * 1.2)
        var res = if (a.confidence >= b.confidence) a else b
        if (a.source.measured && b.source.measured) {
            if (dist <= gate) {
                val w = max(0.001, a.confidence + b.confidence)
                val mx = (a.imageX * a.confidence + b.imageX * b.confidence) / w
                val my = (a.imageY * a.confidence + b.imageY * b.confidence) / w
                val rx = if (a.irisRelX != null && b.irisRelX != null) {
                    (a.irisRelX * a.confidence + b.irisRelX * b.confidence) / w
                } else res.irisRelX
                val ry = if (a.irisRelY != null && b.irisRelY != null) {
                    (a.irisRelY * a.confidence + b.irisRelY * b.confidence) / w
                } else res.irisRelY
                val conf = sqrt(a.confidence * b.confidence) * exp(-0.15 * (dist / gate).pow(2))
                res = res.copy(
                    imageX = mx,
                    imageY = my,
                    measurementX = mx,
                    measurementY = my,
                    irisRelX = rx,
                    irisRelY = ry,
                    confidence = conf,
                )
            } else {
                res = res.copy(
                    confidence = res.confidence * 0.4,
                    source = LabGlintSource.PREDICTED,
                    measurementX = null,
                    measurementY = null,
                    observedDiameterPx = null,
                )
            }
        } else if (res.source.measured) {
            res = res.copy(confidence = res.confidence * if (dist <= gate) 0.8 else 0.5)
        }
        val finalSource = if (res.source.measured) {
            when {
                res.confidence < 0.25 -> LabGlintSource.PREDICTED
                res.confidence >= 0.55 -> LabGlintSource.OBSERVED
                else -> LabGlintSource.WEAK
            }
        } else res.source
        return res.copy(
            source = finalSource,
            confidence = if (finalSource.measured) res.confidence else min(0.25, res.confidence),
            measurementX = if (finalSource.measured) res.measurementX else null,
            measurementY = if (finalSource.measured) res.measurementY else null,
            observedDiameterPx = if (finalSource.measured) res.observedDiameterPx else null,
            uncertaintyPx = max(res.uncertaintyPx, dist * 0.5),
        )
    }

    /** 只增强亮灯帧已有当前像素峰且前后亮灯邻帧共同支持的弱候选；绝不把平坦图像插值成测量。 */
    private fun refineTemporalSupport(input: List<LabGlintFrameEstimate>): List<LabGlintFrameEstimate> {
        val output = input.toMutableList()
        for (i in input.indices) {
            val frame = input[i]
            if (frame.source.measured || frame.emissionPhase != LabEmissionPhase.ON) continue
            val snr = frame.snr ?: 0.0
            val candConf = frame.candidateConfidence ?: 0.0
            val cx = frame.candidateX ?: continue
            val cy = frame.candidateY ?: continue
            val relX = frame.candidateRelX ?: continue
            val relY = frame.candidateRelY ?: continue
            val predX = frame.imageX ?: continue
            val predY = frame.imageY ?: continue
            if (snr < 3.0 || candConf < 0.15 || frame.ambiguity > 0.65) continue
            if (hypot(cx - predX, cy - predY) > max(2.0, (frame.expectedDiameterPx ?: 2.0) * 1.25)) continue

            var past = 0
            var future = 0
            for (j in max(0, i - 3)..min(input.lastIndex, i + 3)) {
                if (j == i) continue
                val nb = input[j]
                if (abs(nb.hostTimeSec - frame.hostTimeSec) > 0.12) continue
                if (!nb.source.measured || nb.confidence < 0.4 || nb.emissionPhase != LabEmissionPhase.ON) continue
                val nrx = nb.irisRelX ?: continue
                val nry = nb.irisRelY ?: continue
                if (hypot(nrx - relX, nry - relY) < 0.07) {
                    if (j < i) past++ else future++
                }
            }
            if (past > 0 && future > 0) {
                output[i] = frame.copy(
                    imageX = cx,
                    imageY = cy,
                    measurementX = cx,
                    measurementY = cy,
                    irisRelX = relX,
                    irisRelY = relY,
                    source = LabGlintSource.WEAK,
                    confidence = min(0.55, max(0.25, candConf * 1.3)),
                    observedDiameterPx = frame.candidateDiameterPx,
                    temporalSupportFrames = past + future,
                )
            }
        }
        return output
    }

    private data class CodeValidation(
        val trainR: Double? = null,
        val heldOutR: Double? = null,
        val maxWrongR: Double? = null,
        val lagSec: Double? = null,
        val verified: Boolean = false,
        val shiftNulls: List<Double> = emptyList(),
    )

    private fun validateCode(
        estimates: List<LabGlintFrameEstimate>,
        events: List<LabDisplayEvent>,
        startSec: Double,
        durationSec: Double,
        chipSec: Double,
        scheme: LabCodingScheme,
    ): CodeValidation {
        if (!scheme.usesTemporalCode || events.isEmpty()) return CodeValidation()
        fun lookupCode(timeSec: Double, shift: Int = 0): Double {
            var low = 0
            var high = events.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (events[mid].hostTimeSec <= timeSec) low = mid + 1 else high = mid
            }
            if (low <= 0) return 0.0
            val ev = events[low - 1]
            if (timeSec - ev.hostTimeSec > chipSec + 0.15) return 0.0
            return if (shift == 0) ev.code else StimulusCodeV8.codeValue(ev.elapsedSec, chipSec, scheme, shift)
        }
        val half = durationSec * 0.5
        val train = estimates.filter { (it.hostTimeSec - startSec) < half && it.signal != null }
        val test = estimates.filter { (it.hostTimeSec - startSec) >= half && it.signal != null }
        if (train.size < 16 || test.size < 16) return CodeValidation()

        var bestR = Double.NEGATIVE_INFINITY
        var bestLag = 0.0
        for (step in -3..9) {
            val delay = step / 30.0
            val r = pearson(train.map { it.signal!! }, train.map { lookupCode(it.hostTimeSec - delay, 0) })
            if (r != null && r > bestR) {
                bestR = r
                bestLag = delay
            }
        }
        if (!bestR.isFinite()) return CodeValidation()
        val heldOut = pearson(test.map { it.signal!! }, test.map { lookupCode(it.hostTimeSec - bestLag, 0) })
        val nulls = (1 until 64).mapNotNull { shift ->
            pearson(test.map { it.signal!! }, test.map { lookupCode(it.hostTimeSec - bestLag, shift) })
        }
        val maxWrong = nulls.maxOfOrNull { abs(it) } ?: 1.0
        val verified = bestR >= 0.35 && (heldOut ?: -1.0) >= 0.30 && (heldOut ?: -1.0) > maxWrong + 0.15
        return CodeValidation(
            trainR = bestR,
            heldOutR = heldOut,
            maxWrongR = maxWrong,
            lagSec = bestLag,
            verified = verified,
            shiftNulls = nulls,
        )
    }

    private fun buildWaveform(
        estimates: List<LabGlintFrameEstimate>,
        events: List<LabDisplayEvent>,
        startSec: Double,
        lagSec: Double,
        chipSec: Double,
        scheme: LabCodingScheme,
    ): List<LabWaveformPoint> {
        if (estimates.isEmpty()) return emptyList()
        val stride = max(1, estimates.size / 180)
        return estimates.filterIndexed { i, _ -> i % stride == 0 }.map { e ->
            val tAligned = e.hostTimeSec - lagSec
            val code = events.lastOrNull { it.hostTimeSec <= tAligned }?.code
                ?: StimulusCodeV8.codeValue((tAligned - startSec).coerceAtLeast(0.0), chipSec, scheme)
            LabWaveformPoint(
                tSec = (e.hostTimeSec - startSec).coerceAtLeast(0.0),
                signal = e.independentSignal ?: e.signal ?: 0.0,
                alignedCode = code,
                phase = e.emissionPhase,
            )
        }
    }

    fun pearson(x: List<Double>, y: List<Double>): Double? {
        if (x.size != y.size || x.size < 8) return null
        var mx = 0.0
        var my = 0.0
        for (i in x.indices) {
            if (!x[i].isFinite() || !y[i].isFinite()) return null
            mx += x[i]
            my += y[i]
        }
        mx /= x.size
        my /= y.size
        var xx = 0.0
        var yy = 0.0
        var xy = 0.0
        for (i in x.indices) {
            val a = x[i] - mx
            val b = y[i] - my
            xx += a * a
            yy += b * b
            xy += a * b
        }
        val denom = sqrt(xx * yy)
        return if (denom > 1e-9) xy / denom else null
    }
}

internal fun median(values: List<Double>): Double? {
    val sorted = values.filter { it.isFinite() }.sorted()
    if (sorted.isEmpty()) return null
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) * 0.5 else sorted[mid]
}
