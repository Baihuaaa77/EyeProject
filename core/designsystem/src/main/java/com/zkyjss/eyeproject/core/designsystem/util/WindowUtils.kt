package com.zkyjss.eyeproject.core.designsystem.util

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/** 顶部状态栏高度（dp），页面顶部留白统一读它。 */
@Composable
fun getTopStatusBarPadding(): Dp {
    val statusBarInsets = WindowInsets.statusBars.getTop(LocalDensity.current)
    return with(LocalDensity.current) { statusBarInsets.toDp() }
}
