package com.zkyjss.eyeproject.strabismuslab

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

@Serializable
enum class LabStage(val title: String, val subtitle: String) {
    IDLE("就绪", "调整距离与光照，确认双眼追踪稳定"),
    NEAR_FIXATION("看近光码映光", "距离 33 cm，注视顶部编码白点"),
    FAR_FIXATION("看远光码映光", "手机保持 33 cm，越过屏幕注视 6 m 远目标"),
    COVER_TEST("动态交替遮盖", "按节拍遮盖并移开，记录去遮盖再注视"),
    PURSUIT("平滑追踪", "头部不动，双眼跟随目标轨迹"),
    COMPLETED("采集完成", "已完成 v0.8 双向光码解调与量化分析");

    val usesOpticalStimulus: Boolean
        get() = this == NEAR_FIXATION || this == FAR_FIXATION || this == COVER_TEST
}

@Serializable
enum class LabRiskGrade(val label: String) {
    NORMAL("未见显著偏斜"),
    SUSPECT("可疑眼位异常"),
    HIGH_RISK("高度疑似斜视"),
    INCONCLUSIVE("证据不足 / 建议复测");
}

@Serializable
data class LabProtocolConfig(
    val subjectId: String = "SUBJ-001",
    val glassesState: String = "裸眼",
    val lightingCondition: String = "室内自然光",
    val clinicalNote: String = "",
    val codingScheme: LabCodingScheme = LabCodingScheme.M_SEQUENCE,
    /** 默认放宽至 0.80f 虹膜半径，覆盖大角度斜视映光。 */
    val searchRadiusFraction: Float = 0.80f,
    val chipDurationMs: Long = 250L,
    val stimulusDiameterMm: Double = 34.0,
    val nearDurationSec: Int = 16,
    val farDurationSec: Int = 12,
    val pursuitDurationSec: Int = 18,
    val includeNear: Boolean = true,
    val includeFar: Boolean = true,
    val includeCover: Boolean = true,
    val includePursuit: Boolean = true,
) {
    val activeStages: List<LabStage>
        get() = buildList {
            if (includeNear) add(LabStage.NEAR_FIXATION)
            if (includeFar) add(LabStage.FAR_FIXATION)
            if (includeCover) add(LabStage.COVER_TEST)
            if (includePursuit) add(LabStage.PURSUIT)
        }
}

@Serializable
data class LabCandidateSummary(
    val x: Float,
    val y: Float,
    val contrast: Float,
    val snr: Float,
    val diameterPx: Float,
    val quality: Float,
)

@Serializable
data class LabFrameLog(
    val index: Int,
    val timestampMs: Long,
    val elapsedSec: Double,
    val stage: LabStage,
    val valid: Boolean,
    val rejectionReason: String? = null,
    val distanceCm: Float,
    val fps: Float,
    val yawDeg: Float? = null,
    val pitchDeg: Float? = null,
    val rollDeg: Float? = null,
    val stimulusIntensity: Double = 0.0,
    val stimulusCode: Double = 0.0,
    val targetNormX: Float? = null,
    val targetNormY: Float? = null,
    val leftIrisX: Float? = null,
    val leftIrisY: Float? = null,
    val leftIrisDiameterPx: Float? = null,
    val leftEar: Float? = null,
    val leftVisibilityContrast: Float? = null,
    val leftGlintX: Float? = null,
    val leftGlintY: Float? = null,
    val leftGlintConfidence: Float? = null,
    val leftGlintSource: LabGlintSource = LabGlintSource.UNAVAILABLE,
    val leftEmissionPhase: LabEmissionPhase = LabEmissionPhase.UNKNOWN,
    val leftRelX: Float? = null,
    val leftRelY: Float? = null,
    val rightIrisX: Float? = null,
    val rightIrisY: Float? = null,
    val rightIrisDiameterPx: Float? = null,
    val rightEar: Float? = null,
    val rightVisibilityContrast: Float? = null,
    val rightGlintX: Float? = null,
    val rightGlintY: Float? = null,
    val rightGlintConfidence: Float? = null,
    val rightGlintSource: LabGlintSource = LabGlintSource.UNAVAILABLE,
    val rightEmissionPhase: LabEmissionPhase = LabEmissionPhase.UNKNOWN,
    val rightRelX: Float? = null,
    val rightRelY: Float? = null,
    val hdePrism: Double? = null,
    val vdePrism: Double? = null,
    val coveredEye: String? = null,
    val leftCandidateCount: Int = 0,
    val rightCandidateCount: Int = 0,
)

