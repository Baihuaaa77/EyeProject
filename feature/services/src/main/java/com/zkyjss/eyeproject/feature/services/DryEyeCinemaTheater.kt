package com.zkyjss.eyeproject.feature.services

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeGamePalette
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.theme.rememberReducedMotion
import com.zkyjss.eyeproject.core.ui.component.EyeText
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 干眼自测电影级护眼科普动画剧场：
 * - 自然眨眼阶段（60 秒 6 幕 / 延长至 180 秒 18 幕）：每 10 秒切换一幕完整代码渲染矢量科普动画，
 *   配合中文语音讲解与底部影院字幕，前置相机同步静默统计真实眨眼次数与时间分布。
 * - 持续睁眼阶段（20 秒）：三阶进化「泪膜护盾」定格挑战动画。
 */
@Composable
internal fun DryEyeCinemaTheater(
    state: DryEyeTestUiState,
    voiceEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val reducedMotion = rememberReducedMotion()
    val isOpenChallenge = state.phase == DryEyePhase.Open
    val totalSec = if (isOpenChallenge) DryEyeProtocol.OPEN_SECONDS else state.naturalSeconds.coerceAtLeast(1)
    val elapsedFloatSec = (state.progressFraction * totalSec).coerceIn(0f, totalSec.toFloat())
    val elapsedSec = elapsedFloatSec.toInt()
    val scene = DryEyeStoryScript.sceneAt(elapsedSec)
    val totalActs = DryEyeStoryScript.totalScenesFor(state.naturalSeconds)
    val currentActNumber = (scene.index + 1).coerceAtMost(totalActs)
    val blinkCount = if (isOpenChallenge) state.phase2BlinkCount else state.phase1BlinkCount

    // 流畅 60fps 动画相位（使用 Compose InfiniteTransition，测试暂停或减少动画时自动静止）
    val animateActive = !state.paused && !reducedMotion
    val cinemaTransition = rememberInfiniteTransition(label = "干眼科普影院")
    val microLoop by if (animateActive) {
        cinemaTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(10_000, easing = LinearEasing), RepeatMode.Restart),
            label = "幕内循环",
        )
    } else {
        remember { mutableStateOf(0.58f) }
    }
    val subWave by if (animateActive) {
        cinemaTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing), RepeatMode.Restart),
            label = "微动相位",
        )
    } else {
        remember { mutableStateOf(0.35f) }
    }

    // 幕内起承转合进度 (0..1)：进度为 0（如静态预览）时走平滑循环，进行中严格对齐当前幕时间轴
    val actLocalProgress = if (!animateActive) {
        0.58f
    } else if (state.progressFraction > 0f) {
        ((elapsedFloatSec % DryEyeStoryScript.SCENE_DURATION_SEC) / DryEyeStoryScript.SCENE_DURATION_SEC)
            .coerceIn(0f, 1f)
    } else {
        microLoop
    }
    val extra = rememberStageExtra()

    // 底部声波跳动（仅语音讲解开启且未暂停时跳动）
    val eqPhase by if (!animateActive || !voiceEnabled) {
        remember { mutableStateOf(0.4f) }
    } else {
        cinemaTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "声波相位",
        )
    }

    Column(
        modifier
            .fillMaxWidth()
            .testTag("answer-pad"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 顶部状态栏：阶段胶囊 + 实时眨眼水滴统计 + 剩余时间
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                        .padding(horizontal = 11.dp, vertical = 4.dp),
                ) {
                    EyeText(
                        if (isOpenChallenge) "持续睁眼" else "自然眨眼",
                        style = typography.chip,
                        color = if (isOpenChallenge) colors.warning else colors.moodGold,
                    )
                }
                if (!isOpenChallenge) {
                    EyeText(
                        "$currentActNumber / $totalActs 幕",
                        style = typography.caption,
                        color = colors.textSecondary,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .clip(EyeShapes.chip)
                        .background(colors.surfaceVariant)
                        .border(1.dp, EyeGamePalette.Theater.tearCyan.copy(alpha = 0.35f), EyeShapes.chip)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
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

        // 中央宽银幕代码渲染科普动画画布
        Box(
            Modifier
                .fillMaxWidth()
                .height(250.dp + extra)
                .clip(EyeShapes.hero)
                .background(EyeGamePalette.Theater.stageBg)
                .border(1.dp, colors.cardBorder.copy(alpha = 0.45f), EyeShapes.hero),
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription = if (isOpenChallenge) {
                            "泪膜护盾终极定格挑战，剩余 ${state.remainingSeconds} 秒"
                        } else {
                            "护眼科普动画第 ${currentActNumber} 幕：${scene.title}"
                        }
                    },
            ) {
                if (isOpenChallenge) {
                    drawOpenShieldCinema(
                        progress = state.progressFraction,
                        elapsedSec = elapsedSec,
                        wave = subWave,
                        blinkCount = blinkCount,
                    )
                } else {
                    drawCinemaStoryScene(
                        sceneKey = scene.sceneKey,
                        actT = actLocalProgress,
                        wave = subWave,
                    )
                }
            }

            // 银幕顶部分段电影时间轴
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val segCount = if (isOpenChallenge) 3 else totalActs.coerceIn(1, 12)
                val activeSeg = if (isOpenChallenge) {
                    (elapsedSec / 7).coerceIn(0, 2)
                } else {
                    (scene.index % segCount)
                }
                val activeFill = if (isOpenChallenge) {
                    ((elapsedFloatSec % 7f) / 7f).coerceIn(0f, 1f)
                } else actLocalProgress
                repeat(segCount) { idx ->
                    val fill = when {
                        idx < activeSeg -> 1f
                        idx == activeSeg -> activeFill
                        else -> 0f
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(colors.white.copy(alpha = 0.2f)),
                    ) {
                        if (fill > 0f) {
                            Box(
                                Modifier
                                    .fillMaxWidth(fill)
                                    .height(3.dp)
                                    .clip(CircleShape)
                                    .background(if (isOpenChallenge) EyeGamePalette.Theater.tearCyan else colors.primary),
                            )
                        }
                    }
                }
            }

            // 银幕左上剧目名 + 右上科普要点标签
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .clip(EyeShapes.chip)
                        .background(EyeGamePalette.Theater.stageDeep.copy(alpha = 0.78f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    EyeText(
                        if (isOpenChallenge) "定格挑战 · 守护水膜" else scene.title,
                        style = typography.caption,
                        color = colors.textPrimary,
                    )
                }
                Box(
                    Modifier
                        .clip(EyeShapes.chip)
                        .background(EyeGamePalette.Theater.stageDeep.copy(alpha = 0.78f))
                        .border(1.dp, EyeGamePalette.Theater.tearCyan.copy(alpha = 0.4f), EyeShapes.chip)
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    EyeText(
                        if (isOpenChallenge) {
                            when {
                                elapsedSec >= 14 -> "满级星盾"
                                elapsedSec >= 7 -> "暖金护盾"
                                else -> "青露护盾"
                            }
                        } else scene.factTag,
                        style = typography.caption,
                        color = EyeGamePalette.Theater.tearHighlight,
                    )
                }
            }

            // 银幕右下角：中文语音讲解动态声波指示
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .clip(EyeShapes.chip)
                    .background(EyeGamePalette.Theater.stageDeep.copy(alpha = 0.76f))
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.size(width = 16.dp, height = 12.dp)) {
                    val barW = size.width / 7f
                    for (i in 0..3) {
                        val amp = if (voiceEnabled && !state.paused) {
                            0.32f + 0.68f * ((sin((eqPhase + i * 0.24f) * 2f * PI.toFloat()) + 1f) * 0.5f)
                        } else 0.25f
                        val bh = size.height * amp
                        drawRoundRect(
                            color = if (voiceEnabled) EyeGamePalette.Theater.lipidGold else colors.textTertiary,
                            topLeft = Offset(i * barW * 1.9f, (size.height - bh) * 0.5f),
                            size = Size(barW, bh),
                            cornerRadius = CornerRadius(barW * 0.5f),
                        )
                    }
                }
                EyeText(
                    if (voiceEnabled) "中文讲解中" else "静音字幕",
                    style = typography.caption,
                    color = if (voiceEnabled) EyeGamePalette.Theater.lipidGold else colors.textSecondary,
                )
            }
        }

        // 底部电影级中文讲解字幕栏（与实时中文 TTS 语音逐幕完全同步）
        val subtitleHeader = if (isOpenChallenge) {
            if (blinkCount == 0) "稳住晶莹水膜，尽量不要眨眼" else "已眨眼 $blinkCount 次，继续稳住视线坚持"
        } else {
            "护眼小课堂 · ${scene.title}"
        }
        val subtitleText = if (isOpenChallenge) {
            DryEyeStoryScript.openNarration(elapsedSec)
        } else {
            scene.narration
        }
        AnimatedContent(
            targetState = subtitleHeader to subtitleText,
            transitionSpec = { fadeIn(tween(280)) togetherWith fadeOut(tween(180)) },
            label = "电影科普字幕",
        ) { (header, caption) ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(EyeShapes.medium)
                    .background(colors.surfaceVariant)
                    .border(1.dp, colors.borderSubtle, EyeShapes.medium)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EyeText(header, style = typography.caption, color = colors.moodGold)
                EyeText(caption, style = typography.body, color = colors.textPrimary)
            }
        }
    }
}

