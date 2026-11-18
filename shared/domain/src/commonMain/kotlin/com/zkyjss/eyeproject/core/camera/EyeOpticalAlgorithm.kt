package com.zkyjss.eyeproject.core.camera

import com.zkyjss.eyeproject.core.model.EyeLandmarks
import com.zkyjss.eyeproject.core.model.OpticalEyeMeasurement
import com.zkyjss.eyeproject.core.model.Point2f
import com.zkyjss.eyeproject.core.model.ReflexCandidate
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 眼区局部灰度分割基线。候选反光点必须通过对比度与面积检查，否则拒绝测量。
 * 瞳孔与虹膜灰度差不足时（可见光下深色虹膜很常见），改以 MediaPipe 虹膜中心（角膜缘中心）作参照：
 * Hirschberg 法本可用「映光点相对角膜缘中心」，左右眼共同的瞳孔偏心在鼻侧差中相互抵消。
 */
object EyeOpticalAlgorithm {
    fun measure(bitmap: GrayImage, eye: EyeLandmarks?): OpticalEyeMeasurement? = measure(bitmap, eye, 0.75f)

    /** 旧版光码实验的全虹膜基线；保留供历史回放比较，主 App 使用 measureScreenPoint。 */
    fun measureFullIris(bitmap: GrayImage, eye: EyeLandmarks?): OpticalEyeMeasurement? = measure(bitmap, eye, 1f)

    /** 主 App：虹膜半径70%的圆与眼睑可见区域的交集；仍保留高对比度拒判。 */
    fun measureScreenPoint(bitmap: GrayImage, eye: EyeLandmarks?): OpticalEyeMeasurement? =
        measure(bitmap,eye,0.70f,screenPoint = true)

