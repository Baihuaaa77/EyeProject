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
class StrabismusGaze private constructor(
    private val left: Mapping?,
    private val right: Mapping?,
    private val binocular: Mapping?,
) {
    private data class Axis(val a: Double, val bx: Double, val by: Double) {
        fun estimate(point: GazePoint) = (a + bx * point.x + by * point.y).toFloat()
    }
    private data class Mapping(val x: Axis, val y: Axis) {
        fun estimate(point: GazePoint) = GazePoint(x.estimate(point), y.estimate(point))
    }
    private data class Anchor(val index: Int, val eye: GazePoint, val target: GazePoint)

    fun estimate(leftEye: GazePoint?, rightEye: GazePoint?): Pair<GazePoint?, GazePoint?> =
        leftEye?.let { left?.estimate(it) } to rightEye?.let { right?.estimate(it) }

    /**
     * 屏幕上的注视只有一个点。两眼映射后先做双眼融合，再交给任务监督，
     * 避免把两只眼的检测抖动当成两个屏幕目标，也避免用“任一眼命中”放宽质量门控。
     */
    fun estimatePoint(leftEye: GazePoint?, rightEye: GazePoint?): GazePoint? {
        val mapped = estimate(leftEye, rightEye)
        return fuse(mapped.first, mapped.second)
            ?: binocular?.let {
                fuse(leftEye, rightEye)?.let(it::estimate)
            }
    }

    companion object {
        /**
         * 画布内 3×3 网格，四周仅留 2% 边距，覆盖追踪任务的整个显示范围；
         * 从中心开始按顺时针走相邻点，视线每次移动距离短、容易找到新点。
         * 约 18 秒，每点先等待 0.8 秒让视线落定，再采样 1.2 秒（30 帧/秒下约 36 帧，远多于每点至少 4 帧的要求）。
         */
        val calibrationTargets = listOf(
            GazePoint(0.5f, 0.5f),
            GazePoint(0.02f, 0.02f), GazePoint(0.5f, 0.02f), GazePoint(0.98f, 0.02f),
            GazePoint(0.98f, 0.5f), GazePoint(0.98f, 0.98f), GazePoint(0.5f, 0.98f),
            GazePoint(0.02f, 0.98f), GazePoint(0.02f, 0.5f),
        )
        const val CALIBRATION_POINT_MS = 2_000L
        const val SETTLE_MS = 800L
        /** 每点需累计的有效采样时长（正常情况下 2 秒 − 0.8 秒稳定期）。 */
        const val SAMPLE_MS = CALIBRATION_POINT_MS - SETTLE_MS
        /** 异常导致采样不足时，每点最长等待时间；超过后换点，由补测或整体重试处理。 */
        const val CALIBRATION_POINT_MAX_MS = 6_000L
        /** 异常恢复后的重新稳定时间（视线回到圆点）。 */
        const val RESETTLE_MS = 600L
        /** 相邻有效帧间隔超过此值不计入累计采样时长（丢帧期间不算看过）。 */
        const val MAX_SAMPLE_GAP_MS = 400L
        /** 异常持续超过此时长才提示「校准已暂停」，避免眨眼一下就闪提示。 */
        const val PAUSE_NOTICE_MS = 500L
        /** 注视容差（屏幕比例）：只判断「大致在看目标」，手机前置相机的眼动估计误差不小，宁宽勿严。 */
        const val TOLERANCE_X = 0.34f
        const val TOLERANCE_Y = 0.30f
        /** 每点至少的有效帧数；允许最多 3 个点缺失或被剔除为离群点（剩余点仍须覆盖三行三列）。 */
        private const val MIN_SAMPLES_PER_POINT = 4
        private const val MAX_DROPPED_POINTS = 3
        /** 一次补测最多重看的点数；更多点不可用说明姿势或光线有问题，应整体重做。 */
        const val MAX_RETAKE_POINTS = 4

        /** 单眼拟合结果：[used] 为最终参与映射的点；失败时为空映射，[used] 仍给出可用点供补测判断。 */
        private class EyeFit(val mapping: Mapping?, val used: Set<Int>)

        fun calibrate(samples: List<GazeCalibrationSample>): StrabismusGaze? {
            val left = fit(samples, true).mapping; val right = fit(samples, false).mapping
            val binocular = fit(samples.mapNotNull { sample ->
                val eye = fuse(sample.left, sample.right) ?: return@mapNotNull null
                GazeCalibrationSample(sample.index, sample.target, eye, null)
            }, true).mapping
            return if (left == null && right == null && binocular == null) null else StrabismusGaze(left, right, binocular)
        }

        /**
         * 校准失败时需要补测的点：两只眼都没用上（有效帧不足、被判为离群、或拟合质量不足时残差最大）的点。
         * 超过 [MAX_RETAKE_POINTS] 个时返回空，交给整体重试。
         */
        fun pointsToRetake(samples: List<GazeCalibrationSample>): List<Int> {
            val usable = fit(samples, true).used + fit(samples, false).used
            return calibrationTargets.indices.filter { it !in usable }.takeIf { it.size <= MAX_RETAKE_POINTS }.orEmpty()
        }

        /** 校准失败时给用户的原因：是采不到画面（距离、头位、光线），还是眼动对不上圆点。 */
        fun failureReason(samples: List<GazeCalibrationSample>): String {
            val missing = calibrationTargets.indices.count { index ->
                samples.count { it.index == index && it.left != null } < MIN_SAMPLES_PER_POINT &&
                    samples.count { it.index == index && it.right != null } < MIN_SAMPLES_PER_POINT
            }
            return if (missing > MAX_DROPPED_POINTS)
                "有 $missing 个圆点没有采到有效画面。请保持约 33 cm、头部正对屏幕、光线均匀后重试。"
            else "眼睛转动与圆点位置对不上。请只转动眼睛看每个圆点，头和手机保持不动后重试。"
        }

        private fun fit(samples: List<GazeCalibrationSample>, useLeft: Boolean): EyeFit {
            fun median(values: List<Float>) = values.sorted().let { it[it.size / 2] }
            var anchors = calibrationTargets.indices.mapNotNull { index ->
                val group = samples.filter { it.index == index && (if (useLeft) it.left else it.right) != null }
                if (group.size < MIN_SAMPLES_PER_POINT) return@mapNotNull null
                val eyes = group.map { (if (useLeft) it.left else it.right)!! }
                Anchor(index, GazePoint(median(eyes.map { it.x }), median(eyes.map { it.y })),
                    GazePoint(median(group.map { it.target.x }), median(group.map { it.target.y })))
            }.filter { it.eye.x.isFinite() && it.eye.y.isFinite() && it.target.x.isFinite() && it.target.y.isFinite() }
            fun failed() = EyeFit(null, anchors.map { it.index }.toSet())
            if (anchors.size < calibrationTargets.size - MAX_DROPPED_POINTS || !covers(anchors)) return failed()
            var model = fitMapping(anchors) ?: return failed()
            // 剔除明显离群的点（如该点期间看向别处），每次一个，最多剔除到只剩 6 点。
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
            if (quality(model, anchors)) return EyeFit(model, anchors.map { it.index }.toSet())
            // 质量不足：残差最大的两个点留作补测，其余点保留。
            val worst = anchors.sortedByDescending { residual(model, it) }.take(2).map { it.index }.toSet()
            return EyeFit(null, anchors.map { it.index }.toSet() - worst)
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

        /**
         * 每个轴的拟合值须与目标明显相关（≥ 0.6），且均方根误差不超过该轴注视容差的 60%
         * 校准只用于注视监督，眼位估计使用独立的映光与遮盖观测。
         */
        private fun quality(model: Mapping, anchors: List<Anchor>): Boolean {
            for (horizontal in listOf(true, false)) {
                val fitted = anchors.map { (if (horizontal) model.x.estimate(it.eye) else model.y.estimate(it.eye)).toDouble() }
                val target = anchors.map { (if (horizontal) it.target.x else it.target.y).toDouble() }
                if (fitted.any { !it.isFinite() }) return false
                if ((correlation(fitted, target) ?: return false) < MIN_CORRELATION) return false
                val rmse = sqrt(fitted.indices.map { (fitted[it] - target[it]).let { d -> d * d } }.average())
                if (rmse > (if (horizontal) TOLERANCE_X else TOLERANCE_Y) * RMSE_OF_TOLERANCE) return false
            }
            return true
        }
        private const val MIN_CORRELATION = 0.6
        private const val RMSE_OF_TOLERANCE = 0.6

        private fun det3(m: Array<DoubleArray>) =
            m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
                m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])

        fun onTarget(point: GazePoint?, target: GazePoint): Boolean = point != null &&
            point.x.isFinite() && point.y.isFinite() &&
            point.x in -0.15f..1.15f && point.y in -0.15f..1.15f &&
            abs(point.x - target.x) <= TOLERANCE_X && abs(point.y - target.y) <= TOLERANCE_Y

        /** 双眼都可用时取中点，只有一只眼可用时保留该眼，兼容遮盖和短暂丢点。 */
        private fun fuse(left: GazePoint?, right: GazePoint?): GazePoint? = when {
            left != null && right != null && left.x.isFinite() && left.y.isFinite() &&
                right.x.isFinite() && right.y.isFinite() ->
                GazePoint((left.x + right.x) * 0.5f, (left.y + right.y) * 0.5f)
            left != null && left.x.isFinite() && left.y.isFinite() -> left
            right != null && right.x.isFinite() && right.y.isFinite() -> right
            else -> null
        }

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
                    val paired = if (horizontal) {
                        sample.leftGazeX to sample.rightGazeX
                    } else sample.leftGazeY to sample.rightGazeY
                    val fused = (if (horizontal) sample.gazeX else sample.gazeY)?.takeIf { it.isFinite() }
                        ?: paired.first?.let { l -> paired.second?.let { r ->
                        if (abs(l - r) <= 0.22f) (l + r) * 0.5f else null
                    } }
                    val target = points.lastOrNull { it.timeMs <= sample.timeMs - lag } ?: return@mapNotNull null
                    val selected = fused ?: eye
                    if (sample.timeMs - lag - target.timeMs > 150L || selected == null || !selected.isFinite()) return@mapNotNull null
                    selected.toDouble() to (if (horizontal) target.targetScreenX!! else target.targetScreenY!!).toDouble()
                }
                if (pairs.size < 10) continue
                val eye = pairs.map { it.first }; val target = pairs.map { it.second }
                // 实际屏幕轨迹太小或眼动只有抖动，不能据此证明跟随。
                if (target.max() - target.min() < 0.06 || eye.max() - eye.min() < 0.035) continue
                val r = StrabismusMeasures.correlation(eye, target) ?: continue
                val positionRate = pairs.count { abs(it.first - it.second) <=
                    if (horizontal) StrabismusGaze.TOLERANCE_X else StrabismusGaze.TOLERANCE_Y }.toDouble() / pairs.size
                if (r >= 0.30 && positionRate >= 0.50) return true
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
}

