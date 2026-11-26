package com.zkyjss.eyeproject.feature.services

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeText
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 小芽浇水挑战背景模式：
 * - [SCENIC]（默认，高度还原甲方主图）：阳光蓝天 + 层峦湖泊 + 鲜花草坪 + 陶土盆小芽与蓝水滴精灵「小露」+ 实心白大箭头。
 * - [CLEAN]（一键切换，还原底部右图）：纯净淡蓝高对比晴空 + 纯黑 E 字视标 + 下方静谧湖心萌芽与悬垂水滴。
 */
internal enum class SproutBackdropStyle { SCENIC, CLEAN }

/**
 * 准备与 40 cm 校准距离卡（高度还原甲方 PPT 图 1 & 图 2）：
 * 顶部「← 请保持 40cm →」标尺胶囊 + 内嵌阳光湖景花盆小芽与小露画框 + 双手横持手机/桌面支架校准图示与实时 40 cm 进度条。
 */
@Composable
internal fun SproutSetupCard(
    title: String,
    subtitle: String,
    distanceCm: Float,
    targetCm: Float = 40f,
    toleranceCm: Float = 5f,
    faceDetected: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val reducedMotion = rememberReducedMotion()
    val infinite = rememberInfiniteTransition(label = "准备小芽")
    val bob by if (reducedMotion) remember { mutableStateOf(0.5f) } else {
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
            label = "准备漂浮",
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
        ((distanceCm - 20f) / 40f).coerceIn(0.05f, 0.95f)
    } else 0.5f

    Box(
        modifier
            .fillMaxWidth()
            .clip(EyeShapes.hero)
            .border(1.dp, EyeGamePalette.Sprout.dropLight.copy(alpha = 0.45f), EyeShapes.hero),
    ) {
        Canvas(
            Modifier
                .matchParentSize()
                .semantics { contentDescription = "小芽浇水挑战准备与校准卡片" },
        ) {
            drawSproutBackdrop(style = SproutBackdropStyle.SCENIC, wave = bob)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 顶部「← 请保持 40cm →」标尺胶囊（还原图 1）
            Box(
                Modifier
                    .clip(EyeShapes.chip)
                    .background(EyeGamePalette.Sprout.arrowDark.copy(alpha = 0.72f))
                    .border(1.dp, EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.25f), EyeShapes.chip)
                    .padding(horizontal = 14.dp, vertical = 4.dp),
            ) {
                EyeText(
                    "←  请保持 ${targetCm.toInt()}cm  →",
                    style = typography.caption,
                    color = EyeGamePalette.Sprout.cloudWhite,
                )
            }

            // 深蓝半透明磨砂主卡片（还原图 1 & 图 2）
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(EyeShapes.large)
                    .background(EyeGamePalette.Sprout.arrowDark.copy(alpha = 0.80f))
                    .border(1.dp, EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.22f), EyeShapes.large)
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EyeText(title, style = typography.title, color = EyeGamePalette.Sprout.cloudWhite, textAlign = TextAlign.Center)
                EyeText(subtitle, style = typography.caption, color = EyeGamePalette.Sprout.dropLight, textAlign = TextAlign.Center)

                // 内嵌圆角湖景花盆小芽 + 水滴精灵「小露」插画窗（还原图 1）
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(142.dp)
                        .clip(EyeShapes.medium)
                        .border(2.dp, EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.45f), EyeShapes.medium),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawSproutBackdrop(style = SproutBackdropStyle.SCENIC, wave = bob)
                        val sc = min(size.width, size.height) / 145f
                        drawPottedSprout(
                            center = Offset(size.width * 0.45f, size.height * 0.84f),
                            scale = sc,
                            growthStage = 1,
                            wilted = false,
                            wave = bob,
                            bounce = 1f,
                        )
                        drawDropletMascot(
                            center = Offset(size.width * 0.78f, size.height * 0.78f + sin(bob * 2f * PI.toFloat()) * 4f),
                            radius = min(size.width, size.height) * 0.16f,
                            cheer = 0f,
                        )
                        drawGlowingWaterDrop(Offset(size.width * 0.67f, size.height * 0.34f + sin(bob * 2f * PI.toFloat()) * 5f), 8.dp.toPx())
                        drawGlowingWaterDrop(Offset(size.width * 0.75f, size.height * 0.49f - sin(bob * 2f * PI.toFloat()) * 4f), 6.dp.toPx())
                    }
                }

                // 实时 40 cm 距离标尺 + 双姿态示意（还原图 2）
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EyeText("标准测试距离：${targetCm.toInt()}cm", style = typography.chip, color = EyeGamePalette.Sprout.cloudWhite)
                        EyeText("可手持，也可放立桌上", style = typography.caption, color = EyeGamePalette.Sprout.dropLight)
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.22f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(sliderRatio)
                                .height(8.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            EyeGamePalette.Sprout.dropBlue,
                                            if (inRange) EyeGamePalette.Sprout.badgeStable else EyeGamePalette.Sprout.dropLight,
                                        ),
                                    ),
                                ),
                        )
                    }
                    EyeText(
                        distanceLabel,
                        style = typography.bodySecondary,
                        color = if (inRange) EyeGamePalette.Sprout.leafBright else EyeGamePalette.Sprout.cloudWhite,
                    )
                }
            }
        }
    }
}

/**
 * 干眼 & 老视共用的「小芽浇水挑战」看 E 作答主舞台（高度还原甲方 PPT 图 3 & 底部右图）：
 * - 默认呈现充满生机的阳光湖景草地 + 立体花盆小芽 + 蓝水滴精灵「小露」+ 四周无圆框实心纯白大箭头 + 「方向?」气泡。
 * - 右上角可一键切换「切清爽背景」（还原底部右图：纯淡蓝晴空高对比纯黑 E + 湖心悬垂水滴萌芽）。
 * - 精致有趣的实时动画：
 *   - 答对：小露欢快跳起倾斜 → 挥出 5 颗抛物线蓝色水柱飞向小芽 → 炸开 8 颗水花微珠与双层涟漪 → 小芽 Q 弹拔高长叶开花 + 金星闪烁 + 浮现「浇水 +1」气泡！
 *   - 答错/跳过/超时：半空水滴缓缓化作 4 缕上升白雾蒸气与气泡 + 小芽轻晃 + 浮现「水滴蒸发了」气泡！
 * - 严格按批注不保留底部的上下左右按钮行。
 */
