package com.zkyjss.eyeproject.feature.services

import kotlin.math.abs
import kotlin.math.sqrt

/** 屏幕归一化坐标，允许越界；只用于宽容的任务监督，不作为眼位诊断量。 */
data class GazePoint(val x: Float, val y: Float)

data class GazeCalibrationSample(
    val index: Int, val target: GazePoint, val left: GazePoint?, val right: GazePoint?,
)

/**
 * 每只眼独立校准，允许只有一只眼可靠跟随，保留另一只眼的异常供原评估使用。
 * 九点覆盖整个追踪画布；每个屏幕轴用 a + b·水平 + c·垂直 的仿射映射，吸收头部轻微倾斜带来的轴间串扰。
 */
class StrabismusGaze private constructor(private val left: Mapping?, private val right: Mapping?) {
    private data class Axis(val a: Double, val bx: Double, val by: Double) {
        fun estimate(point: GazePoint) = (a + bx * point.x + by * point.y).toFloat()
    }
    private data class Mapping(val x: Axis, val y: Axis) {
        fun estimate(point: GazePoint) = GazePoint(x.estimate(point), y.estimate(point))
    }
    private data class Anchor(val index: Int, val eye: GazePoint, val target: GazePoint)

    fun estimate(leftEye: GazePoint?, rightEye: GazePoint?): Pair<GazePoint?, GazePoint?> =
        leftEye?.let { left?.estimate(it) } to rightEye?.let { right?.estimate(it) }

