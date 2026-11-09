package com.zkyjss.eyeproject.core.ui.component

import com.zkyjss.eyeproject.core.designsystem.theme.eyeGlowBorder
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import com.zkyjss.eyeproject.core.designsystem.theme.eyeSoftSurface
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.EyeGlow
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.EyeSpacing
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.theme.rememberReducedMotion
import kotlin.math.cos
import kotlin.math.sin

/* DESIGN.md：柔和双阴影表面，图形使用渐变光晕，文字保持中性色。 */

/** 主卡：每页一个视觉焦点，同色浮雕表面。 */
@Composable
fun EyeHeroCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = EyeSpacing.heroPadding,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(EyeSpacing.itemGap),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalEyeColors.current
    Column(
        modifier.fillMaxWidth()
            .eyeGlass(colors, EyeShapes.hero, SolidColor(colors.heroStart)).eyeGlowBorder(colors, EyeShapes.hero)
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/** 统一表面入口：保留旧 API，内部使用设计系统双阴影。 */
fun Modifier.eyeGlass(
    colors: EyeColors,
    shape: Shape,
    fill: Brush = SolidColor(colors.glassFill),
    edgeTop: Color = colors.glassEdgeTop,
    elevation: Dp = 0.dp,
): Modifier = eyeSoftSurface(colors, shape, fill, compact = elevation > 0.dp)

/**
 * 读数排版：把「6小时25分」「180 lx」「22 次/分」拆成数字与单位——数字沿用外层文字样式（大号），
 * 单位用 [unit] 字号、次要文字色；数字与单位紧挨时补一个窄空格。[unit] 不为空时接在 [value] 后面。
 */
@Composable
fun eyeReading(value: String, unit: String? = null, unitColor: Color = LocalEyeColors.current.textSecondary): androidx.compose.ui.text.AnnotatedString {
    val unitStyle = LocalEyeTypography.current.unit.toSpanStyle().copy(color = unitColor)
    val text = if (unit.isNullOrEmpty()) value else "$value $unit"
    fun numeric(c: Char) = c.isDigit() || c in ".,:+−—" || (c == '-' && text.length == 1)
    return buildAnnotatedString {
        var i = 0
        var last: Boolean? = null
        while (i < text.length) {
            val c = text[i]
            if (c.isWhitespace()) { append(c); last = null; i++; continue }
            val isNumber = numeric(c)
            var j = i
            while (j < text.length && !text[j].isWhitespace() && numeric(text[j]) == isNumber) j++
            if (last != null && last != isNumber) append(' ')
            if (isNumber) append(text.substring(i, j)) else withStyle(unitStyle) { append(text.substring(i, j)) }
            last = isNumber
            i = j
        }
    }
}

/** 普通浮雕卡片。[tinted] 为 true 时用浅填充底。 */
@Composable
fun EyeCard(
    modifier: Modifier = Modifier,
    tinted: Boolean = false,
    contentPadding: Dp = EyeSpacing.cardPadding,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(EyeSpacing.itemGap),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalEyeColors.current
    Column(
        modifier.fillMaxWidth()
            .eyeGlass(colors, EyeShapes.panel, SolidColor(if (tinted) colors.surfaceVariant else colors.glassFill)).eyeGlowBorder(colors, EyeShapes.panel)
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/** 一项读数：标签在上，数值在下（大号数字 + 小一半的灰色单位）；[attention] 时标签后带琥珀小圆点。 */
@Composable
fun EyeMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    valueColor: Color = LocalEyeColors.current.textPrimary,
    attention: Boolean = false,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EyeText(label, style = typography.label, color = colors.textSecondary, maxLines = 1)
            if (attention) Box(Modifier.padding(start = 6.dp).size(6.dp).clip(CircleShape).background(colors.accent))
        }
        EyeText(eyeReading(value, unit), style = typography.metricValue, color = valueColor, maxLines = 1)
    }
}