@Composable
internal fun SproutWateringAnswerPad(
    direction: Int,
    size: Dp,
    visible: Boolean,
    stageBadge: String,
    stageHeadline: String = "",
    correctCount: Int = 0,
    wrongCount: Int = 0,
    lastAnswerCorrect: Boolean? = null,
    gesture: GestureFeedback = GestureFeedback(),
    belowStimulus: (@Composable () -> Unit)? = null,
    initialStyle: SproutBackdropStyle = SproutBackdropStyle.SCENIC,
    previewWaterPhase: Float? = null,
    previewEvapPhase: Float? = null,
    modifier: Modifier = Modifier,
    onAnswer: (Int) -> Unit,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val reducedMotion = rememberReducedMotion()
    var style by rememberSaveable { mutableStateOf(initialStyle) }

    // 有界 1.15 秒电影级浇水动画 & 1.0 秒蒸发动画（仅在计数递增时触发，且两者互斥）
    val waterAnim = remember { Animatable(1f) }
    val evapAnim = remember { Animatable(1f) }
    var prevCorrect by remember { mutableStateOf(correctCount) }
    var prevWrong by remember { mutableStateOf(wrongCount) }
    LaunchedEffect(correctCount) {
        if (correctCount > prevCorrect && !reducedMotion) {
            evapAnim.snapTo(1f)
            waterAnim.snapTo(0f)
            waterAnim.animateTo(1f, tween(1150, easing = LinearEasing))
        }
        prevCorrect = correctCount
    }
    LaunchedEffect(wrongCount) {
        if (wrongCount > prevWrong && !reducedMotion) {
            waterAnim.snapTo(1f)
            evapAnim.snapTo(0f)
            evapAnim.animateTo(1f, tween(1000, easing = LinearEasing))
        }
        prevWrong = wrongCount
    }
    val wt = previewWaterPhase ?: waterAnim.value
    val et = previewEvapPhase ?: evapAnim.value

    val infinite = rememberInfiniteTransition(label = "湖景微动")
    val wave by if (reducedMotion) remember { mutableStateOf(0.25f) } else {
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart),
            label = "风吹水波",
        )
    }

    val growthStage = when {
        correctCount >= 10 -> 4 // 盛开明媚小雏菊
        correctCount >= 6 -> 3  // 含苞待放
        correctCount >= 3 -> 2  // 繁茂四叶
        correctCount >= 1 -> 1  // 舒展双叶
        else -> 0               // 破土幼芽
    }
    val wilted = lastAnswerCorrect == false && wrongCount > correctCount
    val isClean = style == SproutBackdropStyle.CLEAN
    val optotypeColor = if (isClean) EyeGamePalette.Sprout.optotypeInk else EyeGamePalette.Sprout.cloudWhite
    val headlineColor = if (isClean) EyeGamePalette.Sprout.arrowDark else EyeGamePalette.Sprout.arrowDark
    val statusText = when {
        wt < 0.98f -> "浇水成功 +1！小芽喝饱水正在长高"
        et < 0.98f -> "水滴蒸发啦，看清 E 开口方向再浇水"
        lastAnswerCorrect == true -> "已成功浇水 $correctCount 滴 · 全部通关小芽会开花"
        lastAnswerCorrect == false -> "水滴蒸发了，稳住视线看清方向再答"
        else -> "答对浇水让小芽长大 · 可手持或放立桌上"
    }
    val extra = rememberStageExtra()

    // 小芽喝水后的 Q 弹欢呼放大系数（0.45..0.95 之间弹跳）
    val plantBounce = if (wt in 0.42f..0.95f) {
        val u = (wt - 0.42f) / 0.53f
        1f + 0.16f * sin(u * PI.toFloat())
    } else 1f
    // 答错轻微左右晃动偏移
    val wiltShake = if (et < 0.85f) sin(et * 6f * PI.toFloat()) * (1f - et) * 7f else 0f

    Column(
        modifier
            .fillMaxWidth()
            .testTag("answer-pad"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .testTag("optotype-surround")
                .fillMaxWidth()
                .heightIn(min = maxOf(342.dp, size + 252.dp) + extra)
                .clip(EyeShapes.hero)
                .border(1.dp, colors.cardBorder.copy(alpha = 0.45f), EyeShapes.hero),
        ) {
            // 底层高保真风景 + 浇水喷泉 + 水花 + 蒸发 + 成长小芽画布
            Canvas(
                Modifier
                    .matchParentSize()
                    .semantics { contentDescription = "小芽浇水视标挑战舞台，已浇水 $correctCount 滴" },
            ) {
                val cw = this.size.width
                val ch = this.size.height
                drawSproutBackdrop(style = style, wave = wave)
                val sproutCenter = Offset(cw * (if (isClean) 0.50f else 0.44f) + wiltShake, ch * 0.85f)
                val sproutScale = (min(cw, ch) / 295f).coerceIn(0.85f, 1.38f)
                val mascotCenter = Offset(cw * 0.77f, ch * 0.82f + sin(wave * 2f * PI.toFloat()) * 3f)

                if (isClean) {
                    drawLakeIslandSprout(sproutCenter, sproutScale, growthStage, wilted, wave, bounce = plantBounce)
                } else {
                    drawPottedSprout(sproutCenter, sproutScale, growthStage, wilted, wave, bounce = plantBounce)
                    // 浇水瞬间小露欢快跳起并朝花盆倾斜挥洒水光
                    val cheerJump = if (wt < 0.65f) sin((wt / 0.65f) * PI.toFloat()) else 0f
                    drawDropletMascot(
                        center = Offset(mascotCenter.x - cheerJump * 10.dp.toPx(), mascotCenter.y - cheerJump * 16.dp.toPx()),
                        radius = min(cw, ch) * 0.092f,
                        cheer = cheerJump,
                    )
                }

                // ===== 1. 电影级浇水动画（答对时播放 1.15 秒） =====
                if (wt < 0.99f) {
                    val targetSoil = Offset(sproutCenter.x, sproutCenter.y - 24.dp.toPx() * sproutScale)
                    if (isClean) {
                        // 清爽模式：悬垂水滴加速坠落并炸开湖面晶莹水花
                        val fallT = (wt / 0.55f).coerceIn(0f, 1f)
                        if (wt < 0.58f) {
                            val dy = sproutCenter.y - 68.dp.toPx() + fallT * fallT * 46.dp.toPx()
                            drawGlowingWaterDrop(Offset(sproutCenter.x, dy), 10.dp.toPx() * (1f - fallT * 0.25f))
                        }
                    } else {
                        // 湖景模式：小露挥洒出一道 5 颗错落的晶莹抛物线水珠流！
                        val startPt = Offset(mascotCenter.x - 16.dp.toPx(), mascotCenter.y - 24.dp.toPx())
                        val ctrlPt = Offset((startPt.x + targetSoil.x) * 0.5f, min(startPt.y, targetSoil.y) - 58.dp.toPx())
                        for (i in 0 until 5) {
                            val localT = ((wt - i * 0.065f) / 0.48f).coerceIn(0f, 1f)
                            if (localT > 0.01f && localT < 0.99f) {
                                val inv = 1f - localT
                                val px = inv * inv * startPt.x + 2f * inv * localT * ctrlPt.x + localT * localT * targetSoil.x
                                val py = inv * inv * startPt.y + 2f * inv * localT * ctrlPt.y + localT * localT * targetSoil.y
                                // 水珠飞行柔和尾迹
                                val prevT = (localT - 0.08f).coerceAtLeast(0f)
                                val pinv = 1f - prevT
                                val ppx = pinv * pinv * startPt.x + 2f * pinv * prevT * ctrlPt.x + prevT * prevT * targetSoil.x
                                val ppy = pinv * pinv * startPt.y + 2f * pinv * prevT * ctrlPt.y + prevT * prevT * targetSoil.y
                                drawLine(
                                    EyeGamePalette.Sprout.dropLight.copy(alpha = 0.72f),
                                    Offset(ppx, ppy),
                                    Offset(px, py),
                                    strokeWidth = 4.5.dp.toPx(),
                                    cap = StrokeCap.Round,
                                )
                                drawGlowingWaterDrop(Offset(px, py), (7.5f - i * 0.55f).dp.toPx())
                            }
                        }
                    }
                    // 水珠命中后：放射状 8 颗晶莹水花 + 双层涟漪光环 + 闪耀四角星光
                    if (wt in 0.40f..0.98f) {
                        val sp = ((wt - 0.40f) / 0.58f).coerceIn(0f, 1f)
                        val alpha = (1f - sp).coerceIn(0f, 1f)
                        for (ring in 0..1) {
                            val rr = (10f + sp * (36f + ring * 18f)).dp.toPx()
                            drawOval(
                                EyeGamePalette.Sprout.dropLight.copy(alpha = alpha * (0.85f - ring * 0.3f)),
                                topLeft = Offset(targetSoil.x - rr, targetSoil.y + 12.dp.toPx() - rr * 0.34f),
                                size = Size(rr * 2f, rr * 0.68f),
                                style = Stroke(2.4.dp.toPx()),
                            )
                        }
                        for (k in 0 until 8) {
                            val angle = -PI.toFloat() * (0.12f + k * 0.11f)
                            val dist = (10f + sp * 42f).dp.toPx()
                            val sx = targetSoil.x + cos(angle) * dist
                            val sy = targetSoil.y + sin(angle) * dist + sp * sp * 18.dp.toPx()
                            drawCircle(
                                EyeGamePalette.Sprout.dropLight.copy(alpha = alpha * 0.95f),
                                radius = (3.4f * (1f - sp * 0.5f)).dp.toPx(),
                                center = Offset(sx, sy),
                            )
                        }
                        // 小芽周身闪耀 4 颗金色/亮白成长星光
                        val starAlpha = sin(sp * PI.toFloat())
                        val starOffsets = listOf(
                            Offset(-34f, -34f) to EyeGamePalette.Sprout.daisyCenter,
                            Offset(36f, -42f) to EyeGamePalette.Sprout.cloudWhite,
                            Offset(-22f, -64f) to EyeGamePalette.Sprout.dropLight,
                            Offset(24f, -18f) to EyeGamePalette.Sprout.daisyCenter,
                        )
                        starOffsets.forEach { (d, col) ->
                            drawFourPointStar(
                                Offset(targetSoil.x + d.x.dp.toPx(), targetSoil.y + d.y.dp.toPx() - sp * 12.dp.toPx()),
                                radius = (7.5f * starAlpha).dp.toPx(),
                                color = col.copy(alpha = starAlpha),
                            )
                        }
                    }
                } else if (isClean) {
                    // 清爽模式静默时：上方晶莹悬垂水滴轻柔上下浮动（还原底部右图）
                    drawGlowingWaterDrop(
                        Offset(sproutCenter.x, sproutCenter.y - 54.dp.toPx() + sin(wave * 2f * PI.toFloat()) * 3f),
                        9.dp.toPx(),
                    )
                }

                // ===== 2. 答错/跳过：水滴蒸发升腾白雾动画（1.0 秒） =====
                if (et < 0.99f) {
                    val alpha = (1f - et).coerceIn(0f, 1f)
                    val origin = Offset(sproutCenter.x, sproutCenter.y - 44.dp.toPx())
                    // 正在缩小的透明水滴
                    if (et < 0.45f) {
                        val dropScale = 1f - et / 0.45f
                        drawCircle(
                            EyeGamePalette.Sprout.dropLight.copy(alpha = dropScale * 0.7f),
                            radius = (8f * dropScale).dp.toPx(),
                            center = Offset(origin.x, origin.y - et * 18.dp.toPx()),
                        )
                    }
                    // 4 缕弯曲上升白雾蒸气
                    for (i in 0..3) {
                        val sx = origin.x + (i - 1.5f) * 14.dp.toPx()
                        val startY = origin.y - et * 32.dp.toPx()
                        val sway = sin((et * 4f + i * 0.7f) * PI.toFloat()) * 6.dp.toPx()
                        val vapor = Path().apply {
                            moveTo(sx, startY + 12.dp.toPx())
                            quadraticTo(sx + sway, startY - 4.dp.toPx(), sx - sway * 0.8f, startY - 22.dp.toPx())
                        }
                        drawPath(
                            vapor,
                            color = EyeGamePalette.Sprout.cloudWhite.copy(alpha = alpha * 0.82f),
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                    // 6 颗散逸上升蒸发小汽泡
                    for (i in 0 until 6) {
                        val bx = origin.x + (i - 2.5f) * 12.dp.toPx() + sin(et * 8f + i) * 4.dp.toPx()
                        val by = origin.y - (10f + et * (38f + (i % 3) * 12f)).dp.toPx()
                        drawCircle(
                            EyeGamePalette.Sprout.cloudWhite.copy(alpha = alpha * 0.78f),
                            radius = (2.8f + et * 3.5f).dp.toPx(),
                            center = Offset(bx, by),
                            style = Stroke(1.8.dp.toPx()),
                        )
                    }
                }
            }

            // 浇水 / 蒸发飘字反馈气泡（紧贴小芽上方浮起）
            if (wt in 0.25f..0.96f) {
                val lift = ((wt - 0.25f) / 0.71f).coerceIn(0f, 1f)
                val fade = sin(lift * PI.toFloat()).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = (-86 - lift * 28).dp)
                        .graphicsLayer { alpha = fade }
                        .clip(EyeShapes.chip)
                        .background(EyeGamePalette.Sprout.dropBlue)
                        .border(1.dp, EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.7f), EyeShapes.chip)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    EyeText("浇水 +1", style = typography.chip, color = EyeGamePalette.Sprout.cloudWhite)
                }
            } else if (et in 0.08f..0.92f) {
                val lift = ((et - 0.08f) / 0.84f).coerceIn(0f, 1f)
                val fade = sin(lift * PI.toFloat()).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = (-84 - lift * 24).dp)
                        .graphicsLayer { alpha = fade }
                        .clip(EyeShapes.chip)
                        .background(EyeGamePalette.Sprout.cardSoilBase.copy(alpha = 0.86f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    EyeText("水滴蒸发啦", style = typography.chip, color = EyeGamePalette.Sprout.daisyCenter)
                }
            }

            // 前景内容：顶部绿条 + 标题 + 中央 E 与紧贴四周的实心粗大方向箭头（还原 PPT 图 3）
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier
                                .clip(EyeShapes.chip)
                                .background(EyeGamePalette.Sprout.badgeStable)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(EyeGamePalette.Sprout.cloudWhite))
                            EyeText(stageBadge, style = typography.chip, color = EyeGamePalette.Sprout.cloudWhite)
                        }
                        Box(
                            Modifier
                                .clip(EyeShapes.chip)
                                .background(EyeGamePalette.Sprout.arrowDark.copy(alpha = 0.72f))
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                        ) {
                            EyeText("已浇 $correctCount 滴", style = typography.caption, color = EyeGamePalette.Sprout.dropLight)
                        }
                    }
                    Box(
                        Modifier
                            .clip(EyeShapes.chip)
                            .background(EyeGamePalette.Sprout.arrowDark.copy(alpha = 0.72f))
                            .border(1.dp, EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.35f), EyeShapes.chip)
                            .clickable(role = Role.Button) {
                                style = if (isClean) SproutBackdropStyle.SCENIC else SproutBackdropStyle.CLEAN
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        EyeText(
                            if (isClean) "切湖景背景" else "切清爽背景",
                            style = typography.caption,
                            color = EyeGamePalette.Sprout.cloudWhite,
                        )
                    }
                }

                if (stageHeadline.isNotEmpty()) {
                    EyeText(
                        stageHeadline,
                        style = typography.subtitle,
                        color = headlineColor,
                        textAlign = TextAlign.Center,
                    )
                }

                // 中央视标 + 紧贴四周的实心大箭头（高度贴近图 3：无圆框、饱满粗白箭头 + 右上「方向?」气泡）
                val centerBox = maxOf(76.dp, size + 18.dp)
                Box(contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        SolidDirectionArrowButton(3, "向上", visible, isClean, gesture) { onAnswer(3) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            SolidDirectionArrowButton(2, "向左", visible, isClean, gesture) { onAnswer(2) }
                            Box(
                                Modifier
                                    .size(centerBox)
                                    .clip(EyeShapes.medium)
                                    .background(
                                        if (isClean) Color.Transparent
                                        else EyeGamePalette.Sprout.arrowDark.copy(alpha = if (visible) 0.16f else 0.08f),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (visible) {
                                    TumblingE(
                                        direction = direction,
                                        size = size,
                                        color = optotypeColor,
                                        modifier = Modifier.testTag("answer-optotype"),
                                    )
                                } else {
                                    EyeText("准备", style = typography.caption, color = headlineColor.copy(alpha = 0.7f))
                                }
                            }
                            SolidDirectionArrowButton(0, "向右", visible, isClean, gesture) { onAnswer(0) }
                        }
                        SolidDirectionArrowButton(1, "向下", visible, isClean, gesture) { onAnswer(1) }
                    }
                    // 右上角「方向?」提示气泡（还原图 3 细节）
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 12.dp, y = 14.dp)
                            .clip(EyeShapes.chip)
                            .background(EyeGamePalette.Sprout.arrowDark.copy(alpha = 0.56f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        EyeText("方向?", style = typography.caption, color = EyeGamePalette.Sprout.cloudWhite)
                    }
                }

                if (belowStimulus != null) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { belowStimulus() }
                }

                Spacer(Modifier.height(112.dp))
            }

            // 底部轻量状态条（稳稳贴在舞台底端）
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp, start = 12.dp, end = 12.dp)
                    .clip(EyeShapes.chip)
                    .background(EyeGamePalette.Sprout.cardSoilBase.copy(alpha = 0.82f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                EyeText(
                    statusText,
                    style = typography.caption,
                    color = if (lastAnswerCorrect == false) EyeGamePalette.Sprout.daisyCenter else EyeGamePalette.Sprout.cloudWhite,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // 不保留底部的上下左右按钮行，仅保留次要「看不清 · 跳过」胶囊
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

/**
 * 高度还原图 3 的饱满实心指向大箭头（无圆框，点击与手势作答热区均 ≥ 52dp）：
 * 基准朝右（0=右 1=下 2=左 3=上），带立体柔影、按下缩放与手势锁定高亮进度描边。
 */
@Composable
private fun SolidDirectionArrowButton(
    direction: Int,
    label: String,
    enabled: Boolean,
    cleanBackdrop: Boolean,
    gesture: GestureFeedback,
    onClick: () -> Unit,
) {
    val colors = LocalEyeColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val matched = enabled && gesture.direction == direction
    val holding = matched && !gesture.needsRelease
    val scale by animateFloatAsState(if (pressed && enabled) 0.88f else 1f, EyeMotion.pressSpring(), label = "实心箭头缩放")
    val alpha by animateFloatAsState(if (enabled) 1f else 0.38f, tween(EyeMotion.SHORT), label = "实心箭头可用")
    val fillColor by animateColorAsState(
        when {
            holding -> colors.primary
            pressed && enabled -> EyeGamePalette.Sprout.dropBlue
            else -> EyeGamePalette.Sprout.cloudWhite
        },
        tween(EyeMotion.SHORT),
        label = "实心箭头填充",
    )
    val strokeColor = when {
        holding -> EyeGamePalette.Sprout.cloudWhite
        cleanBackdrop -> EyeGamePalette.Sprout.arrowDark.copy(alpha = 0.45f)
        else -> EyeGamePalette.Sprout.arrowDark.copy(alpha = 0.25f)
    }

    Box(
        Modifier
            .size(54.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(44.dp).rotate(direction * 90f)) {
            val w = this.size.width
            val h = this.size.height
            // 饱满圆润的实心指向箭头轮廓（基准朝右）
            val arrow = Path().apply {
                moveTo(w * 0.12f, h * 0.34f)
                lineTo(w * 0.50f, h * 0.34f)
                lineTo(w * 0.50f, h * 0.14f)
                lineTo(w * 0.90f, h * 0.50f)
                lineTo(w * 0.50f, h * 0.86f)
                lineTo(w * 0.50f, h * 0.66f)
                lineTo(w * 0.12f, h * 0.66f)
                close()
            }
            // 柔和底层阴影增加立体浮雕感
            val shadow = Path().apply {
                addPath(arrow, Offset(0f, 2.dp.toPx()))
            }
            drawPath(shadow, EyeGamePalette.Sprout.arrowDark.copy(alpha = 0.22f))
            drawPath(arrow, fillColor)
            drawPath(arrow, strokeColor, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))
        }
    }
}

/**
 * 第二关「67cm 慢慢靠近」移近法互动舞台（高度还原甲方 PPT 图 4）：
 * 顶部起始/当前距离发光进度滑轨 + 中央带四角对焦框的精确物理 E + 湖畔青苔小芽与飘向小芽的小水珠 + 奶白按钮「持续模糊，点这里」+ 实时速度。
 */
@Composable
internal fun SproutPushUpStage(
    size: Dp,
    visible: Boolean,
    startCm: Float,
    currentCm: Float,
    speedCmPerSec: Float?,
    roundLabel: String,
    canStillClear: Boolean,
    onBlur: () -> Unit,
    onStillClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val typography = LocalEyeTypography.current
    val reducedMotion = rememberReducedMotion()
    var style by rememberSaveable { mutableStateOf(SproutBackdropStyle.SCENIC) }
    val infinite = rememberInfiniteTransition(label = "移近湖面")
    val wave by if (reducedMotion) remember { mutableStateOf(0.25f) } else {
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(3400, easing = LinearEasing), RepeatMode.Restart),
            label = "移近波光",
        )
    }
    val startVal = startCm.takeIf { it > 25f } ?: 67f
    val progress = if (currentCm > 0f) {
        ((startVal - currentCm) / (startVal - 10f).coerceAtLeast(10f)).coerceIn(0.05f, 1f)
    } else 0.08f
    val speedText = when {
        speedCmPerSec == null || speedCmPerSec <= 0.15f -> "速度提示：缓慢平稳靠近（建议约 1–2 cm/s）"
        speedCmPerSec > 3.2f -> "速度偏快：约 ${"%.1f".format(speedCmPerSec)} cm/s，请慢一点"
        else -> "速度正常：约 ${"%.1f".format(speedCmPerSec)} cm/s"
    }
    val isClean = style == SproutBackdropStyle.CLEAN
    val textColor = if (isClean) EyeGamePalette.Sprout.arrowDark else EyeGamePalette.Sprout.cloudWhite
    val extra = rememberStageExtra()

    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = maxOf(346.dp, size + 236.dp) + extra)
                .clip(EyeShapes.hero)
                .border(1.dp, LocalEyeColors.current.cardBorder.copy(alpha = 0.45f), EyeShapes.hero),
        ) {
            Canvas(
                Modifier
                    .matchParentSize()
                    .semantics { contentDescription = "慢慢靠近移近法小芽湖景舞台" },
            ) {
                val cw = this.size.width
                val ch = this.size.height
                drawSproutBackdrop(style = style, wave = wave)
                val sproutC = Offset(cw * 0.50f, ch * 0.67f)
                drawLakeIslandSprout(
                    center = sproutC,
                    scale = (min(cw, ch) / 310f).coerceIn(0.8f, 1.2f),
                    growthStage = 2,
                    wilted = false,
                    wave = wave,
                    bounce = 1f,
                )
                // 随移近缓缓飘向小芽的亮晶晶送水微珠
                for (i in 0..2) {
                    val t = (wave + i * 0.33f) % 1f
                    val dx = (i - 1) * 28.dp.toPx() * (1f - t)
                    val dy = -48.dp.toPx() * (1f - t) - 10.dp.toPx()
                    drawGlowingWaterDrop(Offset(sproutC.x + dx, sproutC.y + dy), (5.5f * (1f - t * 0.3f)).dp.toPx())
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 顶部深蓝玻璃态滑轨条（还原图 4）
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(EyeShapes.medium)
                        .background(EyeGamePalette.Sprout.arrowDark.copy(alpha = 0.52f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EyeText("起始 ${startVal.toInt()}cm", style = typography.caption, color = EyeGamePalette.Sprout.cloudWhite)
                        EyeText(
                            if (currentCm > 0f) "当前 ${currentCm.toInt()}cm" else "测距中…",
                            style = typography.chip,
                            color = EyeGamePalette.Sprout.leafBright,
                        )
                        Box(
                            Modifier
                                .clip(EyeShapes.chip)
                                .background(EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.18f))
                                .clickable(role = Role.Button) {
                                    style = if (isClean) SproutBackdropStyle.SCENIC else SproutBackdropStyle.CLEAN
                                }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            EyeText(if (isClean) "切湖景" else "切清爽", style = typography.caption, color = EyeGamePalette.Sprout.cloudWhite)
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.25f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress)
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(EyeGamePalette.Sprout.leafBright),
                        )
                    }
                }

                EyeText("第二关 | ${startVal.toInt()}cm 慢慢靠近 · $roundLabel", style = typography.subtitle, color = textColor, textAlign = TextAlign.Center)

                // 中央对焦角框 + 精确物理尺寸 E 字视标
                Box(
                    Modifier
                        .testTag("optotype-surround")
                        .size(maxOf(114.dp, size + 34.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val cw = this.size.width
                        val ch = this.size.height
                        val c = if (isClean) EyeGamePalette.Sprout.arrowDark.copy(alpha = 0.45f)
                        else EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.80f)
                        val len = 18.dp.toPx()
                        val st = 2.5.dp.toPx()
                        drawLine(c, Offset(0f, 0f), Offset(len, 0f), st, StrokeCap.Round)
                        drawLine(c, Offset(0f, 0f), Offset(0f, len), st, StrokeCap.Round)
                        drawLine(c, Offset(cw, 0f), Offset(cw - len, 0f), st, StrokeCap.Round)
                        drawLine(c, Offset(cw, 0f), Offset(cw, len), st, StrokeCap.Round)
                        drawLine(c, Offset(0f, ch), Offset(len, ch), st, StrokeCap.Round)
                        drawLine(c, Offset(0f, ch), Offset(0f, ch - len), st, StrokeCap.Round)
                        drawLine(c, Offset(cw, ch), Offset(cw - len, ch), st, StrokeCap.Round)
                        drawLine(c, Offset(cw, ch), Offset(cw, ch - len), st, StrokeCap.Round)
                    }
                    if (visible) {
                        TumblingE(
                            direction = 0,
                            size = size,
                            color = if (isClean) EyeGamePalette.Sprout.optotypeInk else EyeGamePalette.Sprout.cloudWhite,
                            modifier = Modifier.testTag("answer-optotype"),
                        )
                    }
                }

                EyeText(
                    "像给小芽送水一样，慢慢把手机靠近脸",
                    style = typography.caption,
                    color = textColor.copy(alpha = 0.94f),
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(64.dp))

                EyeText("努力后仍看不清，就停下", style = typography.caption, color = EyeGamePalette.Sprout.cloudWhite)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .clip(EyeShapes.chip)
                        .background(EyeGamePalette.Sprout.cardCream)
                        .clickable(role = Role.Button, onClick = onBlur)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    EyeText("持续模糊，点这里", style = typography.subtitle, color = EyeGamePalette.Sprout.cardSoilBase)
                }
                Box(
                    Modifier
                        .clip(EyeShapes.chip)
                        .background(EyeGamePalette.Sprout.cardSoilBase.copy(alpha = 0.80f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    EyeText(
                        speedText,
                        style = typography.caption,
                        color = if ((speedCmPerSec ?: 0f) > 3.2f) EyeGamePalette.Sprout.daisyCenter else EyeGamePalette.Sprout.leafBright,
                    )
                }
            }
        }
        if (canStillClear) {
            EyeButton("仍看得清", EyeButtonRole.SECONDARY, onStillClear, Modifier.fillMaxWidth())
        }
    }
}

