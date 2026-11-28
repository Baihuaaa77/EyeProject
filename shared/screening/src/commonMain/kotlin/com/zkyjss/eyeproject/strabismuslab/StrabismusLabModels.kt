package com.zkyjss.eyeproject.strabismuslab

import com.zkyjss.eyeproject.core.model.Point2f
import com.zkyjss.eyeproject.core.model.ReflexCandidate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

@Serializable
enum class LabStage(val title: String, val subtitle: String) {
    IDLE("就绪", "调整距离与光照，确认双眼追踪稳定"),
    NEAR_FIXATION("看近光码映光", "距离 33 cm，注视顶部编码白点"),
    FAR_FIXATION("看远光码映光", "手机保持 33 cm，越过屏幕注视 6 m 远目标"),
    COVER_TEST("动态交替遮盖", "白点常亮防盲区，按节拍遮盖并记录显斜/隐斜再注视"),
    PURSUIT("平滑追踪", "头部不动，双眼平滑跟随目标轨迹"),
    COMPLETED("采集完成", "已完成 v0.9 3D 球面光学反演与遮盖动力学分析");

    val usesOpticalStimulus: Boolean
        get() = this == NEAR_FIXATION || this == FAR_FIXATION || this == COVER_TEST

    /** 遮盖阶段强制白点常亮，避免 250ms 暗码元吞掉 150–400ms 的去遮盖再注视扫视。 */
    val forcesSteadyStimulus: Boolean
        get() = this == COVER_TEST
}

@Serializable
enum class LabRiskGrade(val label: String) {
    NORMAL("未见显著偏斜"),
    SUSPECT("可疑眼位异常"),
    HIGH_RISK("高度疑似斜视"),
    INCONCLUSIVE("证据不足 / 建议复测");
}