// ============================================================================
// 12 幕电影级代码渲染矢量科普动画引擎
// ============================================================================

private fun DrawScope.drawCinemaStoryScene(sceneKey: String, actT: Float, wave: Float) {
    val w = size.width
    val h = size.height
    val c = Offset(w * 0.5f, h * 0.56f)
    val u = min(w, h)
    // 柔和影院渐变背景 + 微星装饰
    val topColor = when (sceneKey) {
        "RULE_20_20_20", "HYDRATION_NUTRITION" -> EyeGamePalette.Theater.skyDay
        "NIGHT_LIGHT", "AC_WIND" -> EyeGamePalette.Theater.skyNight
        else -> EyeGamePalette.Theater.stageBg
    }
    drawRect(
        brush = Brush.verticalGradient(
            0f to topColor,
            0.78f to EyeGamePalette.Theater.stageBg,
            1f to EyeGamePalette.Theater.stageDeep,
        ),
    )
    drawCircle(
        brush = Brush.radialGradient(
            0f to EyeGamePalette.Theater.stageGlow.copy(alpha = 0.65f),
            1f to Color.Transparent,
            center = c,
            radius = u * 0.72f,
        ),
        radius = u * 0.72f,
        center = c,
    )

    when (sceneKey) {
        "WATER_WORLD" -> drawSceneWaterWorld(c, u, actT, wave)
        "TEAR_LAYERS" -> drawSceneTearLayers(c, u, actT, wave)
        "SCREEN_VS_TALK" -> drawSceneScreenVsTalk(c, u, actT, wave)
        "TEAR_BREAKUP" -> drawSceneTearBreakup(c, u, actT, wave)
        "RULE_20_20_20" -> drawSceneRule202020(c, u, actT, wave)
        "WARM_COMPRESS" -> drawSceneWarmCompress(c, u, actT, wave)
        "AC_WIND" -> drawSceneAcWind(c, u, actT, wave)
        "SCREEN_ANGLE" -> drawSceneScreenAngle(c, u, actT, wave)
        "NIGHT_LIGHT" -> drawSceneNightLight(c, u, actT, wave)
        "COMPLETE_BLINK" -> drawSceneCompleteBlink(c, u, actT, wave)
        "CONTACT_LENS" -> drawSceneContactLens(c, u, actT, wave)
        else -> drawSceneHydrationNutrition(c, u, actT, wave)
    }
}