/**
 * 干眼/老视自测完成 · 小芽开花 / 缺水结果卡（高度还原甲方 PPT 图 5）：
 * 阳光鲜花草坪 + 盛开 12 瓣白雏菊（或缺水略垂小芽）立在奶白结果便签顶部 + 深棕花盆底座与分享标语。
 */
@Composable
internal fun SproutBloomResultCard(
    testTitle: String,
    report: ResultReport,
    modifier: Modifier = Modifier,
) {
    val typography = LocalEyeTypography.current
    val wilted = !report.valid || report.level == ScreeningRiskLevel.HIGH
    val bloomed = report.valid && (report.level == ScreeningRiskLevel.LOW || report.level == ScreeningRiskLevel.UNCERTAIN)
    val growthStage = when {
        bloomed -> 4
        wilted -> 1
        else -> 3
    }
    val growthMessage = when {
        bloomed -> "小芽喝饱水，开出了健康小雏菊！"
        wilted -> "小芽有点缺水萎蔫，记得多眨眼与及时护眼休息"
        else -> "小芽正在积蓄水分，平时注意定时远眺与润眼"
    }

    Box(
        modifier
            .fillMaxWidth()
            .clip(EyeShapes.hero)
            .border(1.dp, LocalEyeColors.current.cardBorder.copy(alpha = 0.45f), EyeShapes.hero),
    ) {
        Canvas(
            Modifier
                .matchParentSize()
                .semantics { contentDescription = "$testTitle，$growthMessage" },
        ) {
            drawSproutBackdrop(style = SproutBackdropStyle.SCENIC, wave = 0.3f)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EyeText(testTitle, style = typography.title, color = EyeGamePalette.Sprout.cloudWhite, textAlign = TextAlign.Center)
            Canvas(Modifier.size(width = 152.dp, height = 104.dp)) {
                drawPottedSprout(
                    center = Offset(size.width * 0.5f, size.height * 0.96f),
                    scale = 1.12f,
                    growthStage = growthStage,
                    wilted = wilted,
                    wave = 0.25f,
                    showPot = false,
                    bounce = 1f,
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(EyeShapes.large)
                    .background(EyeGamePalette.Sprout.cardSoilBase),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, top = 10.dp)
                        .clip(EyeShapes.medium)
                        .background(EyeGamePalette.Sprout.cardCream)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    EyeText(
                        "${report.badge} · ${report.title}",
                        style = typography.subtitle,
                        color = EyeGamePalette.Sprout.cardSoilBase,
                        textAlign = TextAlign.Center,
                    )
                    EyeText(
                        growthMessage,
                        style = typography.bodySecondary,
                        color = EyeGamePalette.Sprout.soilDark,
                        textAlign = TextAlign.Center,
                    )
                }
                EyeText(
                    "我完成了${testTitle.removeSuffix("完成")}，你也来试试？",
                    style = typography.caption,
                    color = EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.92f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                )
            }
        }
    }
}

