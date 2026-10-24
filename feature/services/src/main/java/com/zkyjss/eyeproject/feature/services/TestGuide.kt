package com.zkyjss.eyeproject.feature.services

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.theme.rememberReducedMotion
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeSectionCard
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.drawCartoonPhone
import com.zkyjss.eyeproject.core.ui.component.drawMascot
import com.zkyjss.eyeproject.core.ui.component.drawMitten
import com.zkyjss.eyeproject.core.ui.component.drawTumblingE
import com.zkyjss.eyeproject.core.ui.component.mascotEyeCenter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal enum class GuideMotion { POSTURE, ACUITY, FVA, BLINK, OPEN, NEAR, MOVE, CURVE, SWITCH, FIXATE, COVER, FOLLOW }

/** 演示里的一个「分镜」：从循环进度 [start] 起高亮该步骤文字。 */
private data class GuideBeat(val start: Float, val label: String)

/**
 * 一个演示场景：循环时长、分镜、减少动效时展示的关键帧，以及绘制函数。
 * 绘制坐标系固定为 320×200 的「舞台单位」，由 [GuideStage] 等比缩放居中。
 */
private class GuideScene(
    val cycleMs: Int,
    val beats: List<GuideBeat>,
    val keyframe: Float,
    val draw: SceneScope.(phase: Float) -> Unit,
)

/** 场景绘制上下文：舞台单位换算 + 品牌色 + 文字测量。 */
private class SceneScope(
    val scope: DrawScope,
    val colors: EyeColors,
    val unit: Float,
    val origin: Offset,
    val measurer: TextMeasurer,
    val labelStyle: TextStyle,
) {
    fun p(x: Float, y: Float) = Offset(origin.x + x * unit, origin.y + y * unit)
    fun u(v: Float) = v * unit

    /** 以 [center] 为中心画一段文字（舞台坐标）。 */
    fun label(text: String, x: Float, y: Float, color: Color, alpha: Float = 1f) {
        val layout = measurer.measure(text, labelStyle.copy(color = color))
        val c = p(x, y)
        scope.drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f), alpha = alpha)
    }
}

/**
 * 测试前的方法演示卡：卡通吉祥物「小瞳」演示动作 + 与动画同步高亮的分镜步骤 + 文字提示。
 * 示范画布只出现在准备阶段，不参与正式刺激呈现或计时。
 * 点按舞台可暂停/继续；系统「移除动画」时停在关键帧并展示全部步骤。
 */
@Composable
internal fun TestGuide(motion: GuideMotion, title: String, cue: String, onReady: (() -> Unit)? = null) {
    val colors = LocalEyeColors.current
    val type = LocalEyeTypography.current
    val scene = remember(motion) { sceneFor(motion) }
    val reduced = rememberReducedMotion()
    var playing by rememberSaveable(motion) { mutableStateOf(true) }
    var frozen by remember(motion) { mutableFloatStateOf(scene.keyframe) }
    // 无限过渡：测试时钟能正确处理；进度只在绘制阶段读取，整卡不会每帧重组
    val live = rememberInfiniteTransition(label = "方法演示").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(scene.cycleMs, easing = LinearEasing)),
        label = "演示进度"
    )
    val phaseProvider: () -> Float = {
        when {
            reduced -> scene.keyframe
            playing -> live.value
            else -> frozen
        }
    }
    val activeBeat by remember(scene, reduced) {
        derivedStateOf { scene.beats.indexOfLast { phaseProvider() >= it.start }.coerceAtLeast(0) }
    }

    EyeSectionCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                EyeText(title, Modifier.weight(1f), style = type.title, color = colors.textPrimary)
                EyeText(
                    text = if (reduced) "示意" else if (playing) "暂停演示" else "继续演示",
                    style = type.chip,
                    color = colors.primary,
                    modifier = Modifier
                        .clip(EyeShapes.chip)
                        .background(colors.primarySoft)
                        .clickable(enabled = !reduced, role = Role.Button) {
                            if (playing) frozen = live.value
                            playing = !playing
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            GuideStage(scene, phaseProvider, cue, Modifier.fillMaxWidth().height(200.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                scene.beats.forEachIndexed { index, beat ->
                    BeatRow(beat.label, active = reduced || index == activeBeat, done = !reduced && index < activeBeat)
                }
            }
            EyeText(cue, style = type.bodySecondary, color = colors.textSecondary, textAlign = TextAlign.Center)
            if (onReady != null) EyeButton("准备好了", EyeButtonRole.PRIMARY, onReady, Modifier.fillMaxWidth())
        }
    }
}