/** 三帧中值去除跳点，再按真实时间低通；短眨眼在显示层沿用上次稳定值。 */
internal class GazeSmoother {
    /** 五帧中值去掉单帧跳点，再用较慢的时间常数保留真正的扫视。 */
    private val window = ArrayDeque<GazePoint>(5)
    private var last: GazePoint? = null
    private var lastTime = 0L
    private var holdingBlink = false

    fun clear() { window.clear(); last = null; lastTime = 0L; holdingBlink = false }

    fun heldDuringBlink(time: Long): GazePoint? =
        last?.takeIf { time - lastTime in 0L..350L }?.also { holdingBlink = true }

    fun update(time: Long, point: GazePoint?): GazePoint? {
        point ?: return null
        if (!point.x.isFinite() || !point.y.isFinite()) return null
        val gap = time - lastTime
        val resumingBlink = holdingBlink && gap in 0L..350L
        if (!resumingBlink && gap !in 0L..240L) clear()
        holdingBlink = false
        if (window.size == 5) window.removeFirst()
        window.addLast(point)
        val center = when {
            window.size < 3 -> point
            else -> {
                val middle = window.size / 2
                GazePoint(window.map { it.x }.sorted()[middle], window.map { it.y }.sorted()[middle])
            }
        }
        val interval = if (resumingBlink) gap.coerceAtMost(40L) else gap.coerceAtLeast(0L)
        val weight = (1.0 - kotlin.math.exp(-interval / 180.0)).toFloat()
        val smoothed = last?.let { GazePoint(it.x + weight * (center.x - it.x), it.y + weight * (center.y - it.y)) } ?: center
        last = smoothed
        lastTime = time
        return smoothed
    }
}