// ============================================================================
// 高保真自然风景 + 立体花盆小芽 + 水滴精灵「小露」矢量绘制原语
// ============================================================================

private fun DrawScope.drawSproutBackdrop(style: SproutBackdropStyle, wave: Float) {
    val w = size.width
    val h = size.height
    if (style == SproutBackdropStyle.CLEAN) {
        // 底部右图清爽高对比风：上方 66% 纯净浅蓝晴空，下方柔和远山与静谧碧湖
        drawRect(
            brush = Brush.verticalGradient(
                0f to EyeGamePalette.Sprout.skyCleanTop,
                0.62f to EyeGamePalette.Sprout.skyCleanMid,
                1f to EyeGamePalette.Sprout.lakeTop,
            ),
        )
        val horizon = h * 0.68f
        val farHill = Path().apply {
            moveTo(0f, horizon)
            quadraticTo(w * 0.18f, horizon - h * 0.08f, w * 0.36f, horizon)
            quadraticTo(w * 0.65f, horizon - h * 0.04f, w * 0.84f, horizon - h * 0.09f)
            lineTo(w, horizon - h * 0.02f)
            lineTo(w, horizon)
            close()
        }
        drawPath(farHill, EyeGamePalette.Sprout.mountainFar.copy(alpha = 0.45f))
        drawRect(
            brush = Brush.verticalGradient(
                0f to EyeGamePalette.Sprout.lakeTop.copy(alpha = 0.72f),
                1f to EyeGamePalette.Sprout.lakeDeep.copy(alpha = 0.88f),
                startY = horizon,
                endY = h,
            ),
            topLeft = Offset(0f, horizon),
            size = Size(w, h - horizon),
        )
        for (i in 0..2) {
            val ry = horizon + (h - horizon) * (0.28f + i * 0.24f)
            val rx = w * (0.5f + sin((wave + i * 0.3f) * 2f * PI.toFloat()) * 0.03f)
            drawLine(
                EyeGamePalette.Sprout.lakeRipple.copy(alpha = 0.48f - i * 0.1f),
                Offset(rx - w * (0.26f - i * 0.05f), ry),
                Offset(rx + w * (0.26f - i * 0.05f), ry),
                strokeWidth = 2f,
                cap = StrokeCap.Round,
            )
        }
        return
    }

    // 完整阳光湖景鲜花草地风（高度还原甲方 PPT 主图）
    drawRect(
        brush = Brush.verticalGradient(
            0f to EyeGamePalette.Sprout.skySceneTop,
            0.46f to EyeGamePalette.Sprout.skySceneMid,
            1f to EyeGamePalette.Sprout.lakeTop,
        ),
    )
    // 左上角暖阳与柔和斜射阳光光束
    drawCircle(
        brush = Brush.radialGradient(
            0f to EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.45f),
            0.5f to EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.12f),
            1f to Color.Transparent,
            center = Offset(w * 0.12f, h * 0.08f),
            radius = min(w, h) * 0.55f,
        ),
        radius = min(w, h) * 0.55f,
        center = Offset(w * 0.12f, h * 0.08f),
    )
    drawSoftCloud(Offset(w * 0.14f, h * 0.26f), min(w, h) * 0.082f)
    drawSoftCloud(Offset(w * 0.86f, h * 0.27f), min(w, h) * 0.072f)

    val horizon = h * 0.56f
    val farMountain = Path().apply {
        moveTo(0f, horizon)
        lineTo(0f, horizon - h * 0.11f)
        quadraticTo(w * 0.22f, horizon - h * 0.25f, w * 0.46f, horizon - h * 0.06f)
        quadraticTo(w * 0.74f, horizon - h * 0.27f, w, horizon - h * 0.09f)
        lineTo(w, horizon)
        close()
    }
    drawPath(farMountain, EyeGamePalette.Sprout.mountainFar.copy(alpha = 0.82f))
    val nearMountain = Path().apply {
        moveTo(0f, horizon)
        quadraticTo(w * 0.16f, horizon - h * 0.15f, w * 0.36f, horizon)
        lineTo(w * 0.64f, horizon)
        quadraticTo(w * 0.85f, horizon - h * 0.17f, w, horizon - h * 0.04f)
        lineTo(w, horizon)
        close()
    }
    drawPath(nearMountain, EyeGamePalette.Sprout.mountainNear.copy(alpha = 0.88f))

    // 碧波湖面与闪烁波光
    val grassTop = h * 0.71f
    drawRect(
        brush = Brush.verticalGradient(
            0f to EyeGamePalette.Sprout.lakeTop,
            1f to EyeGamePalette.Sprout.lakeDeep,
            startY = horizon,
            endY = grassTop,
        ),
        topLeft = Offset(0f, horizon),
        size = Size(w, grassTop - horizon),
    )
    for (r in 0..2) {
        val shimmer = sin((wave + r * 0.28f) * 2f * PI.toFloat()) * w * 0.025f
        val ry = horizon + (grassTop - horizon) * (0.28f + r * 0.26f)
        val half = w * (0.18f - r * 0.03f)
        drawLine(
            EyeGamePalette.Sprout.lakeRipple.copy(alpha = 0.50f - r * 0.12f),
            Offset(w * 0.5f - half + shimmer, ry),
            Offset(w * 0.5f + half + shimmer, ry),
            strokeWidth = 2.2f,
            cap = StrokeCap.Round,
        )
    }

    // 阳光绿坡草坪
    val meadow = Path().apply {
        moveTo(0f, h)
        lineTo(0f, grassTop)
        quadraticTo(w * 0.5f, grassTop - h * 0.07f, w, grassTop)
        lineTo(w, h)
        close()
    }
    drawPath(
        meadow,
        brush = Brush.verticalGradient(
            0f to EyeGamePalette.Sprout.leafBright.copy(alpha = 0.92f),
            0.35f to EyeGamePalette.Sprout.grassLight,
            1f to EyeGamePalette.Sprout.grassDeep,
            startY = grassTop - h * 0.05f,
            endY = h,
        ),
    )
    // 左侧天然小石头 + 草坪点点小白野花（还原图 3 & 图 5 细节）
    drawOval(
        EyeGamePalette.Sprout.mountainFar.copy(alpha = 0.72f),
        topLeft = Offset(w * 0.08f, grassTop + h * 0.02f),
        size = Size(w * 0.12f, h * 0.045f),
    )
    val flowerSpots = listOf(
        Offset(w * 0.14f, h * 0.85f),
        Offset(w * 0.24f, h * 0.91f),
        Offset(w * 0.66f, h * 0.90f),
        Offset(w * 0.88f, h * 0.84f),
    )
    flowerSpots.forEach { pt ->
        drawCircle(EyeGamePalette.Sprout.daisyPetal.copy(alpha = 0.92f), 4.2f, pt)
        drawCircle(EyeGamePalette.Sprout.daisyCenter, 1.8f, pt)
    }
}

