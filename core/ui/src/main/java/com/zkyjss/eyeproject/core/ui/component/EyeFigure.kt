package com.zkyjss.eyeproject.core.ui.component

import android.graphics.BlurMaskFilter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import com.zkyjss.eyeproject.core.designsystem.theme.EyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.illustration
import kotlin.math.sqrt

/*
 * 方法演示里的示意人物（2026-11-10 起）：与卡片同一种材质的「柔和浮雕人偶」，苹果端 EyeCartoon.swift 逐项对应。
 * - 头、手、身体都是中性色，靠 DESIGN.md 的成对阴影（左上高光 + 右下暗影）浮起，不再用肤色、腮红等卡通配色
 * - 眼睛是凹下去的眼窝里一颗蓝 → 青 → 水绿渐变、带荧光的虹膜：全图唯一的饱和色（「颜色只给数据」）
 * - 手是人物自己的：肩 → 肘 → 腕两段袖子接手掌，肘由手腕位置反解，手不会凭空飞来
 */

/** 示意人物某只眼的中心（side = -1 画面左侧，+1 画面右侧）。 */
fun figureEyeCenter(center: Offset, size: Float, side: Int): Offset =
    Offset(center.x + side * size * 0.19f, center.y - size * 0.02f)

/**
 * 柔和浮雕：在本体下面先画右下暗影、左上高光两份模糊副本，清晰的本体由调用方随后再画。
 * [depth] 为阴影偏移（像素），不足半像素时不画；[draw] 用给定画笔把形状画一遍。
 */
internal fun DrawScope.softRelief(colors: EyeColors, depth: Float, draw: (Canvas, Paint) -> Unit) {
    if (depth < 0.5f) return
    val blur = BlurMaskFilter(depth * 1.5f, BlurMaskFilter.Blur.NORMAL)
    drawIntoCanvas { canvas ->
        for ((tint, shift) in listOf(colors.shadow to depth, colors.shadowLight to -depth * 0.8f)) {
            val paint = Paint().apply { color = tint; asFrameworkPaint().maskFilter = blur }
            canvas.save(); canvas.translate(shift, shift); draw(canvas, paint); canvas.restore()
        }
    }
}

/** 上衣与袖子共用的渐变：袖子叠在身体上时颜色连续，只靠浮雕阴影分出前后。 */
private fun garmentBrush(colors: EyeColors, center: Offset, s: Float): Brush {
    val ill = colors.illustration
    return Brush.linearGradient(
        listOf(ill.garmentLight, ill.garment),
        start = Offset(center.x - s * 0.62f, center.y + s * 0.5f), end = Offset(center.x + s * 0.4f, center.y + s * 1.4f)
    )
}

/**
 * 示意人物：上半身人偶，眼睛位置沿用旧版（演示里遮眼、视线、眨眼的坐标不用改）。
 * @param size 头宽（脸宽约 0.8 倍，耳朵到耳朵约 0.96 倍）
 * @param gaze 看向（-1..1，x/y）：虹膜在眼窝里平移
 * @param lid 闭眼程度 0 睁开 .. 1 闭合（< 0.2 睁开度时画成闭眼弧线）
 * @param wide 睁大程度（>1 眼睛更大、眉毛上扬），用于「保持睁眼」
 * @param bottom 上衣下沿的 y；缺省为头部中心下方 0.8 倍头宽（演示舞台传舞台底边，让上身一直画到画框底）
 * @param relief 是否画浮雕阴影与虹膜荧光；深色底上的小图标传 false
 */
