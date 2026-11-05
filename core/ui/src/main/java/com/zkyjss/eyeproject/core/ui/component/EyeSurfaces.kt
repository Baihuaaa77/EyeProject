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
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.theme.rememberReducedMotion
import kotlin.math.cos
import kotlin.math.sin

/*
 * 层次规则（与苹果端 EyeStyle / EyeSurfaces 一致）：
 * - 每页最重要的一张卡用 [EyeHeroCard]：更亮的底（深色为深蓝渐变 + 淡蓝描边）、更深的投影，浮在最上层
 * - 其余卡片用 [EyeCard] 平铺：玻璃面（半透明 + 上沿高光描边），不加投影
 * - 玻璃不依赖系统模糊（安卓 12 以下也一致）：底下是柔和渐变，半透明本身就是毛玻璃观感
 * - 一张卡只讲一件事；同卡多项读数用 [EyeMetricRow] 竖线分隔，标签在上、数值在下
 */

/** 主卡：每页一张，渐变底 + 描边 + 深投影（深色主题投影带蓝色泛光）。 */
@Composable
fun EyeHeroCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 20.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalEyeColors.current
    Column(
        modifier.fillMaxWidth()
            .shadow(if (colors.isDark) 28.dp else 20.dp, EyeShapes.hero, clip = false,
                ambientColor = if (colors.hasGlow) colors.glow else colors.shadow, spotColor = colors.shadow)
            .eyeGlass(colors, EyeShapes.hero, if (colors.isDark) colors.heroBrush else SolidColor(colors.heroStart),
                edgeTop = if (colors.isDark) colors.heroBorder else colors.glassEdgeTop, elevation = 0.dp)
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/**
 * 玻璃面：柔和投影 → 裁圆角 → 半透明填充 → 左上一道斜向柔光（液态玻璃的高光）→ 上亮下淡的渐变描边。
 * 卡片、列表组、底栏共用。
 */
fun Modifier.eyeGlass(
    colors: EyeColors,
    shape: Shape,
    fill: Brush = SolidColor(colors.glassFill),
    edgeTop: Color = colors.glassEdgeTop,
    elevation: Dp = 6.dp,
): Modifier = (if (elevation > 0.dp) shadow(elevation, shape, clip = false, ambientColor = colors.shadow, spotColor = colors.shadow) else this)
    .clip(shape)
    .background(fill)
    .drawBehind {
        drawRect(Brush.linearGradient(listOf(colors.white.copy(alpha = if (colors.isDark) 0.06f else 0.35f), Color.Transparent),
            Offset.Zero, Offset(size.width * 0.6f, size.height * 0.7f)))
    }
    .border(1.dp, Brush.verticalGradient(listOf(edgeTop, colors.glassEdgeBottom)), shape)

/** 普通卡片：平铺玻璃面。[tinted] 为 true 时用浅色块底（入口行，如 AI 主动问诊）。 */
@Composable
fun EyeCard(
    modifier: Modifier = Modifier,
    tinted: Boolean = false,
    contentPadding: Dp = 18.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalEyeColors.current
    Column(
        modifier.fillMaxWidth()
            .eyeGlass(colors, EyeShapes.panel, SolidColor(if (tinted) colors.surfaceVariant.copy(alpha = 0.8f) else colors.glassFill))
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/** 一项读数：标签在上，数值在下（数值大号粗体、单位常规字重）。 */
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
            if (attention) Box(Modifier.padding(start = 6.dp).size(6.dp).clip(EyeShapes.chip).background(colors.accent))
        }
        EyeText(
            buildAnnotatedString {
                append(value)
                if (!unit.isNullOrEmpty()) withStyle(SpanStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)) { append(" $unit") }
            },
            style = typography.metricValue, color = valueColor, maxLines = 1,
        )
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

/**
 * 进度圆环：底环 + 光流渐变进度弧（主蓝 → 青蓝，或 [tint]），深色主题下进度弧带同色泛光、末端一个亮点。
 * 光晕用多层半透明粗描边叠出，不依赖模糊（所有系统版本一致）。
 */
@Composable
fun EyeProgressRing(
    fraction: Float?,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 9.dp,
    tint: Color? = null,
    description: String = "",
    center: @Composable () -> Unit = {},
) {
    val colors = LocalEyeColors.current
    val animated by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), tween(EyeMotion.LONG, easing = EyeMotion.Standard), label = "圆环进度")
    Box(modifier.semantics { if (description.isNotEmpty()) contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawProgressRing(colors, if (fraction == null) null else animated, strokeWidth.toPx(), tint) }
        center()
    }
}

fun DrawScope.drawProgressRing(colors: EyeColors, fraction: Float?, stroke: Float, tint: Color? = null) {
    val pad = stroke * 1.6f
    val arcSize = Size(size.minDimension - pad * 2, size.minDimension - pad * 2)
    val topLeft = Offset((size.width - arcSize.width) / 2, (size.height - arcSize.height) / 2)
    drawArc(colors.surfaceVariant, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
    if (fraction == null) return
    val f = fraction.coerceIn(0.02f, 1f)
    val sweep = 360f * f
    // 光流：从起点的主蓝沿弧线流向末端的青蓝（扫描渐变跟着画布一起转到 12 点钟起笔）
    val brush = if (tint != null) Brush.sweepGradient(listOf(tint.copy(alpha = 0.55f), tint), center)
    else {
        // 起点的圆头会往回伸进 360° 之前的一小段：那一段也接回起始色，否则起点会冒出一块末端色的半圆
        val tail = maxOf(f + 0.01f, 0.96f).coerceAtMost(0.999f)
        Brush.sweepGradient(0f to colors.flowStart, f to colors.flowEnd, (f + tail) / 2 to colors.flowEnd, tail to colors.flowStart,
            1f to colors.flowStart, center = center)
    }
    rotate(-90f) {
        if (colors.hasGlow) {
            for ((w, a) in listOf(3.2f to 0.07f, 2.4f to 0.11f, 1.7f to 0.17f)) {
                drawArc(brush, 0f, sweep, false, topLeft, arcSize, alpha = a, style = Stroke(stroke * w, cap = StrokeCap.Round))
            }
        }
        drawArc(brush, 0f, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
    if (colors.hasGlow) {
        val r = arcSize.width / 2
        val a = Math.toRadians((sweep - 90f).toDouble())
        drawCircle(colors.white, stroke * 0.36f, Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat()))
    }
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