/** 闭眼后至少连续可见约两帧才恢复采样，避免眼睑边缘干扰刚睁眼时的虹膜中心。 */
internal class GazeBlinkGate {
    private var blinking = false
    private var reopenedAt: Long? = null
    private var openBaseline: Float? = null
    private var openSamples = 0

    fun clear() {
        reopen()
        openBaseline = null
        openSamples = 0
    }

    private fun reopen() { blinking = false; reopenedAt = null }

    fun isBlinking(time: Long, ear: Float?): Boolean {
        if (ear == null) { reopen(); return false }
        val threshold = if (openSamples >= 12) (openBaseline!! * 0.62f).coerceIn(0.14f,0.21f) else 0.16f
        if (ear < threshold) {
            blinking = true
            reopenedAt = null
        } else if (blinking && ear >= threshold + 0.01f) {
            val start = reopenedAt ?: time.also { reopenedAt = it }
            if (time - start >= 50L) reopen()
        } else reopenedAt = null
        if (!blinking) {
            openBaseline = openBaseline?.let { it + (ear - it) * 0.045f } ?: ear
            openSamples = (openSamples + 1).coerceAtMost(12)
        }
        return blinking
    }
}

/**
 * 任务内动态纠偏。九点标定只提供眼动范围和初始映射，进入每个屏幕目标阶段后，
 * 用受试者开始阶段持续注视的实际目标重新估计整体偏移，适应手机与人脸相对位置变化。
 */
