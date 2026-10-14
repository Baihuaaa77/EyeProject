package com.zkyjss.eyeproject.core.model

/**
 * 单只眼睛的疲劳检测关键点。
 *
 * @param label 眼睛标识（LEFT / RIGHT）
 * @param earPoints 用于 EAR 眨眼检测的 6 个点（p1..p6，像素坐标）
 * @param irisPoints 虹膜 5 个关键点（中心 + 4 边界，像素坐标）
 * @param irisDiameterPx 虹膜水平直径（像素），用于 Depth-from-Iris 测距
 */
data class EyeLandmarks(
    val label: String,
    val earPoints: List<Point2f>,
    val irisPoints: List<Point2f>,
    val irisDiameterPx: Float
)
