package com.zkyjss.eyeproject.feature.services

import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.zkyjss.eyeproject.core.ui.component.BubbleTail
import com.zkyjss.eyeproject.core.ui.component.MascotBubble
import com.zkyjss.eyeproject.core.ui.component.rememberMascotSounds
import com.zkyjss.eyeproject.core.ui.component.drawTumblingE
import com.zkyjss.eyeproject.core.ui.component.leafEyePath
import kotlin.math.abs

/** 服务页：小瞳示范舞台 + 四个自测入口。 */
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
            .padding(top = getTopStatusBarPadding())
            .screenHorizontalPaddingLarge(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        EyePageHeader("服务", onOpenSettings, onOpenAccount = onOpenAccount)
        DemoHero()
        Spacer(Modifier.height(4.dp))
        EyeText("选择一项自测", style = typography.subtitle, color = colors.textPrimary)

        EyeSelectCard(
            content = "干眼自测",
            subtitle = "约 2 分钟 · 自然眨眼与睁眼观察",
            leading = { ServiceGlyph(ServiceKind.DRY_EYE) },
            onClick = onDryEyeClick
        )
        EyeSelectCard(
            content = "眼位自测",
            subtitle = "注视、遮眼、跟随 · 需一位协助者",
            leading = { ServiceGlyph(ServiceKind.ALIGNMENT) },
            onClick = onStrabismusClick,
        )
        EyeSelectCard(
            content = "老视自测",
            subtitle = "近视力与调节 · 看 E 选方向",
            leading = { ServiceGlyph(ServiceKind.PRESBYOPIA) },
            onClick = onPresbyopiaClick,
        )
        EyeSelectCard(
            content = "色觉自测",
            subtitle = "约 1 分钟 · 看色点图认数字",
            leading = { ServiceGlyph(ServiceKind.COLOR) },
            onClick = onColorVisionClick,
        )
        EyeText(
            "自测结果仅供参考，不能替代医院检查。",
            style = typography.bodySecondary,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
        )
    }
}

/** 服务页轮播的三种示范：场景、气泡里依次说的两句话。 */
private class DemoClip(val motion: GuideMotion, val name: String, val lines: List<String>)

private val DEMO_CLIPS = listOf(
    DemoClip(GuideMotion.BLINK, "干眼自测", listOf("干眼自测：像平时一样眨眨眼", "跟着我一起做吧")),
    DemoClip(GuideMotion.COVER, "眼位自测", listOf("眼位自测：遮住一只眼，再移开", "找家人帮忙一起做哦")),
    DemoClip(GuideMotion.NEAR, "老视自测", listOf("老视自测：看清 E 的开口朝哪边", "跟着我一起做吧")),
)

/** 每种示范连播的遍数。 */
private const val DEMO_REPEATS = 2

/**
 * 示范舞台：小瞳随机轮流演示三项自测的做法（每种连播两遍），上方气泡先说这是哪项测试、再说「跟着我一起做吧」。
 * 点舞台换下一个示范。与苹果端 DemoHeroCard 同一排版与文案；系统「移除动画」时停在关键帧、气泡常显。
 */