fun DrawScope.drawFigure(
    colors: EyeColors,
    center: Offset,
    size: Float,
    gaze: Offset = Offset.Zero,
    lid: Float = 0f,
    wide: Float = 1f,
    bottom: Float = center.y + size * 0.8f,
    relief: Boolean = true,
) {
    val s = size
    val ill = colors.illustration
    val depth = if (relief) s * 0.035f else 0f
    fun p(x: Float, y: Float) = Offset(center.x + x * s, center.y + y * s)
    // 上衣：圆肩，一直画到 bottom
    val y0 = center.y + s * 0.5f
    val hem = maxOf(bottom, y0 + s * 0.3f)
    val shirt = Path().apply {
        moveTo(center.x - s * 0.62f, hem)
        lineTo(center.x - s * 0.62f, y0 + s * 0.3f)
        quadraticBezierTo(center.x - s * 0.62f, y0, center.x - s * 0.32f, y0)
        lineTo(center.x + s * 0.32f, y0)
        quadraticBezierTo(center.x + s * 0.62f, y0, center.x + s * 0.62f, y0 + s * 0.3f)
        lineTo(center.x + s * 0.62f, hem)
        close()
    }
    softRelief(colors, depth) { canvas, paint -> canvas.drawPath(shirt, paint) }
    drawPath(shirt, garmentBrush(colors, center, s))
    // 脖子 + 圆领（领口里露出的一点脖子 + 浅色滚边）
    drawRect(ill.clayShade, p(-0.1f, 0.28f), Size(s * 0.2f, s * 0.26f))
    val collar = Path().apply {
        moveTo(center.x - s * 0.16f, y0)
        quadraticBezierTo(center.x, y0 + s * 0.15f, center.x + s * 0.16f, y0)
    }
    drawPath(collar, ill.clayShade)
    drawPath(collar, ill.garmentLight, style = Stroke(s * 0.035f, cap = StrokeCap.Round))
    // 耳朵（在脸后面）
    for (side in listOf(-1, 1)) {
        drawCircle(ill.clay, s * 0.08f, p(side * 0.4f, 0.04f))
        drawCircle(ill.clayShade, s * 0.035f, p(side * 0.405f, 0.04f))
    }
    // 脸：左上受光的径向渐变
    val faceTl = p(-0.4f, -0.43f)
    val face = Size(s * 0.8f, s * 0.86f)
    softRelief(colors, depth) { canvas, paint -> canvas.drawOval(Rect(faceTl, face), paint) }
    drawOval(Brush.radialGradient(listOf(ill.clayLight, ill.clay), center = p(-0.14f, -0.18f), radius = s * 0.72f), faceTl, face)
    // 侧分短发：盖住头顶，刘海从左向右扫开，露出额头
    drawPath(Path().apply {
        val a = p(-0.43f, 0.04f); moveTo(a.x, a.y)
        cubic(p(-0.47f, -0.36f), p(-0.22f, -0.56f), p(0.04f, -0.54f))
        cubic(p(0.32f, -0.53f), p(0.5f, -0.36f), p(0.43f, 0.04f))
        cubic(p(0.4f, -0.1f), p(0.34f, -0.2f), p(0.24f, -0.25f))
        cubic(p(0.1f, -0.17f), p(-0.12f, -0.15f), p(-0.3f, -0.23f))
        cubic(p(-0.37f, -0.15f), p(-0.4f, -0.06f), p(-0.43f, 0.04f))
        close()
    }, Brush.linearGradient(listOf(ill.hairLight, ill.hair), start = p(-0.3f, -0.56f), end = p(0.3f, 0f)))
    // 眉毛 + 眼睛（眼窝 + 渐变虹膜）
    val open = (1f - lid).coerceIn(0f, 1f)
    for (side in listOf(-1, 1)) {
        val base = figureEyeCenter(center, s, side)
        val browY = base.y - s * 0.135f - (wide - 1f) * s * 0.08f
        drawPath(Path().apply {
            moveTo(base.x - s * 0.06f, browY + s * 0.01f)
            quadraticBezierTo(base.x, browY - s * 0.025f, base.x + s * 0.06f, browY + s * 0.01f)
        }, ill.hair, style = Stroke(s * 0.024f, cap = StrokeCap.Round))
        if (open < 0.2f) {
            drawPath(
                Path().apply {
                    moveTo(base.x - s * 0.07f, base.y)
                    quadraticBezierTo(base.x, base.y + s * 0.05f, base.x + s * 0.07f, base.y)
                },
                ill.lineStrong, style = Stroke(width = s * 0.024f, cap = StrokeCap.Round)
            )
            continue
        }
        val rx = s * 0.08f
        val ry = s * 0.074f * wide * open
        val wellTl = Offset(base.x - rx, base.y - ry)
        val well = Size(rx * 2, ry * 2)
        drawOval(ill.eyeWell, wellTl, well)
        val iris = Offset(base.x + gaze.x * s * 0.028f, base.y + gaze.y * s * 0.022f)
        val ir = s * 0.05f
        clipPath(Path().apply { addOval(Rect(wellTl, well)) }) {
            if (relief) drawIntoCanvas { canvas ->
                canvas.drawCircle(iris, ir * 1.25f, Paint().apply {
                    color = colors.glow
                    asFrameworkPaint().maskFilter = BlurMaskFilter(ir * 0.6f, BlurMaskFilter.Blur.NORMAL)
                })
            }
            drawCircle(Brush.verticalGradient(listOf(colors.brandStart, colors.dataCyan, colors.dataAqua), iris.y - ir, iris.y + ir), ir, iris)
            drawCircle(colors.ink, ir * 0.44f, iris)
            drawCircle(colors.white, ir * 0.24f, Offset(iris.x + ir * 0.36f, iris.y - ir * 0.4f))
        }
        // 上眼睑：一道深色弧线，眼睛在浅色脸上也立得住
        drawArc(ill.lineStrong, 198f, 144f, false, wellTl, well, style = Stroke(s * 0.02f, cap = StrokeCap.Round))
    }
    // 鼻子、微笑
    drawPath(Path().apply {
        val a = p(-0.022f, 0.085f); val b = p(0f, 0.105f); val e = p(0.022f, 0.085f)
        moveTo(a.x, a.y); quadraticBezierTo(b.x, b.y, e.x, e.y)
    }, ill.line, style = Stroke(s * 0.018f, cap = StrokeCap.Round))
    drawPath(Path().apply {
        val a = p(-0.06f, 0.2f); val b = p(0f, 0.25f); val e = p(0.06f, 0.2f)
        moveTo(a.x, a.y); quadraticBezierTo(b.x, b.y, e.x, e.y)
    }, ill.lineStrong, style = Stroke(s * 0.022f, cap = StrokeCap.Round))
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
 * 示意人物自己的手臂（袖子 + 手）：肩固定在身体上，上臂、前臂长度不变；先在三维里由手腕位置反解出肘，
 * 再正投影到画面——手抬到脸前时肘自然收在胸前，放下时垂在身侧，手始终连着身体。手的方向顺着前臂。
 * @param side -1 人物右臂（画面左侧），+1 左臂
 * @param wrist 手腕位置，相对头部中心、以头宽为单位（x 向右、y 向下），见 [figureArmRest] / [figureArmCover]
 * @param depth 手腕离身体平面的前伸量（头宽的倍数）：垂在身侧约 0.05，抬到脸前约 0.45
 */
fun DrawScope.drawFigureArm(
    colors: EyeColors, center: Offset, size: Float, side: Int, wrist: Offset, depth: Float, relief: Boolean = true,
) {
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
    // 袖子：肩 → 肘 → 腕，圆头圆角
    val sleeveWidth = s * 0.25f
    val sleeve = Path().apply { moveTo(shoulder.x, shoulder.y); lineTo(elbow.x, elbow.y); lineTo(wristAt.x, wristAt.y) }
    softRelief(colors, if (relief) s * 0.03f else 0f) { canvas, paint ->
        canvas.drawPath(sleeve, paint.apply {
            style = PaintingStyle.Stroke; strokeWidth = sleeveWidth; strokeCap = StrokeCap.Round; strokeJoin = StrokeJoin.Round
        })
    }
    drawPath(sleeve, garmentBrush(colors, center, s), style = Stroke(sleeveWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
    // 袖口：一圈浅色滚边
    drawLine(ill.garmentLight, wristAt - dir * (s * 0.05f), wristAt, sleeveWidth, StrokeCap.Butt)
    drawHand(colors, wristAt + dir * (s * ARM_HAND), s * 0.44f, dir, thumbSide = -side, relief = relief)
}

/**
 * 示意手（手背朝外）：掌 + 四指 + 拇指，都由圆头粗线拼成，带浅描边与指缝。
 * @param up 指尖方向（单位向量）
 * @param thumbSide 指尖朝上时拇指在哪一侧（-1 画面左，+1 画面右）
 */
private fun DrawScope.drawHand(colors: EyeColors, center: Offset, size: Float, up: Offset, thumbSide: Int, relief: Boolean) {
    val ill = colors.illustration
    val right = Offset(-up.y, up.x)
    // 手的局部坐标：x 沿 right，y 指向手腕，单位为 size
    fun h(x: Float, y: Float) = center + right * (x * size) - up * (y * size)
    softRelief(colors, if (relief) size * 0.07f else 0f) { canvas, paint ->
        canvas.drawLine(h(0f, -0.14f), h(0f, 0.14f), paint.apply { strokeWidth = size * 0.74f; strokeCap = StrokeCap.Round })
    }
    // 拇指、掌、四指：先整体画一遍略粗的描边色，再画本色，重叠处的描边自然消失
    val parts = listOf(
        Triple(h(thumbSide * 0.33f, 0.24f), h(thumbSide * 0.47f, 0.02f), 0.2f),
        Triple(h(-0.19f, 0.25f), h(0.19f, 0.25f), 0.42f),
        Triple(h(-0.29f, -0.26f), h(-0.29f, 0.12f), 0.185f),
        Triple(h(-0.097f, -0.385f), h(-0.097f, 0.12f), 0.185f),
        Triple(h(0.097f, -0.345f), h(0.097f, 0.12f), 0.185f),
        Triple(h(0.29f, -0.2f), h(0.29f, 0.12f), 0.185f),
    )
    val edge = size * 0.035f
    for ((a, b, w) in parts) drawLine(ill.line, a, b, w * size + edge * 2, StrokeCap.Round)
    val fill = Brush.radialGradient(listOf(ill.clayLight, ill.clay), center = h(-0.15f, -0.2f), radius = size * 0.9f)
    for ((a, b, w) in parts) drawLine(fill, a, b, w * size, StrokeCap.Round)
    // 指缝
    for (x in listOf(-0.193f, 0f, 0.193f)) drawLine(ill.line, h(x, -0.16f), h(x, 0.1f), size * 0.03f, StrokeCap.Round)
}
