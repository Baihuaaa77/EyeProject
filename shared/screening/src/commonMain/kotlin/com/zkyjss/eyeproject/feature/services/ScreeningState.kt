package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.model.HeadPoseEstimate

enum class ScreeningKind { PRESBYOPIA, STRABISMUS }
enum class ScreeningStep { PROFILE, ENVIRONMENT, NEAR_VISION, PUSH_UP, DISTANCE_CURVE, FLIPPER, GAZE_CALIBRATION, FIXATION, COVER, PURSUIT, RESULT }
enum class VisionEye(val label: String) { RIGHT("右眼"), LEFT("左眼"), BOTH("双眼") }
enum class PushStage { READY, APPROACH }

data class EyeMotionSample(
    val timeMs: Long,
    val step: ScreeningStep,
    val leftX: Float,
    val leftY: Float,
    val rightX: Float,
    val rightY: Float,
    val distanceCm: Float,
    val targetX: Float = 0f,
    val targetY: Float = 0f,
    val leftOpticalX: Float? = null,
    val leftOpticalY: Float? = null,
    val rightOpticalX: Float? = null,
    val rightOpticalY: Float? = null,
    val headYawDeg: Float? = null,
    val headPitchDeg: Float? = null,
    val headRollDeg: Float? = null,
    val leftPupilX: Float? = null,
    val leftPupilY: Float? = null,
    val leftReflexX: Float? = null,
    val leftReflexY: Float? = null,
    val rightPupilX: Float? = null,
    val rightPupilY: Float? = null,
    val rightReflexX: Float? = null,
    val rightReflexY: Float? = null,
    /** 映光点相对瞳孔中心的鼻侧偏心（虹膜直径单位），正值偏鼻侧。 */
    val leftNasal: Float? = null,
    val rightNasal: Float? = null,
    /** 映光点相对瞳孔中心的垂直偏心（虹膜直径单位）。 */
    val leftVertical: Float? = null,
    val rightVertical: Float? = null,
    /** 追踪段：0 水平 / 1 垂直 / 2 斜向；其他任务为 -1。 */
    val pursuitSegment: Int = -1,
    val targetScreenX: Float? = null,
    val targetScreenY: Float? = null,
    val leftGazeX: Float? = null,
    val leftGazeY: Float? = null,
    val rightGazeX: Float? = null,
    val rightGazeY: Float? = null,
    val gazeOnTarget: Boolean? = null,
)

data class VisionAnswer(
    val step: ScreeningStep,
    val distanceCm: Float,
    val level: Float,
    val expectedDirection: Int,
    val selectedDirection: Int,
    val correct: Boolean,
    val latencyMs: Long,
    val optotypePx: Float,
    val eye: VisionEye = VisionEye.BOTH,
    val presentedAtMs: Long = 0L,
    val movementMs: Long? = null,
    val clarityMs: Long? = null,
    val distanceSpreadCm: Float = 0f,
    val answerDistanceCm: Float = distanceCm,
    /** 多距离任务的目标距离。 */
    val targetCm: Float? = null,
    /** true 表示语音作答（反应时包含语音识别延迟）。 */
    val byVoice: Boolean = false,
)
/** 一次移近法测量：确认持续模糊时的距离与当时的移动速度（cm/秒，靠近为正）。 */
data class NearPoint(
    val distanceCm: Float,
    val speedCmPerSec: Float? = null,
    val startCm: Float? = null,
    val censored: Boolean = false,
    /** 本次移近的距离轨迹：(相对开始移近的秒数, 平滑距离 cm)，约每 100 ms 一点，用于离线评估反应延迟。 */
    val trace: List<Pair<Float, Float>> = emptyList(),
)

/** 距离—视觉表现曲线上的一点：该距离下双眼通过的最高小数视力。 */
data class CurvePoint(val distanceCm: Float, val thresholdDecimal: Float?, val resolutionLimited: Boolean = false)
data class CoverMark(val label: String, val timeMs: Long, val uncovered: VisionEye? = null, val covered: VisionEye? = null)

