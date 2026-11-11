package com.zkyjss.eyeproject.feature.services

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.*
import com.zkyjss.eyeproject.core.ui.component.*
import kotlin.math.PI
import kotlin.math.sin

private fun GuidePose.image() = when (this) {
    GuidePose.OPEN -> R.drawable.eyecho_guide_open
    GuidePose.BLINK -> R.drawable.eyecho_guide_blink
    GuidePose.LEFT -> R.drawable.eyecho_guide_left
    GuidePose.RIGHT -> R.drawable.eyecho_guide_right
    GuidePose.HELPER_OPEN -> R.drawable.eyecho_guide_helper_open
    GuidePose.HELPER_LEFT -> R.drawable.eyecho_guide_helper_left
    GuidePose.HELPER_RIGHT -> R.drawable.eyecho_guide_helper_right
}

/** 测试中的静态准备提示也使用同一套透明人物，避免混用卡通图。 */
@Composable
internal fun EyechoGuidePortrait(pose: GuidePose, description: String, modifier: Modifier = Modifier) {
    Image(painterResource(pose.image()), description, modifier, contentScale = ContentScale.Fit)
}

/** 爱科完整图片与操作示意分栏；不裁切头顶、手势或标识，也不镜像左右眼。 */
@Composable
internal fun GuideStage(scene: GuideScene, phase: () -> Float, description: String, modifier: Modifier = Modifier) {
    val colors = LocalEyeColors.current
    val type = LocalEyeTypography.current
    val pose by remember(scene, phase) { derivedStateOf { guidePoseAt(scene.motion, phase()) } }
    val caption by remember(scene, phase) { derivedStateOf { guideDiagramLabel(scene.motion, phase()) } }
    val portraitOnly = scene.motion in listOf(GuideMotion.COVER, GuideMotion.COVER_SELF, GuideMotion.BLINK, GuideMotion.OPEN)
    Column(modifier.clip(EyeShapes.large).background(colors.surfaceVariant).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EyechoGuidePortrait(pose, "爱科示范：${guidePoseLabel(pose)}。$description",
                Modifier.weight(if (portraitOnly) 1f else 2f).fillMaxHeight().clip(EyeShapes.medium))
            if (!portraitOnly) Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                GuideDiagram(scene.motion, phase, Modifier.weight(1f).fillMaxWidth())
                EyeText(caption, style = type.chip, color = colors.textPrimary, textAlign = TextAlign.Center)
            }
        }
        if (portraitOnly || scene.motion == GuideMotion.NEAR) EyeText(guidePoseLabel(pose),
            style = type.chip, color = colors.textPrimary, textAlign = TextAlign.Center)
    }
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
    GuideMotion.FIXATE -> "看圆点 · 头不动"
    GuideMotion.FOLLOW -> "跟随圆点 · 头不动"
    GuideMotion.FAR -> "越过手机看 6 米外"
    else -> ""
}

/** 保留手机、视标和目标的简图。这里的 E 只讲解操作，正式视标尺寸算法不变。 */
@Composable
private fun GuideDiagram(motion: GuideMotion, phase: () -> Float, modifier: Modifier) {
    val colors = LocalEyeColors.current
    Canvas(modifier.semantics { contentDescription = "操作示意" }) {
        val p = phase().coerceIn(0f, 1f)
        val unit = minOf(size.width / 100f, size.height / 170f)
        fun at(x: Float, y: Float) = Offset(size.width / 2 + (x - 50) * unit, size.height / 2 + (y - 85) * unit)
        val approach = when (motion) {
            GuideMotion.MOVE -> if (p < 0.15f) 0f else if (p < 0.68f) (p - 0.15f) / 0.53f else if (p < 0.8f) 1f else (1 - p) / 0.2f
            GuideMotion.SWITCH -> if (p < 0.15f || p > 0.7f) 0f else if (p < 0.3f) (p - 0.15f) / 0.15f else if (p < 0.55f) 1f else (0.7f - p) / 0.15f
            GuideMotion.CURVE -> if (p < 0.3f) 0f else if (p < 0.38f) (p - 0.3f) / 0.08f * 0.6f else if (p < 0.64f) 0.6f else if (p < 0.72f) 0.6f + (p - 0.64f) / 0.08f * 0.4f else 1f
            else -> 0.5f
        }
        val phoneHeight = if (motion == GuideMotion.FAR) 76f else 106f + approach * 36f
        val (tl, screen) = drawPhone(colors, at(50f, if (motion == GuideMotion.FAR) 116f else 85f), phoneHeight * unit)
        val target = Offset(tl.x + screen.width / 2, tl.y + screen.height * 0.3f)
        when (motion) {
            GuideMotion.FAR -> {
                drawFixationTarget(colors, at(50f, 27f), 22f * unit)
                drawLine(colors.primary, at(50f, 46f), at(50f, 66f), 2f * unit)
            }
            GuideMotion.POSTURE -> {
                drawCircle(colors.success, screen.width * 0.28f, Offset(target.x, tl.y + screen.height / 2), style = Stroke(2f * unit))
            }
            GuideMotion.FIXATE, GuideMotion.FOLLOW -> {
                val twoPi = 2 * PI.toFloat()
                val relative = when {
                    p < 0.36f -> Offset(0.32f * sin(p / 0.36f * twoPi), 0f)
                    p < 0.72f -> Offset(0f, 0.32f * sin((p - 0.36f) / 0.36f * twoPi))
                    else -> {
                        val t = (p - 0.72f) / 0.28f
                        val wave = if (t < 0.5f) t * 4 - 1 else 3 - t * 4
                        Offset(0.32f * wave, -0.32f * wave)
                    }
                }
                val dot = if (motion == GuideMotion.FOLLOW) Offset(tl.x + screen.width * (0.5f + relative.x),
                    tl.y + screen.height * (0.5f + relative.y)) else target
                drawFixationTarget(colors, dot, 17f * unit)
            }
            else -> {
                val step = (p * 5).toInt().coerceIn(0, 4)
                val direction = listOf(0, 3, 2, 1, 0)[step]
                val eSize = screen.width * if (motion == GuideMotion.ACUITY && step == 1) 0.26f else 0.36f
                drawTumblingE(colors.ink, target, eSize, direction)
                // 四个方向只作示意，不拦截触摸。
                for ((index, offset) in listOf(Offset(1f, 0f), Offset(0f, 1f), Offset(-1f, 0f), Offset(0f, -1f)).withIndex()) {
                    val center = Offset(target.x + offset.x * screen.width * 0.32f, tl.y + screen.height * 0.7f + offset.y * screen.width * 0.32f)
                    drawCircle(if (index == direction) colors.primary else colors.borderSubtle, screen.width * 0.12f, center)
                    val ink = if (index == direction) colors.white else colors.ink
                    val length = screen.width * 0.055f
                    val tip = center + offset * length
                    val normal = Offset(-offset.y, offset.x) * length
                    drawLine(ink, center - offset * length, tip, unit)
                    drawLine(ink, tip, center + normal, unit)
                    drawLine(ink, tip, center - normal, unit)
                }
            }
        }
    }
}