/** 分镜行：当前步骤主色高亮、已完成步骤弱化，颜色切换有过渡。 */
@Composable
private fun BeatRow(label: String, active: Boolean, done: Boolean) {
    val colors = LocalEyeColors.current
    val type = LocalEyeTypography.current
    val bg by animateColorAsState(if (active) colors.primarySoft else colors.panelBackground, tween(EyeMotion.MEDIUM), label = "分镜底色")
    val fg by animateColorAsState(
        when {
            active -> colors.primary
            done -> colors.textSecondary
            else -> colors.textSecondary.copy(alpha = 0.7f)
        },
        tween(EyeMotion.MEDIUM),
        label = "分镜文字色"
    )
    EyeText(
        text = label,
        style = type.body,
        color = fg,
        modifier = Modifier
            .fillMaxWidth()
            .clip(EyeShapes.small)
            .background(bg)
            .semantics { if (active) stateDescription = "当前步骤" }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

/** 演示舞台：浅主色圆角底 + 地面阴影，场景按 320×200 等比缩放居中。 */
@Composable
private fun GuideStage(scene: GuideScene, phase: () -> Float, description: String, modifier: Modifier = Modifier) {
    val colors = LocalEyeColors.current
    val measurer = rememberTextMeasurer()
    val labelStyle = LocalEyeTypography.current.chip
    Box(modifier.clip(EyeShapes.large).background(colors.primarySoft)) {
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = description }) {
            val unit = minOf(size.width / 320f, size.height / 200f)
            val origin = Offset((size.width - 320f * unit) / 2f, (size.height - 200f * unit) / 2f)
            val sceneScope = SceneScope(this, colors, unit, origin, measurer, labelStyle)
            scene.draw(sceneScope, phase())
        }
    }
}

/** 无文字的循环演示舞台（用于入口页等装饰位置）。 */
@Composable
internal fun GuideIllustration(motion: GuideMotion, description: String, modifier: Modifier = Modifier) {
    val scene = remember(motion) { sceneFor(motion) }
    val reduced = rememberReducedMotion()
    val live = rememberInfiniteTransition(label = "演示").animateFloat(
        0f, 1f, infiniteRepeatable(tween(scene.cycleMs, easing = LinearEasing)), label = "演示进度"
    )
    GuideStage(scene, { if (reduced) scene.keyframe else live.value }, description, modifier.fillMaxWidth().height(200.dp))
}

/** 某个场景一遍的时长（毫秒），供轮播示范排时间。 */
internal fun guideCycleMs(motion: GuideMotion): Int = sceneFor(motion).cycleMs

/** 由外部给进度的无文字演示舞台（服务页轮播示范用）；系统「移除动画」时停在关键帧。 */
@Composable
internal fun GuideFrame(motion: GuideMotion, phase: () -> Float, description: String, modifier: Modifier = Modifier) {
    val scene = remember(motion) { sceneFor(motion) }
    val reduced = rememberReducedMotion()
    GuideStage(scene, { if (reduced) scene.keyframe else phase() }, description, modifier)
}

// region 时间工具

private fun clamp01(t: Float) = t.coerceIn(0f, 1f)
private fun ease(t: Float) = EyeMotion.Gentle.transform(clamp01(t))
private fun seg(p: Float, a: Float, b: Float) = ease((p - a) / (b - a))
private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** 三角脉冲：在 [at] 处达到 1，前后 [half] 内线性回落到 0（用于眨眼、点按）。 */
private fun tri(p: Float, at: Float, half: Float) = clamp01(1f - abs(p - at) / half)

/** 关键帧插值：相邻关键帧之间柔和过渡，相同取值即为停留。 */
private fun keyframes(p: Float, vararg frames: Pair<Float, Float>): Float {
    if (p <= frames.first().first) return frames.first().second
    for (i in 1 until frames.size) {
        val (t1, v1) = frames[i]
        val (t0, v0) = frames[i - 1]
        if (p <= t1) return lerp(v0, v1, seg(p, t0, t1))
    }
    return frames.last().second
}

// endregion

// region 场景

private fun sceneFor(motion: GuideMotion): GuideScene = when (motion) {
    GuideMotion.POSTURE -> postureScene
    GuideMotion.ACUITY -> acuityScene
    GuideMotion.FVA -> fvaScene
    GuideMotion.CURVE -> curveScene
    GuideMotion.BLINK -> blinkScene
    GuideMotion.OPEN -> openScene
    GuideMotion.NEAR -> nearScene
    GuideMotion.MOVE -> moveScene
    GuideMotion.SWITCH -> switchScene
    GuideMotion.FIXATE -> fixateScene
    GuideMotion.COVER -> coverScene
    GuideMotion.FOLLOW -> followScene
}