private fun DrawScope.drawSoftCloud(center: Offset, r: Float) {
    val c = EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.52f)
    drawCircle(c, r * 0.72f, Offset(center.x - r * 0.65f, center.y + r * 0.12f))
    drawCircle(c, r, center)
    drawCircle(c, r * 0.68f, Offset(center.x + r * 0.70f, center.y + r * 0.14f))
}

private fun DrawScope.drawLakeIslandSprout(
    center: Offset,
    scale: Float,
    growthStage: Int,
    wilted: Boolean,
    wave: Float,
    bounce: Float = 1f,
) {
    val s = scale
    for (i in 0..1) {
        val rw = (46f + i * 22f + sin(wave * 2f * PI.toFloat()) * 3f) * s
        val rh = (12f + i * 5f) * s
        drawOval(
            EyeGamePalette.Sprout.lakeRipple.copy(alpha = 0.55f - i * 0.20f),
            topLeft = Offset(center.x - rw * 0.5f, center.y - rh * 0.5f + 4f * s),
            size = Size(rw, rh),
            style = Stroke(2f),
        )
    }
    drawOval(
        EyeGamePalette.Sprout.soilDark,
        topLeft = Offset(center.x - 22f * s, center.y - 6f * s),
        size = Size(44f * s, 14f * s),
    )
    drawSproutPlant(Offset(center.x, center.y - 2f * s), s * bounce, growthStage, wilted, wave)
}

