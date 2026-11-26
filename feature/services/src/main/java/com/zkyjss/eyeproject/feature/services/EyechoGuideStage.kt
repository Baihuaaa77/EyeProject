package com.zkyjss.eyeproject.feature.services

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeGamePalette
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.theme.rememberReducedMotion
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.drawTumblingE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private fun GuideMotion.isFireflyTheme(): Boolean = this in setOf(
    GuideMotion.FIXATE,
    GuideMotion.FAR,
    GuideMotion.COVER,
    GuideMotion.COVER_SELF,
    GuideMotion.FOLLOW,
    GuideMotion.LIGHTING,
)

/**
 * 保留供兼容的静态人物徽标入口：游戏版统一渲染阳光湖景水滴精灵「小露」与花盆小芽。
 */
@Composable
internal fun EyechoGuidePortrait(pose: GuidePose, description: String, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .clip(EyeShapes.medium)
            .semantics { contentDescription = description },
    ) {
        drawSproutBackdrop(style = SproutBackdropStyle.SCENIC, wave = 0.25f)
        val u = min(size.width, size.height)
        drawPottedSprout(
            center = Offset(size.width * 0.36f, size.height * 0.86f),
            scale = (u / 130f).coerceIn(0.45f, 1.3f),
            growthStage = 2,
            wilted = false,
            wave = 0.25f,
            bounce = 1f,
        )
        drawDropletMascot(
            center = Offset(size.width * 0.72f, size.height * 0.72f),
            radius = u * 0.20f,
            cheer = if (pose == GuidePose.OPEN) 0.2f else 0f,
        )
    }
}

/**
 * 老视戴镜确认页趣味插画：阳光湖景中戴着小圆眼镜的水滴精灵「小露」+ 嫩绿花盆小芽。
 */
@Composable
internal fun SproutGlassesMascotCard(modifier: Modifier = Modifier) {
    val reducedMotion = rememberReducedMotion()
    val infinite = rememberInfiniteTransition(label = "戴镜小露")
    val wave by if (reducedMotion) remember { mutableStateOf(0.25f) } else {
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart),
            label = "戴镜微动",
        )
    }
    Canvas(
        modifier
            .size(width = 216.dp, height = 128.dp)
            .clip(EyeShapes.large)
            .border(1.5.dp, EyeGamePalette.Sprout.dropLight.copy(alpha = 0.65f), EyeShapes.large)
            .semantics { contentDescription = "戴着小圆眼镜的小露与小芽" },
    ) {
        val w = size.width
        val h = size.height
        val u = min(w, h)
        drawSproutBackdrop(style = SproutBackdropStyle.SCENIC, wave = wave)
        drawPottedSprout(
            center = Offset(w * 0.30f, h * 0.88f),
            scale = (u / 145f).coerceIn(0.65f, 1.15f),
            growthStage = 2,
            wilted = false,
            wave = wave,
            bounce = 1f,
        )
        val bob = sin(wave * 2f * PI.toFloat()) * 3.5f
        val mascotC = Offset(w * 0.66f, h * 0.64f + bob)
        val mascotR = u * 0.27f
        drawDropletMascot(center = mascotC, radius = mascotR, cheer = 0.12f)
        // 给小露戴上可爱高对比小圆眼镜（镜框 + 鼻梁 + 镜片反光）
        val lensR = mascotR * 0.23f
        val leftLens = Offset(mascotC.x - mascotR * 0.25f, mascotC.y + mascotR * 0.22f)
        val rightLens = Offset(mascotC.x + mascotR * 0.25f, mascotC.y + mascotR * 0.22f)
        drawCircle(EyeGamePalette.Sprout.dropLight.copy(alpha = 0.30f), lensR, leftLens)
        drawCircle(EyeGamePalette.Sprout.dropLight.copy(alpha = 0.30f), lensR, rightLens)
        drawCircle(EyeGamePalette.Sprout.arrowDark, lensR, leftLens, style = Stroke(mascotR * 0.08f))
        drawCircle(EyeGamePalette.Sprout.arrowDark, lensR, rightLens, style = Stroke(mascotR * 0.08f))
        drawLine(
            EyeGamePalette.Sprout.arrowDark,
            Offset(leftLens.x + lensR * 0.9f, leftLens.y),
            Offset(rightLens.x - lensR * 0.9f, rightLens.y),
            strokeWidth = mascotR * 0.075f,
            cap = StrokeCap.Round,
        )
        // 镜片星光闪烁
        val glint = 0.55f + 0.45f * sin(wave * 2f * PI.toFloat())
        drawFourPointStar(
            Offset(rightLens.x + lensR * 0.55f, rightLens.y - lensR * 0.55f),
            radius = mascotR * 0.16f * glint,
            color = EyeGamePalette.Sprout.daisyCenter,
        )
    }
}

/**
 * 斜视看远舞台顶部微动画徽标：静谧月夜中远山暖星 + 轻舞萤火虫（保持暗底，不增加角膜大面积反光）。
 */
