package com.zkyjss.eyeproject.core.domain.gaze

import com.zkyjss.eyeproject.core.model.EyeLandmarks
import com.zkyjss.eyeproject.core.model.FaceMetrics
import com.zkyjss.eyeproject.core.model.HeadPoseEstimate
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

data class ScreenGazePoint(val x: Float, val y: Float) {
    fun distance(other: ScreenGazePoint) = hypot(x - other.x, y - other.y)
    fun mix(other: ScreenGazePoint, weight: Float) =
        ScreenGazePoint(x + (other.x - x) * weight, y + (other.y - y) * weight)

    fun isOnScreen() = x in 0f..1f && y in 0f..1f
}

internal data class RelativePose(val center: ScreenGazePoint, val scale: Float, val angles: HeadPoseEstimate) {
    /** 按与标定时相对视角、眼睛位置和距离的差异选择模型。阈值需真机调优。 */
    fun distance(other: RelativePose): Float {
        fun angleDelta(first: Float, second: Float) = abs((first - second + 540f) % 360f - 180f)
        return maxOf(
            angleDelta(angles.yawDeg, other.angles.yawDeg) / 10f,
            angleDelta(angles.pitchDeg, other.angles.pitchDeg) / 10f,
            angleDelta(angles.rollDeg, other.angles.rollDeg) / 12f,
            center.distance(other.center) / (scale * 0.27f),
            abs(ln(scale / other.scale)) / 0.18f,
        )
    }
}

internal data class GazeObservation(
    val pose: RelativePose,
    val left: ScreenGazePoint?,
    val right: ScreenGazePoint?,
) {
    companion object {
        fun from(face: FaceMetrics?, sourceWidth: Int, sourceHeight: Int): GazeObservation? {
            face ?: return null
            val angles = face.headPose ?: return null
            if (!listOf(angles.yawDeg, angles.pitchDeg, angles.rollDeg).all { it.isFinite() }) return null
            val bounds = face.faceBox
            val side = maxOf(sourceWidth, sourceHeight).toFloat()
            if (side <= 0f || bounds.width <= 0f || bounds.height <= 0f) return null
            val pose = RelativePose(
                ScreenGazePoint((bounds.centerX - sourceWidth / 2f) / side,
                    (bounds.centerY - sourceHeight / 2f) / side),
                hypot(bounds.width, bounds.height) / side,
                angles,
            )
            val left = eyeSignal(face.leftEye, sourceWidth, sourceHeight)
            val right = eyeSignal(face.rightEye, sourceWidth, sourceHeight)
            return if (left != null || right != null) GazeObservation(pose, left, right) else null
        }

        private fun eyeSignal(eye: EyeLandmarks?, width: Int, height: Int): ScreenGazePoint? {
            if (eye == null || eye.earPoints.size != 6 || eye.irisDiameterPx < 7f) return null
            val iris = eye.irisPoints.firstOrNull() ?: return null
            val (first, last) = eye.earPoints[0] to eye.earPoints[3]
            if (listOf(first, last, iris).any { it.x !in 0f..width.toFloat() || it.y !in 0f..height.toFloat() }) return null
            val dx = last.x - first.x
            val dy = last.y - first.y
            val span = hypot(dx, dy)
            if (span < 18f) return null
            val upper = eye.earPoints[1]
            val lower = eye.earPoints[5]
            val upperInner = eye.earPoints[2]
            val lowerInner = eye.earPoints[4]
            val opening = (hypot(upper.x - lower.x, upper.y - lower.y) +
                hypot(upperInner.x - lowerInner.x, upperInner.y - lowerInner.y)) / (span * 2f)
            if (!opening.isFinite() || opening < 0.14f) return null

            // 按眼角连线建立局部坐标，消除平面内旋转和图像缩放对虹膜位移的直接影响。
            val x = iris.x - (first.x + last.x) / 2f
            val y = iris.y - (first.y + last.y) / 2f
            return ScreenGazePoint((x * dx + y * dy) / (span * span), (y * dx - x * dy) / (span * span))
                .takeIf { abs(it.x) < 0.65f && abs(it.y) < 0.45f }
        }
    }
}

