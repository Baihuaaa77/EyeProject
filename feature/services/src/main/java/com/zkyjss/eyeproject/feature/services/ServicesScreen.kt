package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.designsystem.theme.eyeLiquidGlass
import androidx.compose.animation.core.LinearEasing
import com.zkyjss.eyeproject.core.ui.component.eyeBackdrop
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.theme.rememberReducedMotion
import com.zkyjss.eyeproject.core.designsystem.util.getTopStatusBarPadding
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingLarge
import com.zkyjss.eyeproject.core.ui.component.EyePageHeader
import com.zkyjss.eyeproject.core.ui.component.EyeSelectCard
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.EyeTileTone
import com.zkyjss.eyeproject.core.ui.component.colors
import com.zkyjss.eyeproject.core.ui.component.EyeHeroCard
import com.zkyjss.eyeproject.core.ui.component.rememberEyeHaptics
import com.zkyjss.eyeproject.core.ui.component.drawTumblingE
import com.zkyjss.eyeproject.core.ui.component.leafEyePath
import kotlin.math.abs
import com.zkyjss.eyeproject.core.designsystem.theme.EyeSpacing
import com.zkyjss.eyeproject.core.ui.component.EyeListGroup
import com.zkyjss.eyeproject.core.ui.component.EyeListRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.res.painterResource
import com.zkyjss.eyeproject.core.ui.component.*
import com.zkyjss.eyeproject.core.designsystem.theme.eyeSoftSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.text.style.TextAlign

/** 自测页：方法示范与四项浮雕入口；普通字号双列，窄屏大字号自动单列。 */
@Composable
fun ServicesScreen(
    onDryEyeClick: () -> Unit,
    onPresbyopiaClick: () -> Unit,
    onStrabismusClick: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    onOpenAccount: (() -> Unit)? = null,
    onColorVisionClick: () -> Unit = {},
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.appBackground)
            .verticalScroll(rememberScrollState())
            .eyeBackdrop(colors)
            .padding(top = getTopStatusBarPadding())
            .screenHorizontalPaddingLarge(),
        verticalArrangement = Arrangement.spacedBy(EyeSpacing.cardGap)
    ) {
        EyePageHeader("服务", onOpenSettings)
        DemoHero()
        EyeText("全部自测", Modifier.padding(top = EyeSpacing.sectionGap - EyeSpacing.cardGap, start = 4.dp),
            style = typography.title, color = colors.textPrimary)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val singleColumn = maxWidth / LocalDensity.current.fontScale < 280.dp
            Column(verticalArrangement = Arrangement.spacedBy(EyeSpacing.md)) {
                if (singleColumn) {
                    ServiceTile("干眼自测", ServiceKind.DRY_EYE, Modifier.fillMaxWidth(), onDryEyeClick)
                    ServiceTile("眼位自测", ServiceKind.ALIGNMENT, Modifier.fillMaxWidth(), onStrabismusClick)
                    ServiceTile("老视自测", ServiceKind.PRESBYOPIA, Modifier.fillMaxWidth(), onPresbyopiaClick)
                    ServiceTile("色觉自测", ServiceKind.COLOR, Modifier.fillMaxWidth(), onColorVisionClick)
                } else {
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(EyeSpacing.md)) {
                        ServiceTile("干眼自测", ServiceKind.DRY_EYE, Modifier.weight(1f).fillMaxHeight(), onDryEyeClick)
                        ServiceTile("眼位自测", ServiceKind.ALIGNMENT, Modifier.weight(1f).fillMaxHeight(), onStrabismusClick)
                    }
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(EyeSpacing.md)) {
                        ServiceTile("老视自测", ServiceKind.PRESBYOPIA, Modifier.weight(1f).fillMaxHeight(), onPresbyopiaClick)
                        ServiceTile("色觉自测", ServiceKind.COLOR, Modifier.weight(1f).fillMaxHeight(), onColorVisionClick)
                    }
                }
            }
        }
        EyeText(
            "自测结果仅供参考，不能替代医院检查。",
            style = typography.bodySecondary,
            color = colors.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 24.dp)
        )
    }
}

