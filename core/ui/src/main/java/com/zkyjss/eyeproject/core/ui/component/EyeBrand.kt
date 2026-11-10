package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.shape.RoundedCornerShape
import com.zkyjss.eyeproject.core.designsystem.R
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.theme.illustration

/*
 * 品牌与示意图绘制（2026-11 视觉改版，去卡通化；苹果端 EyeCartoon.swift 逐项对应）：
 * - 标志：主色渐变圆角底 + 白色同心「虹膜」环与中心点，克制、几何，不再用吉祥物
 * - 方法演示里的人物与手臂见 EyeFigure.kt（柔和浮雕人偶，2026-11-10 起）
 * - 手机用扁平机身带一层柔和投影；斜视注视目标用准星（同心环 + 十字 + 中心点）
 * 启动图标（app/src/main/res/mipmap-* 与 iOS AppIcon）为爱科真人头像 + 浅灰蓝圆盘，由 scripts/generate_eyecho_icon.py 生成。
 */

/** 叶形眼轮廓（用于眼位类图标与示意人物的眼睛）：左右尖端 + 上下两段三次曲线，[halfHeight] 为最大半高。 */
fun leafEyePath(left: Float, right: Float, centerY: Float, halfHeight: Float): Path {
    val w = right - left
    // 三次曲线两控制点取 1.33h 时，曲线顶点约等于 h
    val k = halfHeight * 1.33f
    return Path().apply {
        moveTo(left, centerY)
        cubicTo(left + w * 0.22f, centerY - k, right - w * 0.30f, centerY - k, right, centerY)
        cubicTo(right - w * 0.22f, centerY + k, left + w * 0.30f, centerY + k, left, centerY)
        close()
    }
}

/** 品牌标志：主色渐变圆角底 + 白色同心环与中心点。 */
@Composable
fun EyeBrandMark(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val colors = LocalEyeColors.current
    Canvas(modifier.size(size).semantics { contentDescription = "眼健康助手标志" }) {
        drawBrandMark(colors, this.size.minDimension)
    }
}

/** 标志 + 中文字标的横排组合。 */
@Composable
fun EyeBrandLockup(modifier: Modifier = Modifier, tagline: String? = "守护每一次注视") {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        EyeBrandMark(size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            EyeText("眼健康助手", style = typography.subtitle, color = colors.textPrimary)
            if (tagline != null) EyeText(tagline, style = typography.bodySecondary, color = colors.textSecondary)
        }
    }
}

/** 应用图标的展示（关于页等）：与桌面图标同一张图（爱科头像 + 浅灰蓝圆盘，scripts/generate_eyecho_icon.py 生成），系统图标圆角。 */
@Composable
fun EyeAppIcon(modifier: Modifier = Modifier, size: Dp = 64.dp) {
    androidx.compose.foundation.Image(
        painterResource(R.drawable.eyecho_app_icon), contentDescription = "眼健康助手图标",
        modifier.size(size).clip(RoundedCornerShape(size * 0.2237f))
    )
}

fun DrawScope.drawBrandMark(colors: EyeColors, s: Float, origin: Offset = Offset.Zero, corner: Float = 0.28f) {
    // 浅色令牌下按钮起止同色，标志另用品牌渐变终点加深一点做层次
    val from = if (colors.isDark) colors.buttonEnd else colors.blue
    drawRoundRect(
        brush = Brush.linearGradient(listOf(from, colors.buttonStart, colors.primaryDark), start = origin, end = Offset(origin.x + s, origin.y + s)),
        topLeft = origin, size = Size(s, s), cornerRadius = CornerRadius(s * corner)
    )
    val c = Offset(origin.x + s / 2, origin.y + s / 2)
    drawCircle(colors.white.copy(alpha = 0.3f), s * 0.31f, c, style = Stroke(s * 0.035f))
    drawCircle(colors.white, s * 0.2f, c, style = Stroke(s * 0.07f))
    drawCircle(colors.white, s * 0.065f, c)
}

// region 示意图道具（人物与手臂见 EyeFigure.kt）

/** 示意手机：返回屏幕区域左上角与尺寸，方便调用方在屏幕里画内容。 */
fun DrawScope.drawPhone(colors: EyeColors, center: Offset, height: Float): Pair<Offset, Size> {
    val w = height * 0.5f
    val topLeft = Offset(center.x - w / 2, center.y - height / 2)
    softRelief(colors, w * 0.06f) { canvas, paint ->
        canvas.drawRoundRect(topLeft.x, topLeft.y, topLeft.x + w, topLeft.y + height, w * 0.18f, w * 0.18f, paint)
    }
    drawRoundRect(colors.illustration.device, topLeft, Size(w, height), CornerRadius(w * 0.18f))
    val inset = w * 0.05f
    val screenTl = Offset(topLeft.x + inset, topLeft.y + inset)
    val screen = Size(w - inset * 2, height - inset * 2)
    // 屏幕恒为白底：与正式视标「黑白对比独立于主题」保持一致
    drawRoundRect(colors.white, screenTl, screen, CornerRadius(w * 0.14f))
    // 前置摄像头（屏幕顶部小孔）
    drawCircle(colors.ink, w * 0.03f, Offset(center.x, screenTl.y + w * 0.07f))
    return screenTl to screen
}

/** 注视目标（斜视测试）：同心环 + 十字 + 中心点，[size] 为外环直径；[pulse] 0..1 外环轻微扩张。 */
fun DrawScope.drawFixationTarget(colors: EyeColors, center: Offset, size: Float, pulse: Float = 0f) {
    val r = size / 2
    val accent = colors.primary
    if (colors.hasGlow) drawCircle(colors.glow.copy(alpha = 0.18f), r * 1.2f, center)
    drawCircle(accent.copy(alpha = 0.35f), r * (0.9f + pulse * 0.1f), center, style = Stroke(r * 0.1f))
    drawCircle(accent, r * 0.55f, center, style = Stroke(r * 0.12f))
    val gap = r * 0.22f
    for ((dx, dy) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
        drawLine(accent, Offset(center.x + dx * gap, center.y + dy * gap), Offset(center.x + dx * r * 0.78f, center.y + dy * r * 0.78f),
            r * 0.08f, StrokeCap.Round)
    }
    drawCircle(accent, r * 0.14f, center)
}

/** 视标 E：[direction] 0 右 1 下 2 左 3 上（开口方向）。 */
fun DrawScope.drawTumblingE(color: Color, center: Offset, edge: Float, direction: Int, alpha: Float = 1f) {
    rotate(direction * 90f, center) {
        val bar = edge / 5f
        val origin = Offset(center.x - edge / 2, center.y - edge / 2)
        drawRect(color, origin, Size(bar, edge), alpha = alpha)
        for (row in listOf(0, 2, 4)) drawRect(color, Offset(origin.x, origin.y + row * bar), Size(edge, bar), alpha = alpha)
    }
}

// endregion
