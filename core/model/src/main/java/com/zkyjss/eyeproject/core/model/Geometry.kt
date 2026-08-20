package com.zkyjss.eyeproject.core.model

/** 二维点，替代 android.graphics.PointF 以保持模型层纯 Kotlin。 */
data class Point2f(val x: Float, val y: Float)

/** 像素坐标包围盒。 */
data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}