/** 四项自测等权展示，标题与箭头一起提供可操作线索。 */
@Composable
private fun ServiceTile(title: String, kind: ServiceKind, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(modifier.eyeLiquidGlass(colors, EyeShapes.card, pressed = pressed).clickable(interactionSource = interaction, indication = null, role = Role.Button) { haptics.tap(); onClick() }
        .padding(EyeSpacing.lg), verticalArrangement = Arrangement.spacedBy(EyeSpacing.lg)) {
        Box(Modifier.size(48.dp).eyeSoftSurface(colors, EyeShapes.medium, compact = true), contentAlignment = Alignment.Center) {
            ServiceGlyph(kind, when (kind) {
                ServiceKind.DRY_EYE -> colors.tileTeal
                ServiceKind.ALIGNMENT -> colors.tileIndigo
                ServiceKind.PRESBYOPIA -> colors.tileOrange
                ServiceKind.COLOR -> colors.tileRed
            }, 26.dp)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EyeText(title, Modifier.weight(1f), style = typography.subtitle, color = colors.textPrimary)
            EyeIcon(painterResource(com.zkyjss.eyeproject.core.designsystem.R.drawable.ic_arrow_right), null,
                Modifier.size(16.dp), tint = colors.primary)
        }
    }
}

/** 服务页轮播的三种示范：场景、名称与一句做法。 */
private class DemoClip(val motion: GuideMotion, val name: String, val line: String)

private val DEMO_CLIPS = listOf(
    DemoClip(GuideMotion.BLINK, "干眼自测", "像平时一样自然眨眼"),
    DemoClip(GuideMotion.COVER, "眼位自测", "本人遮眼，再移开 · 协助者在手机旁打光"),
    DemoClip(GuideMotion.NEAR, "老视自测", "看清 E 的开口朝哪边"),
)

/** 每种示范连播的遍数。 */
private const val DEMO_REPEATS = 2

/**
 * 示范主卡：随机轮流演示三项自测的做法（每种连播两遍），上方是测试名称与一句做法（随示范淡入淡出）。
 * 点示范区换下一个。与苹果端 DemoHeroCard 同一排版与文案；系统「移除动画」时停在关键帧。
 */
@Composable
private fun DemoHero() {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    val order = remember { DEMO_CLIPS.shuffled() }
    val lengths = remember(order) { order.map { guideCycleMs(it.motion) * DEMO_REPEATS } }
    val total = lengths.sum()
    val reduced = rememberReducedMotion()
    var paused by remember { mutableStateOf(false) }
    var clock by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(paused, reduced) {
        if (!paused && !reduced) while (isActive) { delay(32); clock = (clock + 32f) % total }
    }
    var skip by remember { mutableIntStateOf(0) }
    val segment by remember { derivedStateOf { segmentAt(clock, lengths).first } }
    val clip = order[(segment + skip) % order.size]
    EyeHeroCard(contentPadding = 16.dp, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AnimatedContent(clip, transitionSpec = { fadeIn(tween(EyeMotion.MEDIUM)) togetherWith fadeOut(tween(EyeMotion.SHORT)) },
            label = "示范标题") { c ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                EyeText("示范：${c.name.removeSuffix("自测")}", style = typography.subtitle, color = colors.textPrimary)
                EyeText(c.line, style = typography.bodySecondary, color = colors.textSecondary)
            }
        }
        if (!reduced) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            EyeText(if (paused) "播放示范" else "暂停示范",
                Modifier.defaultMinSize(minHeight = 48.dp).clickable(role = Role.Button) { paused = !paused }.padding(12.dp),
                style = typography.bodySecondary, color = colors.primaryDark)
        }
        GuideFrame(clip.motion, {
            val (index, elapsed) = segmentAt(clock, lengths)
            val cycle = guideCycleMs(clip.motion).toFloat()
            if (index >= 0) (elapsed % cycle) / cycle else 0f
        }, "正在示范${clip.name}，点一下换下一个示范", Modifier.fillMaxWidth().height(190.dp).clip(EyeShapes.large)
            .clickable(role = Role.Button, onClickLabel = "换下一个示范") { skip++; haptics.tap() })
    }
}