@Serializable
data class LabCameraIntrinsics(
    val fx: Double = 0.0,
    val fy: Double = 0.0,
    val cx: Double = 0.0,
    val cy: Double = 0.0,
    /** 屏幕白点中心相对前置相机光学中心的物理偏移（mm，相机坐标系：+x 向右，+y 向下）。 */
    val lightOffsetXMm: Double = 0.0,
    val lightOffsetYMm: Double = 22.0,
) {
    val isValid: Boolean get() = fx > 10.0 && fy > 10.0
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
    /** 与双端 140pt/dp 绘制上限严格对齐的真实白点物理直径（mm，约 22.0mm）。 */
    val stimulusDiameterMm: Double = 22.0,
    val nearDurationSec: Int = 16,
    val farDurationSec: Int = 12,
    val pursuitDurationSec: Int = 18,
    val includeNear: Boolean = true,
    val includeFar: Boolean = true,
    val includeCover: Boolean = true,
    val includePursuit: Boolean = true,
    /** 屏幕白点中心相对前摄镜头中心的物理偏移（mm），用于 3D 球面光学离轴补偿。 */
    val lightOffsetXMm: Double = 0.0,
    val lightOffsetYMm: Double = 22.0,
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
enum class LabSessionStatus(val label: String) {
    COMPLETED("正常完成"),
    STOPPED_EARLY("提前结束"),
    CANCELLED("中途取消"),
    ERROR("异常中断");
}

@Serializable
data class LabCandidateSummary(
    val x: Float,
    val y: Float,
    val contrast: Float,
    val snr: Float,
    val diameterPx: Float,
    val quality: Float,
    val brightness: Float = 0f,
    val shapeScore: Float = 0f,
) {
    fun toReflexCandidate(): ReflexCandidate = ReflexCandidate(
        point = Point2f(x, y),
        contrast = contrast,
        brightness = brightness,
        quality = quality,
        snr = snr,
        diameterPx = diameterPx,
        shapeScore = shapeScore,
    )
}

@Serializable
data class LabDisplayEventRecord(
    val stage: LabStage,
    val hostTimeSec: Double,
    val callbackHostTimeSec: Double,
    val intensity: Double,
    val code: Double,
    val chip: Int,
    val elapsedSec: Double,
    val screenBrightness: Double = 1.0,
) {
    fun toDisplayEvent(): LabDisplayEvent = LabDisplayEvent(
        hostTimeSec = hostTimeSec,
        callbackHostTimeSec = callbackHostTimeSec,
        intensity = intensity,
        code = code,
        chip = chip,
        elapsedSec = elapsedSec,
        screenBrightness = screenBrightness,
    )
}

/** 单帧硬件采集上下文，由双端相机管线注入，用于精确审计曝光、时钟与丢帧。 */
@Serializable
data class LabFrameCaptureMeta(
    /** 实测相机帧曝光时长（秒）；无法获取时为 null，引擎将回退 FPS 近似并在 exposureSource 明确标记。 */
    val exposureSec: Double? = null,
    val exposureSource: String = "FPS_APPROX",
    /** 相机源单调时间戳（ms，与分析回调 nowMs 分开记录）。 */
    val sourceTimestampMs: Long? = null,
    val intrinsicsSource: String = "UNKNOWN",
    val mirrored: Boolean = false,
    val cropNote: String = "",
    val exposureLocked: Boolean = false,
    /** 上一已分析帧到本帧之间因背压/节流丢弃的相机帧数。 */
    val droppedSincePrev: Int = 0,
)

/** 会话级冻结可追溯与复算审计元信息。 */
@Serializable
data class LabCaptureAuditMeta(
    val protocolVersion: String = "v0.9.5-reproducible",
    val appVersion: String = "",
    val gitCommit: String = "",
    val clockDomain: String = "unknown",
    val displayClockDomain: String = "unknown",
    val intrinsicsSource: String = "UNKNOWN",
    val mirrored: Boolean = false,
    val cropNote: String = "",
    val exposureSource: String = "FPS_APPROX",
    val medianExposureMs: Double? = null,
    val exposureLockedStages: List<String> = emptyList(),
    val droppedFramesCount: Int = 0,
    val lightOffsetCalibrationSource: String = "CONFIG_DEFAULT",
    val stageStartTimesSec: Map<String, Double> = emptyMap(),
    val stageDurationsSec: Map<String, Double> = emptyMap(),
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
    /** 旧版线性 175.5Δ/D 估算（保留作消融基线）。 */
    val hdePrism: Double? = null,
    val vdePrism: Double? = null,
    /** v0.9 球面角膜镜 3D 光学反演 + Roll 旋正后的不对称水平/垂直偏斜（Δ）。 */
    val hde3dPrism: Double? = null,
    val vde3dPrism: Double? = null,
    /** v0.9 3D 聚散偏斜分量（Δ，对共轭偏视与横向离轴免疫）。 */
    val hdeVergence3dPrism: Double? = null,
    val rollCorrectionDeg: Double? = null,
    /** 归一化虹膜相对眼角坐标（遮盖阶段辅助轨迹与平滑追踪共用）。 */
    val leftNormX: Float? = null,
    val leftNormY: Float? = null,
    val rightNormX: Float? = null,
    val rightNormY: Float? = null,
    val coveredEye: String? = null,
    val leftCandidateCount: Int = 0,
    val rightCandidateCount: Int = 0,
    /** 完整候选映光列表（支持归档后纯离线重跑 0.60R/0.80R 与光码/常亮配对消融）。 */
    val leftCandidates: List<LabCandidateSummary> = emptyList(),
    val rightCandidates: List<LabCandidateSummary> = emptyList(),
    /** 相机源单调时间戳（ms）。 */
    val sourceTimestampMs: Long? = null,
    /** 本帧曝光时长（秒）及来源标记。 */
    val exposureSec: Double? = null,
    val exposureSource: String = "FPS_APPROX",
)

@Serializable
data class LabAlignmentMetrics(
    val stage: LabStage,
    /** 旧版线性 175.5 估算（Δ，作消融对照）。 */
    val hdePrism: Double? = null,
    val vdePrism: Double? = null,
    val hdeSdPrism: Double? = null,
    val vdeSdPrism: Double? = null,
    val hdeCi95HalfPrism: Double? = null,
    val vdeCi95HalfPrism: Double? = null,
    /** v0.9 3D 球面角膜镜光学反演主指标（Δ）。 */
    val hde3dPrism: Double? = null,
    val vde3dPrism: Double? = null,
    val hde3dSdPrism: Double? = null,
    val vde3dSdPrism: Double? = null,
    val hde3dCi95HalfPrism: Double? = null,
    val vde3dCi95HalfPrism: Double? = null,
    val hdeVergence3dPrism: Double? = null,
    val stableOnOnly: Boolean = false,
    val validBinocularFrames: Int = 0,
    val totalStageFrames: Int = 0,
    val leftCodeVerified: Boolean = false,
    val rightCodeVerified: Boolean = false,
)

@Serializable
data class LabRefixationTracePoint(
    /** 相对精确回溯拐点的时间偏移（ms，包含少量负值前基线）。 */
    val dtMs: Long,
    /** 相对起始位置的位移幅度（Δ）。 */
    val displacementPrism: Double,
    /** 瞬时角速度（°/s）。 */
    val velocityDegPerSec: Double,
    /** 该点是否来自角膜映光（false 表示由虹膜相对眼角轨迹补齐）。 */
    val fromGlint: Boolean = true,
)

@Serializable
data class LabCoverRefixation(
    val index: Int,
    val eye: String,
    val label: String,
    val source: String,
    /** 事件类型："COVER_CONTRALATERAL"（遮盖瞬间对侧眼，判显斜 Tropia）或 "UNCOVER_IPSILATERAL"（去遮盖同侧眼，判隐斜/IXT）。 */
    val eventType: String = "UNCOVER_IPSILATERAL",
    /** 精确回溯后的事件起始时刻（秒，相对会话起点）。 */
    val uncoveredAtSec: Double,
    /** 相对 CoverDetector 实时时间戳向前回溯修正的毫秒数（正值表示提前锁定首帧）。 */
    val onsetShiftMs: Long = 0L,
    val amplitudePrism: Double,
    /** 峰值角速度（°/s，30Hz 采样带宽下表观峰值）。 */
    val speedDegPerSec: Double,
    /** 扫视潜伏期（ms，从拐点到超过 15% 幅度）。 */
    val latencyMs: Long? = null,
    /** 复位耗时（ms，从移动开始到达到 90% 稳态）。 */
    val recoveryDurationMs: Long? = null,
    val residualPrism: Double,
    val codeConfirmed: Boolean,
    val trace: List<LabRefixationTracePoint> = emptyList(),
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
    val schemaVersion: Int = 2,
    val algorithmVersion: String = "strabismus-lab-v0.9.5-reproducible",
    val sessionStatus: LabSessionStatus = LabSessionStatus.COMPLETED,
    val terminationReason: String? = null,
    val platform: String = "Android",
    val deviceModel: String = "",
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val cameraIntrinsics: LabCameraIntrinsics? = null,
    val auditMeta: LabCaptureAuditMeta = LabCaptureAuditMeta(),
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
    val coverTropiaMedianPrism: Double? = null,
    val coverPhoriaMedianPrism: Double? = null,
    val coverClassification: String? = null,
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
    val displayEvents: List<LabDisplayEventRecord> = emptyList(),
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

    fun toDisplayEventsJsonl(): String {
        val compact = Json { prettyPrint = false; encodeDefaults = true }
        return buildString {
            displayEvents.forEach { ev ->
                append(compact.encodeToString(ev))
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
                "hde_prism,vde_prism,hde_3d_prism,vde_3d_prism,hde_verg_3d_prism,roll_corr_deg,covered_eye,l_cands,r_cands," +
                "source_ts_ms,exposure_ms,exposure_source",
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
            append(f.stimulusCode.let { fmt2(it) }).append(',')
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
            append(f.hde3dPrism?.let { fmt2(it) } ?: "").append(',')
            append(f.vde3dPrism?.let { fmt2(it) } ?: "").append(',')
            append(f.hdeVergence3dPrism?.let { fmt2(it) } ?: "").append(',')
            append(f.rollCorrectionDeg?.let { fmt2(it) } ?: "").append(',')
            append(f.coveredEye ?: "").append(',')
            append(f.leftCandidateCount).append(',')
            append(f.rightCandidateCount).append(',')
            append(f.sourceTimestampMs?.toString() ?: "").append(',')
            append(f.exposureSec?.let { fmt3(it * 1000.0) } ?: "").append(',')
            append(f.exposureSource).append('\n')
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
