package com.zkyjss.eyeproject.feature.testmodel.blink

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.model.BoundingBox

/**
 * 眨眼检测覆盖层：人脸框 + 双眼 EAR 6 点轮廓 + 虹膜圆。
 * 睁眼轮廓用 success 色，闭眼切换为 warning 色。纯 Canvas，零依赖。
 *
 * 坐标对齐：FILL_CENTER 映射 + 前置摄像头镜像（PreviewView 预览被水平翻转，
 * 而关键点来自未镜像的分析图，x 必须翻转，参照 services 的 EyeOverlay）。
 */
@Composable
fun BlinkOverlay(
    faceDetected: Boolean,
    faceBox: BoundingBox?,
    leftEye: BlinkEyeLandmarks?,
    rightEye: BlinkEyeLandmarks?,
    eyeClosed: Boolean,
    sourceWidth: Int,
    sourceHeight: Int,
    modifier: Modifier = Modifier
) {
    val colors = LocalEyeColors.current
    Canvas(modifier = modifier) {
        if (!faceDetected || faceBox == null || sourceWidth <= 0 || sourceHeight <= 0) {
            return@Canvas
        }

        // 正立图坐标 → 预览坐标（FILL_CENTER 映射 + 前置摄像头水平镜像）
        val scale = maxOf(size.width / sourceWidth.toFloat(), size.height / sourceHeight.toFloat())
        val scaledWidth = sourceWidth * scale
        val scaledHeight = sourceHeight * scale
        val dx = (size.width - scaledWidth) / 2f
        val dy = (size.height - scaledHeight) / 2f
        val toScreen: (Float, Float) -> Offset = { x, y ->
            // 前置摄像头镜像：预览被水平翻转，关键点 x 需翻转
            Offset(size.width - (x * scale + dx), y * scale + dy)
        }

        // 人脸框（镜像后左边界对应原图右边界）
        drawRect(
            color = colors.success,
            topLeft = toScreen(faceBox.right, faceBox.top),
            size = Size(faceBox.width * scale, faceBox.height * scale),
            style = Stroke(width = 3.dp.toPx())
        )

        // 双眼：轮廓（EAR 6 点闭合成眼形）+ 虹膜圆
        val eyeColor = if (eyeClosed) colors.warning else colors.success
        listOf(leftEye, rightEye).forEach { eye ->
            if (eye == null) return@forEach
            val mapped = eye.earPoints.map { toScreen(it.x, it.y) }
            if (mapped.size >= 6) {
                val outline = Path().apply {
                    moveTo(mapped[0].x, mapped[0].y)
                    for (i in 1 until mapped.size) {
                        lineTo(mapped[i].x, mapped[i].y)
                    }
                    close()
                }
                drawPath(outline, color = eyeColor, style = Stroke(width = 2.dp.toPx()))
            }
            // 虹膜：中心点 + 直径圆
            val irisCenter = eye.irisPoints.firstOrNull() ?: return@forEach
            val center = toScreen(irisCenter.x, irisCenter.y)
            drawCircle(color = colors.primary, radius = 2.dp.toPx(), center = center)
            drawCircle(
                color = colors.primary,
                radius = eye.irisDiameterPx / 2f * scale,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}