private fun SceneScope.floorShadow(x: Float, y: Float, w: Float) {
    scope.drawOval(colors.primary.copy(alpha = 0.12f), p(x - w / 2, y - 6f), Size(u(w), u(12f)))
}

/** 头部固定的四角取景框：表达「头保持不动」。 */
private fun SceneScope.headLock(cx: Float, cy: Float, half: Float) {
    val len = 14f
    val stroke = u(3f)
    for (sx in listOf(-1, 1)) for (sy in listOf(-1, 1)) {
        val corner = p(cx + sx * half, cy + sy * half)
        scope.drawLine(colors.primary, corner, Offset(corner.x - sx * u(len), corner.y), stroke, StrokeCap.Round)
        scope.drawLine(colors.primary, corner, Offset(corner.x, corner.y - sy * u(len)), stroke, StrokeCap.Round)
    }
}

/** 准备姿势：手机在小瞳面前前后微调到合适距离后出现对勾；左上角灯光示意光线均匀。 */
private val postureScene = GuideScene(
    cycleMs = 5200,
    beats = listOf(GuideBeat(0f, "① 正对屏幕，露出双眼"), GuideBeat(0.25f, "② 前后微调到提示的距离"), GuideBeat(0.6f, "③ 光线均匀，避免逆光")),
    keyframe = 0.7f,
) { phase ->
    floorShadow(82f, 182f, 100f)
    scope.drawMascot(colors, p(82f, 112f), u(100f), gaze = Offset(0.9f, 0f))
    val x = keyframes(phase, 0f to 290f, 0.15f to 290f, 0.3f to 215f, 0.4f to 250f, 0.5f to 238f, 1f to 238f)
    val (tl, screen) = scope.drawCartoonPhone(colors, p(x, 104f), u(128f))
    // 屏幕里的取景框：到位后变成对勾
    val ok = seg(phase, 0.5f, 0.56f)
    val c = Offset(tl.x + screen.width / 2, tl.y + screen.height / 2)
    val r = screen.width * 0.32f
    scope.drawCircle(lerpColor(colors.borderSubtle, colors.success, ok), r, c, style = Stroke(u(3f)))
    if (ok > 0f) {
        scope.drawLine(colors.success, Offset(c.x - r * 0.45f, c.y), Offset(c.x - r * 0.1f, c.y + r * 0.35f), u(3.5f), StrokeCap.Round, alpha = ok)
        scope.drawLine(colors.success, Offset(c.x - r * 0.1f, c.y + r * 0.35f), Offset(c.x + r * 0.5f, c.y - r * 0.35f), u(3.5f), StrokeCap.Round, alpha = ok)
    }
    ruler(140f, x - 24f, 186f)
    // 灯光：缓慢旋转的光芒
    val sun = p(160f, 34f)
    val bright = 0.6f + 0.4f * seg(phase, 0.6f, 0.7f)
    for (i in 0 until 8) {
        val a = (i / 8f + phase * 0.25f) * 2f * PI.toFloat()
        scope.drawLine(
            colors.accent, Offset(sun.x + cos(a) * u(14f), sun.y + sin(a) * u(14f)),
            Offset(sun.x + cos(a) * u(20f), sun.y + sin(a) * u(20f)), u(3f), StrokeCap.Round, alpha = bright
        )
    }
    scope.drawCircle(colors.accent, u(9f), sun, alpha = bright)
}

private fun lerpColor(a: Color, b: Color, t: Float) = Color(
    red = lerp(a.red, b.red, t), green = lerp(a.green, b.green, t), blue = lerp(a.blue, b.blue, t), alpha = lerp(a.alpha, b.alpha, t)
)

private val blinkTimes = listOf(0.2f, 0.5f, 0.8f)

private val blinkScene = GuideScene(
    cycleMs = 4200,
    beats = listOf(GuideBeat(0f, "① 正对屏幕，保持放松"), GuideBeat(0.15f, "② 像平时一样自然眨眼"), GuideBeat(0.55f, "③ 应用自动记录眨眼")),
    keyframe = 0.6f,
) { phase ->
    floorShadow(160f, 178f, 120f)
    val lid = blinkTimes.maxOf { tri(phase, it, 0.035f) }
    scope.drawMascot(colors, p(160f, 108f), u(118f), lid = lid)
    // 每次眨眼后飘起「+1」
    blinkTimes.forEach { t ->
        val k = (phase - t) / 0.22f
        if (k in 0f..1f) label("+1", 232f, 72f - 30f * k, colors.primary, alpha = 1f - k)
    }
    val count = blinkTimes.count { phase > it }
    scope.drawRoundRect(colors.panelBackground, p(222f, 150f), Size(u(80f), u(30f)), CornerRadius(u(15f)))
    label("眨眼 ×$count", 262f, 165f, colors.textPrimary)
}

