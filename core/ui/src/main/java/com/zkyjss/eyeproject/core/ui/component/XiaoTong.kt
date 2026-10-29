package com.zkyjss.eyeproject.core.ui.component

import android.content.Context
import android.os.SystemClock
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.zkyjss.eyeproject.core.designsystem.theme.EyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.rememberReducedMotion
import com.zkyjss.eyeproject.core.ui.R
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 小瞳的心情：开心（放心）/ 思考（看向一侧、头上冒问号）/ 担心（睁大眼、波浪嘴、冒汗）/ 犯困（眯眼打盹、飘 Z）/
 * 疲惫（用眼负荷偏高：眼皮耷拉、黑眼圈、冒汗、嫩芽打蔫）。首页按用眼负荷、结果页按测试等级选择；与苹果端 MascotStage 同一套动作。
 */
enum class MascotMood { HAPPY, THINKING, CONCERNED, SLEEPY, TIRED;
    companion object {
        /** 共用核心给出的神情代码（ResultReport / EyeCareReport 的 mood）。 */
        fun of(code: String?): MascotMood = entries.firstOrNull { it.name == code } ?: HAPPY
    }
}

/** 小瞳的反应（动作 + 音效 + 时长）：点一下随机一种；长按是比心；连点五下会转晕；待机时也会自己做几个小动作（无声）。 */
private enum class MascotReaction(val sound: String, val ms: Int) {
    HOP("eye_hop", 900), WINK("eye_boop", 900), GIGGLE("eye_giggle", 900), SURPRISE("eye_wow", 900), SHY("eye_boop", 900),
    SPIN("eye_spin", 1_000), HEART("eye_heart", 1_400), DIZZY("eye_dizzy", 1_600), YAWN("eye_yawn", 1_400), PERK("eye_sigh", 1_400),
}

private fun reactionsFor(mood: MascotMood) = when (mood) {
    MascotMood.HAPPY -> listOf(MascotReaction.HOP, MascotReaction.WINK, MascotReaction.GIGGLE, MascotReaction.SHY,
        MascotReaction.SURPRISE, MascotReaction.SPIN, MascotReaction.HEART)
    MascotMood.THINKING -> listOf(MascotReaction.SURPRISE, MascotReaction.WINK, MascotReaction.GIGGLE, MascotReaction.HOP, MascotReaction.SPIN)
    MascotMood.CONCERNED -> listOf(MascotReaction.SHY, MascotReaction.WINK, MascotReaction.HOP, MascotReaction.HEART)
    MascotMood.SLEEPY -> listOf(MascotReaction.YAWN, MascotReaction.SURPRISE, MascotReaction.GIGGLE)
    MascotMood.TIRED -> listOf(MascotReaction.PERK, MascotReaction.YAWN, MascotReaction.SHY)
}

/** 待机小动作：每个 [FIDGET_MS] 周期在 30% 与 75% 处各做一个（null 为不做）。 */
private fun fidgetsFor(mood: MascotMood): Pair<MascotReaction?, MascotReaction?> = when (mood) {
    MascotMood.HAPPY -> MascotReaction.HOP to MascotReaction.GIGGLE
    MascotMood.THINKING -> MascotReaction.WINK to MascotReaction.HOP
    MascotMood.CONCERNED -> MascotReaction.SHY to null
    MascotMood.SLEEPY -> MascotReaction.YAWN to null
    MascotMood.TIRED -> MascotReaction.YAWN to null
}

private const val FIDGET_MS = 14_000
private const val RAPID_TAPS = 5
private const val RAPID_WINDOW_MS = 2_500L

/** 心情对应的嫩芽打蔫程度（调用方可按负荷分另行指定）。 */
fun MascotMood.defaultWilt(): Float = when (this) {
    MascotMood.TIRED -> 1f
    MascotMood.CONCERNED -> 0.4f
    MascotMood.SLEEPY -> 0.2f
    else -> 0f
}