/** 第 1 幕：立体分层大眼球 + 眨眼刷水膜 + 微笑水滴精灵滑行。 */
private fun DrawScope.drawSceneWaterWorld(c: Offset, u: Float, actT: Float, wave: Float) {
    // 周期轻柔眨眼（第 3.5 秒和第 7.5 秒各眨一次）
    val blink1 = if (actT in 0.30f..0.42f) sin(((actT - 0.30f) / 0.12f) * PI.toFloat()) else 0f
    val blink2 = if (actT in 0.72f..0.84f) sin(((actT - 0.72f) / 0.12f) * PI.toFloat()) else 0f
    val lidClose = maxOf(blink1, blink2)
    drawDetailedEye(c, u * 0.34f, lidClose = lidClose, gazeX = sin(wave * 2f * PI.toFloat()) * 0.25f, dryRatio = 0f)
    // 三道水光涟漪环
    for (i in 0..2) {
        val r = u * (0.37f + i * 0.065f)
        drawArc(
            color = EyeGamePalette.Theater.tearCyan.copy(alpha = 0.55f - i * 0.15f),
            startAngle = -145f + actT * 360f * (if (i % 2 == 0) 1f else -0.7f),
            sweepAngle = 82f,
            useCenter = false,
            topLeft = Offset(c.x - r, c.y - r * 0.72f),
            size = Size(r * 2f, r * 1.44f),
            style = Stroke(width = 3.2f, cap = StrokeCap.Round),
        )
    }
    // 微笑水滴精灵「小露」沿着眼表上方弧线轻快滑行
    val arcAngle = -PI.toFloat() * (0.82f - actT * 0.64f)
    val dropPos = Offset(c.x + cos(arcAngle) * u * 0.42f, c.y + sin(arcAngle) * u * 0.30f)
    drawSmilingDrop(dropPos, u * 0.082f)
    // 晶莹浮游微泡
    for (i in 0..5) {
        val px = size.width * (0.14f + i * 0.145f)
        val py = size.height * (0.32f + ((i * 0.23f + wave) % 0.5f))
        drawCircle(EyeGamePalette.Theater.tearHighlight.copy(alpha = 0.32f), u * 0.014f, Offset(px, py))
    }
}

/** 第 2 幕：动态三层泪膜解剖图（油脂层 / 水液层 / 黏蛋白层 + 水分子弹回锁水）。 */
private fun DrawScope.drawSceneTearLayers(c: Offset, u: Float, actT: Float, wave: Float) {
    val left = size.width * 0.10f
    val right = size.width * 0.90f
    val w = right - left
    val baseY = size.height * 0.78f
    // 角膜底座
    val cornea = Path().apply {
        moveTo(left, size.height * 0.88f)
        lineTo(left, baseY)
        quadraticTo(c.x, baseY - u * 0.08f, right, baseY)
        lineTo(right, size.height * 0.88f)
        close()
    }
    drawPath(cornea, EyeGamePalette.Theater.skinWarm.copy(alpha = 0.52f))

    // 三层波浪依次浮现动画
    val mucinAlpha = (actT / 0.25f).coerceIn(0.35f, 1f)
    val aqueousAlpha = ((actT - 0.18f) / 0.25f).coerceIn(0.25f, 1f)
    val lipidAlpha = ((actT - 0.36f) / 0.25f).coerceIn(0.25f, 1f)

    drawWaveBand(left, right, baseY - u * 0.06f, u * 0.09f, wave * 1.0f, EyeGamePalette.Theater.mucinViolet.copy(alpha = 0.75f * mucinAlpha))
    drawWaveBand(left, right, baseY - u * 0.19f, u * 0.15f, wave * 1.4f + 0.3f, EyeGamePalette.Theater.tearCyan.copy(alpha = 0.78f * aqueousAlpha))
    drawWaveBand(left, right, baseY - u * 0.31f, u * 0.065f, wave * 1.1f + 0.6f, EyeGamePalette.Theater.lipidGold.copy(alpha = 0.92f * lipidAlpha))

    // 几颗水分子向上撞击金黄油脂层并反弹锁回水液层
    for (i in 0..4) {
        val cycle = (wave + i * 0.21f) % 1f
        val bounce = sin(cycle * PI.toFloat())
        val mx = left + w * (0.16f + i * 0.17f)
        val my = (baseY - u * 0.14f) - bounce * u * 0.12f
        drawCircle(EyeGamePalette.Theater.tearHighlight.copy(alpha = 0.9f), u * 0.022f, Offset(mx, my))
    }
    // 顶部三层色块图例条（极简直观）
    val legendY = size.height * 0.29f
    val items = listOf(
        EyeGamePalette.Theater.lipidGold to 0.24f,
        EyeGamePalette.Theater.tearCyan to 0.50f,
        EyeGamePalette.Theater.mucinViolet to 0.76f,
    )
    items.forEach { (col, rx) ->
        drawCircle(col, u * 0.026f, Offset(size.width * rx, legendY))
        drawLine(
            col.copy(alpha = 0.55f),
            Offset(size.width * rx, legendY + u * 0.03f),
            Offset(size.width * rx, baseY - u * (if (col == EyeGamePalette.Theater.lipidGold) 0.32f else if (col == EyeGamePalette.Theater.tearCyan) 0.20f else 0.08f)),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )
    }
}

