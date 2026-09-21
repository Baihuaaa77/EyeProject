package com.zkyjss.eyeproject.feature.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.util.getTopStatusBarPadding
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingLarge
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingMedium
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingSmall
import com.zkyjss.eyeproject.core.ui.component.EyeText

/** 我的页：占位。 */
@Composable
fun MineScreen(modifier: Modifier = Modifier) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.appBackground)
            .padding(top = getTopStatusBarPadding())
            .screenHorizontalPaddingLarge(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        EyeText(text = "我的", style = typography.subtitle, color = colors.textPrimary)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            EyeText(text = "开发中", style = typography.body, color = colors.textSecondary)
        }
    }
}