/**
 * 会动、能互动的小瞳。待机：呼吸起伏、眨眼、张望，隔一会儿自己蹦一下 / 打个哈欠，按心情加上闪光 / 问号 / 汗滴 / Z / 黑眼圈；
 * [interactive] 时点一下随机换一种反应（动作 + 音效 + 轻触觉），长按比心，连点五下转晕；[greet] 首次出现时蹦一下打招呼（无音效）。
 * [headScale] 为头宽占画布短边的比例；[wilt] 为嫩芽打蔫程度（默认按心情）。系统「移除动画」时只换表情、不做位移。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun XiaoTong(
    mood: MascotMood,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    greet: Boolean = false,
    headScale: Float = 0.7f,
    wilt: Float? = null,
    onTap: () -> Unit = {},
) {
    val colors = LocalEyeColors.current
    val reduced = rememberReducedMotion()
    val sounds = rememberMascotSounds()
    val haptics = rememberEyeHaptics()
    val scope = rememberCoroutineScope()
    val idle = rememberInfiniteTransition(label = "小瞳待机")
    val phase = idle.animateFloat(0f, 1f, infiniteRepeatable(tween(6_000, easing = LinearEasing)), label = "待机相位")
    val fidget = idle.animateFloat(0f, 1f, infiniteRepeatable(tween(FIDGET_MS, easing = LinearEasing)), label = "待机小动作")
    val progress = remember { Animatable(1f) }
    var reaction by remember { mutableStateOf<MascotReaction?>(null) }
    val tapTimes = remember { ArrayDeque<Long>() }
    val leaf = wilt ?: mood.defaultWilt()
    fun play(next: MascotReaction, sound: Boolean) {
        reaction = next
        if (sound) { sounds.play(next.sound); if (sounds.hapticsEnabled()) haptics.tap() }
        scope.launch { progress.snapTo(0f); progress.animateTo(1f, tween(next.ms, easing = LinearEasing)) }
    }
    LaunchedEffect(greet) { if (greet) play(MascotReaction.HOP, sound = false) }
    val touch = Modifier.combinedClickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button,
        onLongClick = { play(MascotReaction.HEART, sound = true); onTap() },
    ) {
        // 转晕的时候先晕完，不被接下来的点按打断
        if (reaction == MascotReaction.DIZZY && progress.value < 1f) return@combinedClickable
        val now = SystemClock.uptimeMillis()
        tapTimes.addLast(now)
        while (tapTimes.size > RAPID_TAPS || now - tapTimes.first() > RAPID_WINDOW_MS) tapTimes.removeFirst()
        val next = if (tapTimes.size >= RAPID_TAPS) { tapTimes.clear(); MascotReaction.DIZZY }
            else reactionsFor(mood).filter { it != reaction }.random()
        play(next, sound = true)
        onTap()
    }
    Canvas(modifier.semantics { contentDescription = if (interactive) "小瞳，点一下和它互动，长按摸摸头" else "小瞳" }
        .then(if (interactive) touch else Modifier)) {
        val r = progress.value
        val tapped = reaction.takeIf { r < 1f }
        val (a, b) = fidgetsFor(mood)
        val f = fidget.value
        val idleMove = when {
            reduced || tapped != null -> null
            a != null && f in 0.3f..(0.3f + a.ms.toFloat() / FIDGET_MS) -> a to (f - 0.3f) * FIDGET_MS / a.ms
            b != null && f in 0.75f..(0.75f + b.ms.toFloat() / FIDGET_MS) -> b to (f - 0.75f) * FIDGET_MS / b.ms
            else -> null
        }
        drawXiaoTong(colors, mood, if (reduced) 0f else phase.value, tapped ?: idleMove?.first,
            if (tapped != null) r else idleMove?.second ?: 1f, if (tapped != null) 1f else 0.6f, headScale, leaf, reduced)
    }
}

private fun DrawScope.drawXiaoTong(colors: EyeColors, mood: MascotMood, p: Float, reaction: MascotReaction?, t: Float, amp: Float,
                                   scale: Float, wiltLevel: Float, still: Boolean) {
    val s = size.minDimension * scale
    val baseY = size.height * 0.58f
    val tau = (2 * PI).toFloat()
    var cx = size.width / 2
    var cy = baseY + if (still) 0f else sin(tau * p * 3) * s * 0.012f
    var rotation = 0f
    var pivot: Offset? = null
    var squashY = 1f
    var lid = max(max(1f - abs(p - 0.3f) / 0.02f, 1f - abs(p - 0.36f) / 0.02f), max(1f - abs(p - 0.82f) / 0.02f, 0f))
    var gaze = Offset.Zero
    var wide = 1f
    var eyes = MascotEyes.DOT
    var mouth = MascotMouth.SMILE
    var blush = 0.75f
    var leaf = if (still) 0f else sin(tau * p * 2) * 4f
    var wilt = wiltLevel

    when (mood) {
        MascotMood.HAPPY -> gaze = Offset(lookAround(p), 0f)
        MascotMood.THINKING -> { rotation = if (still) 4f else sin(tau * p * 2) * 6f; gaze = Offset(0.6f, -0.6f); mouth = MascotMouth.ROUND }
        MascotMood.CONCERNED -> {
            wide = 1.12f; mouth = MascotMouth.WOBBLY; gaze = Offset(if (still) 0f else sin(tau * p) * 0.3f, 0f)
            if (!still && p in 0.5f..0.58f) cx += sin(tau * p * 60) * s * 0.008f
        }
        MascotMood.SLEEPY -> {
            mouth = MascotMouth.ROUND
            lid = if (still) 0.7f else if (p in 0.55f..0.7f) 1f else 0.62f + 0.18f * (0.5f + 0.5f * sin(tau * p * 2))
            if (!still && p in 0.55f..0.7f) cy += s * 0.03f
        }
        MascotMood.TIRED -> {
            // 眼皮耷拉、慢慢眨，身体微微塌下去，嫩芽随呼吸轻轻晃
            mouth = MascotMouth.FLAT; gaze = Offset(0f, 0.4f); blush = 0.35f
            lid = if (still) 0.5f else max(0.45f + 0.06f * sin(tau * p), max(1f - abs(p - 0.6f) / 0.05f, 0f))
            squashY = 0.96f
            cy = baseY + s * 0.02f + if (still) 0f else sin(tau * p * 2) * s * 0.01f
            leaf = if (still) 0f else sin(tau * p) * 2f
            wilt = wiltLevel * if (still) 1f else 0.94f + 0.06f * sin(tau * p * 2)
        }
    }

    if (reaction != null) {
        val bump = sin(PI.toFloat() * t)
        val move = if (still) 0f else amp
        when (reaction) {
            MascotReaction.HOP -> {
                cy -= bump * s * 0.22f * move
                val edge = max(0f, 1f - t / 0.12f) + max(0f, (t - 0.88f) / 0.12f)
                squashY = 1f + (0.06f * bump - 0.14f * edge) * move
                eyes = MascotEyes.HAPPY; mouth = MascotMouth.GRIN; lid = 0f
            }
            MascotReaction.WINK -> { eyes = MascotEyes.WINK; mouth = MascotMouth.SMILE; rotation = bump * 8f * move; blush = 0.9f; lid = 0f }
            MascotReaction.GIGGLE -> {
                eyes = MascotEyes.HAPPY; mouth = MascotMouth.GRIN; lid = 0f
                rotation = sin(tau * t * 4) * 7f * (1f - t) * move; leaf = sin(tau * t * 3) * 25f * move
                cy -= abs(sin(tau * t * 3)) * s * 0.04f * move
            }
            MascotReaction.SURPRISE -> { lid = 0f; wide = 1.3f; mouth = MascotMouth.ROUND; gaze = Offset.Zero; cy -= bump * s * 0.08f * move }
            MascotReaction.SHY -> { eyes = MascotEyes.HAPPY; mouth = MascotMouth.SMILE; blush = 1f; rotation = -bump * 10f * move; lid = 0f }
            MascotReaction.SPIN -> {
                eyes = MascotEyes.HAPPY; mouth = MascotMouth.GRIN; lid = 0f
                rotation = 360f * EyeMotion.Standard.transform(t) * move; pivot = Offset(cx, cy + s * 0.05f)
                cy -= bump * s * 0.12f * move; leaf = bump * 30f * move
            }
            MascotReaction.HEART -> {
                eyes = MascotEyes.HAPPY; mouth = MascotMouth.SMILE; blush = 1f; lid = 0f
                rotation = sin(tau * t * 2) * 6f * move; squashY = 1f - 0.04f * bump * move
            }
            MascotReaction.DIZZY -> {
                eyes = MascotEyes.SPIRAL; mouth = MascotMouth.WOBBLY; lid = 0f
                rotation = sin(tau * t * 3) * 12f * (1f - t) * move; cx += sin(tau * t * 2) * s * 0.04f * (1f - t) * move
            }
            MascotReaction.YAWN -> {
                lid = 1f; mouth = MascotMouth.YAWN; squashY = 1f + 0.08f * bump * move
                rotation = -bump * 5f * move; leaf = bump * 12f * move
            }
            MascotReaction.PERK -> {
                // 被戳一下打起精神：嫩芽立起来、眼睛睁大，过一会儿又蔫下去
                val up = if (t < 0.6f) EyeMotion.Standard.transform(t / 0.6f) else 1f - EyeMotion.Standard.transform((t - 0.6f) / 0.4f)
                wilt = wiltLevel * (1f - up); lid = 0.45f * (1f - up); wide = 1f + 0.15f * up
                mouth = if (up > 0.5f) MascotMouth.SMILE else MascotMouth.FLAT; cy -= up * s * 0.06f * move; squashY = 1f
                leaf = sin(tau * t * 3) * 10f * up * move
            }
        }
    }

    // 地面投影：跳起时变淡变小
    val lift = (baseY - cy).coerceAtLeast(0f) / s
    drawOval(colors.ink.copy(alpha = (0.1f - lift * 0.25f).coerceIn(0.03f, 0.1f)),
        Offset(size.width / 2 - s * 0.32f * (1f - lift), baseY + s * 0.43f), Size(s * 0.64f * (1f - lift), s * 0.07f))
    withTransform({
        rotate(rotation, pivot ?: Offset(cx, cy + s * 0.3f))
        scale(2f - squashY, squashY, Offset(cx, cy + s * 0.42f))
    }) {
        drawMascot(colors, Offset(cx, cy), s, gaze = gaze, lid = lid, wide = wide, eyes = eyes, mouth = mouth, blush = blush,
            leafSway = leaf, wilt = wilt)
        // 疲惫：两道淡淡的黑眼圈
        if (mood == MascotMood.TIRED && eyes == MascotEyes.DOT) for (side in listOf(-1, 1)) {
            val e = mascotEyeCenter(Offset(cx, cy), s, side)
            drawArc(colors.ink.copy(alpha = 0.22f), 20f, 140f, false, Offset(e.x - s * 0.06f, e.y + s * 0.01f),
                Size(s * 0.12f, s * 0.07f), style = Stroke(s * 0.014f, cap = StrokeCap.Round))
        }
    }

    // 心情点缀
    when (mood) {
        MascotMood.HAPPY -> if (reaction == null && !still) {
            drawSparkle(colors.accent, Offset(cx + s * 0.58f, cy - s * 0.32f), s * 0.07f, twinkle(p, 0.15f))
            drawSparkle(colors.brandStart, Offset(cx - s * 0.6f, cy - s * 0.08f), s * 0.05f, twinkle(p, 0.62f))
        }
        MascotMood.THINKING -> drawQuestion(colors.primary, Offset(cx + s * 0.6f, cy - s * 0.5f - (if (still) 0f else sin(tau * p * 2) * s * 0.03f)), s * 0.2f)
        MascotMood.CONCERNED, MascotMood.TIRED -> if (!still) {
            val slide = (p * if (mood == MascotMood.TIRED) 1f else 2f) % 1f
            drawDrop(colors.blue.copy(alpha = 0.55f * (1f - slide)), Offset(cx + s * 0.44f, cy - s * 0.28f + slide * s * 0.16f), s * 0.07f)
        } else drawDrop(colors.blue.copy(alpha = 0.5f), Offset(cx + s * 0.44f, cy - s * 0.24f), s * 0.07f)
        MascotMood.SLEEPY -> for (i in 0..1) {
            val z = if (still) 0.3f + i * 0.3f else (p * 2f + i * 0.5f) % 1f
            drawZ(colors.primary.copy(alpha = (1f - z).coerceIn(0f, 1f)),
                Offset(cx + s * (0.45f + z * 0.12f), cy - s * (0.32f + z * 0.32f)), s * (0.1f - i * 0.03f))
        }
    }
    if (still) return
    when (reaction) {
        // 蹦跳、转圈时四周迸出闪光
        MascotReaction.HOP, MascotReaction.SPIN -> for (i in 0 until 4) {
            val a = (30 + i * 90) * PI.toFloat() / 180f
            val d = s * (0.55f + 0.25f * t)
            drawSparkle(if (i % 2 == 0) colors.accent else colors.brandStart, Offset(cx + cos(a) * d, cy + sin(a) * d * 0.8f), s * 0.06f, (1f - t) * amp)
        }
        // 比心：三颗小心心往上飘
        MascotReaction.HEART -> for (i in 0 until 3) {
            val k = ((t - i * 0.15f) / 0.7f).coerceIn(0f, 1f)
            if (k > 0f) drawHeart(colors.danger.copy(alpha = (1f - k) * 0.85f),
                Offset(cx + s * (-0.35f + i * 0.35f) + sin(tau * k) * s * 0.05f, cy - s * (0.45f + k * 0.45f)), s * (0.07f + 0.02f * i))
        }
        // 转晕：头顶三颗星星绕圈
        MascotReaction.DIZZY -> for (i in 0 until 3) {
            val a = tau * (t * 2f + i / 3f)
            drawSparkle(colors.accent, Offset(cx + cos(a) * s * 0.32f, cy - s * 0.62f + sin(a) * s * 0.08f), s * 0.06f, 1f - max(0f, t - 0.8f) / 0.2f)
        }
        // 打哈欠：眼角挤出一滴泪
        MascotReaction.YAWN -> drawDrop(colors.blue.copy(alpha = 0.6f * sin(PI.toFloat() * t)),
            Offset(cx + s * 0.27f, cy + s * 0.02f + t * s * 0.04f), s * 0.035f)
        else -> Unit
    }
}

/** 心形（比心时往上飘）。 */
private fun DrawScope.drawHeart(color: Color, c: Offset, r: Float) {
    drawPath(Path().apply {
        moveTo(c.x, c.y + r)
        cubicTo(c.x - r * 1.6f, c.y - r * 0.2f, c.x - r * 0.7f, c.y - r * 1.3f, c.x, c.y - r * 0.45f)
        cubicTo(c.x + r * 0.7f, c.y - r * 1.3f, c.x + r * 1.6f, c.y - r * 0.2f, c.x, c.y + r)
        close()
    }, color)
}

