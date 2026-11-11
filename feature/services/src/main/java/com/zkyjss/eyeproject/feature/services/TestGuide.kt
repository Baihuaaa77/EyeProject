package com.zkyjss.eyeproject.feature.services

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.*
import com.zkyjss.eyeproject.core.ui.component.*

/**
 * 测试前的方法演示卡：爱科数字人分镜演示 + 与动画同步高亮的分镜步骤 + 文字提示。
 * 示范画布只出现在准备阶段，不参与正式刺激呈现或计时。
 * 演示自动播放；系统「移除动画」时停在关键帧并展示全部步骤。
 */
@Composable
internal fun TestGuide(motion: GuideMotion, title: String, cue: String, onReady: (() -> Unit)? = null) {
    val colors = LocalEyeColors.current
    val type = LocalEyeTypography.current
    val scene = remember(motion) { guideScene(motion) }
    val reduced = rememberReducedMotion()
    // 无限过渡：测试时钟能正确处理；进度只在绘制阶段读取，整卡不会每帧重组
    val live = rememberInfiniteTransition(label = "方法演示").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(scene.cycleMs, easing = LinearEasing)),
        label = "演示进度"
    )
    val phaseProvider: () -> Float = { if (reduced) scene.keyframe else live.value }
    val activeBeat by remember(scene, reduced) {
        derivedStateOf { scene.beats.indexOfLast { phaseProvider() >= it.start }.coerceAtLeast(0) }
    }

    EyeSectionCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EyeText(title, style = type.title, color = colors.textPrimary)

            }
            GuideStage(scene, phaseProvider, cue, Modifier.fillMaxWidth().height(EyeControlSizes.guideStageHeight))
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
            else -> colors.textSecondary
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

/** 入口与测试准备页使用同一组爱科分镜；正式刺激不经过此组件。 */
@Composable
internal fun GuideIllustration(motion: GuideMotion, description: String, modifier: Modifier = Modifier) {
    val scene = remember(motion) { guideScene(motion) }
    val reduced = rememberReducedMotion()
    val live = rememberInfiniteTransition(label = "爱科演示").animateFloat(
        0f, 1f, infiniteRepeatable(tween(scene.cycleMs, easing = LinearEasing)), label = "演示进度"
    )
    GuideStage(scene, { if (reduced) scene.keyframe else live.value }, description,
        modifier.fillMaxWidth().height(EyeControlSizes.guideStageHeight))
}

@Composable
internal fun GuideFrame(motion: GuideMotion, phase: () -> Float, description: String, modifier: Modifier = Modifier) {
    val scene = remember(motion) { guideScene(motion) }
    val reduced = rememberReducedMotion()
    GuideStage(scene, { if (reduced) scene.keyframe else phase() }, description, modifier)
}
