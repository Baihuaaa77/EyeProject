package com.zkyjss.eyeproject.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.model.EyeDetection
import com.zkyjss.eyeproject.core.model.FaceEyeDetection

/** 在摄像头预览上绘制眼部关键点（红点 + 虹膜中心），替代旧的 View 自定义绘制。 */
@Composable
fun EyeOverlay(
    detections: List<FaceEyeDetection>,
    sourceWidth: Int,
    sourceHeight: Int,
    modifier: Modifier = Modifier
) {
    val dangerColor = LocalEyeColors.current.danger
    Canvas(modifier = modifier) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || detections.isEmpty()) {
            return@Canvas
        }
        val scale = maxOf(
            size.width / sourceWidth.toFloat(),
            size.height / sourceHeight.toFloat()
        )
        val scaledWidth = sourceWidth * scale
        val scaledHeight = sourceHeight * scale
        val dx = (size.width - scaledWidth) / 2f
        val dy = (size.height - scaledHeight) / 2f

        detections.forEach { detection ->
            drawEye(detection.leftEye, scale, dx, dy, dangerColor)
            drawEye(detection.rightEye, scale, dx, dy, dangerColor)
        }
    }
}

private fun DrawScope.drawEye(
    eye: EyeDetection?,
    scale: Float,
    dx: Float,
    dy: Float,
    color: Color
) {
    eye ?: return
    eye.points.forEach { point ->
        val mappedX = point.x * scale + dx
        val mappedY = point.y * scale + dy
        // 前置摄像头镜像
        val mx = size.width - mappedX
        drawCircle(color = color, radius = 2.dp.toPx(), center = Offset(mx, mappedY))
    }
    eye.irisCenter?.let { iris ->
        val mappedX = iris.x * scale + dx
        val mappedY = iris.y * scale + dy
        val mx = size.width - mappedX
        drawCircle(color = color, radius = 2.dp.toPx(), center = Offset(mx, mappedY))
    }
}
