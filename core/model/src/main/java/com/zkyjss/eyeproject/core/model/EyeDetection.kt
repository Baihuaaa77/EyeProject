package com.zkyjss.eyeproject.core.model

/**
 * 单只眼睛的检测结果。
 *
 * @param label 眼睛标识（LEFT / RIGHT）
 * @param points 眼周关键点（用于画面 overlay 绘制）
 * @param center 眼中心点（由内外眼角中点估算）
 * @param irisCenter 虹膜/瞳孔中心（MediaPipe 468/473），用于更精确的距离估算与后续注视检测
 */
data class EyeDetection(
    val label: String,
    val points: List<Point2f>,
    val center: Point2f,
    val irisCenter: Point2f?
)