/** 待机张望：前半周期看正前方，之后看左、看右再回来（与服务页欢迎卡相同时间点）。 */
private fun lookAround(p: Float): Float = when {
    p < 0.5f -> 0f
    p < 0.65f -> -(p - 0.5f) / 0.15f
    p < 0.8f -> -1f + (p - 0.65f) / 0.15f * 2f
    p < 0.9f -> 1f - (p - 0.8f) / 0.1f
    else -> 0f
}

/** 闪光在 [at] 附近亮起再熄灭。 */
private fun twinkle(p: Float, at: Float): Float = (1f - abs(p - at) / 0.08f).coerceIn(0f, 1f)

/** 问号：上方弯钩（取点画弧，与苹果端一致）+ 竖笔 + 圆点。 */
private fun DrawScope.drawQuestion(color: Color, c: Offset, h: Float) {
    val r = h * 0.24f
    val hook = Offset(c.x, c.y - h * 0.22f)
    drawPath(Path().apply {
        for (i in 0..16) {
            val a = (200f + 250f * i / 16) * PI.toFloat() / 180f
            val q = Offset(hook.x + r * cos(a), hook.y + r * sin(a))
            if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y)
        }
        lineTo(c.x, c.y + h * 0.12f)
    }, color, style = Stroke(h * 0.12f, cap = StrokeCap.Round))
    drawCircle(color, h * 0.07f, Offset(c.x, c.y + h * 0.36f))
}

