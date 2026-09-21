package com.zkyjss.eyeproject.feature.services

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.util.getTopStatusBarPadding
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingLarge
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeSectionCard
import com.zkyjss.eyeproject.core.ui.component.EyeSelectCard
import com.zkyjss.eyeproject.core.ui.component.EyeText

/** 服务页：列出三个病症检测服务入口。 */
@Composable
fun ServicesScreen(
    onDryEyeClick: () -> Unit,
    onPresbyopiaClick: () -> Unit,
    onStrabismusClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        Column(
            modifier = Modifier
        ) {
            EyeText(text = "检测服务", style = typography.subtitle, color = colors.textPrimary)
            EyeText(text = "选择要进行的眼健康检测服务", style = typography.body, color = colors.textSecondary)
        }
        Spacer(Modifier.height(4.dp))

        EyeSelectCard(
            modifier = Modifier,
            content = "干眼病检测",
            onClick = onDryEyeClick
        )

        EyeSelectCard(
            modifier = Modifier,
            content = "斜视检测",
            onClick = onStrabismusClick,
        )

        EyeSelectCard(
            modifier = Modifier,
            content = "老花眼检测",
            onClick = onPresbyopiaClick,
        )
    }
}
