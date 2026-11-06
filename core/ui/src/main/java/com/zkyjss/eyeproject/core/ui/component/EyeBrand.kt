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
 * - 方法演示里的人物是平面上半身短发男孩（2026-11-06 起，取代原吉祥物与中性线面头像；动画沿用原吉祥物的场景）
 * - 手为肤色手掌带浅描边，手机用扁平面；斜视注视目标用准星（同心环 + 十字 + 中心点）
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

// region 示意图人物与道具

/** 示意人物某只眼的中心（side = -1 画面左侧，+1 画面右侧）。 */
fun figureEyeCenter(center: Offset, size: Float, side: Int): Offset =
    Offset(center.x + side * size * 0.19f, center.y - size * 0.02f)

/**
 * 示意人物（方法演示用）：平面上半身短发男孩——圆脸、侧分短发、豆豆眼（带一点高光）、眉毛、腮红、小微笑，
 * 蓝色圆领上衣。纯色平涂，不做渐变与立体阴影。眼睛位置与尺寸沿用原吉祥物，演示里遮眼、视线、眨眼的坐标不用改。
 * @param size 头宽（脸宽约 0.8 倍，耳朵到耳朵约 0.96 倍）
 * @param gaze 看向（-1..1，x/y）：豆豆眼整体平移
 * @param lid 闭眼程度 0 睁开 .. 1 闭合（< 0.2 睁开度时画成闭眼弧线）
 * @param wide 睁大程度（>1 眼睛更大、眉毛上扬），用于「保持睁眼」
 * @param bottom 上衣下沿的 y；缺省为头部中心下方 0.8 倍头宽（演示舞台传舞台底边，让上身一直画到画框底）
 */
fun DrawScope.drawFigure(
    colors: EyeColors,
    center: Offset,
    size: Float,
    gaze: Offset = Offset.Zero,
    lid: Float = 0f,
    wide: Float = 1f,
    bottom: Float = center.y + size * 0.8f,
) {
    val s = size
    val ill = colors.illustration
    fun p(x: Float, y: Float) = Offset(center.x + x * s, center.y + y * s)
    // 上衣：圆肩，一直画到 bottom
    val y0 = center.y + s * 0.5f
    val hem = maxOf(bottom, y0 + s * 0.3f)
    drawPath(Path().apply {
        moveTo(center.x - s * 0.62f, hem)
        lineTo(center.x - s * 0.62f, y0 + s * 0.3f)
        quadraticBezierTo(center.x - s * 0.62f, y0, center.x - s * 0.32f, y0)
        lineTo(center.x + s * 0.32f, y0)
        quadraticBezierTo(center.x + s * 0.62f, y0, center.x + s * 0.62f, y0 + s * 0.3f)
        lineTo(center.x + s * 0.62f, hem)
        close()
    }, ill.shirt)
    // 脖子 + 圆领（领口里露出的一点脖子 + 浅色滚边）
    drawRect(ill.skinShade, p(-0.1f, 0.28f), Size(s * 0.2f, s * 0.26f))
    val collar = Path().apply {
        moveTo(center.x - s * 0.16f, y0)
        quadraticBezierTo(center.x, y0 + s * 0.15f, center.x + s * 0.16f, y0)
    }
    drawPath(collar, ill.skinShade)
    drawPath(collar, ill.shirtLight, style = Stroke(s * 0.04f, cap = StrokeCap.Round))
    // 耳朵（在脸后面）
    for (side in listOf(-1, 1)) {
        drawCircle(ill.skin, s * 0.08f, p(side * 0.4f, 0.04f))
        drawCircle(ill.skinShade, s * 0.035f, p(side * 0.405f, 0.04f))
    }
    // 脸
    drawOval(ill.skin, p(-0.4f, -0.43f), Size(s * 0.8f, s * 0.86f))
    // 侧分短发：盖住头顶，刘海从左向右扫开，露出额头
    drawPath(Path().apply {
        val a = p(-0.43f, 0.04f); moveTo(a.x, a.y)
        cubic(p(-0.47f, -0.36f), p(-0.22f, -0.56f), p(0.04f, -0.54f))
        cubic(p(0.32f, -0.53f), p(0.5f, -0.36f), p(0.43f, 0.04f))
        cubic(p(0.4f, -0.1f), p(0.34f, -0.2f), p(0.24f, -0.25f))
        cubic(p(0.1f, -0.17f), p(-0.12f, -0.15f), p(-0.3f, -0.23f))
        cubic(p(-0.37f, -0.15f), p(-0.4f, -0.06f), p(-0.43f, 0.04f))
        close()
    }, ill.hair)
    // 眉毛 + 豆豆眼
    val open = (1f - lid).coerceIn(0f, 1f)
    for (side in listOf(-1, 1)) {
        val base = figureEyeCenter(center, s, side)
        val browY = base.y - s * 0.13f - (wide - 1f) * s * 0.08f
        drawPath(Path().apply {
            moveTo(base.x - s * 0.055f, browY + s * 0.01f)
            quadraticBezierTo(base.x, browY - s * 0.025f, base.x + s * 0.055f, browY + s * 0.01f)
        }, ill.hair, style = Stroke(s * 0.024f, cap = StrokeCap.Round))
        if (open < 0.2f) {
            drawPath(
                Path().apply {
                    moveTo(base.x - s * 0.055f, base.y)
                    quadraticBezierTo(base.x, base.y + s * 0.045f, base.x + s * 0.055f, base.y)
                },
                colors.ink, style = Stroke(width = s * 0.026f, cap = StrokeCap.Round)
            )
            continue
        }
        val c = Offset(base.x + gaze.x * s * 0.035f, base.y + gaze.y * s * 0.03f)
        val rx = s * 0.052f * wide
        val ry = s * 0.072f * wide * open
        drawOval(colors.ink, Offset(c.x - rx, c.y - ry), Size(rx * 2, ry * 2))
        if (open > 0.5f) drawCircle(colors.white, s * 0.02f * wide, Offset(c.x + rx * 0.4f, c.y - ry * 0.45f))
    }
    // 鼻子、腮红、微笑
    drawPath(Path().apply {
        val a = p(-0.022f, 0.075f); val b = p(0f, 0.095f); val e = p(0.022f, 0.075f)
        moveTo(a.x, a.y); quadraticBezierTo(b.x, b.y, e.x, e.y)
    }, ill.skinLine, style = Stroke(s * 0.018f, cap = StrokeCap.Round))
    for (side in listOf(-1, 1)) {
        drawOval(ill.blush.copy(alpha = 0.6f), p(side * 0.26f - 0.055f, 0.14f - 0.032f), Size(s * 0.11f, s * 0.064f))
    }
    drawPath(Path().apply {
        val a = p(-0.06f, 0.19f); val b = p(0f, 0.245f); val e = p(0.06f, 0.19f)
        moveTo(a.x, a.y); quadraticBezierTo(b.x, b.y, e.x, e.y)
    }, ill.mouth, style = Stroke(s * 0.024f, cap = StrokeCap.Round))
}