private fun DrawScope.drawPottedSprout(
    center: Offset,
    scale: Float,
    growthStage: Int,
    wilted: Boolean,
    wave: Float,
    showPot: Boolean = true,
    bounce: Float = 1f,
) {
    val s = scale
    if (showPot) {
        // 草地上的花盆投影
        drawOval(
            EyeGamePalette.Sprout.soilDark.copy(alpha = 0.30f),
            topLeft = Offset(center.x - 26f * s, center.y - 5f * s),
            size = Size(52f * s, 11f * s),
        )
        val potW = 48f * s
        val potH = 36f * s
        val potTop = center.y - potH
        val body = Path().apply {
            moveTo(center.x - potW * 0.44f, potTop + 6f * s)
            lineTo(center.x + potW * 0.44f, potTop + 6f * s)
            lineTo(center.x + potW * 0.33f, center.y)
            lineTo(center.x - potW * 0.33f, center.y)
            close()
        }
        drawPath(
            body,
            brush = Brush.horizontalGradient(
                0f to EyeGamePalette.Sprout.potRim,
                0.55f to EyeGamePalette.Sprout.potBody,
                1f to EyeGamePalette.Sprout.soilDark,
                startX = center.x - potW * 0.44f,
                endX = center.x + potW * 0.44f,
            ),
        )
        drawRoundRect(
            brush = Brush.horizontalGradient(
                0f to EyeGamePalette.Sprout.potRim,
                0.65f to EyeGamePalette.Sprout.potBody,
                1f to EyeGamePalette.Sprout.soilDark,
                startX = center.x - potW * 0.52f,
                endX = center.x + potW * 0.52f,
            ),
            topLeft = Offset(center.x - potW * 0.52f, potTop),
            size = Size(potW * 1.04f, 10.5f * s),
            cornerRadius = CornerRadius(4.5f * s),
        )
        drawOval(
            EyeGamePalette.Sprout.soilDark,
            topLeft = Offset(center.x - potW * 0.42f, potTop - 3f * s),
            size = Size(potW * 0.84f, 8.5f * s),
        )
        drawSproutPlant(Offset(center.x, potTop), s * bounce, growthStage, wilted, wave)
    } else {
        drawOval(
            EyeGamePalette.Sprout.soilDark,
            topLeft = Offset(center.x - 26f * s, center.y - 8f * s),
            size = Size(52f * s, 14f * s),
        )
        drawSproutPlant(Offset(center.x, center.y - 4f * s), s * bounce, growthStage, wilted, wave)
    }
}