@Composable
private fun DemoHero() {
    val colors = LocalEyeColors.current
    val reduced = rememberReducedMotion()
    val sounds = rememberMascotSounds()
    val order = remember { DEMO_CLIPS.shuffled() }
    val lengths = remember(order) { order.map { guideCycleMs(it.motion) * DEMO_REPEATS } }
    val total = lengths.sum()
    val clock = rememberInfiniteTransition(label = "服务页示范").animateFloat(
        0f, total.toFloat(), infiniteRepeatable(tween(total, easing = LinearEasing)), label = "示范进度")
    var skip by remember { mutableIntStateOf(0) }
    // 当前在第几段、该段已播多久（毫秒）：段号变化或进入 / 离开气泡时段才重组
    val segment by remember { derivedStateOf { segmentAt(clock.value, lengths).first } }
    val line by remember {
        derivedStateOf {
            val (index, elapsed) = segmentAt(clock.value, lengths)
            val k = elapsed / lengths[index]
            when { k in 0.03f..0.42f -> 0; k in 0.52f..0.92f -> 1; else -> -1 }
        }
    }
    val clip = order[(segment + skip) % order.size]
    val shownLine = if (reduced) 0 else line
    val pop by animateFloatAsState(if (shownLine >= 0) 1f else 0f,
        if (shownLine >= 0 && !reduced) spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow) else tween(180), label = "示范气泡")
    // 收起动画期间继续显示刚才那句（普通数组，不触发重组）
    val lastLine = remember { IntArray(1) }
    if (shownLine >= 0) lastLine[0] = shownLine
    Column(Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.primarySoft).padding(12.dp)) {
        Box(Modifier.fillMaxWidth().height(60.dp).padding(start = 4.dp), contentAlignment = Alignment.BottomStart) {
            if (pop > 0.01f) MascotBubble(clip.lines[lastLine[0]], Modifier.graphicsLayer {
                scaleX = 0.6f + 0.4f * pop; scaleY = 0.6f + 0.4f * pop; alpha = pop.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(0.15f, 1f)
            }, tail = BubbleTail.START)
        }
        GuideFrame(clip.motion, {
            val (index, elapsed) = segmentAt(clock.value, lengths)
            val cycle = guideCycleMs(clip.motion).toFloat()
            if (index >= 0) (elapsed % cycle) / cycle else 0f
        }, "小瞳正在示范${clip.name}，点一下换下一个示范", Modifier.fillMaxWidth().height(190.dp)
            .clickable(role = Role.Button, onClickLabel = "换下一个示范") { skip++; sounds.play("eye_pop") })
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

/** 入口卡图标：水滴（干眼）/ 双眼对位（眼位）/ 视标 E（老视）/ 色点圆（色觉），统一主色线性风格。 */
@Composable
private fun ServiceGlyph(kind: ServiceKind) {
    val colors = LocalEyeColors.current
    Canvas(Modifier.size(28.dp)) {
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
                drawPath(drop, colors.primary, style = stroke)
                drawArc(colors.primary, 100f, 70f, false, Offset(s * 0.3f, s * 0.5f), Size(s * 0.34f, s * 0.3f), style = Stroke(s * 0.08f, cap = StrokeCap.Round))
            }
            ServiceKind.ALIGNMENT -> {
                for (side in listOf(-1, 1)) {
                    val cx = s * 0.5f + side * s * 0.24f
                    drawPath(leafEyePath(cx - s * 0.2f, cx + s * 0.2f, s * 0.5f, s * 0.13f), colors.primary, style = Stroke(s * 0.08f))
                    drawCircle(colors.primary, s * 0.06f, Offset(cx, s * 0.5f))
                }
                drawLine(colors.accent, Offset(s * 0.1f, s * 0.86f), Offset(s * 0.9f, s * 0.86f), s * 0.08f, StrokeCap.Round)
            }
            ServiceKind.PRESBYOPIA -> drawTumblingE(colors.primary, Offset(s / 2, s / 2), s * 0.62f, 0)
            ServiceKind.COLOR -> {
                drawCircle(colors.primary, s * 0.42f, Offset(s / 2, s / 2), style = Stroke(s * 0.07f))
                // 一圈主色点、中间一竖暖色点：像一张极简的色觉图
                for (i in 0 until 6) {
                    val a = Math.toRadians(i * 60.0 + 30.0)
                    drawCircle(colors.primary, s * 0.07f, Offset(s / 2 + (s * 0.24f * kotlin.math.cos(a)).toFloat(), s / 2 + (s * 0.24f * kotlin.math.sin(a)).toFloat()))
                }
                drawCircle(colors.accent, s * 0.08f, Offset(s / 2, s * 0.38f))
                drawCircle(colors.accent, s * 0.08f, Offset(s / 2, s * 0.62f))
            }
        }
    }
}
