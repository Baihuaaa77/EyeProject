package com.zkyjss.eyeproject.feature.camera

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.pow
import kotlin.math.sqrt

object IrisToDepthCalculator {
    // 虹膜平均物理直径（毫米），来自 MediaPipe 官方数据
    private const val IRIS_DIAMETER_MM = 11.7f


    /**
     * 从虹膜关键点计算距离
     */
    fun calculateDepthFromLandmarks(
        irisLandmarks: List<NormalizedLandmark>,
        focalLengthPx: Float,
        imageWidth: Int,
        imageHeight: Int
    ): Float {
        if (irisLandmarks.size < 5) return 0f

        // 取虹膜左右两个端点：索引 0 和 4
        val left = irisLandmarks[0]
        val right = irisLandmarks[4]

        // 归一化坐标转像素坐标
        val x1 = left.x() * imageWidth
        val y1 = left.y() * imageHeight
        val x2 = right.x() * imageWidth
        val y2 = right.y() * imageHeight

        // 计算距离
        val irisDiameterPx = sqrt((x2 - x1).pow(2) + (y2 - y1).pow(2))

        if (focalLengthPx <=0 || irisDiameterPx <= 0) {
            return 0f
        }

        return IRIS_DIAMETER_MM * (focalLengthPx / irisDiameterPx) / 10f
    }
}