/** 第 3 幕：左右分屏动态对比——「轻松聊天 15 次/分」vs「专注盯屏 5 次/分」。 */
private fun DrawScope.drawSceneScreenVsTalk(c: Offset, u: Float, actT: Float, wave: Float) {
    val leftC = Offset(size.width * 0.27f, size.height * 0.50f)
    val rightC = Offset(size.width * 0.73f, size.height * 0.50f)
    // 中间竖向分割线
    drawLine(
        EyeGamePalette.Theater.steam.copy(alpha = 0.18f),
        Offset(c.x, size.height * 0.24f),
        Offset(c.x, size.height * 0.86f),
        strokeWidth = 2f,
    )
    // 左侧：规律轻眨 + 高水润能量柱
    val leftBlink = if (wave in 0.42f..0.56f) sin(((wave - 0.42f) / 0.14f) * PI.toFloat()) else 0f
    drawDetailedEye(Offset(leftC.x, leftC.y - u * 0.04f), u * 0.18f, lidClose = leftBlink, dryRatio = 0f)
    val barW = u * 0.12f
    val barMaxH = u * 0.26f
    val leftFill = (0.35f + actT * 0.58f).coerceAtMost(0.92f)
    drawRoundRect(
        EyeGamePalette.Theater.tearCyan.copy(alpha = 0.22f),
        topLeft = Offset(leftC.x - barW * 0.5f, size.height * 0.85f - barMaxH),
        size = Size(barW, barMaxH),
        cornerRadius = CornerRadius(6f),
    )
    drawRoundRect(
        EyeGamePalette.Theater.leafSage,
        topLeft = Offset(leftC.x - barW * 0.5f, size.height * 0.85f - barMaxH * leftFill),
        size = Size(barW, barMaxH * leftFill),
        cornerRadius = CornerRadius(6f),
    )

    // 右侧：紧盯发光屏 + 瞪大不眨 + 低水位 + 蒸发气泡
    drawDetailedEye(Offset(rightC.x, rightC.y - u * 0.04f), u * 0.18f, lidClose = 0f, dryRatio = (actT * 0.85f).coerceIn(0.2f, 0.9f))
    val rightFill = (0.72f - actT * 0.44f).coerceAtLeast(0.26f)
    drawRoundRect(
        EyeGamePalette.Theater.dryCoral.copy(alpha = 0.22f),
        topLeft = Offset(rightC.x - barW * 0.5f, size.height * 0.85f - barMaxH),
        size = Size(barW, barMaxH),
        cornerRadius = CornerRadius(6f),
    )
    drawRoundRect(
        EyeGamePalette.Theater.dryCoral,
        topLeft = Offset(rightC.x - barW * 0.5f, size.height * 0.85f - barMaxH * rightFill),
        size = Size(barW, barMaxH * rightFill),
        cornerRadius = CornerRadius(6f),
    )
    // 右侧升腾蒸发水汽
    for (i in 0..2) {
        val st = (wave + i * 0.33f) % 1f
        val sx = rightC.x + (i - 1) * u * 0.08f
        val sy = rightC.y - u * 0.18f - st * u * 0.18f
        drawCircle(EyeGamePalette.Theater.dryCoral.copy(alpha = (1f - st) * 0.65f), u * 0.018f, Offset(sx, sy), style = Stroke(2f))
    }
}

/** 第 4 幕：微距泪膜破裂干斑 → 眼睑雨刷温柔扫过瞬间修复。 */
private fun DrawScope.drawSceneTearBreakup(c: Offset, u: Float, actT: Float, wave: Float) {
    val filmR = u * 0.34f
    // 晶莹角膜泪膜圆盘
    drawCircle(
        brush = Brush.radialGradient(
            0f to EyeGamePalette.Theater.tearCyan.copy(alpha = 0.78f),
            0.75f to EyeGamePalette.Theater.tearDeep.copy(alpha = 0.62f),
            1f to EyeGamePalette.Theater.tearDeep.copy(alpha = 0.25f),
            center = c,
            radius = filmR,
        ),
        radius = filmR,
        center = c,
    )
    drawCircle(EyeGamePalette.Theater.tearHighlight.copy(alpha = 0.7f), filmR, c, style = Stroke(3f))

    // 0..0.62 干斑慢慢扩大；0.62..0.92 眼睑雨刷从上而下扫过修复干斑
    val drySeverity = when {
        actT < 0.62f -> (actT / 0.62f)
        actT < 0.88f -> (1f - (actT - 0.62f) / 0.26f).coerceIn(0f, 1f)
        else -> 0f
    }
    if (drySeverity > 0.05f) {
        val spots = listOf(
            Offset(c.x - filmR * 0.34f, c.y - filmR * 0.12f) to 0.22f,
            Offset(c.x + filmR * 0.28f, c.y + filmR * 0.18f) to 0.26f,
            Offset(c.x + filmR * 0.06f, c.y - filmR * 0.32f) to 0.17f,
        )
        spots.forEach { (pt, scale) ->
            val r = filmR * scale * drySeverity
            drawCircle(EyeGamePalette.Theater.dryCoral.copy(alpha = 0.72f * drySeverity), r, pt)
            drawCircle(EyeGamePalette.Theater.warmLamp.copy(alpha = 0.55f * drySeverity), r * 1.15f, pt, style = Stroke(2f))
        }
    }
    // 眼睑雨刷扫过光带
    if (actT in 0.60f..0.92f) {
        val sweep = (actT - 0.60f) / 0.32f
        val barY = c.y - filmR + sweep * filmR * 2f
        drawLine(
            EyeGamePalette.Theater.tearHighlight,
            Offset(c.x - filmR * 1.1f, barY),
            Offset(c.x + filmR * 1.1f, barY),
            strokeWidth = 7f,
            cap = StrokeCap.Round,
        )
    } else if (actT >= 0.90f) {
        // 修复完成后绽放晶莹十字高光
        drawSparkle(Offset(c.x - filmR * 0.25f, c.y - filmR * 0.22f), u * 0.055f, EyeGamePalette.Theater.tearHighlight)
        drawSparkle(Offset(c.x + filmR * 0.30f, c.y + filmR * 0.10f), u * 0.045f, EyeGamePalette.Theater.lipidGold)
    }
}