private fun Path.cubic(c1: Offset, c2: Offset, end: Offset) = cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)

/**
 * 示意手（肤色手掌 + 拇指 + 指缝 + 蓝色袖子）：用于遮眼、点按。手掌带一圈浅描边，遮在脸上也分得清。
 * @param angle 手掌整体旋转角度
 * @param armFrom 手臂起点（通常在画面边缘）；为 null 时只画手掌
 */
fun DrawScope.drawHand(colors: EyeColors, center: Offset, size: Float, angle: Float = 0f, armFrom: Offset? = null) {
    val ill = colors.illustration
    val outline = size * 0.05f
    if (armFrom != null) drawLine(ill.shirt, armFrom, Offset(center.x, center.y + size * 0.3f), size * 0.56f, StrokeCap.Round)
    rotate(angle, center) {
        val palm = Size(size * 0.8f, size)
        val tl = Offset(center.x - palm.width / 2, center.y - palm.height / 2)
        // 拇指（在手掌后面）
        val thumbTl = Offset(center.x - palm.width * 0.5f - size * 0.16f, center.y + size * 0.08f - size * 0.24f)
        val thumb = Size(size * 0.32f, size * 0.48f)
        drawOval(ill.skin, thumbTl, thumb)
        drawOval(ill.skinLine, thumbTl, thumb, style = Stroke(outline))
        drawRoundRect(ill.skin, tl, palm, CornerRadius(size * 0.4f))
        drawRoundRect(ill.skinLine, tl, palm, CornerRadius(size * 0.4f), style = Stroke(outline))
        // 指缝：三道短线，让轮廓一眼读成「手」
        for (i in 1..3) {
            val x = tl.x + palm.width * i / 4f
            drawLine(ill.skinLine, Offset(x, tl.y + size * 0.08f), Offset(x, tl.y + size * 0.36f), size * 0.04f, StrokeCap.Round)
        }
    }
}

/** 示意手机：返回屏幕区域左上角与尺寸，方便调用方在屏幕里画内容。 */
fun DrawScope.drawPhone(colors: EyeColors, center: Offset, height: Float): Pair<Offset, Size> {
    val w = height * 0.5f
    val topLeft = Offset(center.x - w / 2, center.y - height / 2)
    drawRoundRect(colors.ink, topLeft, Size(w, height), CornerRadius(w * 0.18f))
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
