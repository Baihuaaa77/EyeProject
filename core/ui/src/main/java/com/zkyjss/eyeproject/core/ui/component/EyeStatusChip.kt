package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography

enum class StatusTone { NEUTRAL, SUCCESS, DANGER, WARNING }

/** 自定义状态徽标，按语义色着色。 */
@Composable
fun EyeStatusChip(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val background: Color
    val foreground: Color
    when (tone) {
        StatusTone.NEUTRAL -> {
            background = colors.neutralSoft
            foreground = colors.textSecondary
        }
        StatusTone.SUCCESS -> {
            background = colors.successSoft
            foreground = colors.success
        }
        StatusTone.DANGER -> {
            background = colors.dangerSoft
            foreground = colors.danger
        }
        StatusTone.WARNING -> {
            background = colors.warningSoft
            foreground = colors.warning
        }
    }
    Box(
        modifier = modifier
            .clip(EyeShapes.chip)
            .background(background)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        EyeText(
            text = text,
            style = typography.chip,
            fontWeight = FontWeight.Bold,
            color = foreground
        )
    }
}