private val openScene = GuideScene(
    cycleMs = 4800,
    beats = listOf(GuideBeat(0f, "① 轻松睁开双眼"), GuideBeat(0.15f, "② 尽量保持睁眼，看着屏幕"), GuideBeat(0.86f, "③ 不舒服就眨眼，随时可停止")),
    keyframe = 0.5f,
) { phase ->
    floorShadow(130f, 178f, 120f)
    val rest = tri(phase, 0.92f, 0.04f)
    val wide = 1f + 0.22f * seg(phase, 0.02f, 0.15f) * (1f - seg(phase, 0.86f, 0.9f))
    scope.drawMascot(colors, p(130f, 108f), u(118f), lid = rest, wide = wide)
    // 眼旁闪光，表达「睁开」
    if (phase in 0.15f..0.86f) {
        val twinkle = (sin(phase * 40f) + 1f) / 2f
        for (side in listOf(-1, 1)) {
            val eye = mascotEyeCenter(p(130f, 108f), u(118f), side)
            val c = Offset(eye.x + side * u(26f), eye.y - u(22f))
            val r = u(3f + 3f * twinkle)
            scope.drawLine(colors.accent, Offset(c.x - r, c.y), Offset(c.x + r, c.y), u(2.5f), StrokeCap.Round)
            scope.drawLine(colors.accent, Offset(c.x, c.y - r), Offset(c.x, c.y + r), u(2.5f), StrokeCap.Round)
        }
    }
    // 计时环
    val center = p(255f, 92f)
    val r = u(34f)
    val progress = seg(phase, 0.1f, 0.86f)
    scope.drawCircle(colors.panelBackground, r + u(6f), center)
    scope.drawCircle(colors.borderSubtle, r, center, style = Stroke(u(6f)))
    scope.drawArc(colors.primary, -90f, 360f * progress, false, Offset(center.x - r, center.y - r), Size(r * 2, r * 2), style = Stroke(u(6f), cap = StrokeCap.Round))
    label("${(20 - (progress * 20).toInt()).coerceAtLeast(0)}s", 255f, 92f, colors.textPrimary)
}

/** 视标四周的箭头按键；[highlight] 为被点选的方向（-1 无），[tap] 0..1 为点按涟漪进度。 */
private fun SceneScope.arrowPad(screenTl: Offset, screen: Size, highlight: Int, tap: Float) {
    val c = Offset(screenTl.x + screen.width / 2, screenTl.y + screen.height * 0.3f)
    val d = screen.width * 0.35f
    val dirs = listOf(Offset(1f, 0f), Offset(0f, 1f), Offset(-1f, 0f), Offset(0f, -1f))
    dirs.forEachIndexed { i, v ->
        val at = Offset(c.x + v.x * d, c.y + v.y * d)
        val on = i == highlight
        scope.drawCircle(if (on) colors.primary else colors.borderSubtle, screen.width * 0.11f, at)
        if (on && tap > 0f) scope.drawCircle(colors.primary, screen.width * (0.11f + 0.12f * tap), at, alpha = (1f - tap) * 0.4f)
        val ink = if (on) colors.white else colors.ink
        val length = screen.width * 0.055f
        val tip = at + v * length
        val normal = Offset(-v.y, v.x) * length
        scope.drawLine(ink, at - v * length, tip, u(1.5f), StrokeCap.Round)
        scope.drawLine(ink, tip, at + normal, u(1.5f), StrokeCap.Round)
        scope.drawLine(ink, tip, at - normal, u(1.5f), StrokeCap.Round)
    }
    scope.drawRoundRect(colors.borderSubtle,
        Offset(screenTl.x + screen.width * 0.12f, screenTl.y + screen.height * 0.67f),
        Size(screen.width * 0.76f, screen.height * 0.13f), CornerRadius(screen.width * 0.06f))
}

private val nearDirections = listOf(0, 3, 2, 1)

