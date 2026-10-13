package com.zkyjss.eyeproject.feature.services

import kotlin.math.abs
import kotlin.math.sqrt

/** 屏幕归一化坐标，允许越界；只用于宽容的任务监督，不作为眼位诊断量。 */
internal data class GazePoint(val x: Float, val y: Float)

internal data class GazeCalibrationSample(
    val index: Int, val target: GazePoint, val left: GazePoint?, val right: GazePoint?,
)

/** 每只眼独立校准，允许只有一只眼可靠跟随，保留另一只眼的异常供原评估使用。 */
internal class StrabismusGaze private constructor(private val left: Mapping?, private val right: Mapping?) {
    private data class Axis(val slope: Float, val intercept: Float) {
        fun estimate(value: Float) = slope * value + intercept
    }
    private data class Mapping(val x: Axis, val y: Axis) {
        fun estimate(point: GazePoint) = GazePoint(x.estimate(point.x), y.estimate(point.y))
    }

    fun estimate(leftEye: GazePoint?, rightEye: GazePoint?): Pair<GazePoint?, GazePoint?> =
        leftEye?.let { left?.estimate(it) } to rightEye?.let { right?.estimate(it) }

    companion object {
        // 点位与正式任务共用屏幕坐标；约 9 秒，等待眼动稳定后采样。
        val calibrationTargets = listOf(
            GazePoint(0.5f, 0.5f), GazePoint(0.15f, 0.5f), GazePoint(0.85f, 0.5f),
            GazePoint(0.5f, 0.15f), GazePoint(0.5f, 0.85f),
        )
        const val CALIBRATION_POINT_MS = 1_800L
        const val SETTLE_MS = 600L
        const val TOLERANCE_X = 0.25f
        const val TOLERANCE_Y = 0.20f

        fun calibrate(samples: List<GazeCalibrationSample>): StrabismusGaze? {
            fun mapping(useLeft: Boolean): Mapping? {
                val points = calibrationTargets.indices.map { index ->
                    val group = samples.filter { it.index == index && (if (useLeft) it.left else it.right) != null }
                    if (group.size < 4) return null
                    fun median(values: List<Float>) = values.sorted().let { it[it.size / 2] }
                    val eyes = group.map { (if (useLeft) it.left else it.right)!! }
                    GazePoint(median(eyes.map { it.x }), median(eyes.map { it.y })) to
                        GazePoint(median(group.map { it.target.x }), median(group.map { it.target.y }))
                }
                fun fit(horizontal: Boolean): Axis? {
                    val raw = points.map { (if (horizontal) it.first.x else it.first.y).toDouble() }
                    val target = points.map { (if (horizontal) it.second.x else it.second.y).toDouble() }
                    if (raw.any { !it.isFinite() } || target.any { !it.isFinite() } ||
                        raw.max() - raw.min() < (if (horizontal) 0.006 else 0.004)) return null
                    if (abs(correlation(raw, target) ?: return null) < 0.75) return null
                    val mr = raw.average(); val mt = target.average()
                    val slope = raw.indices.sumOf { (raw[it] - mr) * (target[it] - mt) } /
                        raw.sumOf { (it - mr) * (it - mr) }
                    val intercept = mt - slope * mr
                    val error = sqrt(raw.indices.map { val d = slope * raw[it] + intercept - target[it]; d * d }.average())
                    if (error > 0.10) return null
                    return Axis(slope.toFloat(), intercept.toFloat())
                }
                return Mapping(fit(true) ?: return null, fit(false) ?: return null)
            }
            val left = mapping(true); val right = mapping(false)
            return if (left == null && right == null) null else StrabismusGaze(left, right)
        }

        fun onTarget(point: GazePoint?, target: GazePoint): Boolean = point != null &&
            point.x.isFinite() && point.y.isFinite() &&
            point.x in -0.15f..1.15f && point.y in -0.15f..1.15f &&
            abs(point.x - target.x) <= TOLERANCE_X && abs(point.y - target.y) <= TOLERANCE_Y

        private fun correlation(a: List<Double>, b: List<Double>) = StrabismusMeasures.correlation(a, b)
    }
}

/** 将眼动和实际显示的目标比较；接受最多 600 ms 的反应延迟，不要求双眼都正常。 */
internal object PursuitGazeCheck {
    fun follows(samples: List<EyeMotionSample>, segment: Int): Boolean? {
        val points = samples.filter { it.step == ScreeningStep.PURSUIT && it.pursuitSegment == segment &&
            it.targetScreenX != null && it.targetScreenY != null }.sortedBy { it.timeMs }
        if (points.size < 12 || points.last().timeMs - points.first().timeMs < 2_000L) return null
        fun axis(useLeft: Boolean, horizontal: Boolean): Boolean {
            for (lag in 0L..600L step 100L) {
                val pairs = points.mapNotNull { sample ->
                    val eye = if (useLeft) {
                        if (horizontal) sample.leftGazeX else sample.leftGazeY
                    } else if (horizontal) sample.rightGazeX else sample.rightGazeY
                    val target = points.lastOrNull { it.timeMs <= sample.timeMs - lag } ?: return@mapNotNull null
                    if (sample.timeMs - lag - target.timeMs > 150L || eye == null || !eye.isFinite()) return@mapNotNull null
                    eye.toDouble() to (if (horizontal) target.targetScreenX!! else target.targetScreenY!!).toDouble()
                }
                if (pairs.size < 10) continue
                val eye = pairs.map { it.first }; val target = pairs.map { it.second }
                // 实际屏幕轨迹太小或眼动只有抖动，不能据此证明跟随。
                if (target.max() - target.min() < 0.06 || eye.max() - eye.min() < 0.035) continue
                val r = StrabismusMeasures.correlation(eye, target) ?: continue
                val positionRate = pairs.count { abs(it.first - it.second) <=
                    if (horizontal) StrabismusGaze.TOLERANCE_X else StrabismusGaze.TOLERANCE_Y }.toDouble() / pairs.size
                if (r >= 0.30 && positionRate >= 0.60) return true
            }
            return false
        }
        fun eye(useLeft: Boolean) = when (segment) {
            0 -> axis(useLeft, true)
            1 -> axis(useLeft, false)
            else -> axis(useLeft, true) && axis(useLeft, false)
        }
        return eye(true) || eye(false)
    }

    fun qualityReasons(samples: List<EyeMotionSample>, calibrated: Boolean): List<String> = buildList {
        if (!calibrated) { add("注视校准未完成，请重新测试"); return@buildList }
        for (step in listOf(ScreeningStep.FIXATION, ScreeningStep.COVER, ScreeningStep.PURSUIT)) {
            val points = samples.filter { it.step == step }
            val accepted = points.count { it.gazeOnTarget == true }
            if (points.isEmpty() || accepted.toDouble() / points.size < 0.60)
                add("${stepTitle(step)}未获得足够的有效注视，请看着屏幕目标重新测试")
        }
        for (segment in 0..2) if (follows(samples, segment) != true)
            add("${listOf("水平", "垂直", "斜向")[segment]}跟随未获得有效轨迹，请跟随小球重新测试")
    }
}