internal class GazeDriftCorrector {
    private var stage: ScreeningStep? = null
    private var startedAt = 0L
    private var offset = GazePoint(0f, 0f)
    private val raw = ArrayDeque<GazePoint>(30)
    private val targets = ArrayDeque<GazePoint>(30)
    private var anchored = false

    fun clear() {
        stage = null; startedAt = 0L; offset = GazePoint(0f, 0f)
        raw.clear(); targets.clear(); anchored = false
    }

    fun begin(step: ScreeningStep, time: Long) {
        if (stage == step) return
        stage = step; startedAt = time; offset = GazePoint(0f, 0f)
        raw.clear(); targets.clear(); anchored = false
    }

    fun update(time: Long, estimate: GazePoint?, target: GazePoint?): GazePoint? {
        estimate ?: return null
        if (target == null || stage == ScreeningStep.FAR_FIXATION) return estimate.shift(offset)
        if (!anchored) {
            raw.addLast(estimate); targets.addLast(target)
            while (raw.size > 30) raw.removeFirst()
            while (targets.size > 30) targets.removeFirst()
            if (time - startedAt >= 700L && raw.size >= 8) {
                val source = GazePoint(median(raw.map { it.x }), median(raw.map { it.y }))
                val destination = GazePoint(median(targets.map { it.x }), median(targets.map { it.y }))
                offset = GazePoint(destination.x - source.x, destination.y - source.y)
                anchored = true
            }
        } else {
            // 只在当前点已接近目标时缓慢跟随漂移，走神时不把偏移吸收到模型里。
            val corrected = estimate.shift(offset)
            if (StrabismusGaze.onTarget(corrected, target)) {
                val alpha = 0.012f
                offset = GazePoint(offset.x + alpha * (target.x - corrected.x), offset.y + alpha * (target.y - corrected.y))
            }
        }
        return estimate.shift(offset)
    }

    private fun GazePoint.shift(delta: GazePoint) = GazePoint(x + delta.x, y + delta.y)
    private fun median(values: List<Float>): Float = values.sorted()[values.size / 2]
}