/** 同一张卡里并排的几项，用细竖线隔开。 */
@Composable
fun EyeMetricRow(modifier: Modifier = Modifier, items: List<@Composable (Modifier) -> Unit>) {
    val colors = LocalEyeColors.current
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        items.forEachIndexed { i, item ->
            if (i > 0) Box(Modifier.padding(horizontal = 14.dp).width(1.dp).fillMaxHeight().background(colors.borderSubtle))
            item(Modifier.weight(1f))
        }
    }
}

/** 进度圆环：浅填充底环 + 纯色圆头进度弧（默认主色，或 [tint]），从 12 点钟顺时针。默认使用蓝青渐变。 */
@Composable
fun EyeProgressRing(
    fraction: Float?,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 9.dp,
    tint: Color? = null,
    description: String = "",
    /** 圆环内侧的刻度数（0 不画）：每 5 格一根长刻度，已走过的刻度用光流色点亮。 */
    ticks: Int = 0,
    /** 在进度末端加一个亮点，供小尺寸首页圆环使用。 */
    showEndpoint: Boolean = false,
    startAngle: Float = -90f,
    sweepAngle: Float = 360f,
    uniformTicks: Boolean = false,
    center: @Composable () -> Unit = {},
) {
    val colors = LocalEyeColors.current
    val reduced = rememberReducedMotion()
    val animated by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), tween(if (reduced) 0 else EyeMotion.LONG, easing = EyeMotion.Standard), label = "圆环进度")
    Box(modifier.semantics { if (description.isNotEmpty()) contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val progress = if (fraction == null) null else animated
            if (ticks > 0) drawRingTicks(colors, progress, strokeWidth.toPx(), ticks, startAngle, sweepAngle, uniformTicks)
            drawProgressRing(colors, progress, strokeWidth.toPx(), tint, showEndpoint, startAngle, sweepAngle)
        }
        center()
    }
}

/** 圆环内侧刻度：等长模式按弧线位置取蓝青渐变色并发光；旧式模式保留长短刻度。 */
fun DrawScope.drawRingTicks(colors: EyeColors, fraction: Float?, stroke: Float, count: Int,
    startAngle: Float = -90f, sweepAngle: Float = 360f, uniform: Boolean = false) {
    if (uniform) {
        val outer = size.minDimension / 2 - stroke * 1.7f
        val inner = outer - stroke * 0.45f
        for (i in 0 until count) {
            val t = i.toFloat() / (count - 1).coerceAtLeast(1)
            val a = Math.toRadians((startAngle + sweepAngle * t).toDouble())
            val c = cos(a).toFloat(); val s = sin(a).toFloat()
            val color = ringTone(colors, t)
            val from = Offset(center.x + c * inner, center.y + s * inner)
            val to = Offset(center.x + c * outer, center.y + s * outer)
            drawDataGlowTick(colors, color, from, to, stroke * 0.12f)
            drawLine(color, from, to, stroke * 0.12f, StrokeCap.Round)
        }
        return
    }
    val ringRadius = (size.minDimension - stroke * 3.2f) / 2
    val outer = ringRadius - stroke / 2 - stroke * 0.9f
    for (i in 0 until count) {
        val major = i % 5 == 0
        val inner = outer - if (major) stroke * 1.1f else stroke * 0.6f
        val t = i.toFloat() / count
        val a = Math.toRadians((t * 360f - 90f).toDouble())
        val lit = fraction != null && t <= fraction
        val color = when {
            lit -> lerp(colors.flowStart, colors.flowEnd, t / fraction!!.coerceAtLeast(0.01f))
            else -> colors.textTertiary.copy(alpha = if (major) 0.55f else 0.28f)
        }
        val c = cos(a).toFloat(); val s = sin(a).toFloat()
        drawLine(color, Offset(center.x + c * inner, center.y + s * inner), Offset(center.x + c * outer, center.y + s * outer),
            strokeWidth = if (major) stroke * 0.2f else stroke * 0.13f, cap = StrokeCap.Round, alpha = if (lit) (if (major) 1f else 0.75f) else 1f)
    }
}