/** 汗滴：尖头朝上的水滴。 */
private fun DrawScope.drawDrop(color: Color, c: Offset, r: Float) {
    drawPath(Path().apply {
        moveTo(c.x, c.y - r * 1.6f)
        cubicTo(c.x + r * 0.4f, c.y - r * 0.8f, c.x + r, c.y - r * 0.3f, c.x + r, c.y + r * 0.2f)
        cubicTo(c.x + r, c.y + r * 0.8f, c.x + r * 0.5f, c.y + r * 1.1f, c.x, c.y + r * 1.1f)
        cubicTo(c.x - r * 0.5f, c.y + r * 1.1f, c.x - r, c.y + r * 0.8f, c.x - r, c.y + r * 0.2f)
        cubicTo(c.x - r, c.y - r * 0.3f, c.x - r * 0.4f, c.y - r * 0.8f, c.x, c.y - r * 1.6f)
        close()
    }, color)
}

/** 字母 Z（三笔）。 */
private fun DrawScope.drawZ(color: Color, c: Offset, h: Float) {
    drawPath(Path().apply {
        moveTo(c.x - h / 2, c.y - h / 2); lineTo(c.x + h / 2, c.y - h / 2)
        lineTo(c.x - h / 2, c.y + h / 2); lineTo(c.x + h / 2, c.y + h / 2)
    }, color, style = Stroke(h * 0.2f, cap = StrokeCap.Round))
}