    private fun measure(bitmap: GrayImage, eye: EyeLandmarks?, searchRadiusFraction: Float, screenPoint: Boolean = false): OpticalEyeMeasurement? {
        eye ?: return null
        val iris = eye.irisPoints.firstOrNull() ?: return null
        val diameter = eye.irisDiameterPx
        // 光学测量拒判与遮盖像素证据分开：被遮后预测虹膜缩小，仍可能有足够的眼区可见性像素。
        fun reject(reason: String) = rejected(diameter, reason).copy(irisVisibilityContrast = visibilityContrast(bitmap, eye, null))
        if (diameter < 12f) return reject("虹膜像素不足")
        val radius = diameter * 0.5f
        val x0 = (iris.x - radius).roundToInt().coerceAtLeast(0)
        val x1 = (iris.x + radius).roundToInt().coerceAtMost(bitmap.width - 1)
        val y0 = (iris.y - radius).roundToInt().coerceAtLeast(0)
        val y1 = (iris.y + radius).roundToInt().coerceAtMost(bitmap.height - 1)
        if (x1 - x0 < 10 || y1 - y0 < 10) return reject("虹膜区域被裁切")

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
        if (central.size < 15 || irisValues.size < 30) return reject("眼区像素不足")
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
        // 屏幕白点在前置相机自动曝光下常落到 180~205 灰度；先缩小搜索区，再降低门槛，
        // 让轻微失焦、眼镜反光和暗肤色场景仍有候选，最终由短光码与时序一致性确认。
        val threshold = if (screenPoint) maxOf(125, median + 20, bright - 28) else maxOf(205, median + 55, bright - 8)
        val visited = BooleanArray(gray.size)
        var bestScore = Float.NEGATIVE_INFINITY
        var glint: Point2f? = null
        var glintContrast = 0f
        val candidates = mutableListOf<ReflexCandidate>()
        fun search(x: Int,y: Int): Boolean {
            val px = (x+x0).toFloat(); val py = (y+y0).toFloat()
            if (hypot(px-iris.x,py-iris.y)>radius*searchRadiusFraction) return false
            if (!screenPoint) return true
            val lid = eye.earPoints
            if (lid.size != 6) return false
            var inside = false; var j = lid.lastIndex
            for (i in lid.indices) {
                val a = lid[i]; val b = lid[j]
                if ((a.y>py)!=(b.y>py) && px<(b.x-a.x)*(py-a.y)/(b.y-a.y)+a.x) inside = !inside
                j = i
            }
            return inside
        }
        val queue = IntArray(gray.size)
        val maxArea = maxOf(2, (kotlin.math.PI * radius * radius * 0.10).toInt())
        for (start in gray.indices) {
            if (visited[start] || gray[start] < threshold) continue
            val sx = start % width; val sy = start / width
            if (!search(sx,sy)) continue
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
                    if (!visited[next] && gray[next] >= threshold && search(nx,ny)) {
                        visited[next] = true; queue[tail++] = next
                    }
                }
            }
            if (tail !in 1..maxArea) continue
            val candidate = Point2f(sumX / tail, sumY / tail)
            val brightness = sumBrightness / tail
            val contrast = brightness - median
            if (screenPoint && contrast >= 22f) candidates += ReflexCandidate(candidate, contrast, brightness)
            val score = contrast - 2f * hypot(candidate.x - iris.x, candidate.y - iris.y)
            val minimumContrast = if (screenPoint) 35f else 55f
            if (contrast >= minimumContrast && (!screenPoint || brightness >= 180f) && score > bestScore) {
                bestScore = score; glint = candidate; glintContrast = contrast
            }
        }
        val reason = if (glint == null) "未识别到可靠角膜反光点" else null
        return OpticalEyeMeasurement(pupil ?: iris, glint, diameter, pupilContrast, glintContrast, reason,
            referenceFromIris = pupil == null, irisVisibilityContrast = visibilityContrast(bitmap, eye, dark),
            reflexCandidates = candidates.sortedByDescending { it.contrast }.take(8),
            reflexConfidence = glint?.let { (glintContrast / 150f).coerceIn(0.15f, 0.65f) })
    }

    /** 只比较同一帧的局部灰度，不保存眼区图像；反光点缺失不等于被遮盖。 */
    private fun visibilityContrast(bitmap: GrayImage, eye: EyeLandmarks, irisDark: Int?): Float? {
        val iris = eye.irisPoints.firstOrNull() ?: return null
        val a = eye.earPoints.getOrNull(0) ?: return null
        val b = eye.earPoints.getOrNull(3) ?: return null
        val radius = eye.irisDiameterPx * 0.5f
        if (!radius.isFinite() || radius < 3f) return null
        val x0 = minOf(a.x, b.x).roundToInt().coerceIn(0, bitmap.width - 1)
        val x1 = maxOf(a.x, b.x).roundToInt().coerceIn(0, bitmap.width - 1)
        val y0 = (iris.y - radius * 0.25f).roundToInt().coerceIn(0, bitmap.height - 1)
        val y1 = (iris.y + radius * 0.25f).roundToInt().coerceIn(0, bitmap.height - 1)
        if (x1 <= x0 || y1 <= y0) return null
        val width = x1 - x0 + 1
        val gray = bitmap.grayRegion(x0, y0, width, y1 - y0 + 1)
        val dark = irisDark ?: run {
            val center = gray.indices.filter { kotlin.math.abs(it % width + x0 - iris.x) <= radius * 0.52f }.map { gray[it] }.sorted()
            if (center.size < 6) return null
            center[(center.size * 0.30f).toInt()]
        }
        val sides = gray.indices.filter { kotlin.math.abs(it % width + x0 - iris.x) > radius * 1.1f }.map { gray[it] }.sorted()
        if (sides.size < 12) return null
        return (sides[sides.size / 2] - dark).coerceAtLeast(0).toFloat()
    }

    private fun rejected(diameter: Float, reason: String) =
        OpticalEyeMeasurement(null, null, diameter, 0f, 0f, reason)
}