internal data class GazeAnchor(val target: ScreenGazePoint, val observations: List<GazeObservation>)

internal class GazeProfile private constructor(
    val pose: RelativePose,
    private val left: EyeMapping?,
    private val right: EyeMapping?,
) {
    fun project(observation: GazeObservation): ScreenGazePoint? {
        val a = observation.left?.let { left?.project(it) }
        val b = observation.right?.let { right?.project(it) }
        if (a == null || b == null) return a ?: b
        return a.mix(b, 0.5f).takeIf { a.distance(b) < 0.32f }
    }

    companion object {
        fun fit(pose: RelativePose, anchors: List<GazeAnchor>): GazeProfile? {
            val left = EyeMapping.fit(anchors, GazeObservation::left)
            val right = EyeMapping.fit(anchors, GazeObservation::right)
            return if (left != null || right != null) GazeProfile(pose, left, right) else null
        }
    }
}

private class EyeMapping(
    private val origin: ScreenGazePoint,
    private val meanTarget: ScreenGazePoint,
    private val horizontal: ScreenGazePoint,
    private val vertical: ScreenGazePoint,
) {
    fun project(point: ScreenGazePoint): ScreenGazePoint {
        val x = point.x - origin.x
        val y = point.y - origin.y
        return ScreenGazePoint(meanTarget.x + x * horizontal.x + y * horizontal.y,
            meanTarget.y + x * vertical.x + y * vertical.y)
    }

    companion object {
        fun fit(anchors: List<GazeAnchor>, eye: (GazeObservation) -> ScreenGazePoint?): EyeMapping? {
            val pairs = anchors.mapNotNull { anchor ->
                val points = anchor.observations.mapNotNull(eye)
                if (points.size < 4) return@mapNotNull null
                medianPoint(points) to anchor.target
            }
            if (pairs.size < 7 || pairs.map { it.second.x }.distinct().size < 3 ||
                pairs.map { it.second.y }.distinct().size < 3) return null
            val origin = ScreenGazePoint(pairs.map { it.first.x }.average().toFloat(),
                pairs.map { it.first.y }.average().toFloat())
            val target = ScreenGazePoint(pairs.map { it.second.x }.average().toFloat(),
                pairs.map { it.second.y }.average().toFloat())
            val inputs = pairs.map { (point, _) ->
                (point.x - origin.x).toDouble() to (point.y - origin.y).toDouble()
            }
            val xx = inputs.sumOf { it.first * it.first }
            val yy = inputs.sumOf { it.second * it.second }
            val xy = inputs.sumOf { it.first * it.second }
            val determinant = xx * yy - xy * xy
            // 两轴眼动幅度过小或几乎共线时，外推会被噪声放大。
            if (xx < 0.00008 || yy < 0.000015 || determinant <= 0.06 * xx * yy) return null
            fun axis(value: (ScreenGazePoint) -> Float, mean: Float): ScreenGazePoint {
                val tx = inputs.indices.sumOf { inputs[it].first * (value(pairs[it].second) - mean) }
                val ty = inputs.indices.sumOf { inputs[it].second * (value(pairs[it].second) - mean) }
                return ScreenGazePoint(((yy * tx - xy * ty) / determinant).toFloat(),
                    ((xx * ty - xy * tx) / determinant).toFloat())
            }
            val mapping = EyeMapping(origin, target, axis(ScreenGazePoint::x, target.x), axis(ScreenGazePoint::y, target.y))
            return mapping.takeIf { pairs.map { (eyePoint, expected) ->
                it.project(eyePoint).distance(expected)
            }.average() <= 0.14 }
        }
    }
}

internal fun median(values: List<Float>): Float {
    val sorted = values.sorted()
    return (sorted[sorted.lastIndex / 2] + sorted[sorted.size / 2]) / 2f
}

internal fun medianPoint(points: List<ScreenGazePoint>) =
    ScreenGazePoint(median(points.map(ScreenGazePoint::x)), median(points.map(ScreenGazePoint::y)))