private fun DrawScope.drawSproutPlant(
    base: Offset,
    s: Float,
    growthStage: Int,
    wilted: Boolean,
    wave: Float,
) {
    val sway = sin(wave * 2f * PI.toFloat()) * 2.6f * s
    val stemH = (24f + growthStage * 7.8f) * s
    val bend = if (wilted) 11f * s else sway
    val top = Offset(base.x + bend, base.y - stemH * (if (wilted) 0.78f else 1f))
    val leafPrimary = if (wilted) EyeGamePalette.Sprout.leafWilt else EyeGamePalette.Sprout.leafBright
    val leafSecondary = if (wilted) EyeGamePalette.Sprout.leafWilt.copy(alpha = 0.82f) else EyeGamePalette.Sprout.leafMid

    val stem = Path().apply {
        moveTo(base.x, base.y)
        quadraticTo(base.x + bend * 0.35f, base.y - stemH * 0.5f, top.x, top.y)
    }
    drawPath(stem, leafSecondary, style = Stroke(width = 4.4f * s, cap = StrokeCap.Round))

    val leafLen = (17f + growthStage * 2.4f) * s
    val droop = if (wilted) 9f * s else -8.5f * s
    drawGlossyLeaf(Offset(top.x, top.y + 4f * s), -leafLen, droop, 9.5f * s, leafPrimary, leafSecondary)
    drawGlossyLeaf(Offset(top.x, top.y + 4f * s), leafLen, droop, 9.5f * s, leafSecondary, leafPrimary)

    if (growthStage >= 2) {
        val mid = Offset(base.x + bend * 0.45f, base.y - stemH * 0.46f)
        drawGlossyLeaf(mid, -leafLen * 0.85f, droop * 0.6f, 7.8f * s, leafSecondary, leafPrimary)
        drawGlossyLeaf(mid, leafLen * 0.85f, droop * 0.6f, 7.8f * s, leafPrimary, leafSecondary)
    }

    if (growthStage == 3 && !wilted) {
        drawCircle(EyeGamePalette.Sprout.daisyCenter, 7f * s, Offset(top.x, top.y - 4f * s))
        drawCircle(EyeGamePalette.Sprout.daisyPetal, 3.8f * s, Offset(top.x, top.y - 7.5f * s))
    } else if (growthStage >= 4 && !wilted) {
        val flowerC = Offset(top.x, top.y - 9f * s)
        val petalCount = 12
        val petalDist = 12.5f * s
        for (i in 0 until petalCount) {
            val a = i * (2f * PI.toFloat() / petalCount)
            val pc = Offset(flowerC.x + cos(a) * petalDist, flowerC.y + sin(a) * petalDist)
            drawCircle(EyeGamePalette.Sprout.daisyPetal, 5.8f * s, pc)
        }
        drawCircle(EyeGamePalette.Sprout.daisyCenter, 8.8f * s, flowerC)
        drawCircle(EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.55f), 2.8f * s, Offset(flowerC.x - 2.6f * s, flowerC.y - 2.6f * s))
    }
}