@Composable
internal fun FarStarFireflyBadge(modifier: Modifier = Modifier) {
    val reducedMotion = rememberReducedMotion()
    val infinite = rememberInfiniteTransition(label = "看远暖星")
    val pulse by if (reducedMotion) remember { mutableStateOf(0.5f) } else {
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
            label = "远星呼吸",
        )
    }
    Canvas(
        modifier
            .size(width = 104.dp, height = 64.dp)
            .semantics { contentDescription = "越过手机望向远处暖星" },
    ) {
        val w = size.width
        val h = size.height
        val glow = 0.72f + 0.28f * sin(pulse * 2f * PI.toFloat())
        // 远处暖星与柔光
        val starC = Offset(w * 0.5f, h * 0.30f)
        drawCircle(
            brush = Brush.radialGradient(
                0f to EyeGamePalette.Firefly.glowCore.copy(alpha = 0.75f * glow),
                0.5f to EyeGamePalette.Firefly.glowMid.copy(alpha = 0.25f * glow),
                1f to Color.Transparent,
                center = starC,
                radius = h * 0.42f,
            ),
            radius = h * 0.42f,
            center = starC,
        )
        drawFourPointStar(starC, radius = h * 0.19f * glow, color = EyeGamePalette.Firefly.glowCore)
        // 两侧点缀微弱伴星
        drawCircle(EyeGamePalette.Firefly.moon.copy(alpha = 0.65f), 2.2.dp.toPx(), Offset(w * 0.22f, h * 0.48f))
        drawCircle(EyeGamePalette.Firefly.moon.copy(alpha = 0.55f), 1.8.dp.toPx(), Offset(w * 0.78f, h * 0.44f))
        // 远山剪影线
        val ridge = Path().apply {
            moveTo(w * 0.10f, h * 0.88f)
            quadraticTo(w * 0.34f, h * 0.58f, w * 0.52f, h * 0.76f)
            quadraticTo(w * 0.72f, h * 0.54f, w * 0.90f, h * 0.88f)
        }
        drawPath(ridge, EyeGamePalette.Firefly.creekHighlight.copy(alpha = 0.45f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/**
 * 斜视自测 33 cm 月夜森林萤火虫准备与校准卡（与干眼/老视的 [SproutSetupCard] 形成双主题呼应）。
 */
@Composable
internal fun FireflySetupCard(
    title: String,
    subtitle: String,
    distanceCm: Float,
    targetCm: Float = 33f,
    toleranceCm: Float = 5f,
    faceDetected: Boolean,
    modifier: Modifier = Modifier,
) {
    val typography = LocalEyeTypography.current
    val reducedMotion = rememberReducedMotion()
    val infinite = rememberInfiniteTransition(label = "准备萤火虫")
    val wave by if (reducedMotion) remember { mutableStateOf(0.35f) } else {
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
            label = "萤火虫漂浮",
        )
    }
    val inRange = faceDetected && distanceCm > 0f && abs(distanceCm - targetCm) <= toleranceCm
    val distanceLabel = when {
        !faceDetected || distanceCm <= 0f -> "当前距离：检测中…"
        inRange -> "当前距离：${distanceCm.toInt()} cm · 已稳定"
        distanceCm < targetCm - toleranceCm -> "当前距离：${distanceCm.toInt()} cm · 请微调远一点至 ${targetCm.toInt()} cm"
        else -> "当前距离：${distanceCm.toInt()} cm · 请微调近一点至 ${targetCm.toInt()} cm"
    }
    val sliderRatio = if (faceDetected && distanceCm > 0f) {
        ((distanceCm - 15f) / 36f).coerceIn(0.05f, 0.95f)
    } else 0.5f

    Box(
        modifier
            .fillMaxWidth()
            .clip(EyeShapes.hero)
            .border(1.dp, EyeGamePalette.Firefly.moonHalo.copy(alpha = 0.45f), EyeShapes.hero),
    ) {
        Canvas(
            Modifier
                .matchParentSize()
                .semantics { contentDescription = "月夜萤火虫眼位自测准备与校准卡片" },
        ) {
            drawMoonlitForestBackdrop(phase = wave)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .clip(EyeShapes.chip)
                    .background(EyeGamePalette.Firefly.skyTop.copy(alpha = 0.78f))
                    .border(1.dp, EyeGamePalette.Firefly.moonHalo.copy(alpha = 0.32f), EyeShapes.chip)
                    .padding(horizontal = 14.dp, vertical = 4.dp),
            ) {
                EyeText(
                    "←  请保持 ${targetCm.toInt()}cm  →",
                    style = typography.caption,
                    color = EyeGamePalette.Firefly.glowCore,
                )
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(EyeShapes.large)
                    .background(EyeGamePalette.Firefly.skyTop.copy(alpha = 0.76f))
                    .border(1.dp, EyeGamePalette.Firefly.moonHalo.copy(alpha = 0.26f), EyeShapes.large)
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EyeText(title, style = typography.title, color = EyeGamePalette.Firefly.glowCore, textAlign = TextAlign.Center)
                EyeText(subtitle, style = typography.caption, color = EyeGamePalette.Firefly.moon, textAlign = TextAlign.Center)

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(142.dp)
                        .clip(EyeShapes.medium)
                        .border(1.5.dp, EyeGamePalette.Firefly.moonHalo.copy(alpha = 0.40f), EyeShapes.medium),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawMoonlitForestBackdrop(phase = wave)
                        val w = size.width
                        val h = size.height
                        val flyX = w * 0.50f + sin(wave * 2f * PI.toFloat()) * w * 0.08f
                        val flyY = h * 0.34f + cos(wave * 4f * PI.toFloat()) * h * 0.04f
                        drawMoonlitFirefly(
                            center = Offset(flyX, flyY),
                            radius = min(w, h) * 0.13f,
                            wingPhase = (wave * 5f) % 1f,
                            glowIntensity = 1f,
                            tiltDeg = -14f + sin(wave * 2f * PI.toFloat()) * 10f,
                        )
                        drawCuteEyesPair(
                            center = Offset(w * 0.50f, h * 0.74f),
                            eyeRadius = min(w, h) * 0.115f,
                            lookTarget = Offset(flyX, flyY),
                            blinking = false,
                            cover = null,
                            darkTheme = true,
                        )
                    }
                }

                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EyeText("标准测试距离：${targetCm.toInt()}cm", style = typography.chip, color = EyeGamePalette.Firefly.glowCore)
                        EyeText("正对屏幕 · 露出双眼", style = typography.caption, color = EyeGamePalette.Firefly.moon)
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(EyeGamePalette.Firefly.moon.copy(alpha = 0.22f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(sliderRatio)
                                .height(8.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            EyeGamePalette.Firefly.glowOuter,
                                            if (inRange) EyeGamePalette.Sprout.badgeStable else EyeGamePalette.Firefly.glowCore,
                                        ),
                                    ),
                                ),
                        )
                    }
                    EyeText(
                        distanceLabel,
                        style = typography.bodySecondary,
                        color = if (inRange) EyeGamePalette.Sprout.leafBright else EyeGamePalette.Firefly.moon,
                    )
                }
            }
        }
    }
}

/**
 * 游戏版全套方法演示趣味动画舞台：
 * - 干眼 / 老视 / 通用场景：阳光蓝天湖景鲜花草坪 + 陶土盆小芽 + 水滴精灵「小露」动态演示；
 * - 斜视场景：深蓝月夜森林 + 弯月 + 月光小溪 + 发光萤火虫 + 灵动双眼动态演示。
 * 由调用方传入 [phase] 驱动，不启动内部循环协程，完全兼容系统减弱动态效果与 Compose 单测。
 */