private val nearScene = GuideScene(
    cycleMs = 5600,
    beats = listOf(GuideBeat(0f, "① 用手掌遮住左眼（不压眼球）"), GuideBeat(0.2f, "② 用右眼看 E 的开口方向"), GuideBeat(0.28f, "③ 点选对应方向；之后换眼再做")),
    keyframe = 0.35f,
) { phase ->
    floorShadow(95f, 182f, 110f)
    val mascot = p(95f, 110f)
    scope.drawMascot(colors, mascot, u(108f), gaze = Offset(0.8f, 0f))
    // 手掌从下方抬起，遮住小瞳的左眼（画面右侧那只）
    val eye = mascotEyeCenter(mascot, u(108f), 1)
    val rise = seg(phase, 0.02f, 0.16f)
    val hand = Offset(eye.x + u(2f), lerp(eye.y + u(90f), eye.y + u(3f), rise))
    scope.drawMitten(colors, hand, u(46f), angle = -12f, armFrom = Offset(hand.x + u(18f), p(0f, 210f).y))
    val (tl, screen) = scope.drawCartoonPhone(colors, p(242f, 100f), u(160f))
    val segment = ((phase - 0.2f) / 0.2f).toInt().coerceIn(0, 3)
    val dir = nearDirections[segment]
    scope.drawTumblingE(colors.ink, Offset(tl.x + screen.width / 2, tl.y + screen.height * 0.3f), screen.width * 0.36f, dir)
    val tapProgress = if (phase >= 0.2f) clamp01((phase - 0.2f - segment * 0.2f - 0.08f) / 0.1f) else 0f
    arrowPad(tl, screen, if (phase >= 0.28f) dir else -1, tapProgress)
}

private fun SceneScope.distanceBadge(x: Float, cm: Float) {
    scope.drawRoundRect(colors.panelBackground, p(x - 30f, 12f), Size(u(60f), u(24f)), CornerRadius(u(12f)))
    label("${cm.toInt()} cm", x, 24f, colors.textPrimary)
}

private fun SceneScope.verdict(x: Float, y: Float, clear: Boolean, alpha: Float) {
    if (alpha <= 0f) return
    val tint = if (clear) colors.success else colors.warning
    scope.drawRoundRect(tint.copy(alpha = 0.18f * alpha), p(x - 26f, y - 12f), Size(u(52f), u(24f)), CornerRadius(u(12f)))
    label(if (clear) "清晰" else "模糊", x, y, tint, alpha)
}

private fun SceneScope.ruler(fromX: Float, toX: Float, y: Float) {
    scope.drawLine(
        colors.primary.copy(alpha = 0.5f), p(fromX, y), p(toX, y), u(2.5f),
        cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(u(6f), u(6f)))
    )
}

/** 手机水平位置（舞台 x）换算为示意距离：280 → 40 cm，140 → 15 cm。 */
private fun cmAt(x: Float) = 15f + (x - 140f) / 140f * 25f

private val moveScene = GuideScene(
    cycleMs = 7600,
    beats = listOf(GuideBeat(0f, "① 从 45 cm 外开始，看着 E"), GuideBeat(0.15f, "② 慢慢移近（每秒不超过 5 cm）"), GuideBeat(0.6f, "③ E 一开始持续模糊就点按，记录近点")),
    keyframe = 0.66f,
) { phase ->
    floorShadow(62f, 182f, 96f)
    scope.drawMascot(colors, p(62f, 112f), u(96f), gaze = Offset(1f, 0f))
    ruler(120f, 300f, 188f)
    // 连续、匀速地由远向近，记录近点后再移回远处
    val x = keyframes(phase, 0f to 290f, 0.15f to 290f, 0.68f to 150f, 0.76f to 150f, 0.9f to 290f, 1f to 290f)
    val (tl, screen) = scope.drawCartoonPhone(colors, p(x, 104f), u(118f))
    val eCenter = Offset(tl.x + screen.width / 2, tl.y + screen.height / 2)
    val blur = seg(phase, 0.58f, 0.68f) * (1f - seg(phase, 0.78f, 0.84f))
    if (blur > 0.05f) {
        for (dx in listOf(-2.5f, 0f, 2.5f)) {
            scope.drawTumblingE(colors.ink, Offset(eCenter.x + u(dx * blur), eCenter.y), screen.width * 0.42f, 0, alpha = 1f - 0.65f * blur)
        }
    } else {
        scope.drawTumblingE(colors.ink, eCenter, screen.width * 0.42f, 0)
    }
    distanceBadge(x, cmAt(x))
    if (phase in 0.15f..0.68f) label("约 3 cm/秒", 215f, 172f, colors.primary)
    val tap = tri(phase, 0.69f, 0.04f)
    if (tap > 0f) scope.drawCircle(colors.primary, u(10f + 16f * (1f - tap)), Offset(eCenter.x, tl.y + screen.height + u(14f)), alpha = tap * 0.5f)
    verdict(x, 176f, clear = false, alpha = tri(phase, 0.72f, 0.05f))
}

