package com.zkyjss.eyeproject.core.designsystem.util

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun getScreenHeight(): Dp {
    val configuration = LocalConfiguration.current
    return with(LocalDensity.current) {
        configuration.screenHeightDp.dp
    }
}

@Composable
fun getTopStatusBarPadding(): Dp {
    val statusBarInsets = WindowInsets.statusBars.getTop(LocalDensity.current)
    return with(LocalDensity.current) { statusBarInsets.toDp() }
}

@Composable
fun getBottomNavigationBarPadding(): Dp {
    val statusBarInsets = WindowInsets.navigationBars.getBottom(LocalDensity.current)
    return with(LocalDensity.current) { statusBarInsets.toDp() }
}