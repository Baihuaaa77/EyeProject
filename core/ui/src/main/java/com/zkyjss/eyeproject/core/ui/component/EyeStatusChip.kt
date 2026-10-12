package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.theme.rememberReducedMotion

enum class StatusTone { NEUTRAL, SUCCESS, DANGER, WARNING }

/**
 * 自定义状态徽标，按语义色着色，色调切换平滑过渡。
 * [live] 为 true 时前置一个「呼吸」圆点，表示正在实时运行（系统移除动画时静止）。
 */
@Composable
fun EyeStatusChip(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    live: Boolean = false
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val background by animateColorAsState(
        when (tone) {
            StatusTone.NEUTRAL -> colors.neutralSoft
            StatusTone.SUCCESS -> colors.successSoft
            StatusTone.DANGER -> colors.dangerSoft
            StatusTone.WARNING -> colors.warningSoft
        },
        tween(EyeMotion.MEDIUM),
        label = "徽标底色"
    )
    val foreground by animateColorAsState(
        when (tone) {
            StatusTone.NEUTRAL -> colors.textSecondary
            StatusTone.SUCCESS -> colors.success
            StatusTone.DANGER -> colors.danger
            StatusTone.WARNING -> colors.warning
        },
        tween(EyeMotion.MEDIUM),
        label = "徽标文字色"
    )
    Row(
        modifier = modifier
            .clip(EyeShapes.chip)
            .background(background)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (live) {
            val reduced = rememberReducedMotion()
            val pulse by rememberInfiniteTransition(label = "呼吸点").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
                label = "呼吸进度"
            )
            Canvas(Modifier.size(10.dp)) {
                val r = size.minDimension / 2
                if (!reduced) drawCircle(foreground, r * (0.5f + pulse * 0.5f), alpha = (1f - pulse) * 0.5f)
                drawCircle(foreground, r * 0.5f)
            }
            Spacer(Modifier.width(6.dp))
        }
        EyeText(
            text = text,
            style = typography.chip,
            fontWeight = FontWeight.Bold,
            color = foreground
        )
    }
}
