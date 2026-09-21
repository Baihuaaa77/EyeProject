package com.zkyjss.eyeproject.feature.services

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 视标尺寸三档（大/中/小）。 */
val OPTOTYPE_SIZES_DP: List<Dp> = listOf(150.dp, 105.dp, 65.dp)

/**
 * 绘制 Tumbling E 视标（E 字朝向）。
 *
 * @param direction 0=右、1=下、2=左、3=上
 * @param size 视标整体尺寸
 * @param color 视标颜色（对比度）
 */
@Composable
fun TumblingE(
    direction: Int,
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val bar = w * 0.18f
        rotate(degrees = direction * 90f) {
            // 左侧竖线
            drawRect(color = color, topLeft = Offset(0f, 0f), size = Size(bar, h))
            // 上、中、下三条横线
            drawRect(color = color, topLeft = Offset(0f, 0f), size = Size(w, bar))
            drawRect(color = color, topLeft = Offset(0f, h / 2f - bar / 2f), size = Size(w, bar))
            drawRect(color = color, topLeft = Offset(0f, h - bar), size = Size(w, bar))
        }
    }
}