private fun ringTone(colors: EyeColors, position: Float): Color = if (position < 0.5f) {
    lerp(colors.brandStart, colors.dataCyan, position * 2f)
} else {
    lerp(colors.dataCyan, colors.dataAqua, (position - 0.5f) * 2f)
}

fun DrawScope.drawProgressRing(colors: EyeColors, fraction: Float?, stroke: Float, tint: Color? = null,
    showEndpoint: Boolean = false, startAngle: Float = -90f, sweepAngle: Float = 360f) {
    val arcSize = Size(size.minDimension - stroke, size.minDimension - stroke)
    val topLeft = Offset((size.width - arcSize.width) / 2, (size.height - arcSize.height) / 2)
    val totalSweep = sweepAngle.coerceIn(0f, 360f)
    drawArc(colors.ringTrack, startAngle, totalSweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    if (fraction == null) return
    val progress = fraction.coerceIn(0f, 1f)
    val sweep = totalSweep * progress
    if (sweep <= 0f) return
    val brush = if (tint != null) SolidColor(tint) else Brush.sweepGradient(
        0f to colors.brandStart, (totalSweep / 720f) to colors.dataCyan, (totalSweep / 360f) to colors.dataAqua, 1f to colors.dataAqua,
        center = center,
    )
    // 发光只落在数据弧的副本，主图形与文字保持清晰。
    rotate(startAngle) {
        if (tint == null) {
            drawDataGlowArc(colors, topLeft, arcSize, sweep, stroke)
        }
        drawArc(brush, 0f, sweep, false, topLeft, arcSize, style = Stroke(stroke))
        val radius = arcSize.width / 2
        val end = Math.toRadians(sweep.toDouble())
        val endColor = tint ?: ringTone(colors, progress)
        // 独立圆帽避免角度零点处把渐变尾色混入起点。
        drawCircle(tint ?: colors.brandStart, stroke / 2, Offset(center.x + radius, center.y))
        drawCircle(endColor, stroke / 2, Offset(center.x + radius * cos(end).toFloat(), center.y + radius * sin(end).toFloat()))
        if (showEndpoint) drawCircle(colors.white, stroke * 0.28f,
            Offset(center.x + radius * cos(end).toFloat(), center.y + radius * sin(end).toFloat()))
    }
}

/** AI 入口标识：浮雕图标底与主色声波线，不创建无意义的循环动画。 */
@Composable
fun EyeAiMark(size: Dp, modifier: Modifier = Modifier) {
    val colors = LocalEyeColors.current
    Box(modifier.size(size).eyeSoftSurface(colors, EyeShapes.medium, compact = true), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 0.56f)) { drawWaveGlyph(colors.primary) }
    }
}

/** 声波线：五根竖线，中间最高。 */
fun DrawScope.drawWaveGlyph(color: Color) {
    val w = size.width
    val h = size.height
    val heights = listOf(0.34f, 0.64f, 1f, 0.64f, 0.34f)
    val stroke = w * 0.1f
    heights.forEachIndexed { i, k ->
        val x = w * (0.1f + i * 0.2f)
        drawLine(color, Offset(x, h / 2 - h * k / 2), Offset(x, h / 2 + h * k / 2), stroke, StrokeCap.Round)
    }
}

/** 说明框（替代旧的吉祥物对话气泡）：浅色块底的一段正文，可带标题。 */
@Composable
fun EyeCallout(text: String, modifier: Modifier = Modifier, title: String? = null) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(modifier.fillMaxWidth().clip(EyeShapes.large).background(colors.surfaceVariant).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (title != null) EyeText(title, style = typography.subtitle, color = colors.textPrimary)
        EyeText(text, style = typography.body, color = colors.textPrimary)
    }
}

/** 页面底色（纯色，不加渐变）。放在滚动容器的 verticalScroll 之后，与内容一起绘制。 */
fun Modifier.eyeBackdrop(colors: EyeColors): Modifier = drawBehind { drawRect(colors.appBackground) }