/** 第 5 幕：20-20-20 推窗远眺，紧绷睫状肌弹簧慢慢舒展。 */
private fun DrawScope.drawSceneRule202020(c: Offset, u: Float, actT: Float, wave: Float) {
    val winW = size.width * 0.76f
    val winH = size.height * 0.52f
    val winTL = Offset((size.width - winW) * 0.5f, size.height * 0.26f)
    // 窗外晴空与层峦远山
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to EyeGamePalette.Theater.skyDay,
            1f to EyeGamePalette.Theater.leafSage.copy(alpha = 0.5f),
            startY = winTL.y,
            endY = winTL.y + winH,
        ),
        topLeft = winTL,
        size = Size(winW, winH),
        cornerRadius = CornerRadius(16f),
    )
    val winClip = Path().apply {
        addRoundRect(RoundRect(winTL.x, winTL.y, winTL.x + winW, winTL.y + winH, CornerRadius(16f)))
    }
    val mountain = Path().apply {
        moveTo(winTL.x, winTL.y + winH)
        lineTo(winTL.x, winTL.y + winH * 0.66f)
        quadraticTo(winTL.x + winW * 0.28f, winTL.y + winH * 0.30f, winTL.x + winW * 0.56f, winTL.y + winH * 0.62f)
        quadraticTo(winTL.x + winW * 0.80f, winTL.y + winH * 0.36f, winTL.x + winW, winTL.y + winH * 0.64f)
        lineTo(winTL.x + winW, winTL.y + winH)
        close()
    }
    clipPath(winClip) {
        drawPath(mountain, EyeGamePalette.Theater.leafSage.copy(alpha = 0.78f))
    }
    // 远方暖日
    val sunC = Offset(winTL.x + winW * 0.80f, winTL.y + winH * 0.28f)
    drawCircle(EyeGamePalette.Theater.warmLamp.copy(alpha = 0.25f), u * 0.11f, sunC)
    drawCircle(EyeGamePalette.Theater.warmLamp, u * 0.055f, sunC)
    // 视线从左侧小眼睛舒展延伸向远山，伴随放松波纹
    val eyePos = Offset(winTL.x + winW * 0.18f, winTL.y + winH * 0.56f)
    drawLine(
        EyeGamePalette.Theater.tearHighlight.copy(alpha = 0.7f),
        eyePos,
        Offset(winTL.x + winW * (0.25f + actT * 0.52f), winTL.y + winH * (0.56f - actT * 0.24f)),
        strokeWidth = 3f,
        cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
    )
    for (i in 0..2) {
        val rt = (wave + i * 0.33f) % 1f
        drawCircle(
            EyeGamePalette.Theater.tearHighlight.copy(alpha = (1f - rt) * 0.55f),
            radius = u * (0.04f + rt * 0.15f),
            center = sunC,
            style = Stroke(2.2f),
        )
    }
    // 窗框描边 + 下方舒展的睫状肌波浪弹簧
    drawRoundRect(
        EyeGamePalette.Theater.warmLamp.copy(alpha = 0.65f),
        topLeft = winTL,
        size = Size(winW, winH),
        cornerRadius = CornerRadius(16f),
        style = Stroke(3.5f),
    )
    val relax = actT.coerceIn(0f, 1f)
    val springPath = Path().apply {
        val sx = winTL.x + winW * 0.15f
        val sw = winW * (0.35f + relax * 0.35f)
        val sy = size.height * 0.85f
        moveTo(sx, sy)
        for (i in 1..8) {
            val x = sx + sw * (i / 8f)
            val y = sy + (if (i % 2 == 0) -1f else 1f) * u * (0.045f - relax * 0.025f)
            lineTo(x, y)
        }
    }
    drawPath(
        springPath,
        color = if (relax > 0.45f) EyeGamePalette.Theater.leafSage else EyeGamePalette.Theater.dryCoral,
        style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** 第 6 幕：40°C 暖雾热敷融化睑板腺凝固油脂。 */
private fun DrawScope.drawSceneWarmCompress(c: Offset, u: Float, actT: Float, wave: Float) {
    val melt = ((actT - 0.22f) / 0.60f).coerceIn(0f, 1f)
    val baseY = size.height * 0.84f
    val glandTop = size.height * 0.56f
    // 6 根整齐的睑板腺小管
    for (i in 0 until 6) {
        val gx = size.width * (0.20f + i * 0.12f)
        drawRoundRect(
            EyeGamePalette.Theater.skinWarm.copy(alpha = 0.35f),
            topLeft = Offset(gx - u * 0.032f, glandTop),
            size = Size(u * 0.064f, baseY - glandTop),
            cornerRadius = CornerRadius(12f),
        )
        // 管内小油珠随热敷温度升高慢慢融化并上浮流出！
        val beadY = glandTop + (baseY - glandTop) * (0.55f - melt * 0.55f) - melt * u * 0.10f * ((wave + i * 0.17f) % 1f)
        drawCircle(
            color = if (melt > 0.35f) EyeGamePalette.Theater.lipidGold else EyeGamePalette.Theater.steam.copy(alpha = 0.85f),
            radius = u * (0.024f + melt * 0.008f),
            center = Offset(gx, beadY),
        )
    }
    // 上方柔软的 40°C 暖敷云朵毛巾 + 柔和上升暖雾
    val towelTL = Offset(size.width * 0.22f, size.height * 0.29f)
    val towelSize = Size(size.width * 0.56f, u * 0.17f)
    drawRoundRect(
        brush = Brush.horizontalGradient(
            0f to EyeGamePalette.Theater.dryCoral.copy(alpha = 0.72f),
            0.5f to EyeGamePalette.Theater.warmLamp.copy(alpha = 0.88f),
            1f to EyeGamePalette.Theater.dryCoral.copy(alpha = 0.72f),
        ),
        topLeft = towelTL,
        size = towelSize,
        cornerRadius = CornerRadius(24f),
    )
    for (i in 0..3) {
        val sx = size.width * (0.30f + i * 0.13f)
        val drift = sin((wave + i * 0.25f) * 2f * PI.toFloat()) * u * 0.028f
        val steam = Path().apply {
            moveTo(sx, glandTop - u * 0.02f)
            quadraticTo(sx + drift, glandTop - u * 0.09f, sx - drift * 0.7f, towelTL.y + towelSize.height)
        }
        drawPath(steam, EyeGamePalette.Theater.warmLamp.copy(alpha = 0.48f + melt * 0.35f), style = Stroke(3f, cap = StrokeCap.Round))
    }
}

/** 第 7 幕：偏转空调冷风 + 桌面加湿器水雾。 */
private fun DrawScope.drawSceneAcWind(c: Offset, u: Float, actT: Float, wave: Float) {
    val fixed = actT > 0.45f
    drawDetailedEye(Offset(c.x, c.y + u * 0.04f), u * 0.24f, lidClose = 0f, dryRatio = if (fixed) 0f else 0.7f)
    // 左侧冷风气流线：后半段被向上偏转避开眼睛
    for (i in 0..2) {
        val y0 = size.height * (0.42f + i * 0.10f)
        val endY = if (fixed) size.height * (0.24f + i * 0.05f) else y0
        val p = Path().apply {
            moveTo(size.width * 0.06f, y0)
            quadraticTo(size.width * 0.22f, (y0 + endY) * 0.5f - u * 0.04f, size.width * 0.34f, endY)
        }
        drawPath(
            p,
            EyeGamePalette.Theater.coldWind.copy(alpha = 0.75f),
            style = Stroke(width = 3.5f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), -wave * 40f)),
        )
    }
    // 右侧小加湿器喷出柔和上升青白水雾
    if (fixed) {
        val humC = Offset(size.width * 0.80f, size.height * 0.74f)
        drawRoundRect(
            EyeGamePalette.Theater.tearDeep,
            topLeft = Offset(humC.x - u * 0.09f, humC.y - u * 0.08f),
            size = Size(u * 0.18f, u * 0.18f),
            cornerRadius = CornerRadius(14f),
        )
        for (i in 0..4) {
            val mt = (wave + i * 0.2f) % 1f
            val mp = Offset(humC.x - mt * u * 0.22f + sin(mt * 5f) * u * 0.03f, humC.y - u * 0.12f - mt * u * 0.32f)
            drawCircle(EyeGamePalette.Theater.tearHighlight.copy(alpha = (1f - mt) * 0.75f), u * (0.022f + mt * 0.02f), mp)
        }
    }
}

