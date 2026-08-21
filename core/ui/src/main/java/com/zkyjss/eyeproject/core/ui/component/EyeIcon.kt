package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter

/**
 * 自定义通用图标，基于 Foundation 的 Image + ColorFilter.tint，
 * 不使用 Material 的 Icon。语义与 Material 的 Icon 一致：
 *
 * @param painter 图标资源（通常 painterResource(R.drawable.xxx)）
 * @param contentDescription 无障碍描述，纯装饰图标传 null
 * @param tint 着色；默认 [Color.Unspecified]（保留图标原始颜色）
 */
@Composable
fun EyeIcon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified
) {
    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier,
        colorFilter = if (tint != Color.Unspecified) ColorFilter.tint(tint) else null
    )
}
