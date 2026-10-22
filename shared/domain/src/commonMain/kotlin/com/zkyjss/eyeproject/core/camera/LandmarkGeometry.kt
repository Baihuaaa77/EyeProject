package com.zkyjss.eyeproject.core.camera

import com.zkyjss.eyeproject.core.model.*
import kotlin.math.*

/** MediaPipe 478 点的相同索引与图像坐标约定；输入已转正、不镜像的 RGB 图像。 */
object LandmarkGeometry {
    fun face(points: FloatArray, matrix: FloatArray, image: GrayImage): FaceMetrics? = face(points, matrix, image, optics = true)

    /**
     * [optics] 为 false 时不做瞳孔 / 角膜反光测量（只有斜视用到；干眼与眨眼对比用不到，省下每帧两次眼区像素计算），
     * 两眼的光学测量为 null，其余几何不变。
     */
    fun face(points: FloatArray, matrix: FloatArray, image: GrayImage, optics: Boolean): FaceMetrics? {
        if (points.size < 478 * 3 || points.any { !it.isFinite() } || image.width <= 0 || image.height <= 0) return null
        fun point(i: Int) = Point2f(points[i * 3] * image.width, points[i * 3 + 1] * image.height)
        fun eye(label: String, ear: IntArray, iris: IntArray): EyeLandmarks {
            val irisPoints = iris.map(::point)
            val diameter = irisPoints.maxOf { a -> irisPoints.maxOf { b -> hypot(a.x - b.x, a.y - b.y) } }
            return EyeLandmarks(label, ear.map(::point), irisPoints, diameter)
        }
        val left = eye("LEFT", intArrayOf(362,385,387,263,373,380), intArrayOf(473,474,475,476,477))
        val right = eye("RIGHT", intArrayOf(33,160,158,133,153,144), intArrayOf(468,469,470,471,472))
        val xy = (0 until points.size / 3).map(::point)
        val scale = if (matrix.size == 16) sqrt(matrix[0] * matrix[0] + matrix[1] * matrix[1] + matrix[2] * matrix[2]) else 0f
        val pose = if (scale > 0) HeadPoseEstimate(
            asin((-matrix[2] / scale).coerceIn(-1f,1f)) * (180f / PI.toFloat()),
            atan2(matrix[6] / scale, matrix[10] / scale) * (180f / PI.toFloat()),
            atan2(matrix[1] / scale, matrix[0] / scale) * (180f / PI.toFloat())) else null
        return FaceMetrics(BoundingBox(xy.minOf { it.x }, xy.minOf { it.y }, xy.maxOf { it.x }, xy.maxOf { it.y }),
            left, right, if (optics) EyeOpticalAlgorithm.measure(image,left) else null,
            if (optics) EyeOpticalAlgorithm.measure(image,right) else null, pose)
    }
}
