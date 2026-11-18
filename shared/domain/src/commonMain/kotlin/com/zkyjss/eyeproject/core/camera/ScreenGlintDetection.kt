package com.zkyjss.eyeproject.core.camera

import com.zkyjss.eyeproject.core.model.Point2f
import com.zkyjss.eyeproject.core.model.ReflexCandidate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sqrt

/** 沿用光码实验的多尺度中心－周边差分与局部噪声估计，在原像素上提取亮点。 */
internal object ScreenGlintDetection {
    private const val SEARCH_RADIUS = 0.60f

    fun find(gray: IntArray, width: Int, height: Int, center: Point2f, radius: Float,
             lid: List<Point2f>): List<ReflexCandidate> {
        if (lid.size != 6) return emptyList()
        val searchRadius = radius * SEARCH_RADIUS
        fun inside(x: Float, y: Float): Boolean {
            if (hypot(x - center.x, y - center.y) > searchRadius) return false
            var visible = false
            var previous = lid.last()
            for (point in lid) {
                if ((point.y > y) != (previous.y > y) &&
                    x < (previous.x - point.x) * (y - point.y) / (previous.y - point.y) + point.x) visible = !visible
                previous = point
            }
            return visible
        }

        val stride = width + 1
        val integral = IntArray(stride * (height + 1))
        for (y in 0 until height) {
            var row = 0
            for (x in 0 until width) {
                row += gray[y * width + x]
                integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + row
            }
        }
        val mask = BooleanArray(gray.size)
        val noiseHistogram = IntArray(256)
        var visiblePixels = 0
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            if (!inside(x.toFloat(), y.toFloat())) continue
            val i = y * width + x
            mask[i] = true
            val neighbors = gray[i - 1] + gray[i + 1] + gray[i - width] + gray[i + width]
            noiseHistogram[(abs(4 * gray[i] - neighbors) + 2) / 4]++
            visiblePixels++
        }
        if (visiblePixels < 12) return emptyList()
        var remaining = (visiblePixels + 1) / 2
        val medianResidual = noiseHistogram.indexOfFirst { remaining -= it; remaining <= 0 }
        val noise = maxOf(1.25f, medianResidual * 1.4826f)

        fun boxSum(x: Int, y: Int, halfSize: Int): Int {
            val left = (x - halfSize).coerceAtLeast(0)
            val top = (y - halfSize).coerceAtLeast(0)
            val right = (x + halfSize + 1).coerceAtMost(width)
            val bottom = (y + halfSize + 1).coerceAtMost(height)
            return integral[bottom * stride + right] - integral[top * stride + right] -
                integral[bottom * stride + left] + integral[top * stride + left]
        }
        fun boxArea(x: Int, y: Int, size: Int) =
            (minOf(width - 1, x + size) - maxOf(0, x - size) + 1) *
                (minOf(height - 1, y + size) - maxOf(0, y - size) + 1)

        val response = FloatArray(gray.size)
        val scaleWeights = FloatArray(4) { sqrt(2f * it + 1f) / noise }
        for (i in gray.indices) {
            if (!mask[i]) continue
            val x = i % width
            val y = i / width
            for (scale in scaleWeights.indices) {
                val outer = scale * 2 + 3
                val innerSum = boxSum(x, y, scale)
                val innerArea = boxArea(x, y, scale)
                val ringArea = boxArea(x, y, outer) - innerArea
                if (ringArea <= 0) continue
                val contrast = innerSum.toFloat() / innerArea - (boxSum(x, y, outer) - innerSum).toFloat() / ringArea
                response[i] = maxOf(response[i], contrast * scaleWeights[scale])
            }
        }

        val peaks = gray.indices.filter { i ->
            val score = response[i]
            score >= 4.5f && (-1..1).all { y -> (-1..1).all { x ->
                val neighbor = i + y * width + x
                response[neighbor] < score || (response[neighbor] == score && neighbor >= i)
            } }
        }.sortedByDescending { response[it] }
        val halfWindow = ceil(radius * 0.14f).toInt().coerceIn(3, 10)
        val pending = IntArray((2 * halfWindow + 1) * (2 * halfWindow + 1))
        val visited = IntArray(gray.size)
        val candidates = mutableListOf<ReflexCandidate>()
        for ((index, i) in peaks.take(28).withIndex()) {
            val x = i % width
            val y = i / width
            if (candidates.any { hypot(it.point.x - x, it.point.y - y) < 2.5f }) continue
            val outer = halfWindow + 2
            val border = mutableListOf<Int>()
            for (yy in maxOf(0, y - outer)..minOf(height - 1, y + outer)) {
                for (xx in maxOf(0, x - outer)..minOf(width - 1, x + outer)) {
                    if (maxOf(abs(xx - x), abs(yy - y)) >= halfWindow) border += gray[yy * width + xx]
                }
            }
            if (border.isEmpty()) continue
            border.sort()
            val background = border[border.size / 2]
            val peak = (-1..1).flatMap { dy -> (-1..1).map { dx -> i + dy * width + dx } }
                .filter { mask[it] }.maxBy { gray[it] }
            val signal = gray[peak] - background
            val snr = signal / noise
            if (signal < 14 || snr < 4.5f) continue

            var head = 0
            var tail = 1
            pending[0] = peak
            val stamp = index + 1
            visited[peak] = stamp
            var weight = 0f
            var weightedX = 0f
            var weightedY = 0f
            var halfHeightArea = 0
            var clipped = false
            while (head < tail) {
                val p = pending[head++]
                val px = p % width
                val py = p / width
                val value = gray[p] - background
                weight += value
                weightedX += px * value
                weightedY += py * value
                if (value >= signal * 0.5f) {
                    halfHeightArea++
                    if (abs(px - x) == halfWindow || abs(py - y) == halfWindow ||
                        intArrayOf(p - 1, p + 1, p - width, p + width).any {
                            !mask[it] && gray[it] - background >= signal * 0.5f
                        }) clipped = true
                }
                for (dy in -1..1) for (dx in -1..1) {
                    if (abs(px + dx - x) > halfWindow || abs(py + dy - y) > halfWindow) continue
                    val next = p + dy * width + dx
                    if (mask[next] && visited[next] != stamp && gray[next] - background >= signal * 0.35f) {
                        visited[next] = stamp
                        pending[tail++] = next
                    }
                }
            }
            val diameter = 2f * sqrt(halfHeightArea / kotlin.math.PI.toFloat())
            if (clipped || diameter > maxOf(4f, radius * 0.19f)) continue
            val point = Point2f(weightedX / weight, weightedY / weight)
            if (!inside(point.x, point.y)) continue
            // 优先紧凑、局部对比强且信噪比高的峰；中心位置仅给弱先验。
            val sizeWeight = 1f / (1f + diameter / maxOf(2f, radius * 0.09f))
            val centerWeight = 1f - 0.3f * hypot(point.x - center.x, point.y - center.y) / searchRadius
            val quality = ((signal / 65f).coerceAtMost(1f) * 0.6f + (snr / 20f).coerceAtMost(1f) * 0.4f) *
                sizeWeight * centerWeight
            candidates += ReflexCandidate(point, signal.toFloat(), gray[peak].toFloat(), quality)
        }
        return candidates.sortedByDescending { it.quality }.take(8)
    }
}