private val switchScene = GuideScene(
    cycleMs = 6400,
    beats = listOf(GuideBeat(0f, "① 远处停稳，点选 E 的开口"), GuideBeat(0.15f, "② 移近停稳，再点选一次"), GuideBeat(0.55f, "③ 移远，远近交替进行")),
    keyframe = 0.45f,
) { phase ->
    floorShadow(62f, 182f, 96f)
    scope.drawMascot(colors, p(62f, 112f), u(96f), gaze = Offset(1f, 0f))
    ruler(120f, 300f, 188f)
    val x = keyframes(phase, 0f to 268f, 0.15f to 268f, 0.3f to 170f, 0.55f to 170f, 0.7f to 268f, 1f to 268f)
    val (tl, screen) = scope.drawCartoonPhone(colors, p(x, 102f), u(150f))
    val dir = when {
        phase < 0.3f -> 0
        phase < 0.7f -> 3
        else -> 2
    }
    scope.drawTumblingE(colors.ink, Offset(tl.x + screen.width / 2, tl.y + screen.height * 0.3f), screen.width * 0.34f, dir)
    val tap = maxOf(tri(phase, 0.1f, 0.05f), tri(phase, 0.45f, 0.05f), tri(phase, 0.85f, 0.05f))
    arrowPad(tl, screen, if (tap > 0f) dir else -1, 1f - tap)
    distanceBadge(x, cmAt(x))
    // 移动提示箭头
    val moving = when {
        phase in 0.15f..0.3f -> "← 移近"
        phase in 0.55f..0.7f -> "移远 →"
        else -> null
    }
    if (moving != null) label(moving, 220f, 172f, colors.primary)
}

private val fixateScene = GuideScene(
    cycleMs = 4000,
    beats = listOf(GuideBeat(0f, "① 看着屏幕中央闪烁的小瞳"), GuideBeat(0.45f, "② 头和手机都保持不动")),
    keyframe = 0.5f,
) { phase ->
    floorShadow(95f, 182f, 110f)
    val mascot = p(95f, 110f)
    scope.drawMascot(colors, mascot, u(108f), gaze = Offset(0.9f, -0.1f))
    if (phase >= 0.45f) headLock(95f, 106f, 66f)
    val (tl, screen) = scope.drawCartoonPhone(colors, p(242f, 100f), u(160f))
    val dot = Offset(tl.x + screen.width / 2, tl.y + screen.height / 2)
    val pulse = (sin(phase * 2f * PI.toFloat() * 2f) + 1f) / 2f
    scope.drawContext.canvas.saveLayer(Rect(dot - Offset(u(20f), u(20f)), Size(u(40f), u(40f))),
        Paint().apply { alpha = if (pulse > 0.5f) 1f else 0.3f })
    scope.drawMascot(colors, dot, u(22f))
    scope.drawContext.canvas.restore()
    // 视线：从双眼流向屏幕小瞳的虚线
    val eye = mascotEyeCenter(mascot, u(108f), 1)
    scope.drawLine(
        colors.primary.copy(alpha = 0.45f), Offset(eye.x + u(16f), eye.y), Offset(dot.x - u(14f), dot.y), u(2f),
        cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(u(5f), u(6f)), phase = -phase * u(110f))
    )
}

private val coverScene = GuideScene(
    cycleMs = 9600,
    beats = listOf(
        GuideBeat(0f, "① 遮右眼 2 秒 → 移开，双眼注视 2 秒"),
        GuideBeat(0.36f, "② 遮左眼 2 秒 → 移开，双眼注视 2 秒"),
        GuideBeat(0.7f, "③ 右眼、左眼各遮 1 秒，快速交替两轮"),
    ),
    keyframe = 0.12f,
) { phase ->
    floorShadow(160f, 184f, 120f)
    val mascot = p(160f, 112f)
    scope.drawMascot(colors, mascot, u(118f))
    // 小瞳面向我们：它的右眼在画面左侧（side=-1），左眼在画面右侧（side=+1）
    val rEye = mascotEyeCenter(mascot, u(118f), -1).x
    val lEye = mascotEyeCenter(mascot, u(118f), 1).x
    val away = lEye + u(150f)
    val hx = keyframes(
        phase,
        0f to away, 0.05f to rEye, 0.2f to rEye, 0.25f to away, 0.36f to away,
        0.42f to lEye, 0.56f to lEye, 0.61f to away, 0.7f to away,
        0.74f to rEye, 0.78f to rEye, 0.81f to lEye, 0.85f to lEye,
        0.88f to rEye, 0.91f to rEye, 0.94f to lEye, 0.97f to lEye, 1f to away,
    )
    val eyeY = mascotEyeCenter(mascot, u(118f), 1).y
    scope.drawMitten(colors, Offset(hx, eyeY + u(3f)), u(52f), angle = -14f, armFrom = p(340f, 150f))
    val text = when {
        abs(hx - rEye) < u(6f) -> "遮右眼"
        abs(hx - lEye) < u(6f) -> "遮左眼"
        hx > lEye + u(100f) -> "移开 · 双眼注视"
        else -> "换眼"
    }
    scope.drawRoundRect(colors.panelBackground, p(12f, 12f), Size(u(112f), u(28f)), CornerRadius(u(14f)))
    label(text, 68f, 26f, if (text.startsWith("遮")) colors.primary else colors.textSecondary)
}