    companion object {
        /**
         * 画布内 3×3 网格，四周仅留 8% 边距，覆盖追踪任务的整个显示范围；
         * 从中心开始按顺时针走相邻点，视线每次移动距离短、容易找到新点。
         * 约 27 秒，每点先等待 1.2 秒，再采样 1.8 秒。
         */
        val calibrationTargets = listOf(
            GazePoint(0.5f, 0.5f),
            GazePoint(0.08f, 0.08f), GazePoint(0.5f, 0.08f), GazePoint(0.92f, 0.08f),
            GazePoint(0.92f, 0.5f), GazePoint(0.92f, 0.92f), GazePoint(0.5f, 0.92f),
            GazePoint(0.08f, 0.92f), GazePoint(0.08f, 0.5f),
        )
        const val CALIBRATION_POINT_MS = 3_000L
        const val SETTLE_MS = 1_200L
        const val TOLERANCE_X = 0.25f
        const val TOLERANCE_Y = 0.20f
        /** 每点至少的有效帧数；允许最多 2 个点缺失或被剔除为离群点。 */
        private const val MIN_SAMPLES_PER_POINT = 4
        private const val MAX_DROPPED_POINTS = 2

        fun calibrate(samples: List<GazeCalibrationSample>): StrabismusGaze? {
            fun mapping(useLeft: Boolean): Mapping? {
                fun median(values: List<Float>) = values.sorted().let { it[it.size / 2] }
                var anchors = calibrationTargets.indices.mapNotNull { index ->
                    val group = samples.filter { it.index == index && (if (useLeft) it.left else it.right) != null }
                    if (group.size < MIN_SAMPLES_PER_POINT) return@mapNotNull null
                    val eyes = group.map { (if (useLeft) it.left else it.right)!! }
                    Anchor(index, GazePoint(median(eyes.map { it.x }), median(eyes.map { it.y })),
                        GazePoint(median(group.map { it.target.x }), median(group.map { it.target.y })))
                }
                if (anchors.size < calibrationTargets.size - MAX_DROPPED_POINTS || !covers(anchors)) return null
                if (anchors.any { !it.eye.x.isFinite() || !it.eye.y.isFinite() || !it.target.x.isFinite() || !it.target.y.isFinite() }) return null
                var model = fitMapping(anchors) ?: return null
                // 剔除明显离群的点（如该点期间看向别处），每次一个，最多剔除到只剩 7 点。
                // 用留一预测误差判定：远离其余点的离群值会把最小二乘拉向自己，样本内残差反而不大。
                while (anchors.size > calibrationTargets.size - MAX_DROPPED_POINTS) {
                    val errors = anchors.indices.map { i ->
                        val others = anchors.filterIndexed { j, _ -> j != i }
                        if (!covers(others)) 0.0 else fitMapping(others)?.let { residual(it, anchors[i]) } ?: 0.0
                    }
                    val worst = errors.indices.maxBy { errors[it] }
                    val typical = errors.sorted()[errors.size / 2]
                    if (errors[worst] <= maxOf(0.06, typical * 3)) break
                    val reduced = anchors.filterIndexed { i, _ -> i != worst }
                    model = fitMapping(reduced) ?: break
                    anchors = reduced
                }
                if (anchors.size < calibrationTargets.size - MAX_DROPPED_POINTS || !covers(anchors)) return null
                return model.takeIf { quality(it, anchors) }
            }
            val left = mapping(true); val right = mapping(false)
            return if (left == null && right == null) null else StrabismusGaze(left, right)
        }

        /** 剩余点仍须覆盖左中右三列与上中下三行，否则边缘只能外推。 */
        private fun covers(anchors: List<Anchor>): Boolean {
            fun band(v: Float) = when { v < 0.3f -> 0; v > 0.7f -> 2; else -> 1 }
            val targets = anchors.map { calibrationTargets[it.index] }
            return targets.map { band(it.x) }.toSet().size == 3 && targets.map { band(it.y) }.toSet().size == 3
        }

        private fun residual(model: Mapping, anchor: Anchor): Double {
            val estimate = model.estimate(anchor.eye)
            val dx = (estimate.x - anchor.target.x).toDouble(); val dy = (estimate.y - anchor.target.y).toDouble()
            return sqrt(dx * dx + dy * dy)
        }

        private fun fitMapping(anchors: List<Anchor>): Mapping? {
            val rx = anchors.map { it.eye.x.toDouble() }; val ry = anchors.map { it.eye.y.toDouble() }
            // 眼动幅度过小（静止或检测失败）时不能建立映射。
            if (rx.max() - rx.min() < 0.006 || ry.max() - ry.min() < 0.004) return null
            fun axis(target: List<Double>): Axis? {
                // 最小二乘解 3×3 正规方程。
                val n = anchors.size.toDouble()
                val sx = rx.sum(); val sy = ry.sum()
                val sxx = rx.sumOf { it * it }; val syy = ry.sumOf { it * it }; val sxy = rx.indices.sumOf { rx[it] * ry[it] }
                val st = target.sum(); val sxt = rx.indices.sumOf { rx[it] * target[it] }; val syt = ry.indices.sumOf { ry[it] * target[it] }
                val m = arrayOf(doubleArrayOf(n, sx, sy), doubleArrayOf(sx, sxx, sxy), doubleArrayOf(sy, sxy, syy))
                val det = det3(m)
                if (abs(det) < 1e-18) return null
                fun replaced(column: Int) = Array(3) { r -> DoubleArray(3) { c -> if (c == column) doubleArrayOf(st, sxt, syt)[r] else m[r][c] } }
                return Axis(det3(replaced(0)) / det, det3(replaced(1)) / det, det3(replaced(2)) / det)
            }
            return Mapping(axis(anchors.map { it.target.x.toDouble() }) ?: return null,
                axis(anchors.map { it.target.y.toDouble() }) ?: return null)
        }

        /** 每个轴的拟合值须与目标高度相关，且均方根误差不超过屏幕的 10%。 */
        private fun quality(model: Mapping, anchors: List<Anchor>): Boolean {
            for (horizontal in listOf(true, false)) {
                val fitted = anchors.map { (if (horizontal) model.x.estimate(it.eye) else model.y.estimate(it.eye)).toDouble() }
                val target = anchors.map { (if (horizontal) it.target.x else it.target.y).toDouble() }
                if (fitted.any { !it.isFinite() }) return false
                if ((correlation(fitted, target) ?: return false) < 0.8) return false
                val rmse = sqrt(fitted.indices.map { (fitted[it] - target[it]).let { d -> d * d } }.average())
                if (rmse > 0.10) return false
            }
            return true
        }

        private fun det3(m: Array<DoubleArray>) =
            m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
                m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])

        fun onTarget(point: GazePoint?, target: GazePoint): Boolean = point != null &&
            point.x.isFinite() && point.y.isFinite() &&
            point.x in -0.15f..1.15f && point.y in -0.15f..1.15f &&
            abs(point.x - target.x) <= TOLERANCE_X && abs(point.y - target.y) <= TOLERANCE_Y

        private fun correlation(a: List<Double>, b: List<Double>) = StrabismusMeasures.correlation(a, b)
    }
}

/** 将眼动和实际显示的目标比较；接受最多 600 ms 的反应延迟，不要求双眼都正常。 */
object PursuitGazeCheck {
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
            add("${listOf("水平", "垂直", "斜向")[segment]}跟随未获得有效轨迹，请跟随小瞳重新测试")
    }
}
