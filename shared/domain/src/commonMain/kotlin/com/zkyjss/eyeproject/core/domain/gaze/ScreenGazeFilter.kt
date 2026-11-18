package com.zkyjss.eyeproject.core.domain.gaze

import kotlin.math.exp
import kotlin.math.hypot

/** 三次估计取中值，再低通；大幅、持续的位移使用较短的时间常数。 */
internal class ScreenGazeFilter {
    private val window = ArrayDeque<ScreenGazePoint>(3)
    private var average: ScreenGazePoint? = null
    private var displayed: ScreenGazePoint? = null
    private var lastTime: Long? = null

    fun clear() {
        window.clear()
        average = null
        displayed = null
        lastTime = null
    }

    fun update(point: ScreenGazePoint, time: Long, width: Int, height: Int): ScreenGazePoint? {
        val elapsed = lastTime?.let { time - it } ?: 0L
        if (elapsed !in 1L..400L) {
            clear()
            repeat(3) { window.addLast(point) }
        } else {
            window.removeFirst()
            window.addLast(point)
        }
        lastTime = time
        val center = medianPoint(window)
        if (!center.isOnScreen()) {
            average = null
            displayed = null
            return null
        }

        val diagonal = hypot(width.toFloat(), height.toFloat())
        fun distance(a: ScreenGazePoint, b: ScreenGazePoint) =
            hypot((a.x - b.x) * width, (a.y - b.y) * height) / diagonal

        val smoothed = average?.let { previous ->
            val timeConstant = if (distance(previous, center) >= 0.035f) 85.0 else 260.0
            previous.mix(center, (1 - exp(-elapsed / timeConstant)).toFloat())
        } ?: center
        average = smoothed
        // 很小的变化仅更新滤波值，等累计位移足够大再动圆点。
        val current = displayed
        if (current == null || distance(current, smoothed) >= 0.008f) displayed = smoothed
        return displayed
    }
}