/** 水平正弦 → 垂直正弦 → 45° 斜向直线往复，与正式追踪任务的顺序一致。 */
private val followScene = GuideScene(
    cycleMs = 7200,
    beats = listOf(GuideBeat(0f, "① 水平：只用眼睛跟随小瞳"), GuideBeat(0.36f, "② 垂直：头保持不动"), GuideBeat(0.72f, "③ 斜向：继续跟随")),
    keyframe = 0.2f,
) { phase ->
    val twoPi = 2f * PI.toFloat()
    val cx = 214f
    val cy = 92f
    val dot = when {
        phase < 0.36f -> Offset(cx + 72f * sin(phase / 0.36f * twoPi), cy)
        phase < 0.72f -> Offset(cx, cy + 62f * sin((phase - 0.36f) / 0.36f * twoPi))
        else -> {
            // 三角波：匀速直线往复
            val t = (phase - 0.72f) / 0.28f
            val wave = if (t < 0.5f) t * 4f - 1f else 3f - t * 4f
            Offset(cx + 52f * wave, cy - 52f * wave)
        }
    }
    val text = when {
        phase < 0.36f -> "水平"
        phase < 0.72f -> "垂直"
        else -> "斜向"
    }
    scope.drawRoundRect(colors.panelBackground, p(150f, 18f), Size(u(128f), u(150f)), CornerRadius(u(16f)), alpha = 0.7f)
    floorShadow(72f, 186f, 90f)
    scope.drawMascot(
        colors, p(72f, 130f), u(86f),
        gaze = Offset(((dot.x - 72f) / 140f).coerceIn(-1f, 1f), ((dot.y - 130f) / 90f).coerceIn(-1f, 1f))
    )
    headLock(72f, 126f, 54f)
    scope.drawMascot(colors, p(dot.x, dot.y), u(9f / 0.65f))
    scope.drawRoundRect(colors.panelBackground, p(12f, 12f), Size(u(64f), u(28f)), CornerRadius(u(14f)))
    label(text, 44f, 26f, colors.primary)
}

/** 视标练习：每题 2 秒限时，连对两次变小、超时或答错变大。 */
private val acuityScene = GuideScene(
    cycleMs = 6000,
    beats = listOf(GuideBeat(0f, "① 看 E 的开口方向"), GuideBeat(0.1f, "② 2 秒内点选对应方向"), GuideBeat(0.4f, "③ 连对两次变小，答错或超时变大")),
    keyframe = 0.12f,
) { phase ->
    floorShadow(66f, 184f, 84f)
    scope.drawMascot(colors, p(66f, 118f), u(84f), gaze = Offset(1f, -0.2f))
    val segment = (phase / 0.2f).toInt().coerceIn(0, 4)
    val t = (phase - segment * 0.2f) / 0.2f
    val sizes = listOf(1f, 1f, 0.78f, 1f, 1f)
    val dirs = listOf(0, 3, 2, 1, 0)
    val timeout = segment == 2
    val answerAt = if (timeout) 1f else 0.5f
    val (tl, screen) = scope.drawCartoonPhone(colors, p(200f, 100f), u(170f))
    val eCenter = Offset(tl.x + screen.width / 2, tl.y + screen.height * 0.3f)
    if (t < answerAt) scope.drawTumblingE(colors.ink, eCenter, screen.width * 0.34f * sizes[segment], dirs[segment])
    arrowPad(tl, screen, if (!timeout && t >= 0.45f) dirs[segment] else -1, if (!timeout) clamp01((t - 0.45f) / 0.2f) else 0f)
    // 2 秒限时环
    val ring = p(272f, 40f)
    val r = u(16f)
    scope.drawCircle(colors.panelBackground, r + u(5f), ring)
    scope.drawArc(colors.accent, -90f, 360f * (1f - (t / answerAt).coerceAtMost(1f)), false, Offset(ring.x - r, ring.y - r), Size(r * 2, r * 2), style = Stroke(u(4f), cap = StrokeCap.Round))
    label("2s", 272f, 40f, colors.textPrimary)
    if (t >= answerAt) {
        if (timeout) label("超时 → 变大", 272f, 170f, colors.warning) else label("正确", 272f, 170f, colors.success)
    }
}