private fun DrawScope.drawGlossyLeaf(anchor: Offset, dx: Float, dy: Float, width: Float, fill: Color, vein: Color) {
    val tip = Offset(anchor.x + dx, anchor.y + dy)
    val leaf = Path().apply {
        moveTo(anchor.x, anchor.y)
        quadraticTo(anchor.x + dx * 0.5f, anchor.y + dy - width, tip.x, tip.y)
        quadraticTo(anchor.x + dx * 0.5f, anchor.y + width * 0.62f, anchor.x, anchor.y)
        close()
    }
    drawPath(leaf, fill)
    drawLine(
        vein.copy(alpha = 0.45f),
        anchor,
        Offset(anchor.x + dx * 0.72f, anchor.y + dy * 0.72f),
        strokeWidth = 1.6f,
        cap = StrokeCap.Round,
    )
}

/** 立体萌萌水滴精灵「小露」（带腮红、迷你小手脚与浇水欢呼倾斜动作）。 */
private fun DrawScope.drawDropletMascot(center: Offset, radius: Float, cheer: Float = 0f) {
    // 地面柔影
    drawOval(
        EyeGamePalette.Sprout.soilDark.copy(alpha = 0.25f * (1f - cheer * 0.4f)),
        topLeft = Offset(center.x - radius * 0.72f, center.y + radius * 0.92f),
        size = Size(radius * 1.44f, radius * 0.32f),
    )
    rotate(degrees = -cheer * 16f, pivot = center) {
        // 小手与小脚
        drawCircle(EyeGamePalette.Sprout.dropDeep, radius * 0.20f, Offset(center.x - radius * 0.84f, center.y + radius * 0.34f - cheer * radius * 0.35f))
        drawCircle(EyeGamePalette.Sprout.dropDeep, radius * 0.20f, Offset(center.x + radius * 0.84f, center.y + radius * 0.34f - cheer * radius * 0.15f))
        drawOval(
            EyeGamePalette.Sprout.dropDeep,
            topLeft = Offset(center.x - radius * 0.46f, center.y + radius * 0.80f),
            size = Size(radius * 0.34f, radius * 0.24f),
        )
        drawOval(
            EyeGamePalette.Sprout.dropDeep,
            topLeft = Offset(center.x + radius * 0.12f, center.y + radius * 0.80f),
            size = Size(radius * 0.34f, radius * 0.24f),
        )
        drawGlowingWaterDrop(center, radius)
        // 洁白圆润小脸蛋 + 粉色腮红 + 开心笑眼
        drawOval(
            EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.95f),
            topLeft = Offset(center.x - radius * 0.58f, center.y - radius * 0.06f),
            size = Size(radius * 1.16f, radius * 0.80f),
        )
        drawCircle(EyeGamePalette.Theater.dryCoral.copy(alpha = 0.38f), radius * 0.12f, Offset(center.x - radius * 0.34f, center.y + radius * 0.38f))
        drawCircle(EyeGamePalette.Theater.dryCoral.copy(alpha = 0.38f), radius * 0.12f, Offset(center.x + radius * 0.34f, center.y + radius * 0.38f))
        drawCircle(EyeGamePalette.Sprout.arrowDark, radius * 0.11f, Offset(center.x - radius * 0.22f, center.y + radius * 0.25f))
        drawCircle(EyeGamePalette.Sprout.arrowDark, radius * 0.11f, Offset(center.x + radius * 0.22f, center.y + radius * 0.25f))
        drawCircle(EyeGamePalette.Sprout.cloudWhite, radius * 0.04f, Offset(center.x - radius * 0.25f, center.y + radius * 0.22f))
        drawCircle(EyeGamePalette.Sprout.cloudWhite, radius * 0.04f, Offset(center.x + radius * 0.19f, center.y + radius * 0.22f))
        val smile = Path().apply {
            moveTo(center.x - radius * 0.16f, center.y + radius * 0.42f)
            quadraticTo(center.x, center.y + radius * (0.58f + cheer * 0.10f), center.x + radius * 0.16f, center.y + radius * 0.42f)
        }
        drawPath(smile, EyeGamePalette.Sprout.arrowDark, style = Stroke(radius * 0.085f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawGlowingWaterDrop(center: Offset, radius: Float) {
    val drop = Path().apply {
        moveTo(center.x, center.y - radius * 1.25f)
        cubicTo(
            center.x + radius * 0.48f, center.y - radius * 0.42f,
            center.x + radius * 0.96f, center.y + radius * 0.18f,
            center.x + radius * 0.90f, center.y + radius * 0.56f,
        )
        cubicTo(
            center.x + radius * 0.85f, center.y + radius * 1.05f,
            center.x - radius * 0.85f, center.y + radius * 1.05f,
            center.x - radius * 0.90f, center.y + radius * 0.56f,
        )
        cubicTo(
            center.x - radius * 0.96f, center.y + radius * 0.18f,
            center.x - radius * 0.48f, center.y - radius * 0.42f,
            center.x, center.y - radius * 1.25f,
        )
        close()
    }
    drawPath(
        drop,
        brush = Brush.verticalGradient(
            0f to EyeGamePalette.Sprout.dropLight,
            0.52f to EyeGamePalette.Sprout.dropBlue,
            1f to EyeGamePalette.Sprout.dropDeep,
            startY = center.y - radius * 1.2f,
            endY = center.y + radius,
        ),
    )
    drawCircle(
        EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.85f),
        radius = radius * 0.19f,
        center = Offset(center.x - radius * 0.28f, center.y + radius * 0.22f),
    )
}

private fun DrawScope.drawFourPointStar(center: Offset, radius: Float, color: Color) {
    if (radius <= 0.5f) return
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
