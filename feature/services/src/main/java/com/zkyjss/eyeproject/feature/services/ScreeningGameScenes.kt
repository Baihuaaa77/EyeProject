package com.zkyjss.eyeproject.feature.services

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeGamePalette
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.theme.rememberReducedMotion
import com.zkyjss.eyeproject.core.ui.component.EyeText
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ============================================================================
// 1. 干眼自测：护眼科普小剧场 + 坚持睁眼水膜盾挑战
// ============================================================================

private data class DryEyeStoryAct(
    val badge: String,
    val title: String,
    val subtitle: String,
)

private val DRY_EYE_ACTS = listOf(
    DryEyeStoryAct("第 1 幕 · 泪膜守护盾", "眨眼就是天然保湿刷", "每一次轻眨眼，都会给眼球铺上一层隐形的锁水保护膜。"),
    DryEyeStoryAct("第 2 幕 · 盯屏蒸发记", "专注看屏容易忘记眨眼", "长时间紧盯屏幕时，眨眼次数会悄悄减少，泪膜更容易变薄蒸发。"),
    DryEyeStoryAct("第 3 幕 · 远眺松口气", "20-20-20 护眼魔法", "每用眼 20 分钟，抬头看看远处风景 20 秒，让紧绷的眼肌舒展一下。"),
    DryEyeStoryAct("第 4 幕 · 暖雾润眼时", "温热与眨眼让眼睛水润", "规律眨眼搭配温和热敷，能帮睑板腺保持畅通，眼睛更清爽舒适。"),
)

/**
 * 干眼自测趣味互动舞台：
 * - 自然眨眼阶段：播放 4 幕循环的护眼科普小动画，前置相机静默记录真实眨眼次数与时间分布。
 * - 持续睁眼阶段：切换为「泪膜守护罩」挑战，水滴盾随坚持时长不断充能发光。
 */
@Composable
internal fun DryEyeStoryTheater(
    state: DryEyeTestUiState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val reducedMotion = rememberReducedMotion()
    val isOpenChallenge = state.phase == DryEyePhase.Open
    val elapsedSec = if (isOpenChallenge) {
        (DryEyeProtocol.OPEN_SECONDS - state.remainingSeconds).coerceIn(0, DryEyeProtocol.OPEN_SECONDS)
    } else {
        (state.naturalSeconds - state.remainingSeconds).coerceIn(0, state.naturalSeconds)
    }
    val actIndex = ((elapsedSec / 15) % DRY_EYE_ACTS.size).coerceIn(0, DRY_EYE_ACTS.lastIndex)
    val act = DRY_EYE_ACTS[actIndex]
    val blinkCount = if (isOpenChallenge) state.phase2BlinkCount else state.phase1BlinkCount
    val infinite = rememberInfiniteTransition(label = "干眼小剧场")
    val animPhase by if (reducedMotion) {
        remember { mutableStateOf(0.25f) }
    } else {
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
            label = "剧场动画相位",
        )
    }
    val extra = rememberStageExtra()

    Column(
        modifier
            .fillMaxWidth()
            .testTag("answer-pad"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 顶部情景标题与实时眨眼计数胶囊
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .clip(EyeShapes.chip)
                    .background(if (isOpenChallenge) colors.warningSoft else colors.primarySoft)
                    .border(
                        1.dp,
                        if (isOpenChallenge) colors.warning else colors.cardBorder.copy(alpha = 0.65f),
                        EyeShapes.chip,
                    )
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                EyeText(
                    if (isOpenChallenge) "持续睁眼" else "自然眨眼",
                    style = typography.chip,
                    color = if (isOpenChallenge) colors.warning else colors.moodGold,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .clip(EyeShapes.chip)
                        .background(colors.surfaceVariant)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    EyeText(
                        "已眨眼 $blinkCount 次",
                        style = typography.chip,
                        color = EyeGamePalette.Theater.tearHighlight,
                    )
                }
                EyeText(
                    "剩余 ${state.remainingSeconds} 秒",
                    style = typography.subtitle,
                    color = colors.textPrimary,
                )
            }
        }

        // 中央科普动画大银幕
        Box(
            Modifier
                .fillMaxWidth()
                .height(228.dp + extra)
                .clip(EyeShapes.hero)
                .background(EyeGamePalette.Theater.stageBg)
                .border(1.dp, colors.cardBorder.copy(alpha = 0.45f), EyeShapes.hero),
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription = if (isOpenChallenge) {
                            "泪膜守护挑战动画，剩余 ${state.remainingSeconds} 秒"
                        } else {
                            "护眼科普小剧场：${act.title}"
                        }
                    },
            ) {
                if (isOpenChallenge) {
                    drawOpenChallengeScene(
                        progress = state.progressFraction,
                        phase = animPhase,
                        blinked = blinkCount > 0,
                    )
                } else {
                    drawDryEyeActScene(actIndex = actIndex, phase = animPhase)
                }
            }
            // 幕次小标签
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(EyeShapes.chip)
                    .background(colors.appBackground.copy(alpha = 0.72f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                EyeText(
                    if (isOpenChallenge) "定格挑战 · 守护水膜" else act.badge,
                    style = typography.caption,
                    color = EyeGamePalette.Theater.tearHighlight,
                )
            }
            if (!isOpenChallenge) {
                // 底部四幕小进度点
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    repeat(DRY_EYE_ACTS.size) { idx ->
                        Box(
                            Modifier
                                .size(width = if (idx == actIndex) 18.dp else 6.dp, height = 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (idx == actIndex) colors.primary
                                    else colors.cardBorder.copy(alpha = 0.4f),
                                ),
                        )
                    }
                }
            }
        }

        // 下方科普字幕卡片
        val captionTitle = if (isOpenChallenge) "稳住晶莹水膜，尽量不要眨眼" else act.title
        val captionBody = if (isOpenChallenge) {
            if (blinkCount == 0) "目前还没有眨眼，水膜护盾正在持续充能！"
            else "已经眨眼 $blinkCount 次，继续稳住视线坚持到倒计时结束。"
        } else act.subtitle

        AnimatedContent(
            targetState = captionTitle to captionBody,
            transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(180)) },
            label = "科普字幕",
        ) { (title, body) ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(EyeShapes.medium)
                    .background(colors.surfaceVariant)
                    .border(1.dp, colors.borderSubtle, EyeShapes.medium)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EyeText(title, style = typography.subtitle, color = colors.textPrimary)
                EyeText(body, style = typography.bodySecondary, color = colors.textSecondary)
            }
        }
    }
}

