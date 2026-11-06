package com.zkyjss.eyeproject.core.designsystem.util

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import com.zkyjss.eyeproject.core.designsystem.theme.EyeSpacing

/**
 * 取消默认的水波纹的点击
 */
fun Modifier.clickableNoRipple( enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    clickable(
        enabled = enabled,
        indication = null,
        interactionSource = remember { MutableInteractionSource() }
    ) {
        onClick()
    }
}

/** 页面左右留白（小）：8dp。 */
fun Modifier.screenHorizontalPaddingSmall(): Modifier = padding(horizontal = EyeSpacing.xs)

/** 页面左右留白（中）：12dp。 */
fun Modifier.screenHorizontalPaddingMedium(): Modifier = padding(horizontal = EyeSpacing.sm)

/** 页面左右留白（标准）：[EyeSpacing.gutter] 16dp，所有顶层页与二级页统一使用。 */
fun Modifier.screenHorizontalPaddingLarge(): Modifier = padding(horizontal = EyeSpacing.gutter)
