package com.zkyjss.eyeproject.feature.services

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.theme.rememberReducedMotion
import com.zkyjss.eyeproject.core.ui.component.EyeText
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 眼—屏距离所处区间。颜色约定：太近 = 红、太远 = 蓝、合适 = 绿、未知 = 灰；
 * MOVE_TO = 刚换了目标距离、还在移过去的路上（主色），提示「请移动到 xx 厘米处」而不是太近 / 太远。
 */
internal enum class DistanceZone { UNKNOWN, TOO_CLOSE, OK, TOO_FAR, MOVE_TO }

internal data class DistanceStatus(
    val zone: DistanceZone,
    val distanceCm: Float,
    val targetCm: Float,
    val toleranceCm: Float,
) {
    /** 需要移动的距离（回到容差带边缘），取整到厘米。 */
    val moveCm: Int get() = when (zone) {
        DistanceZone.TOO_CLOSE -> (targetCm - toleranceCm - distanceCm).roundToInt().coerceAtLeast(1)
        DistanceZone.TOO_FAR -> (distanceCm - targetCm - toleranceCm).roundToInt().coerceAtLeast(1)
        else -> 0
    }

    val title: String get() = when (zone) {
        DistanceZone.TOO_CLOSE -> "太近了"
        DistanceZone.TOO_FAR -> "太远了"
        DistanceZone.MOVE_TO -> moveToPrompt(targetCm)
        DistanceZone.OK -> "距离合适"
        DistanceZone.UNKNOWN -> "请正对屏幕"
    }

    val detail: String get() = when (zone) {
        DistanceZone.TOO_CLOSE -> "把手机往远处移约 $moveCm cm"
        DistanceZone.TOO_FAR -> "把手机往近处移约 $moveCm cm"
        DistanceZone.OK -> "约 ${distanceCm.roundToInt()} cm，保持住"
        DistanceZone.MOVE_TO -> if (distanceCm.isFinite() && distanceCm > 0f) "当前约 ${distanceCm.roundToInt()} cm，到位后视标自动出现"
            else "露出双眼，让摄像头看到你"
        DistanceZone.UNKNOWN -> "露出双眼，让摄像头看到你"
    }

    /** 语音提示（合适时不说话，避免打扰）；[repeat] 次重复时换一种说法。 */
    fun spoken(repeat: Int = 0): String? = if (zone == DistanceZone.MOVE_TO) moveToPrompt(targetCm) + "。" else SpokenPrompts.distance(when (zone) {
        DistanceZone.TOO_CLOSE -> -1
        DistanceZone.TOO_FAR -> 1
        DistanceZone.UNKNOWN -> 2
        DistanceZone.OK, DistanceZone.MOVE_TO -> 0
    }, repeat)

    val spoken: String? get() = spoken(0)
}

internal fun distanceStatus(distanceCm: Float, faceVisible: Boolean, targetCm: Float, toleranceCm: Float, moving: Boolean = false): DistanceStatus {
    val zone = when {
        moving -> DistanceZone.MOVE_TO
        !faceVisible || !distanceCm.isFinite() || distanceCm <= 0f -> DistanceZone.UNKNOWN
        distanceCm < targetCm - toleranceCm -> DistanceZone.TOO_CLOSE
        distanceCm > targetCm + toleranceCm -> DistanceZone.TOO_FAR
        else -> DistanceZone.OK
    }
    return DistanceStatus(zone, distanceCm, targetCm, toleranceCm)
}

/**
 * 距离状态横幅：整块填充状态色（太近红 / 太远蓝 / 合适绿），大字结论 + 具体移动量 +
 * 右侧示意标尺（中间绿色带为目标区间，圆点为当前位置）。进入异常区间时轻震一次。
 */
@Composable
internal fun DistanceBanner(status: DistanceStatus, modifier: Modifier = Modifier, compact: Boolean = false, singleLine: Boolean = false) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val bg by animateColorAsState(
        when (status.zone) {
            DistanceZone.TOO_CLOSE -> colors.tooClose
            DistanceZone.TOO_FAR -> colors.tooFar
            DistanceZone.OK -> colors.success
            DistanceZone.MOVE_TO -> colors.primary
            DistanceZone.UNKNOWN -> colors.neutralSoft
        },
        tween(EyeMotion.MEDIUM), label = "距离状态色"
    )
    val fg = if (status.zone == DistanceZone.UNKNOWN) colors.textPrimary else colors.onStatus
    Row(
        modifier
            .fillMaxWidth()
            .clip(EyeShapes.large)
            .background(bg)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 16.dp, vertical = if (singleLine) 6.dp else if (compact) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DirectionGlyph(status.zone, fg, Modifier.size(if (compact) 28.dp else 36.dp))
        Column(Modifier.weight(1f)) {
            if (singleLine) EyeText(status.detail, style = typography.body, color = fg, maxLines = 1)
            else {
                EyeText(status.title, style = if (compact) typography.subtitle else typography.title, color = fg)
                EyeText(status.detail, style = typography.bodySecondary, color = fg)
            }
        }
        if (!singleLine && status.zone != DistanceZone.UNKNOWN) DistanceRuler(status, fg, Modifier.size(width = 72.dp, height = 28.dp))
    }
}

