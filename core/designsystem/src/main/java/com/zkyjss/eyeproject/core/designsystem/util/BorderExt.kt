package com.zkyjss.eyeproject.core.designsystem.util

import android.annotation.SuppressLint
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
/**
 * Modifier拓展，更方便的给容器底部添加border
 */
@SuppressLint("SuspiciousModifierThen")
fun Modifier.borderBottom(color: Color = Color.Black, width: Dp = 0.5.dp): Modifier =
    drawBehind{
        drawLine(
            color = color,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = width.toPx()
        )
    }

/**
 * Modifier拓展，更方便的给容器顶部添加border
 */
@SuppressLint("SuspiciousModifierThen")
fun Modifier.borderTop(color: Color = Color.Black, width: Dp = 0.5.dp): Modifier =
    drawBehind{
        drawLine(
            color = color,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = width.toPx()
        )
    }