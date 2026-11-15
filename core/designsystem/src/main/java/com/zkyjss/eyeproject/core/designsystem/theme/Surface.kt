package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp

/** 暖棕实体表面：细边线与轻微按压底色，不给前景内容投影。 */
fun Modifier.eyeSoftSurface(
    colors: EyeColors,
    shape: Shape,
    fill: Brush = SolidColor(colors.panelBackground),
    pressed: Boolean = false,
    compact: Boolean = false,
): Modifier =
    clip(shape)
        .background(if (pressed) SolidColor(colors.surfaceVariant) else fill)
        .border(1.dp, colors.cardBorder, shape)
