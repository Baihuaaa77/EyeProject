package com.zkyjss.eyeproject.feature.services

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LightEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.ui.component.EyeText

/** 刺激区固定白底、与深浅主题无关，其上的视标环与校准点统一取亮色主色令牌保证对比。 */
internal val StageAccent get() = LightEyeColors.primary

private val KeySize = 64.dp
private val KeyGap = 10.dp

/**
 * 视标单独位于上方白色刺激区；下方为十字作答键：「跳过」居中，上下左右四键环绕。
 * 保留实测物理尺寸和每题的作答门控；[belowStimulus] 紧贴刺激区下方（如单题限时条）。
 */
@Composable
internal fun OptotypeAnswerPad(direction: Int, size: Dp, visible: Boolean, belowStimulus: @Composable () -> Unit = {},
    onAnswer: (Int) -> Unit) {
    val colors = LocalEyeColors.current
    Column(Modifier.fillMaxWidth().testTag("answer-pad"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 白色刺激区吸收内容区剩余高度，视标居中；细边界定出刺激区，浅色主题下也不与卡片混在一起。
        val extra = rememberStageExtra()
        Box(
            Modifier.fillMaxWidth().height(maxOf(152.dp, ringDiameter(size) + 24.dp) + extra).clip(EyeShapes.large)
                .background(colors.white).border(1.dp, colors.borderSubtle, EyeShapes.large),
            contentAlignment = Alignment.Center,
        ) { FramedOptotype(direction, size, visible) }
        belowStimulus()
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(KeyGap)) {
            DirectionButton(3, "向上", visible) { onAnswer(3) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                DirectionButton(2, "向左", visible) { onAnswer(2) }
                SkipButton(visible) { onAnswer(-1) }
                DirectionButton(0, "向右", visible) { onAnswer(0) }
            }
            DirectionButton(1, "向下", visible) { onAnswer(1) }
        }
    }
}

/** 圆形方向键：绘制的箭头不依赖字体字形，按下时轻缩并加深底色。 */
@Composable
private fun DirectionButton(direction: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalEyeColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.9f else 1f, EyeMotion.pressSpring(), label = "方向键缩放")
    val fill by animateColorAsState(colors.primary.copy(alpha = if (pressed && enabled) 0.24f else 0.1f),
        tween(EyeMotion.SHORT), label = "方向键底色")
    val alpha by animateFloatAsState(if (enabled) 1f else 0.35f, tween(EyeMotion.SHORT), label = "方向键可用")
    Box(
        Modifier.size(KeySize)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .clip(CircleShape).background(fill)
            .border(1.dp, colors.primary.copy(alpha = 0.3f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        // 基准箭头朝右，按视标方向编码旋转：0=右 1=下 2=左 3=上。
        Canvas(Modifier.size(24.dp).rotate(direction * 90f)) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w * 0.12f, h * 0.5f); lineTo(w * 0.86f, h * 0.5f)
                moveTo(w * 0.54f, h * 0.18f); lineTo(w * 0.86f, h * 0.5f); lineTo(w * 0.54f, h * 0.82f)
            }
            drawPath(path, colors.primary, style = Stroke(w * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** 中央「跳过」：与方向键同尺寸的圆键，浅底区分为次要动作；提交当前题无法辨认（-1）。 */
@Composable
private fun SkipButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalEyeColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.9f else 1f, EyeMotion.pressSpring(), label = "跳过缩放")
    val alpha by animateFloatAsState(if (enabled) 1f else 0.4f, tween(EyeMotion.SHORT), label = "跳过可用")
    Box(
        Modifier.size(KeySize)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .clip(CircleShape)
            .background(if (pressed && enabled) colors.primary.copy(alpha = 0.12f) else colors.surfaceVariant)
            .border(1.dp, colors.primary.copy(alpha = 0.24f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        EyeText("跳过", style = LocalEyeTypography.current.body, color = colors.textPrimary, textAlign = TextAlign.Center)
    }
}

/** 留白环按视标对角线取径，大视标的四角也完整落在环内。 */
internal fun ringDiameter(size: Dp): Dp = maxOf(96.dp, size * 1.42f + 16.dp)

/** 固定留白环，视标仍按原物理像素尺寸绘制；等待下一题时环变淡，出题时恢复。 */
@Composable
internal fun FramedOptotype(direction: Int, size: Dp, visible: Boolean = true) {
    val colors = LocalEyeColors.current
    val ring by animateColorAsState(StageAccent.copy(alpha = if (visible) 0.72f else 0.2f),
        tween(EyeMotion.SHORT), label = "视标环")
    Box(Modifier.size(ringDiameter(size)).border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center) {
        if (visible) TumblingE(direction, size, colors.black, Modifier.testTag("answer-optotype"))
    }
}
