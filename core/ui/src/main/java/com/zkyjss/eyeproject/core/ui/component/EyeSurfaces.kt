package com.zkyjss.eyeproject.core.ui.component

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
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.EyeSpacing
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.theme.rememberReducedMotion
import kotlin.math.cos
import kotlin.math.sin

/*
 * 层次规则（2026-11-08 视觉改版，与苹果端 EyeStyle / EyeSurfaces 一致）：
 * - 卡片一律纯色、不描边、不投影，只靠「灰底白卡 / 黑底深灰卡」的明度差分层
 * - 每页最重要的一张卡用 [EyeHeroCard]（大圆角 22、内边距更宽），其余用 [EyeCard]（圆角 18）
 * - 一张卡只讲一件事；读数「大数字 + 小一半的灰色单位」（[eyeReading]）
 */

/** 主卡：每页一张，纯色面、大圆角。 */
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
            .eyeGlass(colors, EyeShapes.hero, SolidColor(colors.heroStart))
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/**
 * 卡片面：裁圆角 → 纯色填充。改版后不再有高光与描边；[elevation] 大于 0 时才加投影（只给浮在内容上的元素用）。
 * 卡片、列表组共用（名称沿用旧的「玻璃面」）。
 */
fun Modifier.eyeGlass(
    colors: EyeColors,
    shape: Shape,
    fill: Brush = SolidColor(colors.glassFill),
    edgeTop: Color = colors.glassEdgeTop,
    elevation: Dp = 0.dp,
): Modifier = (if (elevation > 0.dp) shadow(elevation, shape, clip = false, ambientColor = colors.shadow, spotColor = colors.shadow) else this)
    .clip(shape)
    .background(fill)
    .then(if (edgeTop.alpha > 0f) Modifier.border(1.dp, edgeTop, shape) else Modifier)

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

/** 普通卡片：纯色面。[tinted] 为 true 时用浅填充底。 */
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
            .eyeGlass(colors, EyeShapes.panel, SolidColor(if (tinted) colors.surfaceVariant else colors.glassFill))
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
            EyeText(label, style = typography.bodySecondary, color = colors.textSecondary, maxLines = 1)
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

/** 进度圆环：浅填充底环 + 纯色圆头进度弧（默认主色，或 [tint]），从 12 点钟顺时针。不发光。 */
@Composable
fun EyeProgressRing(
    fraction: Float?,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 9.dp,
    tint: Color? = null,
    description: String = "",
    /** 圆环内侧的刻度数（0 不画）：每 5 格一根长刻度，已走过的刻度用光流色点亮。 */
    ticks: Int = 0,
    center: @Composable () -> Unit = {},
) {
    val colors = LocalEyeColors.current
    val animated by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), tween(EyeMotion.LONG, easing = EyeMotion.Standard), label = "圆环进度")
    Box(modifier.semantics { if (description.isNotEmpty()) contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val progress = if (fraction == null) null else animated
            if (ticks > 0) drawRingTicks(colors, progress, strokeWidth.toPx(), ticks)
            drawProgressRing(colors, progress, strokeWidth.toPx(), tint)
        }
        center()
    }
}

/** 圆环内侧的刻度装饰：[count] 根放射短线，每 5 根一根长刻度；进度内的刻度用光流色（主蓝 → 冰蓝）点亮。 */
fun DrawScope.drawRingTicks(colors: EyeColors, fraction: Float?, stroke: Float, count: Int) {
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

fun DrawScope.drawProgressRing(colors: EyeColors, fraction: Float?, stroke: Float, tint: Color? = null) {
    val arcSize = Size(size.minDimension - stroke, size.minDimension - stroke)
    val topLeft = Offset((size.width - arcSize.width) / 2, (size.height - arcSize.height) / 2)
    drawArc(colors.surfaceVariant, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
    if (fraction == null) return
    val sweep = 360f * fraction.coerceIn(0.01f, 1f)
    drawArc(tint ?: colors.primary, -90f, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
}

/**
 * AI 标识（替代旧的卡通头像）：深色主题为发光光球（轻呼吸），浅色主题为主色声波线。
 * 真人数字人形象到位后，问诊页头部用照片，列表入口仍用本标识。
 */
@Composable
fun EyeAiMark(size: Dp, modifier: Modifier = Modifier) {
    val colors = LocalEyeColors.current
    if (colors.hasGlow) {
        val reduced = rememberReducedMotion()
        val breathe by rememberInfiniteTransition(label = "AI 光球").animateFloat(
            0.9f, 1.06f, infiniteRepeatable(tween(1800, easing = EyeMotion.Gentle), RepeatMode.Reverse), label = "呼吸")
        Canvas(modifier.size(size)) {
            val r = this.size.minDimension / 2
            val halo = if (reduced) 1f else breathe
            drawCircle(Brush.radialGradient(listOf(colors.glow.copy(alpha = 0.5f), colors.glow.copy(alpha = 0f)), center, r * halo), r * halo)
            drawCircle(Brush.radialGradient(listOf(colors.white, colors.brandStart, colors.primary, colors.buttonStart),
                Offset(center.x - r * 0.24f, center.y - r * 0.3f), r * 0.9f), r * 0.6f)
        }
    } else {
        Box(modifier.size(size).clip(EyeShapes.small).background(colors.primarySoft), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size * 0.56f)) { drawWaveGlyph(colors.primary) }
        }
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