/** 绘制 4 幕护眼科普矢量动画。 */
private fun DrawScope.drawDryEyeActScene(actIndex: Int, phase: Float) {
    val w = size.width
    val h = size.height
    val c = Offset(w * 0.5f, h * 0.52f)
    val unit = min(w, h)
    // 暖暗银幕底光
    drawCircle(
        brush = Brush.radialGradient(
            0f to EyeGamePalette.Theater.stageGlow.copy(alpha = 0.72f),
            1f to Color.Transparent,
            center = c,
            radius = unit * 0.68f,
        ),
        radius = unit * 0.68f,
        center = c,
    )
    val wave = sin(phase * 2f * PI.toFloat())
    when (actIndex) {
        0 -> {
            // 第 1 幕：眨眼刷水膜（大眼睛 + 周期轻眨 + 三层微光水波 + 飞舞水滴）
            val blinkPulse = if (phase in 0.74f..0.88f) {
                sin(((phase - 0.74f) / 0.14f) * PI.toFloat())
            } else 0f
            drawCartoonEyeMascot(c, unit * 0.34f, lidClose = blinkPulse, moistGlow = 1f)
            // 三层环绕水膜光弧
            for (layer in 0..2) {
                val r = unit * (0.34f + layer * 0.065f)
                drawArc(
                    color = EyeGamePalette.Theater.tearCyan.copy(alpha = 0.52f - layer * 0.14f),
                    startAngle = -150f + phase * 360f * (if (layer % 2 == 0) 1f else -0.6f),
                    sweepAngle = 95f,
                    useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r * 0.72f),
                    size = Size(r * 2f, r * 1.44f),
                    style = Stroke(width = (unit * 0.022f).coerceAtLeast(2.5f), cap = StrokeCap.Round),
                )
            }
            // 左右跳动的小水滴精灵（留足银幕边距避免裁切）
            drawWaterDrop(Offset(c.x - unit * 0.37f, c.y - unit * 0.12f + wave * unit * 0.04f), unit * 0.062f)
            drawWaterDrop(Offset(c.x + unit * 0.37f, c.y + unit * 0.10f - wave * unit * 0.04f), unit * 0.056f)
        }
        1 -> {
            // 第 2 幕：盯屏蒸发（右侧小屏幕发光 + 眼睛专注盯着 + 蒸发小气泡上升）
            val screenRect = Size(unit * 0.36f, unit * 0.28f)
            val screenTopLeft = Offset(c.x + unit * 0.16f, c.y - screenRect.height * 0.5f)
            drawRoundRect(
                color = EyeGamePalette.Theater.tearDeep.copy(alpha = 0.4f),
                topLeft = screenTopLeft,
                size = screenRect,
                cornerRadius = CornerRadius(unit * 0.04f),
            )
            drawRoundRect(
                color = EyeGamePalette.Theater.tearCyan.copy(alpha = 0.78f),
                topLeft = screenTopLeft,
                size = screenRect,
                cornerRadius = CornerRadius(unit * 0.04f),
                style = Stroke(width = unit * 0.018f),
            )
            // 屏幕里的波浪条纹
            for (row in 0..2) {
                val y = screenTopLeft.y + screenRect.height * (0.28f + row * 0.22f)
                drawLine(
                    EyeGamePalette.Theater.tearHighlight.copy(alpha = 0.65f),
                    Offset(screenTopLeft.x + screenRect.width * 0.16f, y),
                    Offset(screenTopLeft.x + screenRect.width * (0.82f - row * 0.12f), y),
                    strokeWidth = unit * 0.018f,
                    cap = StrokeCap.Round,
                )
            }
            val eyePos = Offset(c.x - unit * 0.26f, c.y + unit * 0.04f)
            drawCartoonEyeMascot(eyePos, unit * 0.26f, gazeX = 0.6f, lidClose = 0f, moistGlow = 0.55f)
            // 慢慢上升的蒸发水汽
            for (i in 0..2) {
                val t = (phase + i * 0.33f) % 1f
                val px = eyePos.x + (i - 1) * unit * 0.09f + sin(t * 6f) * unit * 0.02f
                val py = eyePos.y - unit * 0.18f - t * unit * 0.24f
                drawCircle(
                    EyeGamePalette.Theater.warmLamp.copy(alpha = (1f - t) * 0.65f),
                    radius = unit * (0.022f + t * 0.014f),
                    center = Offset(px, py),
                    style = Stroke(width = 2f),
                )
            }
        }
        2 -> {
            // 第 3 幕：20-20-20 远眺窗外远山与弯月
            val winW = unit * 0.92f
            val winH = unit * 0.58f
            val winTL = Offset(c.x - winW * 0.5f, c.y - winH * 0.5f)
            drawRoundRect(
                color = EyeGamePalette.Firefly.skyMid,
                topLeft = winTL,
                size = Size(winW, winH),
                cornerRadius = CornerRadius(unit * 0.06f),
            )
            // 远山
            val hill = Path().apply {
                moveTo(winTL.x, winTL.y + winH)
                lineTo(winTL.x, winTL.y + winH * 0.66f)
                quadraticTo(winTL.x + winW * 0.32f, winTL.y + winH * 0.34f, winTL.x + winW * 0.62f, winTL.y + winH * 0.64f)
                quadraticTo(winTL.x + winW * 0.84f, winTL.y + winH * 0.44f, winTL.x + winW, winTL.y + winH * 0.68f)
                lineTo(winTL.x + winW, winTL.y + winH)
                close()
            }
            drawPath(hill, EyeGamePalette.Theater.leafSage.copy(alpha = 0.55f))
            // 远方的太阳/暖星
            drawCircle(
                EyeGamePalette.Theater.warmLamp,
                radius = unit * 0.06f,
                center = Offset(winTL.x + winW * 0.78f, winTL.y + winH * 0.28f),
            )
            // 舒展扩散的远眺波纹
            for (ring in 0..2) {
                val t = (phase + ring * 0.33f) % 1f
                val r = unit * (0.06f + t * 0.24f)
                drawCircle(
                    EyeGamePalette.Theater.tearHighlight.copy(alpha = (1f - t) * 0.55f),
                    radius = r,
                    center = Offset(winTL.x + winW * 0.26f, winTL.y + winH * 0.48f),
                    style = Stroke(width = 2.2f),
                )
            }
            drawRoundRect(
                color = EyeGamePalette.Theater.warmLamp.copy(alpha = 0.45f),
                topLeft = winTL,
                size = Size(winW, winH),
                cornerRadius = CornerRadius(unit * 0.06f),
                style = Stroke(width = 2.5f),
            )
        }
        else -> {
            // 第 4 幕：暖雾润眼茶杯与舒适微笑眼
            drawCartoonEyeMascot(Offset(c.x - unit * 0.24f, c.y + unit * 0.02f), unit * 0.27f, lidClose = 0.88f, moistGlow = 1f)
            // 右侧温热小杯与柔和上升蒸气
            val cupW = unit * 0.26f
            val cupH = unit * 0.20f
            val cupTL = Offset(c.x + unit * 0.20f, c.y + unit * 0.04f)
            drawRoundRect(
                color = EyeGamePalette.Theater.warmLamp.copy(alpha = 0.85f),
                topLeft = cupTL,
                size = Size(cupW, cupH),
                cornerRadius = CornerRadius(unit * 0.05f),
            )
            for (s in 0..2) {
                val sx = cupTL.x + cupW * (0.25f + s * 0.25f)
                val drift = sin((phase + s * 0.3f) * 2f * PI.toFloat()) * unit * 0.025f
                val steam = Path().apply {
                    moveTo(sx, cupTL.y - unit * 0.03f)
                    quadraticTo(sx + drift, cupTL.y - unit * 0.14f, sx - drift * 0.7f, cupTL.y - unit * 0.26f)
                }
                drawPath(
                    steam,
                    color = EyeGamePalette.Theater.steam.copy(alpha = 0.65f),
                    style = Stroke(width = unit * 0.02f, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/** 持续睁眼 20 秒阶段：晶莹水膜护盾充能动画。 */
private fun DrawScope.drawOpenChallengeScene(progress: Float, phase: Float, blinked: Boolean) {
    val c = Offset(size.width * 0.5f, size.height * 0.54f)
    val unit = min(size.width, size.height)
    val ringR = unit * 0.36f
    val pulse = 0.5f + 0.5f * sin(phase * 2f * PI.toFloat())
    val shieldColor = if (blinked) EyeGamePalette.Theater.warmLamp else EyeGamePalette.Theater.tearCyan
    drawCircle(
        brush = Brush.radialGradient(
            0f to shieldColor.copy(alpha = 0.28f + pulse * 0.12f),
            1f to Color.Transparent,
            center = c,
            radius = ringR * 1.65f,
        ),
        radius = ringR * 1.65f,
        center = c,
    )
    drawCircle(
        color = shieldColor.copy(alpha = 0.22f),
        radius = ringR,
        center = c,
        style = Stroke(width = unit * 0.032f),
    )
    drawArc(
        color = shieldColor,
        startAngle = -90f,
        sweepAngle = 360f * progress.coerceIn(0f, 1f),
        useCenter = false,
        topLeft = Offset(c.x - ringR, c.y - ringR),
        size = Size(ringR * 2f, ringR * 2f),
        style = Stroke(width = unit * 0.042f, cap = StrokeCap.Round),
    )
    drawWaterDrop(c, unit * (0.16f + pulse * 0.015f))
}

private fun DrawScope.drawCartoonEyeMascot(
    center: Offset,
    radius: Float,
    gazeX: Float = 0f,
    lidClose: Float = 0f,
    moistGlow: Float = 1f,
) {
    val open = (1f - lidClose).coerceIn(0f, 1f)
    val w = radius * 1.7f
    val h = radius * 1.05f * open.coerceAtLeast(0.14f)
    if (open < 0.22f) {
        val smile = Path().apply {
            moveTo(center.x - w * 0.46f, center.y)
            quadraticTo(center.x, center.y + radius * 0.36f, center.x + w * 0.46f, center.y)
        }
        drawPath(smile, EyeGamePalette.Theater.warmLamp, style = Stroke(radius * 0.11f, cap = StrokeCap.Round))
        return
    }
    val contour = Path().apply {
        moveTo(center.x - w * 0.5f, center.y)
        quadraticTo(center.x, center.y - h, center.x + w * 0.5f, center.y)
        quadraticTo(center.x, center.y + h, center.x - w * 0.5f, center.y)
        close()
    }
    drawPath(contour, EyeGamePalette.Theater.steam)
    drawPath(
        contour,
        EyeGamePalette.Theater.tearCyan.copy(alpha = 0.65f * moistGlow),
        style = Stroke(width = radius * 0.08f, join = StrokeJoin.Round),
    )
    val irisC = Offset(center.x + gazeX * radius * 0.24f, center.y)
    val irisR = radius * 0.36f * open.coerceAtLeast(0.5f)
    drawCircle(EyeGamePalette.Theater.tearDeep, irisR, irisC)
    drawCircle(EyeGamePalette.Firefly.bodyDark, irisR * 0.52f, irisC)
    drawCircle(EyeGamePalette.Theater.tearHighlight, irisR * 0.26f, Offset(irisC.x - irisR * 0.26f, irisC.y - irisR * 0.24f))
}

private fun DrawScope.drawWaterDrop(center: Offset, radius: Float) {
    val drop = Path().apply {
        moveTo(center.x, center.y - radius * 1.25f)
        cubicTo(
            center.x + radius * 0.45f, center.y - radius * 0.45f,
            center.x + radius * 0.95f, center.y + radius * 0.15f,
            center.x + radius * 0.90f, center.y + radius * 0.55f,
        )
        cubicTo(
            center.x + radius * 0.85f, center.y + radius * 1.05f,
            center.x - radius * 0.85f, center.y + radius * 1.05f,
            center.x - radius * 0.90f, center.y + radius * 0.55f,
        )
        cubicTo(
            center.x - radius * 0.95f, center.y + radius * 0.15f,
            center.x - radius * 0.45f, center.y - radius * 0.45f,
            center.x, center.y - radius * 1.25f,
        )
        close()
    }
    drawPath(
        drop,
        brush = Brush.verticalGradient(
            0f to EyeGamePalette.Theater.tearHighlight,
            0.55f to EyeGamePalette.Theater.tearCyan,
            1f to EyeGamePalette.Theater.tearDeep,
            startY = center.y - radius * 1.2f,
            endY = center.y + radius,
        ),
    )
    drawCircle(
        EyeGamePalette.Theater.tearHighlight.copy(alpha = 0.88f),
        radius = radius * 0.20f,
        center = Offset(center.x - radius * 0.26f, center.y + radius * 0.28f),
    )
}

// ============================================================================
// 2. 老视自测：矢车菊邮局信封 + 四方向复古邮筒作答台（参考图 1 美化升级）
// ============================================================================

/**
 * 老视自测完整邮局互动台：上方是暖灯照耀下的矢车菊蓝信封（正中显示随距离与档位真实缩放的 E 视标），
 * 下方是一排四个复古墨绿邮筒（上/下/左/右），外加次要的「看不清 · 跳过」按钮。
 */
@Composable
internal fun PostOfficeEnvelopePad(
    direction: Int,
    size: Dp,
    visible: Boolean,
    gesture: GestureFeedback = GestureFeedback(),
    onAnswer: (Int) -> Unit,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val extra = rememberStageExtra()
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("answer-pad"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PostOfficeEnvelopeSurface(
            direction = direction,
            size = size,
            visible = visible,
            height = maxOf(196.dp, size + 104.dp) + extra,
        )
        // 一排四个复古邮筒：上 / 下 / 左 / 右（与参考图 1 顺序一致，保留方向编码 3/1/2/0）
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PostboxDirectionButton(3, "向上", visible, gesture, Modifier.weight(1f)) { onAnswer(3) }
            PostboxDirectionButton(1, "向下", visible, gesture, Modifier.weight(1f)) { onAnswer(1) }
            PostboxDirectionButton(2, "向左", visible, gesture, Modifier.weight(1f)) { onAnswer(2) }
            PostboxDirectionButton(0, "向右", visible, gesture, Modifier.weight(1f)) { onAnswer(0) }
        }
        // 次要「看不清 · 跳过」胶囊，保持完整可跳过能力且不打乱四邮筒排布
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .clip(EyeShapes.chip)
                .background(colors.surfaceVariant.copy(alpha = if (visible) 1f else 0.45f))
                .border(1.dp, colors.borderSubtle, EyeShapes.chip)
                .clickable(enabled = visible, role = Role.Button) { onAnswer(-1) }
                .padding(horizontal = 24.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            EyeText(
                "看不清 · 跳过",
                style = typography.bodySecondary,
                color = if (visible) colors.textSecondary else colors.textTertiary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 邮局矢车菊蓝信封舞台（同时供近视力/多距离/翻转拍与移近法复用，精确保持 E 物理尺寸）。 */
@Composable
internal fun PostOfficeEnvelopeSurface(
    direction: Int,
    size: Dp,
    visible: Boolean = true,
    height: Dp = maxOf(196.dp, size + 104.dp) + rememberStageExtra(),
) {
    val colors = LocalEyeColors.current
    val envelopeAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0.52f,
        animationSpec = tween(EyeMotion.SHORT),
        label = "信封显隐",
    )
    Box(
        Modifier
            .testTag("optotype-surround")
            .fillMaxWidth()
            .height(height)
            .clip(EyeShapes.hero)
            .border(1.dp, colors.cardBorder.copy(alpha = 0.4f), EyeShapes.hero),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = "矢车菊邮局信封视标舞台" },
        ) {
            drawPostOfficeScene(envelopeAlpha = envelopeAlpha)
        }
        if (visible) {
            TumblingE(
                direction = direction,
                size = size,
                color = EyeGamePalette.PostOffice.optotypePinkWhite,
                modifier = Modifier.testTag("answer-optotype"),
            )
        }
    }
}

/** 绘制参考图 1 风格的晴空小镇、暖光吊灯与深蓝矢车菊信封。 */
private fun DrawScope.drawPostOfficeScene(envelopeAlpha: Float) {
    val w = size.width
    val h = size.height
    // 晴雾渐变天空
    drawRect(
        brush = Brush.verticalGradient(
            0f to EyeGamePalette.PostOffice.skyTop,
            0.55f to EyeGamePalette.PostOffice.skyMid,
            1f to EyeGamePalette.PostOffice.skyBottom,
        ),
    )
    // 右侧柔和白云
    drawCloud(Offset(w * 0.80f, h * 0.14f), min(w, h) * 0.14f)
    drawCloud(Offset(w * 0.90f, h * 0.42f), min(w, h) * 0.11f)

    // 底部柔和梦幻小镇尖顶剪影
    val baseY = h * 0.84f
    val skyline = Path().apply {
        moveTo(0f, h)
        lineTo(0f, baseY)
        lineTo(w * 0.05f, baseY)
        lineTo(w * 0.09f, h * 0.56f)
        lineTo(w * 0.13f, baseY)
        lineTo(w * 0.25f, baseY)
        lineTo(w * 0.28f, h * 0.72f)
        lineTo(w * 0.34f, h * 0.72f)
        lineTo(w * 0.36f, baseY)
        lineTo(w * 0.73f, baseY)
        lineTo(w * 0.78f, h * 0.62f)
        lineTo(w * 0.83f, baseY)
        lineTo(w * 0.89f, baseY)
        lineTo(w * 0.93f, h * 0.58f)
        lineTo(w * 0.97f, baseY)
        lineTo(w, baseY)
        lineTo(w, h)
        close()
    }
    drawPath(skyline, EyeGamePalette.PostOffice.townFar.copy(alpha = 0.52f))
    val spire = Path().apply {
        moveTo(w * 0.74f, h)
        lineTo(w * 0.78f, h * 0.68f)
        lineTo(w * 0.82f, h)
        close()
    }
    drawPath(spire, EyeGamePalette.PostOffice.spireGreen.copy(alpha = 0.45f))

    // 左上角复古暖黄吊灯与柔和光锥
    val lampC = Offset(w * 0.09f, h * 0.12f)
    val beam = Path().apply {
        moveTo(lampC.x - w * 0.04f, lampC.y + h * 0.04f)
        lineTo(lampC.x + w * 0.06f, lampC.y + h * 0.02f)
        lineTo(w * 0.28f, h * 0.92f)
        lineTo(0f, h * 0.92f)
        close()
    }
    drawPath(
        beam,
        brush = Brush.verticalGradient(
            0f to EyeGamePalette.PostOffice.lampBeam.copy(alpha = 0.42f),
            1f to Color.Transparent,
            startY = lampC.y,
            endY = h * 0.92f,
        ),
    )
    drawLine(
        EyeGamePalette.PostOffice.lampBrass,
        Offset(lampC.x, 0f),
        Offset(lampC.x, lampC.y - h * 0.07f),
        strokeWidth = 3f,
    )
    val shade = Path().apply {
        moveTo(lampC.x - w * 0.055f, lampC.y + h * 0.05f)
        quadraticTo(lampC.x, lampC.y - h * 0.11f, lampC.x + w * 0.055f, lampC.y + h * 0.05f)
        close()
    }
    drawPath(shade, EyeGamePalette.PostOffice.lampGold)
    drawPath(shade, EyeGamePalette.PostOffice.lampBrass, style = Stroke(width = 2.2f, join = StrokeJoin.Round))
    drawOval(
        EyeGamePalette.PostOffice.lampBeam,
        topLeft = Offset(lampC.x - w * 0.045f, lampC.y + h * 0.028f),
        size = Size(w * 0.09f, h * 0.04f),
    )

    // 中央深蓝矢车菊信封
    val envW = (w * 0.72f).coerceAtMost(h * 1.48f)
    val envH = (h * 0.70f).coerceAtMost(envW * 0.68f)
    val envL = (w - envW) * 0.5f
    val envT = (h - envH) * 0.5f
    val envR = envL + envW
    val envB = envT + envH

    // 柔和投影
    drawRoundRect(
        color = EyeGamePalette.PostOffice.envelopeShadow.copy(alpha = 0.32f * envelopeAlpha),
        topLeft = Offset(envL + 4f, envT + 8f),
        size = Size(envW, envH),
        cornerRadius = CornerRadius(10f),
    )
    // 信封主体渐变
    drawRoundRect(
        brush = Brush.linearGradient(
            0f to EyeGamePalette.PostOffice.envelopeBase.copy(alpha = envelopeAlpha),
            1f to EyeGamePalette.PostOffice.envelopeDeep.copy(alpha = envelopeAlpha),
            start = Offset(envL, envT),
            end = Offset(envR, envB),
        ),
        topLeft = Offset(envL, envT),
        size = Size(envW, envH),
        cornerRadius = CornerRadius(8f),
    )
    // 信封左侧三角形折痕（对照图 1）
    val flap = Path().apply {
        moveTo(envL, envT)
        lineTo(envL + envW * 0.23f, envT + envH * 0.5f)
        lineTo(envL, envB)
        close()
    }
    drawPath(flap, EyeGamePalette.PostOffice.envelopeShadow.copy(alpha = 0.22f * envelopeAlpha))
    drawLine(
        EyeGamePalette.PostOffice.envelopeFiligree.copy(alpha = 0.45f * envelopeAlpha),
        Offset(envL + 3f, envT + 3f),
        Offset(envL + envW * 0.23f, envT + envH * 0.5f),
        strokeWidth = 1.6f,
    )
    drawLine(
        EyeGamePalette.PostOffice.envelopeFiligree.copy(alpha = 0.45f * envelopeAlpha),
        Offset(envL + envW * 0.23f, envT + envH * 0.5f),
        Offset(envL + 3f, envB - 3f),
        strokeWidth = 1.6f,
    )
    // 内侧精致银线双框与四角花纹
    val inset = 7f
    drawRoundRect(
        color = EyeGamePalette.PostOffice.envelopeFiligree.copy(alpha = 0.52f * envelopeAlpha),
        topLeft = Offset(envL + inset, envT + inset),
        size = Size(envW - inset * 2, envH - inset * 2),
        cornerRadius = CornerRadius(4f),
        style = Stroke(width = 1.4f),
    )
    drawCornerOrnament(Offset(envL + inset + 6f, envT + inset + 6f), 1f, 1f, envH * 0.14f, envelopeAlpha)
    drawCornerOrnament(Offset(envR - inset - 6f, envT + inset + 6f), -1f, 1f, envH * 0.14f, envelopeAlpha)
    drawCornerOrnament(Offset(envL + inset + 6f, envB - inset - 6f), 1f, -1f, envH * 0.14f, envelopeAlpha)
    drawCornerOrnament(Offset(envR - inset - 6f, envB - inset - 6f), -1f, -1f, envH * 0.14f, envelopeAlpha)

    // 右侧矢车菊花束 + 丝带蜡封（避开中央视标区域）
    val bouquetX = envR - envW * 0.18f
    val bouquetY = envT + envH * 0.42f
    val flowerR = envH * 0.12f
    // 绿茎
    for (offset in listOf(-6f, 0f, 6f)) {
        drawLine(
            EyeGamePalette.PostOffice.stemGreen.copy(alpha = 0.9f * envelopeAlpha),
            Offset(bouquetX + offset * 0.6f, bouquetY - flowerR * 0.3f),
            Offset(bouquetX + offset * 1.4f + 5f, envB - envH * 0.12f),
            strokeWidth = 2.6f,
            cap = StrokeCap.Round,
        )
    }
    // 三朵层次分明的矢车菊
    drawCornflower(Offset(bouquetX - flowerR * 0.35f, bouquetY - flowerR * 0.85f), flowerR * 0.92f, envelopeAlpha)
    drawCornflower(Offset(bouquetX + flowerR * 0.72f, bouquetY - flowerR * 0.25f), flowerR * 0.82f, envelopeAlpha)
    drawCornflower(Offset(bouquetX - flowerR * 0.55f, bouquetY + flowerR * 0.35f), flowerR * 0.88f, envelopeAlpha)
    // 丝带蝴蝶结与小银蜡封
    val sealC = Offset(bouquetX + 2f, envT + envH * 0.68f)
    drawOval(
        EyeGamePalette.PostOffice.envelopeBase.copy(alpha = envelopeAlpha),
        topLeft = Offset(sealC.x - flowerR * 1.1f, sealC.y - flowerR * 0.38f),
        size = Size(flowerR * 2.2f, flowerR * 0.76f),
    )
    drawOval(
        EyeGamePalette.PostOffice.envelopeFiligree.copy(alpha = 0.6f * envelopeAlpha),
        topLeft = Offset(sealC.x - flowerR * 1.1f, sealC.y - flowerR * 0.38f),
        size = Size(flowerR * 2.2f, flowerR * 0.76f),
        style = Stroke(width = 1.4f),
    )
    drawCircle(EyeGamePalette.PostOffice.waxSeal.copy(alpha = envelopeAlpha), flowerR * 0.46f, sealC)
    drawCircle(
        EyeGamePalette.PostOffice.envelopeDeep.copy(alpha = 0.45f * envelopeAlpha),
        flowerR * 0.30f,
        sealC,
        style = Stroke(width = 1.4f),
    )
}

private fun DrawScope.drawCloud(center: Offset, radius: Float) {
    val c = EyeGamePalette.PostOffice.cloud.copy(alpha = 0.85f)
    drawCircle(c, radius * 0.75f, Offset(center.x - radius * 0.65f, center.y + radius * 0.1f))
    drawCircle(c, radius, center)
    drawCircle(c, radius * 0.68f, Offset(center.x + radius * 0.72f, center.y + radius * 0.14f))
}

private fun DrawScope.drawCornerOrnament(corner: Offset, sx: Float, sy: Float, len: Float, alpha: Float) {
    val p = Path().apply {
        moveTo(corner.x, corner.y + sy * len)
        quadraticTo(corner.x, corner.y, corner.x + sx * len, corner.y)
    }
    drawPath(
        p,
        EyeGamePalette.PostOffice.envelopeFiligree.copy(alpha = 0.62f * alpha),
        style = Stroke(width = 1.6f, cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawCornflower(center: Offset, radius: Float, alpha: Float) {
    for (i in 0 until 10) {
        val a = i * (2f * PI.toFloat() / 10f)
        val tip = Offset(center.x + cos(a) * radius, center.y + sin(a) * radius)
        drawLine(
            (if (i % 2 == 0) EyeGamePalette.PostOffice.flowerOuter else EyeGamePalette.PostOffice.flowerInner)
                .copy(alpha = alpha),
            center,
            tip,
            strokeWidth = radius * 0.34f,
            cap = StrokeCap.Round,
        )
    }
    drawCircle(EyeGamePalette.PostOffice.flowerCenter.copy(alpha = alpha), radius * 0.36f, center)
    drawCircle(EyeGamePalette.PostOffice.envelopeFiligree.copy(alpha = 0.7f * alpha), radius * 0.14f, center)
}

/** 单个复古墨绿邮筒方向键（对照图 1）：穹顶 + 投信槽 + 清晰白箭头 + 手势锁定高亮环。 */
@Composable
private fun PostboxDirectionButton(
    direction: Int,
    label: String,
    enabled: Boolean,
    gesture: GestureFeedback,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalEyeColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val gestureMatched = enabled && gesture.direction == direction
    val gestureHolding = gestureMatched && !gesture.needsRelease
    val gestureNeedsRelease = gestureMatched && gesture.needsRelease
    val scale by animateFloatAsState(if (pressed && enabled) 0.93f else 1f, EyeMotion.pressSpring(), label = "邮筒缩放")
    val ringProgress by animateFloatAsState(if (gestureHolding) gesture.progress.coerceIn(0f, 1f) else 0f, tween(EyeMotion.SHORT), label = "邮筒手势进度")
    val borderColor by animateColorAsState(
        when {
            gestureHolding -> colors.primary
            pressed && enabled -> colors.moodGold
            gestureNeedsRelease -> colors.primary.copy(alpha = 0.55f)
            else -> EyeGamePalette.PostOffice.boxSlot
        },
        tween(EyeMotion.SHORT),
        label = "邮筒边框",
    )
    val alpha by animateFloatAsState(if (enabled) 1f else 0.4f, tween(EyeMotion.SHORT), label = "邮筒可用")

    Box(
        modifier
            .height(74.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .clip(EyeShapes.medium)
            .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // 邮筒圆顶
            drawRoundRect(
                color = EyeGamePalette.PostOffice.boxTop,
                topLeft = Offset(0f, 0f),
                size = Size(w, h * 0.42f),
                cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
            )
            // 邮筒主箱体
            drawRoundRect(
                color = EyeGamePalette.PostOffice.boxBody,
                topLeft = Offset(0f, h * 0.24f),
                size = Size(w, h * 0.76f),
                cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
            )
            // 顶部细长投信口
            drawRoundRect(
                color = EyeGamePalette.PostOffice.boxSlot,
                topLeft = Offset(w * 0.18f, h * 0.13f),
                size = Size(w * 0.64f, h * 0.085f),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
            // 前面板凹框 + 四角小铆钉
            val panelTop = h * 0.34f
            val panelH = h * 0.56f
            drawRoundRect(
                color = EyeGamePalette.PostOffice.boxSlot.copy(alpha = 0.45f),
                topLeft = Offset(w * 0.10f, panelTop),
                size = Size(w * 0.80f, panelH),
                cornerRadius = CornerRadius(6.dp.toPx()),
            )
            drawRoundRect(
                color = borderColor,
                topLeft = Offset(1.5.dp.toPx(), 1.5.dp.toPx()),
                size = Size(w - 3.dp.toPx(), h - 3.dp.toPx()),
                cornerRadius = CornerRadius(12.dp.toPx()),
                style = Stroke(width = if (gestureHolding || pressed) 2.5.dp.toPx() else 1.5.dp.toPx()),
            )
            val rivetR = 1.6.dp.toPx()
            for (rx in listOf(w * 0.16f, w * 0.84f)) {
                for (ry in listOf(panelTop + 5.dp.toPx(), panelTop + panelH - 5.dp.toPx())) {
                    drawCircle(EyeGamePalette.PostOffice.boxRivet.copy(alpha = 0.7f), rivetR, Offset(rx, ry))
                }
            }
            // 手势作答进度底部光条
            if (gestureHolding) {
                drawLine(
                    color = colors.primary,
                    start = Offset(w * 0.14f, h - 4.dp.toPx()),
                    end = Offset(w * 0.14f + w * 0.72f * ringProgress.coerceAtLeast(0.12f), h - 4.dp.toPx()),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        // 高对比白色方向粗箭头（基准朝右：0=右 1=下 2=左 3=上）
        Canvas(
            Modifier
                .padding(top = 18.dp)
                .size(26.dp)
                .rotate(direction * 90f),
        ) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w * 0.12f, h * 0.5f); lineTo(w * 0.86f, h * 0.5f)
                moveTo(w * 0.52f, h * 0.18f); lineTo(w * 0.86f, h * 0.5f); lineTo(w * 0.52f, h * 0.82f)
            }
            drawPath(
                path,
                color = if (gestureHolding) colors.moodGold else colors.white,
                style = Stroke(w * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

// ============================================================================
// 3. 斜视自测：月夜森林 + 发光萤火虫（参考图 2 美化升级）
// ============================================================================

/** 绘制参考图 2 同款深蓝月夜森林：弯月、多层林木枝干剪影与月光小溪。 */
internal fun DrawScope.drawMoonlitForestBackdrop(phase: Float = 0f) {
    val w = size.width
    val h = size.height
    // 深邃蓝夜空渐变
    drawRect(
        brush = Brush.verticalGradient(
            0f to EyeGamePalette.Firefly.skyTop,
            0.52f to EyeGamePalette.Firefly.skyMid,
            1f to EyeGamePalette.Firefly.skyBottom,
        ),
    )
    // 右上方弯月与柔光晕（偏右留白，避免与中上方的萤火虫重叠）
    val moonC = Offset(w * 0.78f, h * 0.11f)
    val moonR = (min(w, h) * 0.048f).coerceIn(14f, 28f)
    drawCircle(
        brush = Brush.radialGradient(
            0f to EyeGamePalette.Firefly.moonHalo.copy(alpha = 0.42f),
            0.55f to EyeGamePalette.Firefly.moonHalo.copy(alpha = 0.12f),
            1f to Color.Transparent,
            center = moonC,
            radius = moonR * 3.8f,
        ),
        radius = moonR * 3.8f,
        center = moonC,
    )
    val crescent = Path().apply {
        moveTo(moonC.x + moonR * 0.25f, moonC.y - moonR)
        cubicTo(
            moonC.x - moonR * 0.95f, moonC.y - moonR * 0.72f,
            moonC.x - moonR * 0.95f, moonC.y + moonR * 0.72f,
            moonC.x + moonR * 0.25f, moonC.y + moonR,
        )
        cubicTo(
            moonC.x - moonR * 0.28f, moonC.y + moonR * 0.52f,
            moonC.x - moonR * 0.28f, moonC.y - moonR * 0.52f,
            moonC.x + moonR * 0.25f, moonC.y - moonR,
        )
        close()
    }
    drawPath(crescent, EyeGamePalette.Firefly.moon)

    // 远景树林剪影
    val farTrees = listOf(0.18f, 0.34f, 0.56f, 0.80f)
    for (tx in farTrees) {
        drawLine(
            EyeGamePalette.Firefly.treeFar.copy(alpha = 0.75f),
            Offset(w * tx, 0f),
            Offset(w * tx + w * 0.01f, h * 0.85f),
            strokeWidth = w * 0.032f,
        )
    }
    // 左右两侧近景大树剪影（营造森林纵深感，中间留出开阔舞台给萤火虫）
    val leftTree = Path().apply {
        moveTo(0f, 0f)
        lineTo(w * 0.22f, 0f)
        quadraticTo(w * 0.15f, h * 0.32f, w * 0.12f, h * 0.65f)
        quadraticTo(w * 0.18f, h * 0.80f, w * 0.36f, h * 0.88f)
        lineTo(w * 0.32f, h)
        lineTo(0f, h)
        close()
    }
    drawPath(leftTree, EyeGamePalette.Firefly.treeNear.copy(alpha = 0.88f))
    val rightTree = Path().apply {
        moveTo(w, 0f)
        lineTo(w * 0.78f, 0f)
        quadraticTo(w * 0.86f, h * 0.30f, w * 0.88f, h * 0.64f)
        quadraticTo(w * 0.80f, h * 0.80f, w * 0.64f, h * 0.89f)
        lineTo(w * 0.68f, h)
        lineTo(w, h)
        close()
    }
    drawPath(rightTree, EyeGamePalette.Firefly.treeNear.copy(alpha = 0.88f))

    // 底部林间弯曲月光小溪
    val creek = Path().apply {
        moveTo(w * 0.44f, h * 0.78f)
        quadraticTo(w * 0.54f, h * 0.86f, w * 0.38f, h)
        lineTo(w * 0.66f, h)
        quadraticTo(w * 0.58f, h * 0.86f, w * 0.50f, h * 0.78f)
        close()
    }
    drawPath(creek, EyeGamePalette.Firefly.creek.copy(alpha = 0.65f))
    val shimmer = 0.45f + 0.35f * sin(phase * 2f * PI.toFloat())
    for (row in 0..3) {
        val ry = h * (0.83f + row * 0.042f)
        val rx = w * (0.49f + (if (row % 2 == 0) -0.02f else 0.025f))
        drawLine(
            EyeGamePalette.Firefly.creekHighlight.copy(alpha = shimmer * (0.55f - row * 0.08f)),
            Offset(rx - w * 0.045f, ry),
            Offset(rx + w * 0.045f, ry),
            strokeWidth = 2.2f,
            cap = StrokeCap.Round,
        )
    }
}

/** 绘制参考图 2 同款发光萤火虫：半透明双翅、小触角与温暖明亮的发光腹部。 */
internal fun DrawScope.drawMoonlitFirefly(
    center: Offset,
    radius: Float,
    wingPhase: Float,
    glowIntensity: Float = 1f,
    tiltDeg: Float = -18f,
) {
    val wingFlap = sin(wingPhase * 2f * PI.toFloat()) * 14f
    val glowAlpha = glowIntensity.coerceIn(0.25f, 1f)
    // 外层大柔光晕（中心对准发光尾部）
    val glowR = radius * (2.6f + 0.25f * sin(wingPhase * 2f * PI.toFloat()))
    drawCircle(
        brush = Brush.radialGradient(
            0f to EyeGamePalette.Firefly.glowCore.copy(alpha = 0.92f * glowAlpha),
            0.35f to EyeGamePalette.Firefly.glowMid.copy(alpha = 0.58f * glowAlpha),
            0.70f to EyeGamePalette.Firefly.glowOuter.copy(alpha = 0.22f * glowAlpha),
            1f to Color.Transparent,
            center = center,
            radius = glowR,
        ),
        radius = glowR,
        center = center,
    )
    rotate(degrees = tiltDeg, pivot = center) {
        // 左右半透明翅膀（随 wingFlap 轻快扑闪）
        for (side in listOf(-1f, 1f)) {
            rotate(degrees = side * (34f + wingFlap * 0.6f), pivot = Offset(center.x, center.y - radius * 0.35f)) {
                drawOval(
                    color = EyeGamePalette.Firefly.wing.copy(alpha = 0.78f),
                    topLeft = Offset(
                        center.x + (if (side < 0) -radius * 1.15f else radius * 0.05f),
                        center.y - radius * 1.05f,
                    ),
                    size = Size(radius * 1.10f, radius * 0.56f),
                )
            }
        }
        // 小触角
        for (side in listOf(-1f, 1f)) {
            val ant = Path().apply {
                moveTo(center.x + side * radius * 0.14f, center.y - radius * 0.78f)
                quadraticTo(
                    center.x + side * radius * 0.36f, center.y - radius * 1.18f,
                    center.x + side * radius * 0.54f, center.y - radius * 1.10f,
                )
            }
            drawPath(ant, EyeGamePalette.Firefly.wing.copy(alpha = 0.85f), style = Stroke(radius * 0.08f, cap = StrokeCap.Round))
        }
        // 头部与胸节
        drawCircle(EyeGamePalette.Firefly.bodyDark, radius * 0.34f, Offset(center.x, center.y - radius * 0.62f))
        drawCircle(EyeGamePalette.Firefly.wing, radius * 0.07f, Offset(center.x - radius * 0.11f, center.y - radius * 0.66f))
        drawCircle(EyeGamePalette.Firefly.wing, radius * 0.07f, Offset(center.x + radius * 0.11f, center.y - radius * 0.66f))
        drawOval(
            EyeGamePalette.Firefly.bodyWarm,
            topLeft = Offset(center.x - radius * 0.36f, center.y - radius * 0.42f),
            size = Size(radius * 0.72f, radius * 0.46f),
        )
        // 发光腹部核心（作为精准的视觉注视锚点）
        drawCircle(EyeGamePalette.Firefly.glowOuter.copy(alpha = glowAlpha), radius * 0.52f, center)
        drawCircle(EyeGamePalette.Firefly.glowMid.copy(alpha = glowAlpha), radius * 0.38f, center)
        drawCircle(EyeGamePalette.Firefly.glowCore.copy(alpha = glowAlpha), radius * 0.22f, center)
    }
}

/** 绘制萤火虫飞行时留下的柔和星尘尾迹。 */
internal fun DrawScope.drawFireflyTrail(points: List<Offset>, radius: Float) {
    if (points.size < 2) return
    val total = points.size
    points.forEachIndexed { idx, pt ->
        val ratio = (idx + 1).toFloat() / total
        drawCircle(
            color = EyeGamePalette.Firefly.glowMid.copy(alpha = ratio * 0.34f),
            radius = radius * (0.14f + ratio * 0.22f),
            center = pt,
        )
    }
}
