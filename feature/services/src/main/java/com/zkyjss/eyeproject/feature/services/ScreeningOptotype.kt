package com.zkyjss.eyeproject.feature.services

import android.util.DisplayMetrics
import kotlin.math.min
import kotlin.math.tan

/** 由小数视力、实测眼屏距离和设备物理像素密度计算 E 视标的边长。 */
internal object ScreeningOptotype {
    data class Size(val sizePx: Float, val strokePx: Float, val displayable: Boolean)

    fun calculate(metrics: DisplayMetrics, decimalAcuity: Float, distanceCm: Float): Size {
        val shared = OptotypeGeometry.calculate(DisplayGeometry(metrics.widthPixels, metrics.heightPixels,
            metrics.xdpi, metrics.ydpi), decimalAcuity, distanceCm)
        return Size(shared.sizePx, shared.strokePx, shared.displayable)
    }
}