@Composable
internal fun GuideStage(scene: GuideScene, phase: () -> Float, description: String, modifier: Modifier = Modifier) {
    val type = LocalEyeTypography.current
    val pose by remember(scene, phase) { derivedStateOf { guidePoseAt(scene.motion, phase()) } }
    val caption by remember(scene, phase) { derivedStateOf { guideStageBadge(scene.motion, phase(), pose) } }
    val isFirefly = scene.motion.isFireflyTheme()
    val borderColor = if (isFirefly) {
        EyeGamePalette.Firefly.moonHalo.copy(alpha = 0.40f)
    } else {
        EyeGamePalette.Sprout.dropLight.copy(alpha = 0.50f)
    }
    val pillBg = if (isFirefly) {
        EyeGamePalette.Firefly.skyTop.copy(alpha = 0.80f)
    } else {
        EyeGamePalette.Sprout.arrowDark.copy(alpha = 0.78f)
    }
    val pillText = if (isFirefly) EyeGamePalette.Firefly.glowCore else EyeGamePalette.Sprout.cloudWhite

    Box(
        modifier
            .clip(EyeShapes.large)
            .border(1.dp, borderColor, EyeShapes.large),
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = "趣味动画演示：$caption。$description" },
        ) {
            val p = phase().coerceIn(0f, 0.9999f)
            val currentPose = guidePoseAt(scene.motion, p)
            drawGuideAnimatedScene(scene.motion, p, currentPose)
        }
        // 底部统一高对比动作胶囊（文字随步骤更新，不遮挡核心角色与视标）
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 10.dp, vertical = 7.dp)
                .clip(EyeShapes.chip)
                .background(pillBg)
                .border(1.dp, pillText.copy(alpha = 0.24f), EyeShapes.chip)
                .padding(horizontal = 12.dp, vertical = 3.dp),
        ) {
            EyeText(
                caption,
                style = type.chip,
                color = pillText,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 底部胶囊提示文案（融合姿态与步骤标签，确保每个演示动作一目了然）。 */
private fun guideStageBadge(motion: GuideMotion, phase: Float, pose: GuidePose): String = when (motion) {
    GuideMotion.REST -> "闭眼休息"
    GuideMotion.BLINK -> if (pose == GuidePose.BLINK) "自然眨眼 · 润湿泪膜" else "放松注视 · 自然眨眼"
    GuideMotion.OPEN -> if (pose == GuidePose.BLINK) "不舒服就眨眼" else "保持睁眼 · 稳住水膜"
    GuideMotion.LIGHTING -> "固定支架 · 正对双眼"
    GuideMotion.COVER, GuideMotion.COVER_SELF -> guidePoseLabel(pose)
    GuideMotion.NEAR -> "${guidePoseLabel(pose)} · 看 E 浇水"
    GuideMotion.MOVE -> if (phase < 0.68f) "${guidePoseLabel(pose)} · 67→25 cm" else "持续模糊 · 点击确认"
    else -> guideDiagramLabel(motion, phase).ifEmpty { guidePoseLabel(pose) }
}

/** 文字按步骤更新，连续移动只在画布绘制阶段读取，避免整卡逐帧重组。 */
internal fun guideDiagramLabel(motion: GuideMotion, phase: Float): String = when (motion) {
    GuideMotion.POSTURE -> "调整到提示距离"
    GuideMotion.ACUITY -> if ((phase * 5).toInt() == 2) "超时后视标变大" else "2 秒内点选方向"
    GuideMotion.FVA -> if (phase < 0.68f) "自然眨眼 · 辨认 E" else "保持睁眼 · 辨认 E"
    GuideMotion.NEAR -> "看 E · 点选方向"
    GuideMotion.MOVE -> if (phase < 0.68f) "慢慢移近" else "持续模糊时确认"
    GuideMotion.CURVE -> "${PresbyopiaProtocol.CURVE_DISTANCES[when { phase < 0.34f -> 0; phase < 0.68f -> 1; else -> 2 }].toInt()} cm"
    GuideMotion.SWITCH -> if (phase < 0.3f || phase >= 0.7f) "远处停稳作答" else "近处停稳作答"
    GuideMotion.FIXATE -> "看萤火虫 · 头不动"
    GuideMotion.FOLLOW -> "跟随萤火虫 · 头不动"
    GuideMotion.FAR -> "越过手机看 6 米外"
    else -> ""
}

// ============================================================================
// 16 种趣味情景演示画布渲染
// ============================================================================

private fun DrawScope.drawGuideAnimatedScene(motion: GuideMotion, p: Float, pose: GuidePose) {
    when (motion) {
        GuideMotion.ACUITY, GuideMotion.FVA, GuideMotion.NEAR, GuideMotion.CURVE, GuideMotion.SWITCH ->
            drawSproutWateringGuide(motion, p, pose)
        GuideMotion.MOVE -> drawSproutMoveGuide(p, pose)
        GuideMotion.BLINK, GuideMotion.OPEN -> drawSproutBlinkOpenGuide(motion, p, pose)
        GuideMotion.REST -> drawRestGuide(p)
        GuideMotion.POSTURE -> drawPostureGuide(p)
        GuideMotion.FIXATE, GuideMotion.LIGHTING -> drawFireflyFixateGuide(motion, p)
        GuideMotion.FAR -> drawFireflyFarGuide(p)
        GuideMotion.COVER, GuideMotion.COVER_SELF -> drawFireflyCoverGuide(p, pose)
        GuideMotion.FOLLOW -> drawFireflyFollowGuide(p)
    }
}

/** 1. 看 E 浇水类演示（ACUITY / FVA / NEAR / CURVE / SWITCH）：阳光湖景 + 旋转 E 与四周实心白大箭头 + 小露抛物线浇水 + 花盆小芽弹跳。 */
private fun DrawScope.drawSproutWateringGuide(motion: GuideMotion, p: Float, pose: GuidePose) {
    val w = size.width
    val h = size.height
    val u = min(w, h)
    drawSproutBackdrop(style = SproutBackdropStyle.SCENIC, wave = p)

    val step = (p * 5f).toInt().coerceIn(0, 4)
    val localP = (p * 5f) - step
    val direction = listOf(0, 3, 2, 1, 0)[step]
    val isTimeoutEvap = motion == GuideMotion.ACUITY && step == 2
    val watering = !isTimeoutEvap && localP in 0.28f..0.95f
    val wt = if (watering) ((localP - 0.28f) / 0.67f).coerceIn(0f, 1f) else 1f
    val growthStage = when {
        isTimeoutEvap -> 1
        step >= 4 -> 4
        step >= 3 -> 3
        step >= 1 -> 2
        else -> 1
    }
    val bounce = if (wt in 0.45f..0.95f) 1f + 0.14f * sin(((wt - 0.45f) / 0.50f) * PI.toFloat()) else 1f

    // 左侧花盆小芽
    val sproutC = Offset(w * 0.20f, h * 0.80f)
    val sproutScale = (u / 230f).coerceIn(0.65f, 1.05f)
    drawPottedSprout(
        center = sproutC,
        scale = sproutScale,
        growthStage = growthStage,
        wilted = isTimeoutEvap,
        wave = p,
        bounce = bounce,
    )

    // 右侧小露精灵（答对时跃起挥水）
    val cheer = if (wt < 0.65f) sin((wt / 0.65f) * PI.toFloat()) else 0f
    val mascotC = Offset(w * 0.82f - cheer * u * 0.05f, h * 0.73f - cheer * u * 0.09f + sin(p * 2f * PI.toFloat()) * 3f)
    drawDropletMascot(center = mascotC, radius = u * 0.135f, cheer = cheer)

    // 中央旋转 E 视标 + 四周实心白大箭头（开口方向脉冲高亮）
    val dialC = Offset(w * 0.50f, h * 0.41f)
    val eEdge = u * when {
        motion == GuideMotion.ACUITY && step == 1 -> 0.14f
        motion == GuideMotion.ACUITY && step == 2 -> 0.23f
        motion == GuideMotion.CURVE -> if (p < 0.34f) 0.22f else if (p < 0.68f) 0.18f else 0.15f
        motion == GuideMotion.SWITCH -> if (p < 0.3f || p >= 0.7f) 0.16f else 0.21f
        else -> 0.19f
    }
    // 柔光底框保证 E 在晴空下清晰锐利
    drawRoundRect(
        color = EyeGamePalette.Sprout.arrowDark.copy(alpha = 0.20f),
        topLeft = Offset(dialC.x - u * 0.16f, dialC.y - u * 0.16f),
        size = Size(u * 0.32f, u * 0.32f),
        cornerRadius = CornerRadius(u * 0.05f),
    )
    drawTumblingE(EyeGamePalette.Sprout.cloudWhite, dialC, eEdge, direction)

    val arrowDist = u * 0.25f
    val arrowSize = u * 0.14f
    val offsets = listOf(Offset(1f, 0f), Offset(0f, 1f), Offset(-1f, 0f), Offset(0f, -1f))
    offsets.forEachIndexed { idx, dirVec ->
        val active = !isTimeoutEvap && idx == direction && localP >= 0.18f
        val pulseScale = if (active) 1f + 0.16f * sin(localP * 2f * PI.toFloat()).coerceAtLeast(0f) else 1f
        drawSolidGuideArrow(
            center = Offset(dialC.x + dirVec.x * arrowDist, dialC.y + dirVec.y * arrowDist),
            size = arrowSize * pulseScale,
            direction = idx,
            highlighted = active,
        )
    }

    // 抛物线浇水弧线 + 水花 + 金星 / 或超时白雾蒸发
    val targetSoil = Offset(sproutC.x, sproutC.y - 24f * sproutScale)
    if (watering) {
        val startPt = Offset(mascotC.x - u * 0.09f, mascotC.y - u * 0.08f)
        val ctrlPt = Offset((startPt.x + targetSoil.x) * 0.5f, h * 0.14f)
        for (i in 0 until 4) {
            val lt = ((wt - i * 0.07f) / 0.52f).coerceIn(0f, 1f)
            if (lt in 0.02f..0.98f) {
                val inv = 1f - lt
                val px = inv * inv * startPt.x + 2f * inv * lt * ctrlPt.x + lt * lt * targetSoil.x
                val py = inv * inv * startPt.y + 2f * inv * lt * ctrlPt.y + lt * lt * targetSoil.y
                drawGlowingWaterDrop(Offset(px, py), u * (0.042f - i * 0.004f))
            }
        }
        if (wt in 0.42f..0.98f) {
            val sp = ((wt - 0.42f) / 0.56f).coerceIn(0f, 1f)
            val a = (1f - sp).coerceIn(0f, 1f)
            for (k in 0 until 6) {
                val ang = -PI.toFloat() * (0.14f + k * 0.14f)
                val dist = u * (0.04f + sp * 0.16f)
                drawCircle(
                    EyeGamePalette.Sprout.dropLight.copy(alpha = a),
                    radius = u * 0.018f * (1f - sp * 0.4f),
                    center = Offset(targetSoil.x + cos(ang) * dist, targetSoil.y + sin(ang) * dist),
                )
            }
            drawFourPointStar(
                Offset(targetSoil.x + u * 0.11f, targetSoil.y - u * 0.18f - sp * u * 0.06f),
                radius = u * 0.048f * sin(sp * PI.toFloat()),
                color = EyeGamePalette.Sprout.daisyCenter,
            )
        }
    } else if (isTimeoutEvap) {
        // 超时水滴蒸发白雾
        val a = (1f - localP).coerceIn(0.25f, 1f)
        for (i in 0..2) {
            val sx = targetSoil.x + (i - 1) * u * 0.06f
            val sy = targetSoil.y - u * 0.14f - localP * u * 0.14f
            val sway = sin((localP * 4f + i) * PI.toFloat()) * u * 0.025f
            val vapor = Path().apply {
                moveTo(sx, sy + u * 0.06f)
                quadraticTo(sx + sway, sy, sx - sway * 0.6f, sy - u * 0.08f)
            }
            drawPath(vapor, EyeGamePalette.Sprout.cloudWhite.copy(alpha = a * 0.85f), style = Stroke(u * 0.018f, cap = StrokeCap.Round))
        }
    }

    // 专属场景角标增强教学语义
    when (motion) {
        GuideMotion.NEAR -> {
            // 左上角：萌系双眼 + 温柔云掌遮眼示意（严格按受试者自身左右）
            drawRoundRect(
                color = EyeGamePalette.Sprout.arrowDark.copy(alpha = 0.56f),
                topLeft = Offset(w * 0.04f, h * 0.07f),
                size = Size(w * 0.25f, h * 0.25f),
                cornerRadius = CornerRadius(u * 0.06f),
            )
            drawCuteEyesPair(
                center = Offset(w * 0.165f, h * 0.195f),
                eyeRadius = u * 0.060f,
                lookTarget = dialC,
                blinking = false,
                cover = when (pose) {
                    GuidePose.LEFT -> VisionEye.LEFT
                    GuidePose.RIGHT -> VisionEye.RIGHT
                    else -> null
                },
                darkTheme = true,
            )
        }
        GuideMotion.FVA -> {
            // 左上角：自然眨眼 / 持续睁眼水膜护盾
            val keepOpen = p >= 0.68f
            drawRoundRect(
                color = (if (keepOpen) EyeGamePalette.Sprout.dropDeep else EyeGamePalette.Sprout.arrowDark).copy(alpha = 0.62f),
                topLeft = Offset(w * 0.04f, h * 0.07f),
                size = Size(w * 0.25f, h * 0.25f),
                cornerRadius = CornerRadius(u * 0.06f),
            )
            drawCuteEyesPair(
                center = Offset(w * 0.165f, h * 0.195f),
                eyeRadius = u * 0.058f,
                lookTarget = dialC,
                blinking = pose == GuidePose.BLINK,
                cover = null,
                darkTheme = true,
                tearShield = keepOpen,
            )
        }
        GuideMotion.CURVE -> {
            // 顶部三段距离切换进度点（67 / 50 / 33 cm）
            val activeIdx = when { p < 0.34f -> 0; p < 0.68f -> 1; else -> 2 }
            val barY = h * 0.11f
            val x0 = w * 0.14f
            val x2 = w * 0.34f
            drawLine(EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.5f), Offset(x0, barY), Offset(x2, barY), u * 0.015f, StrokeCap.Round)
            for (i in 0..2) {
                val cx = x0 + (x2 - x0) * (i / 2f)
                val isCur = i == activeIdx
                drawCircle(
                    if (isCur) EyeGamePalette.Sprout.leafBright else EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.65f),
                    radius = if (isCur) u * 0.036f else u * 0.022f,
                    center = Offset(cx, barY),
                )
            }
        }
        GuideMotion.SWITCH -> {
            // 右上角：远 ⇄ 近 穿梭标尺
            val near = p in 0.30f..0.70f
            val bx0 = w * 0.69f
            val bx1 = w * 0.93f
            val by = h * 0.14f
            drawLine(EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.65f), Offset(bx0, by), Offset(bx1, by), u * 0.016f, StrokeCap.Round)
            val ballX = if (near) bx1 else bx0
            drawCircle(EyeGamePalette.Sprout.daisyCenter, u * 0.038f, Offset(ballX, by))
            drawCircle(EyeGamePalette.Sprout.cloudWhite, u * 0.020f, Offset(if (near) bx0 else bx1, by))
        }
        else -> Unit
    }
}

