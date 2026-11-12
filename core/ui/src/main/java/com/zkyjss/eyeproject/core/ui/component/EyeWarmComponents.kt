package com.zkyjss.eyeproject.core.ui.component

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.R
import com.zkyjss.eyeproject.core.designsystem.theme.*

/** 暖色参考稿中的卡片；不承载数据或导航逻辑。 */
@Composable
fun EyeWarmCard(
    modifier: Modifier = Modifier,
    fill: Brush = EyeBrown.surface,
    padding: androidx.compose.ui.unit.Dp = 18.dp,
    spacing: androidx.compose.ui.unit.Dp = 6.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .eyeSoftSurface(LocalEyeColors.current, EyeShapes.card, fill)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** 眼睛只表达现有评估等级；未知数据有独立灰色素材。 */
@Composable
fun EyeLoadArtwork(level: String?, modifier: Modifier = Modifier) {
    Image(
        painterResource(
            when (level) {
                "LOW" -> R.drawable.load_eye_sage
                "MEDIUM" -> R.drawable.load_eye_amber
                "HIGH" -> R.drawable.load_eye_rose
                else -> R.drawable.load_eye_empty
            }
        ),
        null,
        modifier,
        contentScale = ContentScale.Fit,
    )
}

/** 连续负荷分值的三段条；无分值时不显示标记。 */
@Composable
fun EyeLoadRail(score: Int?, modifier: Modifier = Modifier) {
    val c = LocalEyeColors.current
    Canvas(
        modifier.fillMaxWidth().height(16.dp).semantics {
            contentDescription = score?.let { "用眼负荷 $it，满分 100" } ?: "暂无用眼负荷数据"
        }
    ) {
        val y = size.height / 2
        drawLine(EyeBrown.rail, Offset(0f, y), Offset(size.width, y), 5.dp.toPx(), StrokeCap.Round)
        score?.let {
            val x = size.width * it.coerceIn(0, 100) / 100f
            drawLine(c.primary, Offset(0f, y), Offset(x, y), 5.dp.toPx(), StrokeCap.Round)
        }
        for (i in 1..2) drawLine(
            c.heroEnd,
            Offset(size.width * i / 3, y - 4.dp.toPx()),
            Offset(size.width * i / 3, y + 4.dp.toPx()),
            3.dp.toPx(),
        )
        score?.let {
            val x = size.width * it.coerceIn(0, 100) / 100f
            drawCircle(c.heroEnd, 8.dp.toPx(), Offset(x, y))
            drawCircle(c.primary, 6.dp.toPx(), Offset(x, y))
            drawCircle(EyeBrown.marker, 4.dp.toPx(), Offset(x, y))
        }
    }
}

/** 原生趋势丝带：只绘制连续的有效数据段，缺失日期保留间断。 */
@Composable
fun EyeTrendRibbon(values: List<Int?>, modifier: Modifier = Modifier) {
    val phases =
        listOf(values.take(2), values.drop(2).take(3), values.drop(5).take(2)).map { phase ->
            phase.filterNotNull().takeIf { it.isNotEmpty() }?.average()?.toInt()
        }
    val c = LocalEyeColors.current
    Box(
        modifier.fillMaxWidth().height(94.dp).semantics {
            contentDescription =
                if (phases.none { it != null }) "暂无用眼趋势数据"
                else "用眼负荷趋势，" + values.joinToString { it?.toString() ?: "未记录" }
        }
    ) {
        if (phases.none { it != null })
            EyeText(
                "暂无趋势记录",
                Modifier.align(Alignment.Center),
                style = LocalEyeTypography.current.bodySecondary,
                color = c.textSecondary,
            )
        else
            Canvas(
                Modifier.fillMaxSize().drawWithCache {
                    val points = phases.mapIndexed { i, v ->
                        v?.let {
                            Offset(
                                size.width * (i + .5f) / phases.size,
                                size.height * (.76f - .53f * it / 100f),
                            )
                        }
                    }
                    val spans = mutableListOf<List<Offset>>()
                    var run = mutableListOf<Offset>()
                    for (p in points) {
                        if (p == null) {
                            if (run.isNotEmpty()) spans.add(run)
                            run = mutableListOf()
                        } else run.add(p)
                    }
                    if (run.isNotEmpty()) spans.add(run)
                    val brush =
                        Brush.horizontalGradient(
                            listOf(EyeBrown.sage, c.primary, EyeBrown.terracotta)
                        )
                    val paths = spans.map { span ->
                        val left =
                            Offset(
                                (span.first().x - size.width / phases.size * .48f).coerceAtLeast(
                                    0f
                                ),
                                span.first().y + 4.dp.toPx(),
                            )
                        val right =
                            Offset(
                                (span.last().x + size.width / phases.size * .48f).coerceAtMost(
                                    size.width
                                ),
                                span.last().y - 2.dp.toPx(),
                            )
                        fun edge(path: Path, arr: List<Offset>) {
                            for (i in 0 until arr.lastIndex) {
                                val a = arr[i]
                                val b = arr[i + 1]
                                path.cubicTo((a.x + b.x) / 2, a.y, (a.x + b.x) / 2, b.y, b.x, b.y)
                            }
                        }
                        val top =
                            listOf(left) + span.map { it.copy(y = it.y - 6.dp.toPx()) } + right
                        val bottom =
                            listOf(right) +
                                span.reversed().map { it.copy(y = it.y + 6.dp.toPx()) } +
                                left
                        Path().apply {
                            moveTo(left.x, left.y)
                            edge(this, top)
                            edge(this, bottom)
                            close()
                        }
                    }
                    val glow =
                        Paint().apply {
                            brush.applyTo(size, this, .45f)
                            asFrameworkPaint().maskFilter =
                                BlurMaskFilter(16.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
                        }
                    onDrawBehind {
                        for (path in paths) {
                            drawIntoCanvas {
                                it.save()
                                it.translate(0f, 12.dp.toPx())
                                it.drawPath(path, glow)
                                it.restore()
                            }
                            drawPath(path, brush)
                        }
                    }
                }
            ) {}
    }
}

/** 可选日的本周用屏柱图；空值仅画轨道，零值不伪造高度。 */
@Composable
fun EyeWeekUsageCard(
    values: List<Int?>,
    labels: List<String>,
    todayIndex: Int,
    onMissing: (() -> Unit)? = null,
) {
    val c = LocalEyeColors.current
    val t = LocalEyeTypography.current
    var selected by remember(todayIndex) { mutableIntStateOf(todayIndex.coerceIn(0, 6)) }
    val week = (0..6).map { values.getOrNull(it) }
    val amount = week[selected]
    EyeWarmCard(fill = EyeBrown.hero) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EyeText("手机使用时长", Modifier.weight(1f), style = t.subtitle, color = c.textPrimary)
            EyeText(
                "本周",
                Modifier.clip(EyeShapes.chip)
                    .background(c.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                style = t.caption,
                color = c.textSecondary,
            )
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EyeText(
                eyeReading(
                    amount?.let { "${it/60}小时${(it%60).toString().padStart(2,'0')}分" } ?: "—"
                ),
                style = t.metricValue,
                color = c.textPrimary,
            )
            EyeText(
                labels.getOrNull(selected) ?: "",
                Modifier.padding(bottom = 4.dp),
                style = t.caption,
                color = c.textSecondary,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val max = week.maxOfOrNull { it ?: 0 }?.coerceAtLeast(60) ?: 60
            week.forEachIndexed { i, v ->
                Column(
                    Modifier.weight(1f)
                        .clip(EyeShapes.small)
                        .clickable(role = Role.Button) { selected = i }
                        .semantics {
                            contentDescription =
                                "${labels.getOrNull(i).orEmpty()}，${v?.let { "${it}分钟" } ?: "未记录"}"
                        }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier.width(22.dp)
                            .height(64.dp)
                            .clip(CircleShape)
                            .background(EyeBrown.rail)
                            .then(
                                if (i == selected) Modifier.border(1.5.dp, c.primary, CircleShape)
                                else Modifier
                            ),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        if (v != null && v > 0)
                            Box(
                                Modifier.fillMaxWidth()
                                    .fillMaxHeight((v.toFloat() / max * .9f).coerceIn(.02f, 1f))
                                    .clip(CircleShape)
                                    .background(
                                        if (i == selected) c.primary
                                        else EyeBrown.warm.copy(alpha = .72f)
                                    )
                            )
                    }
                    EyeText(
                        labels.getOrNull(i) ?: "",
                        style = t.caption,
                        color = if (i == selected) c.primary else c.textSecondary,
                    )
                }
            }
        }
        if (week.all { it == null } && onMissing != null)
            EyeText(
                "开启用屏统计",
                Modifier.clickable(role = Role.Button, onClick = onMissing)
                    .padding(vertical = 8.dp),
                style = t.bodySecondary,
                color = c.primary,
            )
    }
}
