package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.theme.rememberReducedMotion
import kotlinx.coroutines.launch

/** 小瞳说的一句话；[action] 为可执行时气泡末尾的按钮字（整个气泡可点）。 */
data class MascotTip(val text: String, val action: String? = null, val payload: Any? = null)

/** 气泡尾巴在底边的位置：偏左 / 居中 / 偏右，指向下面的小瞳。 */
enum class BubbleTail { START, CENTER, END }

/** 每句话占用的时长：前约 60% 显示，之后收起，下一句再弹出。 */
private const val TIP_MS = 9_000

/**
 * 对话气泡：白底圆角 + 细边框 + 底边小尾巴，文字用正文字号（不朗读）。[onClick] 不为空时整个气泡可点。
 * 苹果端 MascotBubble 同一样式。
 */
@Composable
fun MascotBubble(
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    tail: BubbleTail = BubbleTail.CENTER,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    val tailHeight = 9.dp
    Row(
        modifier
            .drawBehind {
                val th = tailHeight.toPx()
                val r = 16.dp.toPx()
                val body = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height - th, CornerRadius(r))) }
                val x = when (tail) {
                    BubbleTail.START -> r + th
                    BubbleTail.CENTER -> size.width / 2
                    BubbleTail.END -> size.width - r - th
                }
                val pointer = Path().apply {
                    moveTo(x - th, size.height - th - 1f); lineTo(x + th * 0.15f, size.height); lineTo(x + th * 0.9f, size.height - th - 1f); close()
                }
                val shape = Path.combine(PathOperation.Union, body, pointer)
                drawPath(shape, colors.panelBackground)
                drawPath(shape, colors.borderSubtle, style = Stroke(1.dp.toPx()))
            }
            .then(if (onClick != null) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                role = Role.Button) { haptics.tap(); onClick() } else Modifier)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 9.dp + tailHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EyeText(text, Modifier.weight(1f, fill = false), style = typography.body, color = colors.textPrimary, maxLines = 2)
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            EyeText("$action ›", style = typography.body, color = colors.primary, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/**
 * 会说话的小瞳：上方气泡区依次弹出 [tips]（每句约 5 秒，隔几秒换下一句），下方是会动、能互动的小瞳。
 * 点一下小瞳立刻换下一句；点可执行的气泡交给 [onTip]。系统「移除动画」时气泡直接出现、不做弹跳。
 */
@Composable
fun XiaoTongTalk(
    mood: MascotMood,
    tips: List<MascotTip>,
    modifier: Modifier = Modifier,
    headScale: Float = 0.7f,
    wilt: Float? = null,
    bubbleHeight: Dp = 68.dp,
    tail: BubbleTail = BubbleTail.CENTER,
    mascotFraction: Float = 1f,
    onTip: (MascotTip) -> Unit = {},
) {
    val reduced = rememberReducedMotion()
    val scope = rememberCoroutineScope()
    val count = tips.size.coerceAtLeast(1)
    val cycle = rememberInfiniteTransition(label = "小瞳气泡").animateFloat(
        0f, count.toFloat(), infiniteRepeatable(tween(TIP_MS * count, easing = LinearEasing)), label = "气泡轮换")
    val autoIndex by remember(count) { derivedStateOf { cycle.value.toInt().coerceIn(0, count - 1) } }
    val autoShown by remember { derivedStateOf { cycle.value % 1f in 0.04f..0.62f } }
    var manual by remember { mutableIntStateOf(0) }
    val manualProgress = remember { Animatable(1f) }
    val manualShown = manualProgress.value < 1f
    val index = if (manualShown) manual else autoIndex
    val tip = tips.getOrNull(index % count)
    val showing = tip != null && (manualShown || autoShown || reduced)
    val pop by animateFloatAsState(if (showing) 1f else 0f,
        if (showing && !reduced) spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow) else tween(180), label = "气泡弹出")
    val transformOrigin = TransformOrigin(when (tail) { BubbleTail.START -> 0.15f; BubbleTail.CENTER -> 0.5f; BubbleTail.END -> 0.85f }, 1f)
    // 小瞳只占右侧 [mascotFraction] 宽时，气泡仍可用整行宽度，尾巴对准小瞳的头
    BoxWithConstraints(modifier) {
    val tailInset = if (mascotFraction < 1f) (maxWidth * mascotFraction / 2 - 26.dp).coerceAtLeast(0.dp) else 0.dp
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(bubbleHeight).padding(end = tailInset), contentAlignment = when (tail) {
            BubbleTail.START -> Alignment.BottomStart
            BubbleTail.CENTER -> Alignment.BottomCenter
            BubbleTail.END -> Alignment.BottomEnd
        }) {
            if (tip != null && pop > 0.01f) MascotBubble(tip.text,
                Modifier.graphicsLayer { scaleX = 0.6f + 0.4f * pop; scaleY = 0.6f + 0.4f * pop; alpha = pop.coerceIn(0f, 1f); this.transformOrigin = transformOrigin },
                action = tip.action, tail = tail, onClick = tip.action?.let { { onTip(tip) } })
        }
        XiaoTong(mood, Modifier.fillMaxWidth(mascotFraction).weight(1f).align(Alignment.End), headScale = headScale, wilt = wilt, onTap = {
            if (tips.isNotEmpty()) {
                manual = (index + 1) % count
                scope.launch { manualProgress.snapTo(0f); manualProgress.animateTo(1f, tween(4_500, easing = LinearEasing)) }
            }
        })
    }
    }
}