/** 2. 第二关「67cm 慢慢靠近」移近法演示（高度还原甲方 PPT 图 4）：湖心小芽 + 顶部绿条推进 + 四角框 E 放大柔和虚化 + 送水微珠。 */
private fun DrawScope.drawSproutMoveGuide(p: Float, pose: GuidePose) {
    val w = size.width
    val h = size.height
    val u = min(w, h)
    drawSproutBackdrop(style = SproutBackdropStyle.SCENIC, wave = p)

    val approach = when {
        p < 0.15f -> 0f
        p < 0.68f -> (p - 0.15f) / 0.53f
        p < 0.85f -> 1f
        else -> (1f - p) / 0.15f
    }.coerceIn(0f, 1f)
    val blurred = p in 0.64f..0.88f

    // 顶部推进滑轨
    val barL = w * 0.14f
    val barW = w * 0.72f
    val barY = h * 0.11f
    drawRoundRect(
        color = EyeGamePalette.Sprout.arrowDark.copy(alpha = 0.55f),
        topLeft = Offset(barL - 6f, barY - u * 0.032f),
        size = Size(barW + 12f, u * 0.064f),
        cornerRadius = CornerRadius(u * 0.032f),
    )
    drawRoundRect(
        color = EyeGamePalette.Sprout.leafBright,
        topLeft = Offset(barL, barY - u * 0.016f),
        size = Size(barW * (0.08f + approach * 0.92f), u * 0.032f),
        cornerRadius = CornerRadius(u * 0.016f),
    )

    // 湖心岛小芽 + 飘向小芽的送水微珠
    val sproutC = Offset(w * 0.50f, h * 0.77f)
    drawLakeIslandSprout(sproutC, (u / 230f).coerceIn(0.65f, 1.0f), growthStage = 2, wilted = false, wave = p, bounce = 1f)
    for (i in 0..2) {
        val t = (p * 2f + i * 0.33f) % 1f
        val dx = (i - 1) * u * 0.14f * (1f - t)
        val dy = -u * 0.20f * (1f - t) - u * 0.05f
        drawGlowingWaterDrop(Offset(sproutC.x + dx, sproutC.y + dy), u * 0.028f * (1f - t * 0.3f))
    }

    // 中央四角对焦框 + 随移近放大的 E 字（持续模糊阶段叠柔和光晕脉冲）
    val boxC = Offset(w * 0.50f, h * 0.42f)
    val boxHalf = u * (0.17f + approach * 0.06f)
    val cLen = u * 0.075f
    val frameCol = if (blurred) EyeGamePalette.Sprout.daisyCenter else EyeGamePalette.Sprout.cloudWhite
    val st = u * 0.016f
    drawLine(frameCol, Offset(boxC.x - boxHalf, boxC.y - boxHalf), Offset(boxC.x - boxHalf + cLen, boxC.y - boxHalf), st, StrokeCap.Round)
    drawLine(frameCol, Offset(boxC.x - boxHalf, boxC.y - boxHalf), Offset(boxC.x - boxHalf, boxC.y - boxHalf + cLen), st, StrokeCap.Round)
    drawLine(frameCol, Offset(boxC.x + boxHalf, boxC.y - boxHalf), Offset(boxC.x + boxHalf - cLen, boxC.y - boxHalf), st, StrokeCap.Round)
    drawLine(frameCol, Offset(boxC.x + boxHalf, boxC.y - boxHalf), Offset(boxC.x + boxHalf, boxC.y - boxHalf + cLen), st, StrokeCap.Round)
    drawLine(frameCol, Offset(boxC.x - boxHalf, boxC.y + boxHalf), Offset(boxC.x - boxHalf + cLen, boxC.y + boxHalf), st, StrokeCap.Round)
    drawLine(frameCol, Offset(boxC.x - boxHalf, boxC.y + boxHalf), Offset(boxC.x - boxHalf, boxC.y + boxHalf - cLen), st, StrokeCap.Round)
    drawLine(frameCol, Offset(boxC.x + boxHalf, boxC.y + boxHalf), Offset(boxC.x + boxHalf - cLen, boxC.y + boxHalf), st, StrokeCap.Round)
    drawLine(frameCol, Offset(boxC.x + boxHalf, boxC.y + boxHalf), Offset(boxC.x + boxHalf, boxC.y + boxHalf - cLen), st, StrokeCap.Round)

    val eEdge = u * (0.16f + approach * 0.11f)
    if (blurred) {
        drawTumblingE(EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.28f), Offset(boxC.x - 3f, boxC.y), eEdge, 0)
        drawTumblingE(EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.28f), Offset(boxC.x + 3f, boxC.y), eEdge, 0)
    }
    drawTumblingE(EyeGamePalette.Sprout.cloudWhite.copy(alpha = if (blurred) 0.72f else 1f), boxC, eEdge, 0)

    // 左侧小眼徽标示意保持遮左眼，右侧小手机移近示意
    drawRoundRect(
        color = EyeGamePalette.Sprout.arrowDark.copy(alpha = 0.54f),
        topLeft = Offset(w * 0.05f, h * 0.30f),
        size = Size(w * 0.22f, h * 0.24f),
        cornerRadius = CornerRadius(u * 0.05f),
    )
    drawCuteEyesPair(
        center = Offset(w * 0.16f, h * 0.42f),
        eyeRadius = u * 0.055f,
        lookTarget = boxC,
        blinking = false,
        cover = VisionEye.LEFT,
        darkTheme = true,
    )
    // 右侧小露随移近凑近加油
    val mascotX = w * (0.86f - approach * 0.08f)
    drawDropletMascot(Offset(mascotX, h * 0.70f), u * 0.125f, cheer = if (blurred) 0.45f else 0.08f)
}

