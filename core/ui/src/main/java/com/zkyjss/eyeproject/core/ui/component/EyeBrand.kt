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

/*
 * 品牌语言「青瞳」v2：
 * - 吉祥物「小瞳」：圆润的「团子」脸 + 豆豆眼（没有眼白和写实瞳孔，只留一点高光）+ 头顶双叶嫩芽 + 腮红与小微笑。
 *   刻意卡通化、低细节：写实的眼睛会带来「被盯着看」的不适感，豆豆眼更亲和，也更适合儿童。
 * - 标志：品牌渐变圆角底上的白色小瞳头像——标志即吉祥物，品牌只有一个形象。
 * 启动图标（app/src/main/res/drawable/ic_launcher_*）与 [drawBrandMark] 使用同一组 108dp 视口坐标（可见区 18..90）。
 */

/** 叶形眼轮廓（用于眼位类图标）：左右尖端 + 上下两段三次曲线，[halfHeight] 为最大半高。 */
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

/** 品牌标志：渐变圆角底 + 白色小瞳头像。 */
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

fun DrawScope.drawBrandMark(colors: EyeColors, s: Float, origin: Offset = Offset.Zero) {
    // 108dp 启动图标视口 → 本画布：可见区 18..90 映射到 0..s
    val k = s / 72f
    fun v(x: Float, y: Float) = Offset(origin.x + (x - 18f) * k, origin.y + (y - 18f) * k)
    fun oval(cx: Float, cy: Float, rx: Float, ry: Float) = Pair(v(cx - rx, cy - ry), Size(rx * 2 * k, ry * 2 * k))

    drawRoundRect(
        brush = Brush.linearGradient(colors.brandColors, start = origin, end = Offset(origin.x + s, origin.y + s)),
        topLeft = origin, size = Size(s, s), cornerRadius = CornerRadius(s * 0.3f)
    )
    // 嫩芽
    drawPath(
        Path().apply {
            val a = v(54f, 38f); val c = v(54.5f, 33f); val e = v(55f, 29f)
            moveTo(a.x, a.y); quadraticBezierTo(c.x, c.y, e.x, e.y)
        },
        colors.white, style = Stroke(width = 2.2f * k, cap = StrokeCap.Round)
    )
    rotate(25f, v(50f, 28f)) { val (tl, sz) = oval(50f, 28f, 4.5f, 2.4f); drawOval(colors.white, tl, sz) }
    rotate(-30f, v(59.5f, 26.5f)) { val (tl, sz) = oval(59.5f, 26.5f, 5f, 2.6f); drawOval(colors.white, tl, sz) }
    // 团子头
    drawRoundRect(colors.white, v(29f, 38f), Size(50f * k, 42f * k), CornerRadius(20f * k))
    // 豆豆眼 + 高光
    for (x in listOf(44.5f, 63.5f)) {
        val (tl, sz) = oval(x, 58f, 2.75f, 3.75f)
        drawOval(colors.ink, tl, sz)
        drawCircle(colors.white, 1.25f * k, v(x + 1.1f, 56.25f))
    }
    // 腮红
    for (x in listOf(38.5f, 69.5f)) { val (tl, sz) = oval(x, 64.5f, 2.6f, 1.6f); drawOval(colors.accent.copy(alpha = 0.7f), tl, sz) }
    // 微笑
    drawPath(
        Path().apply {
            val a = v(49f, 64.5f); val c = v(54f, 68.5f); val e = v(59f, 64.5f)
            moveTo(a.x, a.y); quadraticBezierTo(c.x, c.y, e.x, e.y)
        },
        colors.brandStart, style = Stroke(width = 1.8f * k, cap = StrokeCap.Round)
    )
}

// region 吉祥物「小瞳」

/** 吉祥物某只眼的中心（side = -1 画面左侧，+1 画面右侧）。 */
fun mascotEyeCenter(center: Offset, size: Float, side: Int): Offset =
    Offset(center.x + side * size * 0.19f, center.y - size * 0.02f)

/**
 * 画吉祥物小瞳（v2 团子造型）。
 * @param size 头宽
 * @param gaze 看向（-1..1，x/y）：豆豆眼整体平移
 * @param lid 闭眼程度 0 睁开 .. 1 闭合（≥0.8 画成闭眼弧线）
 * @param wide 睁大程度（>1 眼睛更大），用于「保持睁眼」
 * @param smile false 时嘴巴是小圆口（用力 / 惊讶）
 */
