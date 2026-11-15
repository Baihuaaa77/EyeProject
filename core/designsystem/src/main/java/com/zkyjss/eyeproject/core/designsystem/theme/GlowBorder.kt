package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** 兼容旧光边入口，棕色版本只保留低对比实体边线。 */
fun Modifier.eyeGlowBorder(colors: EyeColors, shape: Shape, strength: Float = 1f): Modifier =
    border(1.dp, colors.cardBorder, shape)