/** 3. 干眼自然眨眼 & 持续睁眼演示（BLINK / OPEN）：阳光湖景 + 灵动大双眼 + 泪膜波纹/护盾 + 小露与小芽。 */
private fun DrawScope.drawSproutBlinkOpenGuide(motion: GuideMotion, p: Float, pose: GuidePose) {
    val w = size.width
    val h = size.height
    val u = min(w, h)
    drawSproutBackdrop(style = SproutBackdropStyle.SCENIC, wave = p)

    val blinking = pose == GuidePose.BLINK
    val isOpenHold = motion == GuideMotion.OPEN && !blinking
    val eyesC = Offset(w * 0.50f, h * 0.37f)

    // 双眼背后柔和浅色玻璃光盘
    drawRoundRect(
        color = EyeGamePalette.Sprout.arrowDark.copy(alpha = 0.42f),
        topLeft = Offset(eyesC.x - u * 0.42f, eyesC.y - u * 0.21f),
        size = Size(u * 0.84f, u * 0.42f),
        cornerRadius = CornerRadius(u * 0.21f),
    )
    drawCuteEyesPair(
        center = eyesC,
        eyeRadius = u * 0.125f,
        lookTarget = Offset(eyesC.x, eyesC.y + u * 0.02f),
        blinking = blinking,
        cover = null,
        darkTheme = true,
        tearShield = isOpenHold,
    )

    // 眨眼时刷亮一道晶莹青蓝泪膜波纹
    if (blinking && motion == GuideMotion.BLINK) {
        drawOval(
            EyeGamePalette.Sprout.dropLight.copy(alpha = 0.90f),
            topLeft = Offset(eyesC.x - u * 0.46f, eyesC.y - u * 0.24f),
            size = Size(u * 0.92f, u * 0.48f),
            style = Stroke(u * 0.020f),
        )
    }

    // 下方左右陪伴的花盆小芽与小露
    drawPottedSprout(
        center = Offset(w * 0.24f, h * 0.81f),
        scale = (u / 235f).coerceIn(0.65f, 1.0f),
        growthStage = if (isOpenHold) 3 else 2,
        wilted = false,
        wave = p,
        bounce = if (blinking) 1.09f else 1f,
    )
    val bob = sin(p * 2f * PI.toFloat()) * 4f
    drawDropletMascot(
        center = Offset(w * 0.77f, h * 0.74f + bob),
        radius = u * 0.13f,
        cheer = if (blinking) 0.35f else 0.1f,
    )
    // 飘浮护眼小水滴
    val dropY = h * 0.56f + sin(p * 4f * PI.toFloat()) * u * 0.03f
    drawGlowingWaterDrop(Offset(w * 0.50f, dropY), u * 0.042f)
}