fun DrawScope.drawMascot(
    colors: EyeColors,
    center: Offset,
    size: Float,
    gaze: Offset = Offset.Zero,
    lid: Float = 0f,
    wide: Float = 1f,
    smile: Boolean = true,
) {
    val s = size
    val top = center.y - s * 0.42f
    // 双叶嫩芽
    drawPath(
        Path().apply {
            moveTo(center.x, top + s * 0.02f)
            quadraticBezierTo(center.x + s * 0.005f, top - s * 0.05f, center.x + s * 0.015f, top - s * 0.1f)
        },
        colors.brandStart, style = Stroke(width = s * 0.035f, cap = StrokeCap.Round)
    )
    rotate(25f, Offset(center.x - s * 0.06f, top - s * 0.11f)) {
        drawOval(colors.brandStart, Offset(center.x - s * 0.15f, top - s * 0.155f), Size(s * 0.18f, s * 0.09f))
    }
    rotate(-30f, Offset(center.x + s * 0.09f, top - s * 0.14f)) {
        drawOval(colors.brandStart, Offset(center.x - s * 0.01f, top - s * 0.19f), Size(s * 0.2f, s * 0.1f))
    }
    // 团子头 + 顶部柔光
    drawRoundRect(
        brush = Brush.linearGradient(colors.brandColors, start = Offset(center.x - s / 2, top), end = Offset(center.x + s / 2, top + s * 0.84f)),
        topLeft = Offset(center.x - s / 2, top),
        size = Size(s, s * 0.84f),
        cornerRadius = CornerRadius(s * 0.4f)
    )
    rotate(-18f, Offset(center.x - s * 0.2f, top + s * 0.14f)) {
        drawOval(colors.white.copy(alpha = 0.16f), Offset(center.x - s * 0.33f, top + s * 0.09f), Size(s * 0.26f, s * 0.1f))
    }
    // 豆豆眼
    val open = (1f - lid).coerceIn(0f, 1f)
    for (side in listOf(-1, 1)) {
        val base = mascotEyeCenter(center, s, side)
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
    // 腮红
    for (side in listOf(-1, 1)) {
        val c = Offset(center.x + side * s * 0.31f, center.y + s * 0.11f)
        drawOval(colors.accent.copy(alpha = 0.75f), Offset(c.x - s * 0.055f, c.y - s * 0.032f), Size(s * 0.11f, s * 0.064f))
    }
    // 嘴
    val mouthY = center.y + s * 0.1f
    if (smile) {
        drawPath(
            Path().apply {
                moveTo(center.x - s * 0.05f, mouthY)
                quadraticBezierTo(center.x, mouthY + s * 0.05f, center.x + s * 0.05f, mouthY)
            },
            colors.ink, style = Stroke(width = s * 0.024f, cap = StrokeCap.Round)
        )
    } else {
        drawCircle(colors.ink, s * 0.024f, Offset(center.x, mouthY + s * 0.02f))
    }
}

/**
 * 卡通手（连指手套造型 + 手臂）：用于遮眼、点按。
 * @param angle 手掌整体旋转角度
 * @param armFrom 手臂起点（通常在画面边缘）；为 null 时只画手掌
 */
fun DrawScope.drawMitten(colors: EyeColors, center: Offset, size: Float, angle: Float = 0f, armFrom: Offset? = null) {
    val outline = size * 0.07f
    if (armFrom != null) {
        // 手臂：先画描边粗线，再画填充细线，得到带轮廓的圆头手臂
        drawLine(colors.accent, armFrom, center, size * 0.52f, StrokeCap.Round)
        drawLine(colors.accentSoft, armFrom, center, size * 0.52f - outline * 2, StrokeCap.Round)
    }
    rotate(angle, center) {
        val palm = Size(size * 0.8f, size)
        val tl = Offset(center.x - palm.width / 2, center.y - palm.height / 2)
        // 拇指（在手掌后面）
        val thumb = Offset(center.x - palm.width * 0.5f, center.y + size * 0.08f)
        drawOval(colors.accentSoft, Offset(thumb.x - size * 0.16f, thumb.y - size * 0.24f), Size(size * 0.32f, size * 0.48f))
        drawOval(colors.accent, Offset(thumb.x - size * 0.16f, thumb.y - size * 0.24f), Size(size * 0.32f, size * 0.48f), style = Stroke(outline))
        drawRoundRect(colors.accentSoft, tl, palm, CornerRadius(size * 0.4f))
        drawRoundRect(colors.accent, tl, palm, CornerRadius(size * 0.4f), style = Stroke(outline))
        // 指缝：三道短弧线，让轮廓一眼读成「手」
        for (i in 1..3) {
            val x = tl.x + palm.width * i / 4f
            drawLine(colors.accent, Offset(x, tl.y + size * 0.08f), Offset(x, tl.y + size * 0.36f), outline * 0.8f, StrokeCap.Round)
        }
    }
}

/** 卡通手机：返回屏幕区域左上角与尺寸，方便调用方在屏幕里画内容。 */
fun DrawScope.drawCartoonPhone(colors: EyeColors, center: Offset, height: Float): Pair<Offset, Size> {
    val w = height * 0.5f
    val topLeft = Offset(center.x - w / 2, center.y - height / 2)
    drawRoundRect(colors.ink, topLeft, Size(w, height), CornerRadius(w * 0.2f))
    val inset = w * 0.08f
    val screenTl = Offset(topLeft.x + inset, topLeft.y + inset * 1.6f)
    val screen = Size(w - inset * 2, height - inset * 3.2f)
    // 屏幕恒为白底：与正式视标「黑白对比独立于主题」保持一致
    drawRoundRect(colors.white, screenTl, screen, CornerRadius(w * 0.12f))
    // 前置摄像头
    drawCircle(colors.gray, w * 0.035f, Offset(center.x, topLeft.y + inset * 0.8f))
    return screenTl to screen
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