/**
 * 小瞳互动音：与测试音效同一套开关与音量（「系统设置」的音效反馈、音效音量、震动反馈，
 * 保存在 screening_settings 偏好里，见 feature:services 的 TestSettings）。走媒体音量。
 */
class MascotSounds internal constructor(context: Context) {
    private val preferences = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
    private val pool = runCatching {
        SoundPool.Builder().setMaxStreams(3).setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    }.getOrNull()
    private val ids = mapOf("eye_boop" to R.raw.eye_boop, "eye_hop" to R.raw.eye_hop, "eye_giggle" to R.raw.eye_giggle, "eye_wow" to R.raw.eye_wow,
        "eye_spin" to R.raw.eye_spin, "eye_heart" to R.raw.eye_heart, "eye_dizzy" to R.raw.eye_dizzy, "eye_yawn" to R.raw.eye_yawn,
        "eye_pop" to R.raw.eye_pop, "eye_sigh" to R.raw.eye_sigh, "eye_count" to R.raw.eye_count)
        .mapValues { (_, res) -> pool?.load(context, res, 1) ?: 0 }

    fun play(name: String) {
        if (!preferences.getBoolean("sound", true)) return
        val id = ids[name] ?: return
        val volume = (preferences.getFloat("soundVolume", 1f) * 0.7f).coerceIn(0f, 1f)
        pool?.play(id, volume, volume, 1, 0, 1f)
    }

    fun hapticsEnabled(): Boolean = preferences.getBoolean("haptics", true)

    internal fun release() { pool?.release() }

    private companion object { const val SETTINGS = "screening_settings" }
}

@Composable
fun rememberMascotSounds(): MascotSounds {
    val context = LocalContext.current.applicationContext
    val sounds = remember(context) { MascotSounds(context) }
    DisposableEffect(sounds) { onDispose { sounds.release() } }
    return sounds
}