@Serializable
data class LabAlignmentMetrics(
    val stage: LabStage,
    val hdePrism: Double? = null,
    val vdePrism: Double? = null,
    val hdeSdPrism: Double? = null,
    val vdeSdPrism: Double? = null,
    val hdeCi95HalfPrism: Double? = null,
    val vdeCi95HalfPrism: Double? = null,
    val validBinocularFrames: Int = 0,
    val totalStageFrames: Int = 0,
    val leftCodeVerified: Boolean = false,
    val rightCodeVerified: Boolean = false,
)

@Serializable
data class LabCoverRefixation(
    val index: Int,
    val eye: String,
    val label: String,
    val source: String,
    val uncoveredAtSec: Double,
    val amplitudePrism: Double,
    val speedDegPerSec: Double,
    val residualPrism: Double,
    val codeConfirmed: Boolean,
)

@Serializable
data class LabPursuitMetrics(
    val rHorizontal: Double? = null,
    val rVertical: Double? = null,
    val dNorm: Double? = null,
    val bsi: Double? = null,
    val followedSegments: Int = 0,
    val sampleCount: Int = 0,
)

@Serializable
data class LabGlintScatterPoint(
    val relX: Float,
    val relY: Float,
    val confidence: Float,
    val stage: LabStage,
    val source: LabGlintSource,
)

