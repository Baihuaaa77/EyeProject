package com.zkyjss.eyeproject.feature.testmodel.standalone

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.model.BoundingBox

/**
 * 全替换眨眼覆盖层：人脸框 + 双眼框。
 * 眼框颜色随状态切换（睁眼 success / 闭眼 warning）。坐标同 blink 页：FILL_CENTER 映射 + 前置摄像头水平镜像。
 */
@Composable
fun StandaloneOverlay(
    faceDetected: Boolean,
    faceBox: BoundingBox?,
    rightEyeBox: BoundingBox?,
    leftEyeBox: BoundingBox?,
    rightOpen: Boolean?,
    leftOpen: Boolean?,
    sourceWidth: Int,
    sourceHeight: Int,
    modifier: Modifier = Modifier
) {
    val colors = LocalEyeColors.current
    Canvas(modifier = modifier) {
        if (!faceDetected || faceBox == null || sourceWidth <= 0 || sourceHeight <= 0) {
            return@Canvas
        }

        // FILL_CENTER 映射 + 前置摄像头水平镜像（x 翻转）
        val scale = maxOf(size.width / sourceWidth.toFloat(), size.height / sourceHeight.toFloat())
        val dx = (size.width - sourceWidth * scale) / 2f
        val dy = (size.height - sourceHeight * scale) / 2f
        val toScreen: (Float, Float) -> Offset = { x, y ->
            Offset(size.width - (x * scale + dx), y * scale + dy)
        }

        drawRect(
            color = colors.success,
            topLeft = toScreen(faceBox.right, faceBox.top),
            size = Size(faceBox.width * scale, faceBox.height * scale),
            style = Stroke(width = 3.dp.toPx())
        )

        fun drawEye(box: BoundingBox?, open: Boolean?) {
            box ?: return
            val color = when (open) {
                true -> colors.success
                false -> colors.warning
                null -> colors.primary
            }
            drawRect(
                color = color,
                topLeft = toScreen(box.right, box.top),
                size = Size(box.width * scale, box.height * scale),
                style = Stroke(width = 2.dp.toPx())
            )
        }
        drawEye(rightEyeBox, rightOpen)
        drawEye(leftEyeBox, leftOpen)
    }
}