data class ScreeningState(
    val kind: ScreeningKind = ScreeningKind.PRESBYOPIA,
    val step: ScreeningStep = ScreeningStep.PROFILE,
    val guidePending: Boolean = false,
    val fields: Map<String, String> = emptyMap(),
    /** 用户选择先跳过问卷；记录中注明，依赖问卷的评估（如同龄对照）会说明无法评估。 */
    val profileSkipped: Boolean = false,
    val mode: String = "",
    val faceDetected: Boolean = false,
    val distanceCm: Float = 0f,
    val lux: Float? = null,
    val lightSensorAvailable: Boolean? = null,
    val cameraBrightness: Float? = null,
    val cameraFrames: Int = 0,
    val trackingStatus: String = "等待相机启动",
    val opticalStatus: String = "等待双眼光学测量",
    val opticalValidFrames: Int = 0,
    val opticalRejectionCounts: Map<String, Int> = emptyMap(),
    val leftOpticalQuality: String = "等待眼区像素",
    val rightOpticalQuality: String = "等待眼区像素",
    val leftOpticalX: Float? = null,
    val leftOpticalY: Float? = null,
    val rightOpticalX: Float? = null,
    val rightOpticalY: Float? = null,
    val headPose: HeadPoseEstimate? = null,
    val frameCount: Int = 0,
    val acceptedFrames: Int = 0,
    val secondsLeft: Int = 0,
    val instruction: String = "",
    val direction: Int = 0,
    val visionLevel: Float = 0.5f,
    val trialIndex: Int = 0,
    val visionEye: VisionEye = VisionEye.RIGHT,
    val nearAwaitingEyeReady: Boolean = false,
    val nearDescending: Boolean = false,
    val nearStopReason: String? = null,
    val nearResolutionLimitedEyes: Set<VisionEye> = emptySet(),
    val trialVisible: Boolean = false,
    val trialId: Int = 0,
    val trialOptotypePx: Float = 0f,
    val trialDistanceCm: Float = 0f,
    val trialMovementMs: Long? = null,
    val trialDistanceSpreadCm: Float = 0f,
    val pushStage: PushStage = PushStage.READY,
    val pushSpeedCmPerSec: Float? = null,
    val pushStartCm: Float? = null,
    val nearPoints: List<NearPoint> = emptyList(),
    val curveIndex: Int = 0,
    val curvePoints: List<CurvePoint> = emptyList(),
    val pursuitMaxDeg: Pair<Float, Float>? = null,
    val history: List<String> = emptyList(),
    val invalidTrialCount: Int = 0,
    val answers: List<VisionAnswer> = emptyList(),
    val coverMarks: List<CoverMark> = emptyList(),
    /** 刚执行的遮盖动作仍在保持期内；保持结束时给震动与音效，并只播报下一动作。 */
    val coverHolding: Boolean = false,
    val targetX: Float = 0f,
    val targetY: Float = 0f,
    val gazeCalibrated: Boolean = false,
    val gazeCalibrationIndex: Int = 0,
    /** 第几轮校准（首轮 0，补测 1）；界面据此在同一点重看时也重新上报位置、重播换点动效。 */
    val gazeCalibrationRound: Int = 0,
    val gazeCalibrationFailed: Boolean = false,
    val gazeWarning: String? = null,
    val warning: String? = null,
    val result: List<String> = emptyList(),
    val riskLevel: ScreeningRiskLevel = ScreeningRiskLevel.UNKNOWN,
    val riskScope: String = "",
    /** 结构化评估结果：报告页据此排版（结论、关键指标、需要关注）。 */
    val category: String = "",
    val findings: List<String> = emptyList(),
    val limitations: List<String> = emptyList(),
    val features: Map<String, Double?> = emptyMap(),
    /** 判定依据（证据族），见 [Evidence]。 */
    val evidence: List<Evidence> = emptyList(),
    val savedFile: String? = null,
)
