package com.zkyjss.eyeproject.feature.services

import android.util.DisplayMetrics
import kotlin.math.min
import kotlin.math.tan

/** 由小数视力、实测眼屏距离和设备物理像素密度计算 E 视标的边长。 */
internal object ScreeningOptotype {
    data class Size(val sizePx: Float, val strokePx: Float, val displayable: Boolean)

    fun calculate(metrics: DisplayMetrics, decimalAcuity: Float, distanceCm: Float): Size {
        val pixelsPerMm = metrics.xdpi / 25.4f
        if (decimalAcuity <= 0f || distanceCm <= 0f || pixelsPerMm <= 0f ||
            metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return Size(0f, 0f, false)
        val angleRadians = Math.toRadians(5.0 / decimalAcuity / 60.0)
        val millimeters = 2.0 * distanceCm * 10.0 * tan(angleRadians / 2.0)
        val pixels = (millimeters * pixelsPerMm).toFloat()
        val stroke = pixels * 0.20f
        val fits = pixels <= min(metrics.widthPixels, metrics.heightPixels) / 3f
        return Size(pixels, stroke, pixels.isFinite() && stroke >= 3f && fits)
    }
}
