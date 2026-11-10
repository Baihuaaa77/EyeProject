package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.zkyjss.eyeproject.core.designsystem.theme.EyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.illustration
import kotlin.math.sqrt

/*
 * 方法演示里的示意人物，苹果端 EyeCartoon.swift 逐项对应：
 * - 人物是平面上半身短发男孩（2026-11-06 起）：纯色平涂，不做渐变与立体阴影
 * - 手是人物自己的（2026-11-10 起）：肩 → 肘 → 腕两段袖子接手掌，肘由手腕位置反解，手不会凭空飞来；
 *   手掌的画法沿用原来的肤色手掌 + 拇指 + 指缝
 */

/** 示意人物某只眼的中心（side = -1 画面左侧，+1 画面右侧）。 */
fun figureEyeCenter(center: Offset, size: Float, side: Int): Offset =
    Offset(center.x + side * size * 0.19f, center.y - size * 0.02f)

/**
 * 示意人物（方法演示用）：平面上半身短发男孩——圆脸、侧分短发、豆豆眼（带一点高光）、眉毛、腮红、小微笑，
 * 蓝色圆领上衣。纯色平涂，不做渐变与立体阴影。
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

// 手臂骨骼（以头宽为单位）：上臂、前臂、手腕到掌心
private const val ARM_UPPER = 0.66f
private const val ARM_FORE = 0.58f
private const val ARM_HAND = 0.22f

/** 手臂垂在身侧时手腕的位置（相对头部中心、以头宽为单位；side = -1 人物右臂，在画面左侧）。 */
fun figureArmRest(side: Int): Offset = Offset(side * 0.56f, 1.86f)

/** 手掌盖住某只眼时手腕的位置：同侧的手直接抬起，另一侧的手越过鼻梁伸过去（[eyeSide] 同 [figureEyeCenter]）。 */
fun figureArmCover(side: Int, eyeSide: Int): Offset =
    if (side == eyeSide) Offset(eyeSide * 0.24f, 0.22f) else Offset(eyeSide * 0.11f, 0.21f)

/**
 * 示意人物自己的手臂（蓝色袖子 + 肤色手掌）：肩固定在身体上，上臂、前臂长度不变；先在三维里由手腕位置反解出肘，
 * 再正投影到画面——手抬到脸前时肘自然收在胸前，放下时垂在身侧，手始终连着身体。手的方向顺着前臂。
 * 袖子与上衣同色，外面一圈略深的描边把它和身体分开。
 * @param side -1 人物右臂（画面左侧），+1 左臂
 * @param wrist 手腕位置，相对头部中心、以头宽为单位（x 向右、y 向下），见 [figureArmRest] / [figureArmCover]
 * @param depth 手腕离身体平面的前伸量（头宽的倍数）：垂在身侧约 0.05，抬到脸前约 0.45
 */
fun DrawScope.drawFigureArm(colors: EyeColors, center: Offset, size: Float, side: Int, wrist: Offset, depth: Float) {
    val s = size
    val ill = colors.illustration
    // 肩 → 腕的三维向量；够不着时把手腕拉回到手臂伸直的位置
    val sx = side * 0.5f
    val sy = 0.66f
    var dx = wrist.x - sx
    var dy = wrist.y - sy
    var dz = depth
    var reach = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.001f)
    val longest = (ARM_UPPER + ARM_FORE) * 0.999f
    if (reach > longest) {
        val k = longest / reach
        dx *= k; dy *= k; dz *= k; reach = longest
    }
    val along = (ARM_UPPER * ARM_UPPER - ARM_FORE * ARM_FORE + reach * reach) / (2f * reach)
    val bend = sqrt((ARM_UPPER * ARM_UPPER - along * along).coerceAtLeast(0f))
    val nx = dx / reach
    val ny = dy / reach
    val nz = dz / reach
    // 肘偏向「下、略向外、略向前」：去掉沿肩腕连线的分量后归一
    val px = side * 0.25f
    val py = 1f
    val pz = 0.3f
    val dot = px * nx + py * ny + pz * nz
    var mx = px - dot * nx
    var my = py - dot * ny
    val mz = pz - dot * nz
    val ml = sqrt(mx * mx + my * my + mz * mz)
    if (ml > 0.0001f) { mx /= ml; my /= ml } else { mx = side.toFloat(); my = 0f }
    fun at(x: Float, y: Float) = Offset(center.x + x * s, center.y + y * s)
    val shoulder = at(sx, sy)
    val elbow = at(sx + nx * along + mx * bend, sy + ny * along + my * bend)
    val wristAt = at(sx + dx, sy + dy)
    val fore = wristAt - elbow
    val foreLen = fore.getDistance()
    val dir = if (foreLen > s * 0.001f) fore / foreLen else Offset(0f, -1f)
    // 袖子：肩 → 肘 → 腕，圆头圆角；先画略宽的深色描边，再画衣服本色
    val sleeveWidth = s * 0.25f
    val sleeve = Path().apply { moveTo(shoulder.x, shoulder.y); lineTo(elbow.x, elbow.y); lineTo(wristAt.x, wristAt.y) }
    drawPath(sleeve, ill.shirtShade, style = Stroke(sleeveWidth + s * 0.035f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(sleeve, ill.shirt, style = Stroke(sleeveWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
    // 袖口：一圈浅色滚边（与领口同色）
    drawLine(ill.shirtLight, wristAt - dir * (s * 0.05f), wristAt, sleeveWidth, StrokeCap.Butt)
    drawHand(colors, wristAt + dir * (s * ARM_HAND), s * 0.44f, dir, thumbSide = -side)
}

/**
 * 示意手（肤色手掌 + 拇指 + 指缝，带一圈浅描边，遮在脸上也分得清）：形状与原来的手掌相同，方向顺着前臂。
 * @param up 指尖方向（单位向量）
 * @param thumbSide 指尖朝上时拇指在哪一侧（-1 画面左，+1 画面右）
 */
private fun DrawScope.drawHand(colors: EyeColors, center: Offset, size: Float, up: Offset, thumbSide: Int) {
    val ill = colors.illustration
    val right = Offset(-up.y, up.x)
    val outline = size * 0.05f
    // 手的局部坐标：x 沿 right，y 指向手腕，单位为 size；手掌宽 0.8、高 1（两端半圆）
    fun h(x: Float, y: Float) = center + right * (x * size) - up * (y * size)
    // 拇指（在手掌后面）：宽 0.32、高 0.48 的椭圆，用圆头粗线画
    val thumbA = h(thumbSide * 0.4f, 0f)
    val thumbB = h(thumbSide * 0.4f, 0.16f)
    drawLine(ill.skinLine, thumbA, thumbB, size * 0.32f + outline, StrokeCap.Round)
    drawLine(ill.skin, thumbA, thumbB, size * 0.32f - outline, StrokeCap.Round)
    val palmA = h(0f, -0.1f)
    val palmB = h(0f, 0.1f)
    drawLine(ill.skinLine, palmA, palmB, size * 0.8f + outline, StrokeCap.Round)
    drawLine(ill.skin, palmA, palmB, size * 0.8f - outline, StrokeCap.Round)
    // 指缝：三道短线，让轮廓一眼读成「手」
    for (x in listOf(-0.2f, 0f, 0.2f)) drawLine(ill.skinLine, h(x, -0.42f), h(x, -0.14f), size * 0.04f, StrokeCap.Round)
}
