package com.zkyjss.eyeproject.feature.testmodel.gaze

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.model.BoundingBox
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 视线方向 2D 投影指示器：在摄像头预览上画一个箭头，
 * 起点位于人脸眼睛位置，指向 yaw/pitch 决定的视线方向。纯 Canvas，零依赖。
 *
 * yaw   水平（左/右），范围 [-180, 180]，实际人眼约 [-45, 45]
 * pitch 垂直（上/下），范围 [-90, 90]，实际人眼约 [-30, 30]
 */
@Composable
fun GazeOverlay(
    yawDeg: Float?,
    pitchDeg: Float?,
    faceBox: BoundingBox?,
    sourceWidth: Int,
    sourceHeight: Int,
    faceDetected: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = LocalEyeColors.current
    Canvas(modifier = modifier) {
        if (!faceDetected || yawDeg == null || pitchDeg == null ||
            faceBox == null || sourceWidth <= 0 || sourceHeight <= 0
        ) {
            return@Canvas
        }

        // 正立图坐标 → 预览坐标（与 EyeOverlay 相同的 FILL_CENTER 映射）
        val scale = maxOf(size.width / sourceWidth.toFloat(), size.height / sourceHeight.toFloat())
        val scaledWidth = sourceWidth * scale
        val scaledHeight = sourceHeight * scale
        val dx = (size.width - scaledWidth) / 2f
        val dy = (size.height - scaledHeight) / 2f

        // 人脸框：即检测后裁剪、喂给视线模型的那块区域
        drawRect(
            color = colors.success,
            topLeft = Offset(faceBox.left * scale + dx, faceBox.top * scale + dy),
            size = Size(faceBox.width * scale, faceBox.height * scale),
            style = Stroke(width = 3.dp.toPx())
        )

        // 箭头起点：人脸框中心偏上（眼睛约在框上部 1/3 处）
        val startX = faceBox.centerX * scale + dx
        val startY = (faceBox.top + faceBox.height * 0.35f) * scale + dy
        val start = Offset(startX, startY)

        // 视线方向：yaw/pitch 投影到屏幕平面
        // yaw 正值 → 向右看；pitch 正值 → 向上看（屏幕 y 向下，故取负）
        val dirX = (yawDeg / 45f).coerceIn(-1f, 1f)
        val dirY = -(pitchDeg / 30f).coerceIn(-1f, 1f)
        val angle = atan2(dirY, dirX)

        val arrowLen = size.width * 0.22f
        val end = Offset(
            start.x + cos(angle) * arrowLen,
            start.y + sin(angle) * arrowLen
        )

        // 箭头主体
        drawLine(
            color = colors.primary,
            start = start,
            end = end,
            strokeWidth = 4.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )

        // 箭头头（两条线组成 V 形）
        val headLen = 14.dp.toPx()
        val headAngle = 0.6f  // 弧度
        val leftHead = Offset(
            end.x - cos(angle - headAngle) * headLen,
            end.y - sin(angle - headAngle) * headLen
        )
        val rightHead = Offset(
            end.x - cos(angle + headAngle) * headLen,
            end.y - sin(angle + headAngle) * headLen
        )
        drawLine(color = colors.primary, start = end, end = leftHead, strokeWidth = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color = colors.primary, start = end, end = rightHead, strokeWidth = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}
