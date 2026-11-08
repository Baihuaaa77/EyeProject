package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** 双阴影只作用在背景形状，避免文字、图表与子组件重复投影。 */
fun Modifier.eyeSoftSurface(
    colors: EyeColors,
    shape: Shape,
    fill: Brush = SolidColor(colors.panelBackground),
    pressed: Boolean = false,
    compact: Boolean = false,
): Modifier {
    val raised = if (pressed) this else this
        .dropShadow(shape, Shadow(
            radius = if (compact) 8.dp else 16.dp,
            color = colors.shadowLight,
            offset = DpOffset(if (compact) (-3).dp else (-6).dp, if (compact) (-3).dp else (-6).dp),
        ))
        .dropShadow(shape, Shadow(
            radius = if (compact) 12.dp else 22.dp,
            color = colors.shadow,
            offset = DpOffset(if (compact) 4.dp else 8.dp, if (compact) 4.dp else 8.dp),
        ))
    val surface = raised.clip(shape).background(fill)
        .then(if (colors.isDark) Modifier.border(1.dp, colors.cardBorder, shape) else Modifier)
    return if (!pressed) surface else surface
        .innerShadow(shape, Shadow(radius = 5.dp, color = colors.shadow, offset = DpOffset(2.dp, 2.dp)))
        .innerShadow(shape, Shadow(radius = 5.dp, color = colors.shadowLight, offset = DpOffset((-2).dp, (-2).dp)))
}
