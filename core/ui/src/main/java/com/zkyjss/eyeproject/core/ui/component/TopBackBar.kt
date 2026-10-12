package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.semantics.Role
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.R
import com.zkyjss.eyeproject.core.designsystem.util.borderBottom

/**
 * 作为应用顶部的返回组件，背景覆盖了手机状态栏
 * @param containerColor 返回栏背景色
 * @param containerBrush 渐变
 * @param useBottomBorder 底部边界
 * @param noText 不显示返回字样
 * @param title 标题
 * @param onBackClick 返回点击
 */
@Composable
fun TopBackBar(
    modifier: Modifier = Modifier,
    containerColor: Color = LocalEyeColors.current.appBackground,
    containerBrush: Brush? = null,
    useBottomBorder: Boolean = false,
    noText: Boolean = false,
    title: String? = null,
    onBackClick: () -> Unit
) {
    val titleStyle = LocalEyeTypography.current.subtitle.copy(color = LocalEyeColors.current.textPrimary)

    TopAppBar(
        modifier = modifier
            .then(
                containerBrush?.let {
                    Modifier.background(brush = containerBrush)
                } ?: Modifier.background(color = containerColor)
            )
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .then(if (useBottomBorder) Modifier.borderBottom() else Modifier)
        ) {
            // 返回图标和字样
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .clickable(role = Role.Button, onClick = onBackClick)
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .offset(x = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(
                    space = 4.dp,
                    alignment = Alignment.Start
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EyeIcon(
                    modifier = Modifier.size(10.dp),
                    painter = painterResource(R.drawable.ic_pointer_left),
                    contentDescription = if (noText) "返回" else null,
                    tint = LocalEyeColors.current.primary,
                )

                if (!noText) {
                    EyeText(
                        text = stringResource(R.string.designsystem_back_text),
                        style = LocalEyeTypography.current.body,
                        color = LocalEyeColors.current.primary
                    )
                }
            }

            // 标题
            title?.let {
                Box(
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    EyeText(text = title, style = titleStyle)
                }
            }
        }
    }
}