/** 第 8 幕：屏幕放低 15° 几何对比，减小眼表暴露面积。 */
private fun DrawScope.drawSceneScreenAngle(c: Offset, u: Float, actT: Float, wave: Float) {
    val lowered = ((actT - 0.25f) / 0.45f).coerceIn(0f, 1f)
    val eyePos = Offset(size.width * 0.28f, size.height * 0.48f)
    val screenY = size.height * (0.44f + lowered * 0.22f)
    val screenPos = Offset(size.width * 0.76f, screenY)
    // 水平参考虚线
    drawLine(
        EyeGamePalette.Theater.steam.copy(alpha = 0.28f),
        eyePos,
        Offset(size.width * 0.82f, eyePos.y),
        strokeWidth = 2f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
    )
    // 实际视线连接线（从平视降到 15° 俯视）
    drawLine(
        if (lowered > 0.5f) EyeGamePalette.Theater.leafSage else EyeGamePalette.Theater.dryCoral,
        eyePos,
        screenPos,
        strokeWidth = 4f,
        cap = StrokeCap.Round,
    )
    // 左侧大眼睛（随着俯视，上眼皮自然下垂护住眼球）
    drawDetailedEye(eyePos, u * 0.19f, lidClose = lowered * 0.34f, gazeX = 0.5f, dryRatio = (1f - lowered) * 0.65f)
    // 右侧可移动小屏幕
    drawRoundRect(
        EyeGamePalette.Theater.tearCyan.copy(alpha = 0.85f),
        topLeft = Offset(screenPos.x - u * 0.04f, screenPos.y - u * 0.14f),
        size = Size(u * 0.08f, u * 0.28f),
        cornerRadius = CornerRadius(8f),
    )
}

/** 第 9 幕：暗室刷手机 → 点亮柔和暖光背景台灯。 */
private fun DrawScope.drawSceneNightLight(c: Offset, u: Float, actT: Float, wave: Float) {
    val lampOn = ((actT - 0.35f) / 0.35f).coerceIn(0f, 1f)
    if (lampOn > 0.05f) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to EyeGamePalette.Theater.warmLamp.copy(alpha = 0.42f * lampOn),
                1f to Color.Transparent,
                center = Offset(size.width * 0.24f, size.height * 0.45f),
                radius = u * 0.72f,
            ),
            radius = u * 0.72f,
            center = Offset(size.width * 0.24f, size.height * 0.45f),
        )
    }
    // 左侧小台灯
    val lampC = Offset(size.width * 0.24f, size.height * 0.48f)
    val shade = Path().apply {
        moveTo(lampC.x - u * 0.10f, lampC.y + u * 0.06f)
        lineTo(lampC.x - u * 0.06f, lampC.y - u * 0.07f)
        lineTo(lampC.x + u * 0.06f, lampC.y - u * 0.07f)
        lineTo(lampC.x + u * 0.10f, lampC.y + u * 0.06f)
        close()
    }
    drawPath(shade, if (lampOn > 0.2f) EyeGamePalette.Theater.warmLamp else EyeGamePalette.Theater.steam.copy(alpha = 0.35f))
    drawLine(EyeGamePalette.Theater.steam.copy(alpha = 0.55f), Offset(lampC.x, lampC.y + u * 0.06f), Offset(lampC.x, size.height * 0.80f), strokeWidth = 5f)
    // 中右眼睛：开灯前紧绷、开灯后舒缓
    drawDetailedEye(Offset(size.width * 0.62f, size.height * 0.54f), u * 0.24f, lidClose = 0f, dryRatio = (1f - lampOn) * 0.75f)
}