/** 4. 闭眼休息演示（REST）：舒缓暮蓝湖夜 + 温柔微笑闭眼 + 暖光漂浮粒子 + 休憩小芽。 */
private fun DrawScope.drawRestGuide(p: Float) {
    val w = size.width
    val h = size.height
    val u = min(w, h)
    drawMoonlitForestBackdrop(phase = p)
    val eyesC = Offset(w * 0.50f, h * 0.40f)
    // 柔和护眼暖雾光环
    val breath = 0.85f + 0.15f * sin(p * 2f * PI.toFloat())
    drawCircle(
        brush = Brush.radialGradient(
            0f to EyeGamePalette.Firefly.glowMid.copy(alpha = 0.28f * breath),
            0.65f to EyeGamePalette.Firefly.glowOuter.copy(alpha = 0.10f * breath),
            1f to Color.Transparent,
            center = eyesC,
            radius = u * 0.46f * breath,
        ),
        radius = u * 0.46f * breath,
        center = eyesC,
    )
    drawCuteEyesPair(
        center = eyesC,
        eyeRadius = u * 0.135f,
        lookTarget = eyesC,
        blinking = true,
        cover = null,
        darkTheme = true,
    )
    // 漂浮舒缓微星
    for (i in 0..4) {
        val sx = w * (0.18f + i * 0.16f)
        val sy = h * (0.26f + 0.08f * sin((p * 2f + i * 0.5f) * PI.toFloat()))
        drawFourPointStar(Offset(sx, sy), u * 0.028f * breath, EyeGamePalette.Firefly.glowCore.copy(alpha = 0.72f))
    }
    drawPottedSprout(
        center = Offset(w * 0.24f, h * 0.81f),
        scale = (u / 250f).coerceIn(0.6f, 0.92f),
        growthStage = 2,
        wilted = false,
        wave = p * 0.5f,
        bounce = 1f,
    )
}