/** 轮播时钟 [ms] 落在第几段、该段已播多少毫秒。 */
private fun segmentAt(ms: Float, lengths: List<Int>): Pair<Int, Float> {
    var start = 0f
    lengths.forEachIndexed { i, length ->
        if (ms < start + length || i == lengths.lastIndex) return i to (ms - start).coerceAtLeast(0f)
        start += length
    }
    return 0 to 0f
}

private enum class ServiceKind { DRY_EYE, ALIGNMENT, PRESBYOPIA, COLOR }

/** 自测图标：水滴（干眼）/ 双眼对位（眼位）/ 视标 E（老视）/ 色点圆（色觉），线性风格，[ink] 着色。 */
@Composable
private fun ServiceGlyph(kind: ServiceKind, ink: androidx.compose.ui.graphics.Color, glyphSize: androidx.compose.ui.unit.Dp = 20.dp) {
    Canvas(Modifier.size(glyphSize)) {
        val s = size.minDimension
        val stroke = Stroke(s * 0.09f, cap = StrokeCap.Round)
        when (kind) {
            ServiceKind.DRY_EYE -> {
                val drop = Path().apply {
                    moveTo(s * 0.5f, s * 0.08f)
                    cubicTo(s * 0.62f, s * 0.3f, s * 0.84f, s * 0.48f, s * 0.84f, s * 0.64f)
                    cubicTo(s * 0.84f, s * 0.84f, s * 0.68f, s * 0.94f, s * 0.5f, s * 0.94f)
                    cubicTo(s * 0.32f, s * 0.94f, s * 0.16f, s * 0.84f, s * 0.16f, s * 0.64f)
                    cubicTo(s * 0.16f, s * 0.48f, s * 0.38f, s * 0.3f, s * 0.5f, s * 0.08f)
                    close()
                }
                drawPath(drop, ink, style = stroke)
                drawArc(ink, 100f, 70f, false, Offset(s * 0.3f, s * 0.5f), Size(s * 0.34f, s * 0.3f), style = Stroke(s * 0.08f, cap = StrokeCap.Round))
            }
            ServiceKind.ALIGNMENT -> {
                for (side in listOf(-1, 1)) {
                    val cx = s * 0.5f + side * s * 0.24f
                    drawPath(leafEyePath(cx - s * 0.2f, cx + s * 0.2f, s * 0.5f, s * 0.13f), ink, style = Stroke(s * 0.08f))
                    drawCircle(ink, s * 0.06f, Offset(cx, s * 0.5f))
                }
                drawLine(ink, Offset(s * 0.1f, s * 0.86f), Offset(s * 0.9f, s * 0.86f), s * 0.08f, StrokeCap.Round)
            }
            ServiceKind.PRESBYOPIA -> drawTumblingE(ink, Offset(s / 2, s / 2), s * 0.62f, 0)
            ServiceKind.COLOR -> {
                drawCircle(ink, s * 0.42f, Offset(s / 2, s / 2), style = Stroke(s * 0.07f))
                // 一圈主色点、中间一竖暖色点：像一张极简的色觉图
                for (i in 0 until 6) {
                    val a = Math.toRadians(i * 60.0 + 30.0)
                    drawCircle(ink, s * 0.07f, Offset(s / 2 + (s * 0.24f * kotlin.math.cos(a)).toFloat(), s / 2 + (s * 0.24f * kotlin.math.sin(a)).toFloat()))
                }
                drawCircle(ink, s * 0.08f, Offset(s / 2, s * 0.38f))
                drawCircle(ink, s * 0.08f, Offset(s / 2, s * 0.62f))
            }
        }
    }
}
