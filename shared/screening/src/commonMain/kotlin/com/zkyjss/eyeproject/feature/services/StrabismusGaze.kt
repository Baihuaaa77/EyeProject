package com.zkyjss.eyeproject.feature.services

data class GazePoint(val x: Float, val y: Float)

/** 五帧中值去除跳点，再按真实时间低通；短眨眼在显示层沿用上次稳定值。 */
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

/** 比较相对虹膜位移和实际显示的目标轨迹，允许眼动延迟及个体幅度差异。 */
object PursuitMotionCheck {
    fun follows(samples: List<EyeMotionSample>, segment: Int): Boolean? {
        val points = samples.filter { it.step == ScreeningStep.PURSUIT && it.pursuitSegment == segment &&
            it.targetScreenX?.isFinite() == true && it.targetScreenY?.isFinite() == true }.sortedBy { it.timeMs }
        if (points.size < 12 || points.last().timeMs - points.first().timeMs < 2_000L) return null

        fun followsAxis(left: Boolean, horizontal: Boolean, lag: Long): Boolean {
            var targetIndex = 0
            val pairs = points.mapNotNull { sample ->
                val expectedTime = sample.timeMs - lag
                while (targetIndex < points.lastIndex && points[targetIndex + 1].timeMs <= expectedTime) targetIndex++
                val target = points[targetIndex]
                if (expectedTime - target.timeMs !in 0L..150L) return@mapNotNull null
                val eye = if (left) {
                    if (horizontal) sample.leftX else sample.leftY
                } else if (horizontal) sample.rightX else sample.rightY
                if (!eye.isFinite()) return@mapNotNull null
                eye.toDouble() to (if (horizontal) target.targetScreenX!! else target.targetScreenY!!).toDouble()
            }
            if (pairs.size < 10 || pairs.size * 2 < points.size) return false
            val eye = pairs.map { it.first }
            val target = pairs.map { it.second }
            return centralRange(target) >= 0.06 && centralRange(eye) >= 0.035 &&
                (StrabismusMeasures.correlation(eye, target) ?: 0.0) >= 0.50
        }

        // 任一眼跟随即可验证任务参与，两眼之间的一致性由协调指标评估。
        return (0L..600L step 100L).any { lag ->
            listOf(true, false).any { left ->
                (segment == 1 || followsAxis(left, horizontal = true, lag = lag)) &&
                    (segment == 0 || followsAxis(left, horizontal = false, lag = lag))
            }
        }
    }

    private fun centralRange(values: List<Double>): Double {
        val sorted = values.sorted()
        val trim = sorted.size / 10
        return sorted[sorted.lastIndex - trim] - sorted[trim]
    }
}
