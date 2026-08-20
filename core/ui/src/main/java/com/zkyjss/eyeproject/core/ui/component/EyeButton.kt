package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography

enum class EyeButtonRole { PRIMARY, SECONDARY, DANGER }

/** 自定义按钮，三态（primary/secondary/danger），带按压反馈，不使用 Material Button。 */
@Composable
fun EyeButton(
    text: String,
    role: EyeButtonRole,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val backgroundColor: Color = when (role) {
        EyeButtonRole.PRIMARY -> colors.primary
        EyeButtonRole.SECONDARY -> colors.primarySoft
        EyeButtonRole.DANGER -> colors.dangerSoft
    }
    val contentColor: Color = when (role) {
        EyeButtonRole.PRIMARY -> colors.textPrimary
        EyeButtonRole.SECONDARY -> colors.primary
        EyeButtonRole.DANGER -> colors.danger
    }
    val finalAlpha = when {
        !enabled -> 0.55f
        pressed -> 0.82f
        else -> 1f
    }

    Box(
        modifier = modifier
            .alpha(finalAlpha)
            .clip(EyeShapes.medium)
            .background(backgroundColor)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        EyeText(
            text = text,
            style = typography.button,
            color = contentColor,
            textAlign = TextAlign.Center
        )
    }
}