@Serializable
data class LabSessionReport(
    val sessionId: String,
    val createdAtMs: Long,
    val algorithmVersion: String = "strabismus-lab-v0.8-r080",
    val platform: String = "Android",
    val deviceModel: String = "",
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val medianFps: Double = 0.0,
    val durationSec: Double = 0.0,
    val config: LabProtocolConfig,
    val riskGrade: LabRiskGrade,
    val confidenceScore: Double,
    val headline: String,
    val summaryNotes: List<String>,
    val nearMetrics: LabAlignmentMetrics? = null,
    val farMetrics: LabAlignmentMetrics? = null,
    val coverRefixations: List<LabCoverRefixation> = emptyList(),
    val coverMedianPrism: Double? = null,
    val coverNoiseFloorPrism: Double? = null,
    val pursuitMetrics: LabPursuitMetrics? = null,
    val nearLeftCode: CornealCodeV8Result? = null,
    val nearRightCode: CornealCodeV8Result? = null,
    val farLeftCode: CornealCodeV8Result? = null,
    val farRightCode: CornealCodeV8Result? = null,
    val leftScatter: List<LabGlintScatterPoint> = emptyList(),
    val rightScatter: List<LabGlintScatterPoint> = emptyList(),
    val rejectionCounts: Map<String, Int> = emptyMap(),
    val totalFrames: Int = 0,
    val validFrames: Int = 0,
    val frames: List<LabFrameLog> = emptyList(),
) {
    fun toJson(pretty: Boolean = true): String {
        val codec = Json { prettyPrint = pretty; encodeDefaults = true; ignoreUnknownKeys = true }
        return codec.encodeToString(this)
    }

    fun toFramesJsonl(): String {
        val compact = Json { prettyPrint = false; encodeDefaults = true }
        return buildString {
            frames.forEach { frame ->
                append(compact.encodeToString(frame))
                append('\n')
            }
        }
    }

    fun toFramesCsv(): String = buildString {
        appendLine(
            "index,timestamp_ms,elapsed_s,stage,valid,rejection,distance_cm,fps,yaw_deg,pitch_deg,roll_deg," +
                "stim_intensity,stim_code,target_x,target_y," +
                "l_iris_px,l_ear,l_vis_contrast,l_glint_x,l_glint_y,l_rel_x,l_rel_y,l_conf,l_source,l_phase," +
                "r_iris_px,r_ear,r_vis_contrast,r_glint_x,r_glint_y,r_rel_x,r_rel_y,r_conf,r_source,r_phase," +
                "hde_prism,vde_prism,covered_eye,l_cands,r_cands",
        )
        for (f in frames) {
            append(f.index).append(',')
            append(f.timestampMs).append(',')
            append(fmt3(f.elapsedSec)).append(',')
            append(f.stage.name).append(',')
            append(f.valid).append(',')
            append('"').append((f.rejectionReason ?: "").replace("\"", "'")).append('"').append(',')
            append(fmt2(f.distanceCm.toDouble())).append(',')
            append(fmt1(f.fps.toDouble())).append(',')
            append(f.yawDeg?.let { fmt1(it.toDouble()) } ?: "").append(',')
            append(f.pitchDeg?.let { fmt1(it.toDouble()) } ?: "").append(',')
            append(f.rollDeg?.let { fmt1(it.toDouble()) } ?: "").append(',')
            append(fmt3(f.stimulusIntensity)).append(',')
            append(fmt2(f.stimulusCode)).append(',')
            append(f.targetNormX?.let { fmt3(it.toDouble()) } ?: "").append(',')
            append(f.targetNormY?.let { fmt3(it.toDouble()) } ?: "").append(',')
            append(f.leftIrisDiameterPx?.let { fmt2(it.toDouble()) } ?: "").append(',')
            append(f.leftEar?.let { fmt3(it.toDouble()) } ?: "").append(',')
            append(f.leftVisibilityContrast?.let { fmt1(it.toDouble()) } ?: "").append(',')
            append(f.leftGlintX?.let { fmt2(it.toDouble()) } ?: "").append(',')
            append(f.leftGlintY?.let { fmt2(it.toDouble()) } ?: "").append(',')
            append(f.leftRelX?.let { fmt4(it.toDouble()) } ?: "").append(',')
            append(f.leftRelY?.let { fmt4(it.toDouble()) } ?: "").append(',')
            append(f.leftGlintConfidence?.let { fmt3(it.toDouble()) } ?: "").append(',')
            append(f.leftGlintSource.name).append(',')
            append(f.leftEmissionPhase.name).append(',')
            append(f.rightIrisDiameterPx?.let { fmt2(it.toDouble()) } ?: "").append(',')
            append(f.rightEar?.let { fmt3(it.toDouble()) } ?: "").append(',')
            append(f.rightVisibilityContrast?.let { fmt1(it.toDouble()) } ?: "").append(',')
            append(f.rightGlintX?.let { fmt2(it.toDouble()) } ?: "").append(',')
            append(f.rightGlintY?.let { fmt2(it.toDouble()) } ?: "").append(',')
            append(f.rightRelX?.let { fmt4(it.toDouble()) } ?: "").append(',')
            append(f.rightRelY?.let { fmt4(it.toDouble()) } ?: "").append(',')
            append(f.rightGlintConfidence?.let { fmt3(it.toDouble()) } ?: "").append(',')
            append(f.rightGlintSource.name).append(',')
            append(f.rightEmissionPhase.name).append(',')
            append(f.hdePrism?.let { fmt2(it) } ?: "").append(',')
            append(f.vdePrism?.let { fmt2(it) } ?: "").append(',')
            append(f.coveredEye ?: "").append(',')
            append(f.leftCandidateCount).append(',')
            append(f.rightCandidateCount).append('\n')
        }
    }

    companion object {
        private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(json: String): LabSessionReport = codec.decodeFromString(json)
    }
}

internal fun fmt1(v: Double): String = ((v * 10.0).roundToInt() / 10.0).toString()
internal fun fmt2(v: Double): String = ((v * 100.0).roundToInt() / 100.0).toString()
internal fun fmt3(v: Double): String = ((v * 1000.0).roundToInt() / 1000.0).toString()
internal fun fmt4(v: Double): String = ((v * 10000.0).roundToInt() / 10000.0).toString()
