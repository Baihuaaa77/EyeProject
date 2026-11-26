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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
 * - [CLEAN]（默认）：清爽高对比晴空背景 + 纯黑 E 字视标 + 下方湖水萌芽（对齐甲方参考图底部右图）。
 * - [SCENIC]：完整蓝天湖泊草地盆栽背景 + 纯白 E 字视标 + 小水滴精灵（对齐甲方参考图主图）。
 */
internal enum class SproutBackdropStyle { CLEAN, SCENIC }

/**
 * 甲方新版「小芽浇水挑战」准备与距离校准卡（对应参考图 1 & 图 2）：
 * 展示湖畔陶土盆小芽与蓝水滴精灵、40 cm 校准标尺，以及「可手持，也可放立桌上」提示。
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
            label = "水滴浮动",
        )
    }
    val inRange = faceDetected && distanceCm > 0f && abs(distanceCm - targetCm) <= toleranceCm
    val distanceLabel = when {
        !faceDetected || distanceCm <= 0f -> "等待相机识别距离"
        inRange -> "当前距离：${distanceCm.toInt()} cm · 保持稳定"
        distanceCm < targetCm - toleranceCm -> "当前距离：${distanceCm.toInt()} cm · 请微调远一点至 ${targetCm.toInt()} cm"
        else -> "当前距离：${distanceCm.toInt()} cm · 请微调近一点至 ${targetCm.toInt()} cm"
    }
    val sliderRatio = if (faceDetected && distanceCm > 0f) {
        ((distanceCm - 20f) / 40f).coerceIn(0.04f, 0.96f)
    } else 0.5f

    Column(
        modifier
            .fillMaxWidth()
            .clip(EyeShapes.hero)
            .background(EyeGamePalette.Sprout.skySceneTop)
            .border(1.dp, colors.cardBorder.copy(alpha = 0.45f), EyeShapes.hero),
    ) {
        // 上半部：湖光山色小花盆幼芽 + 水滴精灵主视觉
        Box(
            Modifier
                .fillMaxWidth()
                .height(172.dp),
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "小芽浇水挑战自然湖景主视觉" },
            ) {
                drawSproutBackdrop(style = SproutBackdropStyle.SCENIC, wave = bob)
                drawPottedSprout(
                    center = Offset(size.width * 0.44f, size.height * 0.78f),
                    scale = min(size.width, size.height) / 170f,
                    growthStage = 1,
                    wilted = false,
                    wave = bob,
                )
                drawDropletMascot(
                    center = Offset(size.width * 0.74f, size.height * 0.72f + sin(bob * 2f * PI.toFloat()) * 4f),
                    radius = min(size.width, size.height) * 0.14f,
                )
                // 漂浮的两颗小水珠
                drawFallingWaterDrop(Offset(size.width * 0.63f, size.height * 0.38f + sin(bob * 2f * PI.toFloat()) * 5f), 8.dp.toPx())
                drawFallingWaterDrop(Offset(size.width * 0.70f, size.height * 0.50f - sin(bob * 2f * PI.toFloat()) * 4f), 6.dp.toPx())
            }
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp, start = 14.dp, end = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                EyeText(title, style = typography.title, color = EyeGamePalette.Sprout.cloudWhite, textAlign = TextAlign.Center)
                EyeText(subtitle, style = typography.caption, color = EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.92f), textAlign = TextAlign.Center)
            }
        }
        // 下半部：40cm 实时校准距离标尺 + 可手持/可放立桌上
        Column(
            Modifier
                .fillMaxWidth()
                .background(EyeGamePalette.Sprout.cardSoilBase.copy(alpha = 0.92f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                EyeText("标准测试距离：${targetCm.toInt()} cm", style = typography.chip, color = EyeGamePalette.Sprout.cloudWhite)
                EyeText("可手持，也可放立桌上", style = typography.caption, color = EyeGamePalette.Sprout.dropLight)
            }
            // 实时距离进度条
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
                        .background(if (inRange) EyeGamePalette.Sprout.badgeStable else EyeGamePalette.Sprout.dropBlue),
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

/**
 * 甲方新版「小芽浇水挑战」看 E 作答主舞台（干眼 + 老视 40/67/50/33 cm 共用）：
 * - 严格按批注「不保留底图的上下左右」：上下左右四个方向箭头直接紧贴环绕在中央 E 字视标四周。
 * - 答对 = 水滴落下浇灌，小芽节节长高长叶直至开花；答错/跳过 = 水滴蒸发，小芽轻晃提示。
 * - 默认清爽高对比晴空黑字 E，右上角支持一键切换「清爽 / 湖景」背景。
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
    modifier: Modifier = Modifier,
    onAnswer: (Int) -> Unit,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val reducedMotion = rememberReducedMotion()
    var style by rememberSaveable { mutableStateOf(SproutBackdropStyle.CLEAN) }

    // 答对触发一次浇水降落动画，答错触发水滴蒸发上升动画（有界 Animatable，不阻塞测试 Idle）
    val waterAnim = remember { Animatable(1f) }
    val evapAnim = remember { Animatable(1f) }
    LaunchedEffect(correctCount) {
        if (correctCount > 0 && !reducedMotion) {
            waterAnim.snapTo(0f)
            waterAnim.animateTo(1f, tween(620, easing = FastOutSlowInEasing))
        } else waterAnim.snapTo(1f)
    }
    LaunchedEffect(wrongCount) {
        if (wrongCount > 0 && !reducedMotion) {
            evapAnim.snapTo(0f)
            evapAnim.animateTo(1f, tween(680, easing = LinearEasing))
        } else evapAnim.snapTo(1f)
    }
    val infinite = rememberInfiniteTransition(label = "浇水微动")
    val wave by if (reducedMotion) remember { mutableStateOf(0.25f) } else {
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(3400, easing = LinearEasing), RepeatMode.Restart),
            label = "水波风动",
        )
    }

    val growthStage = when {
        correctCount >= 12 -> 4 // 绽放健康小雏菊
        correctCount >= 8 -> 3  // 含苞待放
        correctCount >= 4 -> 2  // 繁茂四叶
        correctCount >= 1 -> 1  // 舒展双叶
        else -> 0               // 破土幼芽
    }
    val wilted = lastAnswerCorrect == false && wrongCount > correctCount
    val statusText = when {
        lastAnswerCorrect == true -> "浇水成功！已浇 $correctCount 滴，小芽正在长大"
        lastAnswerCorrect == false -> "水滴蒸发了，看清 E 开口方向再给小芽浇水"
        else -> "答对浇水让小芽长大 · 可手持或放立桌上"
    }
    val isClean = style == SproutBackdropStyle.CLEAN
    val optotypeColor = if (isClean) EyeGamePalette.Sprout.optotypeInk else EyeGamePalette.Sprout.cloudWhite
    val headlineColor = if (isClean) EyeGamePalette.Sprout.arrowDark else EyeGamePalette.Sprout.cloudWhite
    val extra = rememberStageExtra()

    Column(
        modifier
            .fillMaxWidth()
            .testTag("answer-pad"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 整合式湖景/晴空浇水舞台卡片
        Box(
            Modifier
                .testTag("optotype-surround")
                .fillMaxWidth()
                .heightIn(min = maxOf(310.dp, size + 236.dp) + extra)
                .clip(EyeShapes.hero)
                .border(1.dp, colors.cardBorder.copy(alpha = 0.45f), EyeShapes.hero),
        ) {
            // 背景与底部浇水小芽矢量画布
            Canvas(
                Modifier
                    .matchParentSize()
                    .semantics { contentDescription = "小芽浇水视标挑战舞台，已浇水 $correctCount 滴" },
            ) {
                val cw = this.size.width
                val ch = this.size.height
                drawSproutBackdrop(style = style, wave = wave)
                val sproutCenter = Offset(cw * (if (isClean) 0.50f else 0.44f), ch * 0.87f)
                val sproutScale = (min(cw, ch) / 310f).coerceIn(0.8f, 1.35f)
                if (isClean) {
                    drawLakeIslandSprout(sproutCenter, sproutScale, growthStage, wilted, wave)
                } else {
                    drawPottedSprout(sproutCenter, sproutScale, growthStage, wilted, wave)
                    drawDropletMascot(
                        Offset(cw * 0.74f, ch * 0.84f + sin(wave * 2f * PI.toFloat()) * 3f),
                        radius = min(cw, ch) * 0.082f,
                    )
                }
                // 答对：蓝水滴从上方落到小芽并泛起水花
                val wt = waterAnim.value
                if (wt < 0.99f) {
                    val dropY = ch * 0.62f + wt * (sproutCenter.y - ch * 0.68f)
                    drawFallingWaterDrop(Offset(sproutCenter.x, dropY), 10.dp.toPx() * (1f - wt * 0.25f))
                    if (wt > 0.65f) {
                        val splashT = (wt - 0.65f) / 0.35f
                        drawCircle(
                            EyeGamePalette.Sprout.dropLight.copy(alpha = (1f - splashT) * 0.85f),
                            radius = (12.dp.toPx() + splashT * 26.dp.toPx()),
                            center = Offset(sproutCenter.x, sproutCenter.y - 6.dp.toPx()),
                            style = Stroke(2.5f),
                        )
                    }
                } else if (isClean) {
                    // 清爽模式常驻一颗晶莹悬垂水滴在幼芽正上方（还原底部右图）
                    drawFallingWaterDrop(
                        Offset(sproutCenter.x, sproutCenter.y - 50.dp.toPx() + sin(wave * 2f * PI.toFloat()) * 3f),
                        8.5.dp.toPx(),
                    )
                }
                // 答错：水滴蒸发升腾淡雾
                val et = evapAnim.value
                if (et < 0.99f) {
                    for (i in -1..1) {
                        val vx = sproutCenter.x + i * 16.dp.toPx() + sin(et * 6f + i) * 4f
                        val vy = sproutCenter.y - 36.dp.toPx() - et * 36.dp.toPx()
                        drawCircle(
                            EyeGamePalette.Sprout.cloudWhite.copy(alpha = (1f - et) * 0.78f),
                            radius = (4.dp.toPx() + et * 5.dp.toPx()),
                            center = Offset(vx, vy),
                            style = Stroke(2f),
                        )
                    }
                }
            }

            // 前景内容：顶部关卡与背景切换 + 中央 E 与紧贴四周的四方向大箭头 + 底部浇水反馈胶囊
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 顶栏：左侧关卡/浇水徽标 + 右侧「清爽 / 湖景」背景切换
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .clip(EyeShapes.chip)
                                .background(EyeGamePalette.Sprout.badgeStable)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            EyeText(stageBadge, style = typography.chip, color = EyeGamePalette.Sprout.cloudWhite)
                        }
                        Box(
                            Modifier
                                .clip(EyeShapes.chip)
                                .background(EyeGamePalette.Sprout.cardSoilBase.copy(alpha = 0.72f))
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                        ) {
                            EyeText("浇水 $correctCount 滴", style = typography.caption, color = EyeGamePalette.Sprout.dropLight)
                        }
                    }
                    Box(
                        Modifier
                            .clip(EyeShapes.chip)
                            .background(EyeGamePalette.Sprout.cardSoilBase.copy(alpha = 0.72f))
                            .border(1.dp, EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.35f), EyeShapes.chip)
                            .clickable(role = Role.Button) {
                                style = if (style == SproutBackdropStyle.CLEAN) SproutBackdropStyle.SCENIC else SproutBackdropStyle.CLEAN
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
                    EyeText(stageHeadline, style = typography.subtitle, color = headlineColor, textAlign = TextAlign.Center)
                }

                // 中央视标 + 紧贴 E 四周的上下左右四个方向箭头（无底部四向按钮行！）
                val centerBox = maxOf(82.dp, size + 20.dp)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SurroundingArrowButton(3, "向上", visible, isClean, gesture) { onAnswer(3) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SurroundingArrowButton(2, "向左", visible, isClean, gesture) { onAnswer(2) }
                        Box(
                            Modifier
                                .size(centerBox)
                                .clip(CircleShape)
                                .background(
                                    if (isClean) Color.Transparent
                                    else EyeGamePalette.Sprout.arrowDark.copy(alpha = if (visible) 0.22f else 0.10f),
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
                                EyeText("方向?", style = typography.caption, color = headlineColor.copy(alpha = 0.65f))
                            }
                        }
                        SurroundingArrowButton(0, "向右", visible, isClean, gesture) { onAnswer(0) }
                    }
                    SurroundingArrowButton(1, "向下", visible, isClean, gesture) { onAnswer(1) }
                }

                if (belowStimulus != null) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { belowStimulus() }
                }

                // 给下方湖景小芽与贴底状态胶囊留足展示空间
                Spacer(Modifier.height(104.dp))
            }

            // 底部轻量浇水/蒸发状态胶囊：始终贴紧舞台最底端，不随大屏拉伸漂浮在湖面中间
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp, start = 12.dp, end = 12.dp)
                    .clip(EyeShapes.chip)
                    .background(EyeGamePalette.Sprout.cardSoilBase.copy(alpha = 0.80f))
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

        // 不保留底部的上下左右按键，仅保留次要「看不清 · 跳过」胶囊以保证无法辨认时可跳过
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

/** 紧贴 E 字四周的单个方向箭头按键（0=右 1=下 2=左 3=上，兼容点击与手势锁定进度）。 */
@Composable
private fun SurroundingArrowButton(
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
    val scale by animateFloatAsState(if (pressed && enabled) 0.90f else 1f, EyeMotion.pressSpring(), label = "箭头缩放")
    val ringProgress by animateFloatAsState(if (holding) gesture.progress.coerceIn(0f, 1f) else 0f, tween(EyeMotion.SHORT), label = "箭头手势")
    val alpha by animateFloatAsState(if (enabled) 1f else 0.42f, tween(EyeMotion.SHORT), label = "箭头可用")
    val fill by animateColorAsState(
        when {
            holding -> colors.primary
            pressed && enabled -> EyeGamePalette.Sprout.dropBlue
            cleanBackdrop -> EyeGamePalette.Sprout.arrowSurface.copy(alpha = 0.94f)
            else -> EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.92f)
        },
        tween(EyeMotion.SHORT),
        label = "箭头底色",
    )
    val arrowColor = when {
        holding || (pressed && enabled) -> EyeGamePalette.Sprout.cloudWhite
        else -> EyeGamePalette.Sprout.arrowDark
    }

    Box(
        Modifier
            .size(52.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .clip(CircleShape)
            .background(fill)
            .border(
                width = if (holding) 2.5.dp else 1.5.dp,
                color = if (holding) colors.primary else EyeGamePalette.Sprout.arrowDark.copy(alpha = 0.22f),
                shape = CircleShape,
            )
            .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (holding) {
            Canvas(Modifier.fillMaxSize().padding(2.dp)) {
                drawArc(
                    color = EyeGamePalette.Sprout.cloudWhite,
                    startAngle = -90f,
                    sweepAngle = 360f * ringProgress.coerceAtLeast(0.12f),
                    useCenter = false,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        // 基准箭头朝右：0=右 1=下 2=左 3=上
        Canvas(Modifier.size(26.dp).rotate(direction * 90f)) {
            val w = size.width
            val h = size.height
            val p = Path().apply {
                moveTo(w * 0.12f, h * 0.50f); lineTo(w * 0.84f, h * 0.50f)
                moveTo(w * 0.52f, h * 0.18f); lineTo(w * 0.84f, h * 0.50f); lineTo(w * 0.52f, h * 0.82f)
            }
            drawPath(p, color = arrowColor, style = Stroke(w * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * 甲方新版「第二关 | 慢慢靠近」移近法互动舞台（对应参考图 4）：
 * 顶部起始/当前距离进度条 + 中央带对焦框的精确尺寸 E + 湖边青苔小芽 + 「持续模糊，点这里」+ 实时移近速度。
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
        ((startVal - currentCm) / (startVal - 10f).coerceAtLeast(10f)).coerceIn(0f, 1f)
    } else 0f
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
                .heightIn(min = maxOf(320.dp, size + 220.dp) + extra)
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
                drawLakeIslandSprout(
                    center = Offset(cw * 0.50f, ch * 0.67f),
                    scale = (min(cw, ch) / 315f).coerceIn(0.75f, 1.18f),
                    growthStage = 2,
                    wilted = false,
                    wave = wave,
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 顶部距离进度条 + 切换背景
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EyeText("起始 ${startVal.toInt()} cm", style = typography.caption, color = textColor)
                    EyeText(
                        if (currentCm > 0f) "当前 ${currentCm.toInt()} cm" else "测距中…",
                        style = typography.chip,
                        color = textColor,
                    )
                    Box(
                        Modifier
                            .clip(EyeShapes.chip)
                            .background(EyeGamePalette.Sprout.cardSoilBase.copy(alpha = 0.72f))
                            .clickable(role = Role.Button) {
                                style = if (isClean) SproutBackdropStyle.SCENIC else SproutBackdropStyle.CLEAN
                            }
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    ) {
                        EyeText(if (isClean) "切湖景" else "切清爽", style = typography.caption, color = EyeGamePalette.Sprout.cloudWhite)
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(EyeGamePalette.Sprout.cardSoilBase.copy(alpha = 0.35f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceAtLeast(0.05f))
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(EyeGamePalette.Sprout.leafBright),
                    )
                }

                EyeText("慢慢靠近 · $roundLabel", style = typography.subtitle, color = textColor, textAlign = TextAlign.Center)

                // 中央对焦角框 + 精确物理尺寸 E 字视标
                Box(
                    Modifier
                        .testTag("optotype-surround")
                        .size(maxOf(116.dp, size + 36.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val cw = this.size.width
                        val ch = this.size.height
                        val c = if (isClean) EyeGamePalette.Sprout.arrowDark.copy(alpha = 0.45f)
                        else EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.75f)
                        val len = 18.dp.toPx()
                        val st = 2.5.dp.toPx()
                        // 四角取景框
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
                    color = textColor.copy(alpha = 0.92f),
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(62.dp))

                // 底部按钮与实时速度提示
                EyeText(
                    "努力后仍看不清，就停下",
                    style = typography.caption,
                    color = EyeGamePalette.Sprout.cloudWhite,
                )
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
                    EyeText(
                        "持续模糊，点这里",
                        style = typography.subtitle,
                        color = EyeGamePalette.Sprout.cardSoilBase,
                    )
                }
                Box(
                    Modifier
                        .clip(EyeShapes.chip)
                        .background(EyeGamePalette.Sprout.cardSoilBase.copy(alpha = 0.78f))
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
 * 甲方新版「自测完成 · 小芽开花 / 缺水结果卡」（对应参考图 5）：
 * 全部通关且低/不明确风险 → 盛开洁白小雏菊绿植；中度关注 → 含苞提醒补水；高风险/无效 → 缺水略蔫小芽。
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EyeText(testTitle, style = typography.title, color = EyeGamePalette.Sprout.cloudWhite, textAlign = TextAlign.Center)
            // 立在结果便签顶部的小雏菊/绿芽矢量绘制区
            Canvas(Modifier.size(width = 140.dp, height = 96.dp)) {
                drawPottedSprout(
                    center = Offset(size.width * 0.5f, size.height * 0.96f),
                    scale = 1.05f,
                    growthStage = growthStage,
                    wilted = wilted,
                    wave = 0.25f,
                    showPot = false,
                )
            }
            // 奶白便签 + 深棕花盆底（完全对齐参考图 5）
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
                    color = EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.90f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                )
            }
        }
    }
}

// ============================================================================
// 矢量风景与小芽水滴绘制原语（全部读取 EyeGamePalette.Sprout 语义令牌）
// ============================================================================

private fun DrawScope.drawSproutBackdrop(style: SproutBackdropStyle, wave: Float) {
    val w = size.width
    val h = size.height
    if (style == SproutBackdropStyle.CLEAN) {
        // 底部右图清爽高对比风：上部 65% 纯净极浅晴空，下部 35% 柔和远山与静谧碧湖
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
        // 湖面轻柔涟漪
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

    // 完整湖景草地风（对齐上方主图）：蓝天白云 + 远近翠山 + 碧波湖水 + 阳光草坪
    drawRect(
        brush = Brush.verticalGradient(
            0f to EyeGamePalette.Sprout.skySceneTop,
            0.48f to EyeGamePalette.Sprout.skySceneMid,
            1f to EyeGamePalette.Sprout.lakeTop,
        ),
    )
    // 柔白云朵（偏左右边角，避让顶部居中白色标题）
    drawSoftCloud(Offset(w * 0.14f, h * 0.25f), min(w, h) * 0.08f)
    drawSoftCloud(Offset(w * 0.87f, h * 0.27f), min(w, h) * 0.07f)
    val horizon = h * 0.58f
    // 远山
    val farMountain = Path().apply {
        moveTo(0f, horizon)
        lineTo(0f, horizon - h * 0.10f)
        quadraticTo(w * 0.22f, horizon - h * 0.24f, w * 0.46f, horizon - h * 0.06f)
        quadraticTo(w * 0.74f, horizon - h * 0.26f, w, horizon - h * 0.08f)
        lineTo(w, horizon)
        close()
    }
    drawPath(farMountain, EyeGamePalette.Sprout.mountainFar.copy(alpha = 0.78f))
    val nearMountain = Path().apply {
        moveTo(0f, horizon)
        quadraticTo(w * 0.16f, horizon - h * 0.14f, w * 0.34f, horizon)
        lineTo(w * 0.66f, horizon)
        quadraticTo(w * 0.85f, horizon - h * 0.16f, w, horizon - h * 0.03f)
        lineTo(w, horizon)
        close()
    }
    drawPath(nearMountain, EyeGamePalette.Sprout.mountainNear.copy(alpha = 0.85f))
    // 碧湖
    val grassTop = h * 0.74f
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
    val shimmer = sin(wave * 2f * PI.toFloat()) * w * 0.02f
    drawLine(
        EyeGamePalette.Sprout.lakeRipple.copy(alpha = 0.52f),
        Offset(w * 0.34f + shimmer, (horizon + grassTop) * 0.5f),
        Offset(w * 0.66f + shimmer, (horizon + grassTop) * 0.5f),
        strokeWidth = 2.4f,
        cap = StrokeCap.Round,
    )
    // 阳光草坪坡地
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
            0f to EyeGamePalette.Sprout.grassLight,
            1f to EyeGamePalette.Sprout.grassDeep,
            startY = grassTop - h * 0.05f,
            endY = h,
        ),
    )
}

private fun DrawScope.drawSoftCloud(center: Offset, r: Float) {
    val c = EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.55f)
    drawCircle(c, r * 0.72f, Offset(center.x - r * 0.65f, center.y + r * 0.12f))
    drawCircle(c, r, center)
    drawCircle(c, r * 0.68f, Offset(center.x + r * 0.70f, center.y + r * 0.14f))
}

/** 绘制清爽版湖心小土丘萌芽（还原参考图底部右图）。 */
private fun DrawScope.drawLakeIslandSprout(
    center: Offset,
    scale: Float,
    growthStage: Int,
    wilted: Boolean,
    wave: Float,
) {
    val s = scale
    // 同心湖面水波纹
    for (i in 0..1) {
        val rw = (44f + i * 20f + sin(wave * 2f * PI.toFloat()) * 3f) * s
        val rh = (12f + i * 5f) * s
        drawOval(
            EyeGamePalette.Sprout.lakeRipple.copy(alpha = 0.52f - i * 0.20f),
            topLeft = Offset(center.x - rw * 0.5f, center.y - rh * 0.5f + 4f * s),
            size = Size(rw, rh),
            style = Stroke(2f),
        )
    }
    // 小泥土岛
    drawOval(
        EyeGamePalette.Sprout.soilDark,
        topLeft = Offset(center.x - 20f * s, center.y - 6f * s),
        size = Size(40f * s, 14f * s),
    )
    drawSproutPlant(Offset(center.x, center.y - 2f * s), s, growthStage, wilted, wave)
}

/** 绘制陶土花盆 + 随答对次数逐阶成长的小芽/绿植/小雏菊。 */
private fun DrawScope.drawPottedSprout(
    center: Offset,
    scale: Float,
    growthStage: Int,
    wilted: Boolean,
    wave: Float,
    showPot: Boolean = true,
) {
    val s = scale
    if (showPot) {
        val potW = 46f * s
        val potH = 34f * s
        val potTop = center.y - potH
        val body = Path().apply {
            moveTo(center.x - potW * 0.44f, potTop + 6f * s)
            lineTo(center.x + potW * 0.44f, potTop + 6f * s)
            lineTo(center.x + potW * 0.34f, center.y)
            lineTo(center.x - potW * 0.34f, center.y)
            close()
        }
        drawPath(body, EyeGamePalette.Sprout.potBody)
        drawRoundRect(
            EyeGamePalette.Sprout.potRim,
            topLeft = Offset(center.x - potW * 0.52f, potTop),
            size = Size(potW * 1.04f, 10f * s),
            cornerRadius = CornerRadius(4f * s),
        )
        drawOval(
            EyeGamePalette.Sprout.soilDark,
            topLeft = Offset(center.x - potW * 0.42f, potTop - 3f * s),
            size = Size(potW * 0.84f, 8f * s),
        )
        drawSproutPlant(Offset(center.x, potTop), s, growthStage, wilted, wave)
    } else {
        drawOval(
            EyeGamePalette.Sprout.soilDark,
            topLeft = Offset(center.x - 24f * s, center.y - 8f * s),
            size = Size(48f * s, 14f * s),
        )
        drawSproutPlant(Offset(center.x, center.y - 4f * s), s, growthStage, wilted, wave)
    }
}

/** 绘制随成长阶数 (0..4) 拔高长叶并开花的小芽本体。 */
private fun DrawScope.drawSproutPlant(
    base: Offset,
    s: Float,
    growthStage: Int,
    wilted: Boolean,
    wave: Float,
) {
    val sway = sin(wave * 2f * PI.toFloat()) * 2.5f * s
    val stemH = (24f + growthStage * 7.5f) * s
    val bend = if (wilted) 10f * s else sway
    val top = Offset(base.x + bend, base.y - stemH * (if (wilted) 0.78f else 1f))
    val leafPrimary = if (wilted) EyeGamePalette.Sprout.leafWilt else EyeGamePalette.Sprout.leafBright
    val leafSecondary = if (wilted) EyeGamePalette.Sprout.leafWilt.copy(alpha = 0.82f) else EyeGamePalette.Sprout.leafMid

    val stem = Path().apply {
        moveTo(base.x, base.y)
        quadraticTo(base.x + bend * 0.35f, base.y - stemH * 0.5f, top.x, top.y)
    }
    drawPath(stem, leafSecondary, style = Stroke(width = 4.2f * s, cap = StrokeCap.Round))

    // 主对生双叶
    val leafLen = (16f + growthStage * 2.2f) * s
    val droop = if (wilted) 9f * s else -8f * s
    drawLeaf(Offset(top.x, top.y + 4f * s), -leafLen, droop, 9f * s, leafPrimary)
    drawLeaf(Offset(top.x, top.y + 4f * s), leafLen, droop, 9f * s, leafSecondary)

    // 第 2 阶起增加下层饱满侧叶
    if (growthStage >= 2) {
        val mid = Offset(base.x + bend * 0.45f, base.y - stemH * 0.46f)
        drawLeaf(mid, -leafLen * 0.85f, droop * 0.6f, 7.5f * s, leafSecondary)
        drawLeaf(mid, leafLen * 0.85f, droop * 0.6f, 7.5f * s, leafPrimary)
    }

    // 第 3 阶含苞 / 第 4 阶绽放明媚小雏菊（对齐参考图 5）
    if (growthStage == 3 && !wilted) {
        drawCircle(EyeGamePalette.Sprout.daisyCenter, 6.5f * s, Offset(top.x, top.y - 4f * s))
        drawCircle(EyeGamePalette.Sprout.daisyPetal, 3.5f * s, Offset(top.x, top.y - 7f * s))
    } else if (growthStage >= 4 && !wilted) {
        val flowerC = Offset(top.x, top.y - 8f * s)
        val petalCount = 10
        val petalDist = 11.5f * s
        for (i in 0 until petalCount) {
            val a = i * (2f * PI.toFloat() / petalCount)
            val pc = Offset(flowerC.x + cos(a) * petalDist, flowerC.y + sin(a) * petalDist)
            drawCircle(EyeGamePalette.Sprout.daisyPetal, 5.8f * s, pc)
        }
        drawCircle(EyeGamePalette.Sprout.daisyCenter, 8.2f * s, flowerC)
    }
}

private fun DrawScope.drawLeaf(anchor: Offset, dx: Float, dy: Float, width: Float, color: Color) {
    val tip = Offset(anchor.x + dx, anchor.y + dy)
    val leaf = Path().apply {
        moveTo(anchor.x, anchor.y)
        quadraticTo(anchor.x + dx * 0.5f, anchor.y + dy - width, tip.x, tip.y)
        quadraticTo(anchor.x + dx * 0.5f, anchor.y + width * 0.6f, anchor.x, anchor.y)
        close()
    }
    drawPath(leaf, color)
}

/** 萌萌水滴精灵「小露」（对齐参考图 1 & 图 3 右下角水滴角色）。 */
private fun DrawScope.drawDropletMascot(center: Offset, radius: Float) {
    drawFallingWaterDrop(center, radius)
    // 洁白小脸窗与开心表情
    drawOval(
        EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.92f),
        topLeft = Offset(center.x - radius * 0.54f, center.y - radius * 0.08f),
        size = Size(radius * 1.08f, radius * 0.78f),
    )
    drawCircle(EyeGamePalette.Sprout.arrowDark, radius * 0.11f, Offset(center.x - radius * 0.22f, center.y + radius * 0.24f))
    drawCircle(EyeGamePalette.Sprout.arrowDark, radius * 0.11f, Offset(center.x + radius * 0.22f, center.y + radius * 0.24f))
    val smile = Path().apply {
        moveTo(center.x - radius * 0.16f, center.y + radius * 0.42f)
        quadraticTo(center.x, center.y + radius * 0.58f, center.x + radius * 0.16f, center.y + radius * 0.42f)
    }
    drawPath(smile, EyeGamePalette.Sprout.arrowDark, style = Stroke(radius * 0.08f, cap = StrokeCap.Round))
}

private fun DrawScope.drawFallingWaterDrop(center: Offset, radius: Float) {
    val drop = Path().apply {
        moveTo(center.x, center.y - radius * 1.25f)
        cubicTo(
            center.x + radius * 0.48f, center.y - radius * 0.42f,
            center.x + radius * 0.95f, center.y + radius * 0.18f,
            center.x + radius * 0.90f, center.y + radius * 0.56f,
        )
        cubicTo(
            center.x + radius * 0.85f, center.y + radius * 1.05f,
            center.x - radius * 0.85f, center.y + radius * 1.05f,
            center.x - radius * 0.90f, center.y + radius * 0.56f,
        )
        cubicTo(
            center.x - radius * 0.95f, center.y + radius * 0.18f,
            center.x - radius * 0.48f, center.y - radius * 0.42f,
            center.x, center.y - radius * 1.25f,
        )
        close()
    }
    drawPath(
        drop,
        brush = Brush.verticalGradient(
            0f to EyeGamePalette.Sprout.dropLight,
            0.55f to EyeGamePalette.Sprout.dropBlue,
            1f to EyeGamePalette.Sprout.dropDeep,
            startY = center.y - radius * 1.2f,
            endY = center.y + radius,
        ),
    )
    drawCircle(
        EyeGamePalette.Sprout.cloudWhite.copy(alpha = 0.78f),
        radius = radius * 0.18f,
        center = Offset(center.x - radius * 0.28f, center.y + radius * 0.24f),
    )
}