/** 第 10 幕：完整轻闭眨眼操分步演示（半眨眼 vs 完整闭眼挤出润眼油脂）。 */
private fun DrawScope.drawSceneCompleteBlink(c: Offset, u: Float, actT: Float, wave: Float) {
    val isCompletePhase = actT >= 0.46f
    val lid = if (!isCompletePhase) {
        // 前半段：不完全眨眼（只闭合到 50%）
        0.5f * ((sin(wave * 4f * PI.toFloat()) + 1f) * 0.5f)
    } else {
        // 后半段：完整轻闭再舒展睁开
        val cyc = (actT - 0.46f) / 0.54f
        if (cyc in 0.2f..0.7f) sin(((cyc - 0.2f) / 0.5f) * PI.toFloat()) else 0f
    }
    drawDetailedEye(c, u * 0.32f, lidClose = lid, dryRatio = if (isCompletePhase) 0f else 0.55f)
    if (isCompletePhase && lid > 0.65f) {
        // 完整闭眼瞬间沿睑缘泛出金色油脂光点
        for (i in -2..2) {
            drawCircle(EyeGamePalette.Theater.lipidGold, u * 0.025f, Offset(c.x + i * u * 0.10f, c.y + u * 0.04f))
        }
    }
}

/** 第 11 幕：摘下隐形眼镜换框架，氧气泡泡欢快涌入角膜。 */
private fun DrawScope.drawSceneContactLens(c: Offset, u: Float, actT: Float, wave: Float) {
    val removed = ((actT - 0.38f) / 0.32f).coerceIn(0f, 1f)
    drawDetailedEye(c, u * 0.28f, lidClose = 0f, dryRatio = (1f - removed) * 0.5f)
    // 隐形眼镜弧面慢慢向右上方摘离
    if (removed < 0.95f) {
        val lensC = Offset(c.x + removed * u * 0.38f, c.y - removed * u * 0.28f)
        drawCircle(
            EyeGamePalette.Theater.tearHighlight.copy(alpha = (1f - removed) * 0.55f),
            u * 0.16f,
            lensC,
            style = Stroke(3.5f),
        )
    }
    // 摘镜后大量清透氧气泡泡涌向角膜
    val bubbleCount = if (removed > 0.3f) 7 else 2
    for (i in 0 until bubbleCount) {
        val a = i * (2f * PI.toFloat() / bubbleCount) + wave * 2f * PI.toFloat()
        val dist = u * (0.44f - removed * 0.14f)
        drawCircle(
            EyeGamePalette.Theater.tearHighlight.copy(alpha = 0.72f),
            u * 0.026f,
            Offset(c.x + cos(a) * dist, c.y + sin(a) * dist * 0.72f),
            style = Stroke(2.2f),
        )
    }
}

/** 第 12 幕：清泉水杯 + Omega-3 营养能量注入泪膜。 */
private fun DrawScope.drawSceneHydrationNutrition(c: Offset, u: Float, actT: Float, wave: Float) {
    val cupC = Offset(size.width * 0.30f, size.height * 0.58f)
    val cupW = u * 0.26f
    val cupH = u * 0.32f
    val fillH = cupH * (0.30f + actT * 0.58f)
    drawRoundRect(
        EyeGamePalette.Theater.tearCyan.copy(alpha = 0.78f),
        topLeft = Offset(cupC.x - cupW * 0.45f, cupC.y + cupH * 0.46f - fillH),
        size = Size(cupW * 0.90f, fillH),
        cornerRadius = CornerRadius(10f),
    )
    drawRoundRect(
        EyeGamePalette.Theater.tearHighlight.copy(alpha = 0.85f),
        topLeft = Offset(cupC.x - cupW * 0.5f, cupC.y - cupH * 0.5f),
        size = Size(cupW, cupH),
        cornerRadius = CornerRadius(14f),
        style = Stroke(3.5f),
    )
    // 右侧 Omega-3 金色胶囊与水润满格大眼睛
    val rightC = Offset(size.width * 0.68f, size.height * 0.56f)
    drawDetailedEye(rightC, u * 0.22f, lidClose = 0f, dryRatio = 0f)
    drawSparkle(Offset(rightC.x + u * 0.22f, rightC.y - u * 0.22f), u * 0.055f, EyeGamePalette.Theater.lipidGold)
    // 营养能量流点从水杯飞向眼睛
    for (i in 0..3) {
        val ft = (wave + i * 0.25f) % 1f
        val pt = Offset(cupC.x + cupW * 0.5f + ft * (rightC.x - cupC.x - cupW * 0.7f), c.y - sin(ft * PI.toFloat()) * u * 0.16f)
        drawCircle(if (i % 2 == 0) EyeGamePalette.Theater.tearCyan else EyeGamePalette.Theater.lipidGold, u * 0.024f, pt)
    }
}

/** 持续睁眼 20 秒阶段：三阶进化立体水膜护盾定格挑战。 */
private fun DrawScope.drawOpenShieldCinema(progress: Float, elapsedSec: Int, wave: Float, blinkCount: Int) {
    val c = Offset(size.width * 0.5f, size.height * 0.55f)
    val u = min(size.width, size.height)
    val ringR = u * 0.34f
    val pulse = 0.5f + 0.5f * sin(wave * 2f * PI.toFloat())
    val shieldColor = when {
        blinkCount > 0 -> EyeGamePalette.Theater.dryCoral
        elapsedSec >= 14 -> EyeGamePalette.Theater.lipidGold
        elapsedSec >= 7 -> EyeGamePalette.Theater.warmLamp
        else -> EyeGamePalette.Theater.tearCyan
    }
    drawRect(
        brush = Brush.verticalGradient(
            0f to EyeGamePalette.Theater.skyNight,
            1f to EyeGamePalette.Theater.stageDeep,
        ),
    )
    drawCircle(
        brush = Brush.radialGradient(
            0f to shieldColor.copy(alpha = 0.35f + pulse * 0.14f),
            1f to Color.Transparent,
            center = c,
            radius = ringR * 1.85f,
        ),
        radius = ringR * 1.85f,
        center = c,
    )
    // 环绕旋转星尘粒子
    for (i in 0 until 8) {
        val a = wave * 2f * PI.toFloat() + i * (PI.toFloat() / 4f)
        val pt = Offset(c.x + cos(a) * ringR * 1.24f, c.y + sin(a) * ringR * 1.24f)
        drawCircle(shieldColor.copy(alpha = 0.55f + 0.35f * (i % 2)), u * 0.016f, pt)
    }
    drawCircle(shieldColor.copy(alpha = 0.22f), ringR, c, style = Stroke(u * 0.032f))
    drawArc(
        color = shieldColor,
        startAngle = -90f,
        sweepAngle = 360f * progress.coerceIn(0f, 1f),
        useCenter = false,
        topLeft = Offset(c.x - ringR, c.y - ringR),
        size = Size(ringR * 2f, ringR * 2f),
        style = Stroke(width = u * 0.044f, cap = StrokeCap.Round),
    )
    drawSmilingDrop(c, u * (0.15f + pulse * 0.012f))
}