/** 5. 通用准备姿态演示（POSTURE）：阳光湖景 + 40 cm 标尺微调居中亮绿勾 + 小芽与小露陪伴。 */
private fun DrawScope.drawPostureGuide(p: Float) {
    val w = size.width
    val h = size.height
    val u = min(w, h)
    drawSproutBackdrop(style = SproutBackdropStyle.SCENIC, wave = p)

    val aligned = p >= 0.48f
    val drift = if (aligned) 0f else sin((1f - p / 0.48f) * PI.toFloat()) * u * 0.14f
    val centerC = Offset(w * 0.50f + drift, h * 0.39f)

    // 居中准星与到位绿环
    val ringR = u * 0.24f
    val ringCol = if (aligned) EyeGamePalette.Sprout.badgeStable else EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.75f)
    drawCircle(EyeGamePalette.Sprout.arrowDark.copy(alpha = 0.40f), ringR, Offset(w * 0.50f, h * 0.39f))
    drawCircle(ringCol, ringR, Offset(w * 0.50f, h * 0.39f), style = Stroke(u * 0.022f))

    drawCuteEyesPair(
        center = centerC,
        eyeRadius = u * 0.095f,
        lookTarget = Offset(w * 0.50f, h * 0.39f),
        blinking = false,
        cover = null,
        darkTheme = true,
    )
    if (aligned) {
        // 右上角绿色对勾徽章
        val badgeC = Offset(w * 0.50f + ringR * 0.76f, h * 0.39f - ringR * 0.76f)
        drawCircle(EyeGamePalette.Sprout.badgeStable, u * 0.078f, badgeC)
        val check = Path().apply {
            moveTo(badgeC.x - u * 0.035f, badgeC.y)
            lineTo(badgeC.x - u * 0.008f, badgeC.y + u * 0.028f)
            lineTo(badgeC.x + u * 0.040f, badgeC.y - u * 0.028f)
        }
        drawPath(check, EyeGamePalette.Sprout.cloudWhite, style = Stroke(u * 0.020f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    drawPottedSprout(Offset(w * 0.21f, h * 0.81f), (u / 235f).coerceIn(0.62f, 1.0f), growthStage = 2, wilted = false, wave = p)
    drawDropletMascot(Offset(w * 0.80f, h * 0.74f + sin(p * 2f * PI.toFloat()) * 3f), u * 0.13f, cheer = if (aligned) 0.3f else 0f)
}

/** 6. 斜视近距凝望 / 支架映光演示（FIXATE / LIGHTING）：月夜森林 + 发光萤火虫悬停 + 灵动双眼注视。 */
private fun DrawScope.drawFireflyFixateGuide(motion: GuideMotion, p: Float) {
    val w = size.width
    val h = size.height
    val u = min(w, h)
    drawMoonlitForestBackdrop(phase = p)

    val flyC = Offset(w * 0.50f, h * 0.28f + sin(p * 2f * PI.toFloat()) * u * 0.025f)
    drawMoonlitFirefly(
        center = flyC,
        radius = u * 0.125f,
        wingPhase = (p * 6f) % 1f,
        glowIntensity = 0.85f + 0.15f * sin(p * 4f * PI.toFloat()),
        tiltDeg = -14f,
    )
    // 33 cm 柔光对准虚线
    drawLine(
        EyeGamePalette.Firefly.glowMid.copy(alpha = 0.42f),
        Offset(w * 0.50f, flyC.y + u * 0.14f),
        Offset(w * 0.50f, h * 0.54f),
        strokeWidth = u * 0.012f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
    )
    drawCuteEyesPair(
        center = Offset(w * 0.50f, h * 0.64f),
        eyeRadius = u * 0.115f,
        lookTarget = flyC,
        blinking = false,
        cover = null,
        darkTheme = true,
    )
    if (motion == GuideMotion.LIGHTING) {
        // 两侧稳定支架示意弧
        val standCol = EyeGamePalette.Firefly.moonHalo.copy(alpha = 0.55f)
        drawLine(standCol, Offset(w * 0.22f, h * 0.76f), Offset(w * 0.28f, h * 0.54f), u * 0.018f, StrokeCap.Round)
        drawLine(standCol, Offset(w * 0.78f, h * 0.76f), Offset(w * 0.72f, h * 0.54f), u * 0.018f, StrokeCap.Round)
    }
}

/** 7. 斜视越过手机看 6 米外演示（FAR）：月夜远山高悬暖金远星 + 前景小手机 + 视线越过手机直指远星。 */
private fun DrawScope.drawFireflyFarGuide(p: Float) {
    val w = size.width
    val h = size.height
    val u = min(w, h)
    drawMoonlitForestBackdrop(phase = p)

    val starC = Offset(w * 0.50f, h * 0.20f)
    val glow = 0.78f + 0.22f * sin(p * 2f * PI.toFloat())
    drawCircle(
        brush = Brush.radialGradient(
            0f to EyeGamePalette.Firefly.glowCore.copy(alpha = 0.85f * glow),
            0.45f to EyeGamePalette.Firefly.glowMid.copy(alpha = 0.35f * glow),
            1f to Color.Transparent,
            center = starC,
            radius = u * 0.26f,
        ),
        radius = u * 0.26f,
        center = starC,
    )
    drawFourPointStar(starC, u * 0.11f * glow, EyeGamePalette.Firefly.glowCore)

    // 前景略低于视线的半透明小手机剪影
    val phoneW = u * 0.20f
    val phoneH = u * 0.28f
    val phoneTL = Offset(w * 0.72f - phoneW * 0.5f, h * 0.44f)
    drawRoundRect(
        color = EyeGamePalette.Firefly.skyTop.copy(alpha = 0.82f),
        topLeft = phoneTL,
        size = Size(phoneW, phoneH),
        cornerRadius = CornerRadius(u * 0.03f),
    )
    drawRoundRect(
        color = EyeGamePalette.Firefly.moonHalo.copy(alpha = 0.65f),
        topLeft = phoneTL,
        size = Size(phoneW, phoneH),
        cornerRadius = CornerRadius(u * 0.03f),
        style = Stroke(u * 0.014f),
    )

    // 下方双眼望向远星的发光引导虚线
    val eyesC = Offset(w * 0.38f, h * 0.64f)
    drawLine(
        EyeGamePalette.Firefly.glowCore.copy(alpha = 0.70f),
        Offset(eyesC.x, eyesC.y - u * 0.13f),
        Offset(starC.x, starC.y + u * 0.11f),
        strokeWidth = u * 0.016f,
        cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
    )
    drawCuteEyesPair(
        center = eyesC,
        eyeRadius = u * 0.11f,
        lookTarget = starC,
        blinking = false,
        cover = null,
        darkTheme = true,
    )
}

/** 8. 斜视 12 秒交替遮盖演示（COVER / COVER_SELF）：上方萤火虫 + 下方灵动双眼配合云朵小手精准遮右/双眼/遮左/快速交替。 */
private fun DrawScope.drawFireflyCoverGuide(p: Float, pose: GuidePose) {
    val w = size.width
    val h = size.height
    val u = min(w, h)
    drawMoonlitForestBackdrop(phase = p)

    val flyC = Offset(w * 0.50f, h * 0.24f + sin(p * 4f * PI.toFloat()) * u * 0.02f)
    drawMoonlitFirefly(
        center = flyC,
        radius = u * 0.11f,
        wingPhase = (p * 12f) % 1f,
        glowIntensity = 1f,
        tiltDeg = -14f,
    )
    drawCuteEyesPair(
        center = Offset(w * 0.50f, h * 0.60f),
        eyeRadius = u * 0.135f,
        lookTarget = flyC,
        blinking = false,
        cover = when (pose) {
            GuidePose.RIGHT -> VisionEye.RIGHT
            GuidePose.LEFT -> VisionEye.LEFT
            else -> null
        },
        darkTheme = true,
    )
}

/** 9. 斜视月夜追萤火虫演示（FOLLOW）：发光萤火虫拖星尘尾迹沿水平 → 垂直 → 斜向飞舞，下方双眼瞳孔同步跟随。 */
private fun DrawScope.drawFireflyFollowGuide(p: Float) {
    val w = size.width
    val h = size.height
    val u = min(w, h)
    drawMoonlitForestBackdrop(phase = p)

    fun posAt(tRaw: Float): Offset {
        val t = (tRaw + 1f) % 1f
        val twoPi = 2f * PI.toFloat()
        val rel = when {
            t < 0.36f -> Offset(0.32f * sin(t / 0.36f * twoPi), 0f)
            t < 0.72f -> Offset(0f, 0.22f * sin((t - 0.36f) / 0.36f * twoPi))
            else -> {
                val uSeg = (t - 0.72f) / 0.28f
                val wave = if (uSeg < 0.5f) uSeg * 4f - 1f else 3f - uSeg * 4f
                Offset(0.28f * wave, -0.18f * wave)
            }
        }
        return Offset(w * (0.50f + rel.x), h * (0.34f + rel.y))
    }

    val trail = (7 downTo 1).map { step -> posAt(p - step * 0.016f) }
    val flyC = posAt(p)
    val prevC = posAt(p - 0.015f)
    val tilt = ((flyC.x - prevC.x) * 1.4f).coerceIn(-26f, 26f)
    drawFireflyTrail(trail + flyC, u * 0.11f)
    drawMoonlitFirefly(
        center = flyC,
        radius = u * 0.115f,
        wingPhase = (p * 10f) % 1f,
        glowIntensity = 1f,
        tiltDeg = tilt,
    )
    drawCuteEyesPair(
        center = Offset(w * 0.50f, h * 0.69f),
        eyeRadius = u * 0.095f,
        lookTarget = flyC,
        blinking = false,
        cover = null,
        darkTheme = true,
    )
}

// ============================================================================
// 通用萌系矢量元件：无圆框实心纯白大箭头 & 灵动卡通双眼（含温柔遮眼云掌）
// ============================================================================

/** 无圆框实心大箭头（0=右 1=下 2=左 3=上），与正式「小芽浇水挑战」完全一致。 */
private fun DrawScope.drawSolidGuideArrow(center: Offset, size: Float, direction: Int, highlighted: Boolean) {
    rotate(degrees = direction * 90f, pivot = center) {
        val half = size * 0.5f
        val left = center.x - half
        val top = center.y - half
        val arrow = Path().apply {
            moveTo(left + size * 0.12f, top + size * 0.34f)
            lineTo(left + size * 0.50f, top + size * 0.34f)
            lineTo(left + size * 0.50f, top + size * 0.14f)
            lineTo(left + size * 0.90f, top + size * 0.50f)
            lineTo(left + size * 0.50f, top + size * 0.86f)
            lineTo(left + size * 0.50f, top + size * 0.66f)
            lineTo(left + size * 0.12f, top + size * 0.66f)
            close()
        }
        drawPath(
            arrow,
            color = if (highlighted) EyeGamePalette.Sprout.dropBlue else EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.94f),
        )
        drawPath(
            arrow,
            color = if (highlighted) EyeGamePalette.Sprout.cloudWhite else EyeGamePalette.Sprout.arrowDark.copy(alpha = 0.28f),
            style = Stroke(width = (size * 0.08f).coerceAtLeast(1.5f), join = StrokeJoin.Round),
        )
    }
}

/**
 * 灵动萌系卡通双眼：
 * - 瞳孔随 [lookTarget] 精准转动；
 * - [cover] 遵循受试者自身左右（面对屏幕时：受试者右眼在画面左侧，受试者左眼在画面右侧），温柔云朵小手不压眼球轻遮；
 * - [tearShield] 亮起青蓝水膜护盾光环（用于持续睁眼演示）。
 */
private fun DrawScope.drawCuteEyesPair(
    center: Offset,
    eyeRadius: Float,
    lookTarget: Offset,
    blinking: Boolean,
    cover: VisionEye?,
    darkTheme: Boolean,
    tearShield: Boolean = false,
) {
    val gap = eyeRadius * 1.48f
    // 面对用户：画面左侧 = 受试者右眼（VisionEye.RIGHT），画面右侧 = 受试者左眼（VisionEye.LEFT）
    val rightEyeC = Offset(center.x - gap, center.y)
    val leftEyeC = Offset(center.x + gap, center.y)

    listOf(rightEyeC to VisionEye.RIGHT, leftEyeC to VisionEye.LEFT).forEach { (ec, which) ->
        if (tearShield) {
            drawCircle(
                EyeGamePalette.Sprout.dropLight.copy(alpha = 0.45f),
                radius = eyeRadius * 1.32f,
                center = ec,
                style = Stroke(eyeRadius * 0.16f),
            )
        }
        if (blinking) {
            // 微笑弯月闭眼
            val lid = Path().apply {
                moveTo(ec.x - eyeRadius * 0.85f, ec.y)
                quadraticTo(ec.x, ec.y + eyeRadius * 0.62f, ec.x + eyeRadius * 0.85f, ec.y)
            }
            drawPath(
                lid,
                color = if (darkTheme) EyeGamePalette.Firefly.glowCore else EyeGamePalette.Sprout.arrowDark,
                style = Stroke(eyeRadius * 0.24f, cap = StrokeCap.Round),
            )
        } else {
            // 眼白 + 外轮廓
            drawCircle(EyeGamePalette.Sprout.cloudWhite, eyeRadius, ec)
            drawCircle(
                if (darkTheme) EyeGamePalette.Firefly.moonHalo.copy(alpha = 0.72f) else EyeGamePalette.Sprout.arrowDark.copy(alpha = 0.45f),
                eyeRadius,
                ec,
                style = Stroke(eyeRadius * 0.13f),
            )
            // 瞳孔跟随目标方向
            val ang = atan2(lookTarget.y - ec.y, lookTarget.x - ec.x)
            val maxShift = eyeRadius * 0.34f
            val dist = (lookTarget - ec).getDistance()
            val shift = (dist * 0.08f).coerceAtMost(maxShift)
            val pupilC = Offset(ec.x + cos(ang) * shift, ec.y + sin(ang) * shift)
            drawCircle(EyeGamePalette.Sprout.arrowDark, eyeRadius * 0.48f, pupilC)
            // 灵动双高光点
            drawCircle(
                EyeGamePalette.Sprout.cloudWhite,
                eyeRadius * 0.17f,
                Offset(pupilC.x - eyeRadius * 0.16f, pupilC.y - eyeRadius * 0.16f),
            )
            drawCircle(
                EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.75f),
                eyeRadius * 0.08f,
                Offset(pupilC.x + eyeRadius * 0.15f, pupilC.y + eyeRadius * 0.14f),
            )
        }

        // 温柔云朵手掌遮板（清晰标示遮右眼或遮左眼）
        if (cover == which) {
            val shieldR = eyeRadius * 1.24f
            drawCircle(
                EyeGamePalette.Sprout.dropLight.copy(alpha = 0.92f),
                radius = shieldR,
                center = ec,
            )
            drawCircle(
                EyeGamePalette.Sprout.cloudWhite,
                radius = shieldR,
                center = ec,
                style = Stroke(eyeRadius * 0.16f),
            )
            // 柔和手掌指节弧线（直观表现「轻遮不压眼球」）
            val palm = Path().apply {
                moveTo(ec.x - shieldR * 0.42f, ec.y + shieldR * 0.18f)
                quadraticTo(ec.x, ec.y - shieldR * 0.34f, ec.x + shieldR * 0.42f, ec.y + shieldR * 0.18f)
            }
            drawPath(
                palm,
                color = EyeGamePalette.Sprout.arrowDark.copy(alpha = 0.72f),
                style = Stroke(eyeRadius * 0.18f, cap = StrokeCap.Round),
            )
        }
    }
}