/** 方向符号：太近 = 向外的双箭头（往远处移），太远 = 向内的双箭头（靠近），合适 = 对勾。 */
@Composable
private fun DirectionGlyph(zone: DistanceZone, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(w * 0.1f, cap = StrokeCap.Round)
        fun chevron(x: Float, pointRight: Boolean) {
            val d = if (pointRight) 1f else -1f
            drawPath(Path().apply {
                moveTo(x - d * w * 0.12f, h * 0.28f); lineTo(x + d * w * 0.06f, h * 0.5f); lineTo(x - d * w * 0.12f, h * 0.72f)
            }, color, style = stroke)
        }
        when (zone) {
            DistanceZone.TOO_CLOSE -> { chevron(w * 0.18f, false); chevron(w * 0.82f, true) }
            DistanceZone.TOO_FAR -> { chevron(w * 0.3f, true); chevron(w * 0.7f, false) }
            // 移动到新距离：单个指向前方的箭头
            DistanceZone.MOVE_TO -> chevron(w * 0.55f, true)
            DistanceZone.OK -> drawPath(Path().apply {
                moveTo(w * 0.2f, h * 0.52f); lineTo(w * 0.42f, h * 0.72f); lineTo(w * 0.8f, h * 0.3f)
            }, color, style = stroke)
            DistanceZone.UNKNOWN -> {
                drawCircle(color, w * 0.36f, center, style = Stroke(w * 0.08f))
                drawCircle(color, w * 0.06f, Offset(w * 0.38f, h * 0.45f))
                drawCircle(color, w * 0.06f, Offset(w * 0.62f, h * 0.45f))
            }
        }
    }
}

/** 示意标尺：左端近、右端远；中间为目标区间。 */
@Composable
private fun DistanceRuler(status: DistanceStatus, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val range = status.toleranceCm * 4f
        val min = status.targetCm - range
        val max = status.targetCm + range
        fun x(cm: Float) = ((cm - min) / (max - min)).coerceIn(0f, 1f) * size.width
        val cy = size.height / 2f
        drawRoundRect(color.copy(alpha = 0.35f), Offset(0f, cy - 3.dp.toPx()), Size(size.width, 6.dp.toPx()), CornerRadius(3.dp.toPx()))
        val bandL = x(status.targetCm - status.toleranceCm)
        val bandR = x(status.targetCm + status.toleranceCm)
        drawRoundRect(color.copy(alpha = 0.85f), Offset(bandL, cy - 5.dp.toPx()), Size(bandR - bandL, 10.dp.toPx()), CornerRadius(5.dp.toPx()))
        drawCircle(color, 7.dp.toPx(), Offset(x(status.distanceCm), cy))
        drawCircle(color.copy(alpha = 0.3f), 11.dp.toPx(), Offset(x(status.distanceCm), cy))
    }
}

/**
 * 全屏边缘光晕：距离异常时屏幕四周亮起状态色并缓慢呼吸，眼睛余光就能察觉，
 * 不必去读文字；不拦截触摸。系统移除动画时保持常亮。
 */
@Composable
internal fun DistanceEdgeGlow(zone: DistanceZone, modifier: Modifier = Modifier) {
    if (zone != DistanceZone.TOO_CLOSE && zone != DistanceZone.TOO_FAR) return
    val colors = LocalEyeColors.current
    val color = if (zone == DistanceZone.TOO_CLOSE) colors.tooClose else colors.tooFar
    val reduced = rememberReducedMotion()
    val pulse = if (reduced) null else rememberInfiniteTransition(label = "边缘光晕").animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "光晕强度"
    )
    val alpha = pulse?.value ?: 0.9f
    Box(modifier.fillMaxSize().border(10.dp, color.copy(alpha = alpha), RoundedCornerShape(28.dp)))
}

/** 距离偏差的「是否值得提醒」判定：避免在容差边缘来回抖动时反复播报。 */
internal fun DistanceStatus.clearlyOff(): Boolean =
    zone == DistanceZone.UNKNOWN || zone == DistanceZone.MOVE_TO || abs(distanceCm - targetCm) > toleranceCm + 1f