// ============================================================================
// 高精度可复用角色绘制原语
// ============================================================================

/** 绘制精致立体的拟人眼精灵（带放射虹膜纹、星点高光与可调干涩/闭眼状态）。 */
private fun DrawScope.drawDetailedEye(
    center: Offset,
    radius: Float,
    lidClose: Float = 0f,
    gazeX: Float = 0f,
    dryRatio: Float = 0f,
) {
    val open = (1f - lidClose).coerceIn(0f, 1f)
    val w = radius * 1.82f
    val h = radius * 1.12f * open.coerceAtLeast(0.14f)
    if (open < 0.22f) {
        val closed = Path().apply {
            moveTo(center.x - w * 0.48f, center.y)
            quadraticTo(center.x, center.y + radius * 0.38f, center.x + w * 0.48f, center.y)
        }
        drawPath(closed, EyeGamePalette.Theater.warmLamp, style = Stroke(radius * 0.11f, cap = StrokeCap.Round))
        return
    }
    val contour = Path().apply {
        moveTo(center.x - w * 0.5f, center.y)
        quadraticTo(center.x, center.y - h, center.x + w * 0.5f, center.y)
        quadraticTo(center.x, center.y + h, center.x - w * 0.5f, center.y)
        close()
    }
    drawPath(contour, EyeGamePalette.Theater.steam)
    val rimColor = if (dryRatio > 0.4f) EyeGamePalette.Theater.dryCoral else EyeGamePalette.Theater.tearCyan
    drawPath(contour, rimColor.copy(alpha = 0.78f), style = Stroke(width = radius * 0.085f, join = StrokeJoin.Round))

    val irisC = Offset(center.x + gazeX * radius * 0.22f, center.y)
    val irisR = radius * 0.39f * open.coerceAtLeast(0.55f)
    drawCircle(
        brush = Brush.radialGradient(
            0f to EyeGamePalette.Theater.tearCyan,
            0.7f to EyeGamePalette.Theater.tearDeep,
            1f to EyeGamePalette.Theater.skyNight,
            center = irisC,
            radius = irisR,
        ),
        radius = irisR,
        center = irisC,
    )
    // 细腻放射虹膜纹
    for (i in 0 until 12) {
        val a = i * (2f * PI.toFloat() / 12f)
        drawLine(
            EyeGamePalette.Theater.tearHighlight.copy(alpha = 0.22f),
            Offset(irisC.x + cos(a) * irisR * 0.48f, irisC.y + sin(a) * irisR * 0.48f),
            Offset(irisC.x + cos(a) * irisR * 0.90f, irisC.y + sin(a) * irisR * 0.90f),
            strokeWidth = 1.6f,
        )
    }
    drawCircle(EyeGamePalette.Firefly.bodyDark, irisR * 0.48f, irisC)
    drawCircle(EyeGamePalette.Theater.tearHighlight, irisR * 0.24f, Offset(irisC.x - irisR * 0.28f, irisC.y - irisR * 0.26f))
    drawCircle(EyeGamePalette.Theater.tearHighlight.copy(alpha = 0.65f), irisR * 0.10f, Offset(irisC.x + irisR * 0.22f, irisC.y + irisR * 0.20f))
}

/** 微笑水滴精灵「小露」。 */
private fun DrawScope.drawSmilingDrop(center: Offset, radius: Float) {
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
    // 萌萌微笑豆豆眼
    drawCircle(EyeGamePalette.Firefly.bodyDark, radius * 0.11f, Offset(center.x - radius * 0.25f, center.y + radius * 0.26f))
    drawCircle(EyeGamePalette.Firefly.bodyDark, radius * 0.11f, Offset(center.x + radius * 0.25f, center.y + radius * 0.26f))
    val mouth = Path().apply {
        moveTo(center.x - radius * 0.18f, center.y + radius * 0.46f)
        quadraticTo(center.x, center.y + radius * 0.64f, center.x + radius * 0.18f, center.y + radius * 0.46f)
    }
    drawPath(mouth, EyeGamePalette.Firefly.bodyDark, style = Stroke(radius * 0.08f, cap = StrokeCap.Round))
}

/** 流动波浪带（用于三层泪膜解剖）。 */
private fun DrawScope.drawWaveBand(left: Float, right: Float, topY: Float, thickness: Float, phase: Float, color: Color) {
    val steps = 16
    val w = right - left
    val amp = thickness * 0.20f
    val path = Path().apply {
        moveTo(left, topY + thickness)
        for (i in 0..steps) {
            val f = i.toFloat() / steps
            val x = left + w * f
            val y = topY + sin((f * 2.5f + phase) * 2f * PI.toFloat()) * amp
            lineTo(x, y)
        }
        lineTo(right, topY + thickness)
        close()
    }
    drawPath(path, color)
}

/** 四角星光闪烁。 */
private fun DrawScope.drawSparkle(center: Offset, radius: Float, color: Color) {
    val inner = radius * 0.28f
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        quadraticTo(center.x + inner * 0.3f, center.y - inner * 0.3f, center.x + radius, center.y)
        quadraticTo(center.x + inner * 0.3f, center.y + inner * 0.3f, center.x, center.y + radius)
        quadraticTo(center.x - inner * 0.3f, center.y + inner * 0.3f, center.x - radius, center.y)
        quadraticTo(center.x - inner * 0.3f, center.y - inner * 0.3f, center.x, center.y - radius)
        close()
    }
    drawPath(path, color)
}
