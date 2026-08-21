package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.util.getTopStatusBarPadding

/**
 * 顶部应用栏，包括手机状态栏
 * @param content 参数为顶部状态栏的padding
 */
@Composable
fun TopAppBar(
    modifier: Modifier = Modifier,
    content: @Composable (topStatusBarPadding: PaddingValues) -> Unit
) {
    val topStatusBarPadding = getTopStatusBarPadding()

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp + topStatusBarPadding)
    ) {
        content(PaddingValues(top = topStatusBarPadding))
    }
}