/** 功能性连续视力：自然眨眼时持续辨认视标，之后不停顿转入保持睁眼。 */
private val fvaScene = GuideScene(
    cycleMs = 7000,
    beats = listOf(GuideBeat(0f, "① 全程看 E，点选开口方向"), GuideBeat(0.12f, "② 前一段像平时一样自然眨眼"), GuideBeat(0.68f, "③ 不停顿，转入尽量保持睁眼")),
    keyframe = 0.3f,
) { phase ->
    floorShadow(84f, 184f, 100f)
    val open = phase >= 0.68f
    val lid = if (open) 0f else maxOf(tri(phase, 0.2f, 0.03f), tri(phase, 0.45f, 0.03f))
    scope.drawMascot(colors, p(84f, 112f), u(100f), gaze = Offset(1f, 0f), lid = lid, wide = if (open) 1.22f else 1f)
    val (tl, screen) = scope.drawCartoonPhone(colors, p(240f, 100f), u(160f))
    val segment = (phase / 0.14f).toInt().coerceIn(0, 7)
    val dir = listOf(0, 3, 2, 1, 0, 1, 3, 2)[segment]
    scope.drawTumblingE(colors.ink, Offset(tl.x + screen.width / 2, tl.y + screen.height * 0.3f), screen.width * 0.32f, dir)
    val local = (phase - segment * 0.14f) / 0.14f
    arrowPad(tl, screen, if (local > 0.5f) dir else -1, clamp01((local - 0.5f) / 0.3f))
    scope.drawRoundRect(colors.panelBackground, p(12f, 12f), Size(u(96f), u(28f)), CornerRadius(u(14f)))
    label(if (open) "持续睁眼" else "自然眨眼", 60f, 26f, if (open) colors.warning else colors.primary)
}

/** 多距离视觉表现：在 60 / 33 / 25 cm 依次停稳并辨认视标。 */
private val curveScene = GuideScene(
    cycleMs = 6600,
    beats = listOf(GuideBeat(0f, "① 移到提示的距离并停稳"), GuideBeat(0.12f, "② 点选 E 的开口方向"), GuideBeat(0.36f, "③ 依次换到下一个距离")),
    keyframe = 0.2f,
) { phase ->
    floorShadow(62f, 182f, 96f)
    scope.drawMascot(colors, p(62f, 112f), u(96f), gaze = Offset(1f, 0f))
    ruler(120f, 300f, 188f)
    val x = keyframes(phase, 0f to 290f, 0.3f to 290f, 0.38f to 200f, 0.64f to 200f, 0.72f to 165f, 1f to 165f)
    val (tl, screen) = scope.drawCartoonPhone(colors, p(x, 102f), u(140f))
    val dir = when {
        phase < 0.34f -> 0
        phase < 0.68f -> 3
        else -> 2
    }
    scope.drawTumblingE(colors.ink, Offset(tl.x + screen.width / 2, tl.y + screen.height * 0.3f), screen.width * 0.3f, dir)
    val tap = maxOf(tri(phase, 0.18f, 0.05f), tri(phase, 0.52f, 0.05f), tri(phase, 0.86f, 0.05f))
    arrowPad(tl, screen, if (tap > 0f) dir else -1, 1f - tap)
    val cm = when {
        phase < 0.34f -> 60
        phase < 0.68f -> 33
        else -> 25
    }
    scope.drawRoundRect(colors.panelBackground, p(x - 30f, 12f), Size(u(60f), u(24f)), CornerRadius(u(12f)))
    label("$cm cm", x, 24f, colors.textPrimary)
}

// endregion

internal fun guideMotion(step: ScreeningStep) = when (step) {
    ScreeningStep.NEAR_VISION -> GuideMotion.NEAR
    ScreeningStep.PUSH_UP -> GuideMotion.MOVE
    ScreeningStep.DISTANCE_CURVE -> GuideMotion.CURVE
    ScreeningStep.FLIPPER -> GuideMotion.SWITCH
    ScreeningStep.FIXATION -> GuideMotion.FIXATE
    ScreeningStep.COVER -> GuideMotion.COVER
    ScreeningStep.PURSUIT -> GuideMotion.FOLLOW
    else -> GuideMotion.POSTURE
}
