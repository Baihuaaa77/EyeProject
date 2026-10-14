package com.zkyjss.eyeproject.core.camera

import com.zkyjss.eyeproject.core.model.EyeLandmarks
import com.zkyjss.eyeproject.core.model.OpticalEyeMeasurement
import com.zkyjss.eyeproject.core.model.Point2f
import kotlin.math.hypot
import kotlin.math.roundToInt

/** 眼区局部灰度分割基线。候选反光点必须通过对比度与面积检查，否则拒绝测量。 */
object EyeOpticalAlgorithm {
    fun measure(bitmap: GrayImage, eye: EyeLandmarks?): OpticalEyeMeasurement? {
        eye ?: return null
        val iris = eye.irisPoints.firstOrNull() ?: return null
        val diameter = eye.irisDiameterPx
        if (diameter < 12f) return rejected(diameter, "虹膜像素不足")
        val radius = diameter * 0.5f
        val x0 = (iris.x - radius).roundToInt().coerceAtLeast(0)
        val x1 = (iris.x + radius).roundToInt().coerceAtMost(bitmap.width - 1)
        val y0 = (iris.y - radius).roundToInt().coerceAtLeast(0)
        val y1 = (iris.y + radius).roundToInt().coerceAtMost(bitmap.height - 1)
        if (x1 - x0 < 10 || y1 - y0 < 10) return rejected(diameter, "虹膜区域被裁切")

        val width = x1 - x0 + 1
        val height = y1 - y0 + 1
        val gray = bitmap.grayRegion(x0, y0, width, height)
        val central = ArrayList<Int>()
        val irisValues = ArrayList<Int>()
        for (y in 0 until height) for (x in 0 until width) {
            val d = hypot(x + x0 - iris.x, y + y0 - iris.y)
            if (d <= radius * 0.8f) irisValues.add(gray[y * width + x])
            if (d <= radius * 0.52f) central.add(gray[y * width + x])
        }
        if (central.size < 15 || irisValues.size < 30) return rejected(diameter, "眼区像素不足")
        central.sort(); irisValues.sort()
        val dark = central[(central.size * 0.30f).toInt()]
        val median = irisValues[irisValues.size / 2]
        val bright = irisValues[(irisValues.size * 0.90f).toInt()]
        val pupilContrast = (median - dark).toFloat()
        var pupilX = 0f; var pupilY = 0f; var pupilWeight = 0f
        for (y in 0 until height) for (x in 0 until width) {
            if (hypot(x + x0 - iris.x, y + y0 - iris.y) > radius * 0.58f) continue
            val value = gray[y * width + x]
            val weight = (dark + 8 - value).coerceAtLeast(0).toFloat()
            pupilX += (x + x0) * weight
            pupilY += (y + y0) * weight
            pupilWeight += weight
        }
        val pupil = if (pupilContrast >= 12f && pupilWeight >= 8f) Point2f(pupilX / pupilWeight, pupilY / pupilWeight) else null

        // 仅接受虹膜内部很小的高亮连通域，避免把眼白、镜片反射或整屏亮斑当作角膜反光点。
        val threshold = maxOf(205, median + 55, bright - 8)
        val visited = BooleanArray(gray.size)
        var bestScore = Float.NEGATIVE_INFINITY
        var glint: Point2f? = null
        var glintContrast = 0f
        val queue = IntArray(gray.size)
        val maxArea = maxOf(2, (kotlin.math.PI * radius * radius * 0.10).toInt())
        for (start in gray.indices) {
            if (visited[start] || gray[start] < threshold) continue
            val sx = start % width; val sy = start / width
            if (hypot(sx + x0 - iris.x, sy + y0 - iris.y) > radius * 0.75f) continue
            var head = 0; var tail = 0
            queue[tail++] = start; visited[start] = true
            var sumX = 0f; var sumY = 0f; var sumBrightness = 0f
            while (head < tail) {
                val index = queue[head++]
                val x = index % width; val y = index / width
                sumX += x + x0; sumY += y + y0; sumBrightness += gray[index]
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val next = ny * width + nx
                    if (!visited[next] && gray[next] >= threshold && hypot(nx + x0 - iris.x, ny + y0 - iris.y) <= radius * 0.75f) {
                        visited[next] = true; queue[tail++] = next
                    }
                }
            }
            if (tail !in 1..maxArea) continue
            val candidate = Point2f(sumX / tail, sumY / tail)
            val contrast = sumBrightness / tail - median
            val score = contrast - 2f * hypot(candidate.x - iris.x, candidate.y - iris.y)
            if (contrast >= 55f && score > bestScore) {
                bestScore = score; glint = candidate; glintContrast = contrast
            }
        }
        val reason = when {
            pupil == null -> "瞳孔对比度不足"
            glint == null -> "未识别到可靠角膜反光点"
            else -> null
        }
        return OpticalEyeMeasurement(pupil, glint, diameter, pupilContrast, glintContrast, reason)
    }

    private fun rejected(diameter: Float, reason: String) =
        OpticalEyeMeasurement(null, null, diameter, 0f, 0f, reason)
}
