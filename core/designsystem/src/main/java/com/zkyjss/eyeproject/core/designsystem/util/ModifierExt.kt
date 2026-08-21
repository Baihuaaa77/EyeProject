package com.zkyjss.eyeproject.core.designsystem.util

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.dp

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

/**
 * 屏幕内容与边界间距24px小
 */
fun Modifier.screenHorizontalPaddingSmall(): Modifier = padding(
    horizontal = 4.dp
)

/**
 * 屏幕内容与屏幕边界间距32px中
 */
fun Modifier.screenHorizontalPaddingMedium(): Modifier = padding(
    horizontal = 8.dp
)

/**
 * 屏幕内容与边界间距58px大
 */
fun Modifier.screenHorizontalPaddingLarge(): Modifier = padding(
    horizontal = 12.dp
)