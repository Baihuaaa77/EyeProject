package com.zkyjss.eyeproject.feature.testmodel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingLarge
import com.zkyjss.eyeproject.core.ui.component.EyeSelectCard
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.TopBackBar

/** 测试模型主页：列出各实验测试入口。 */
@Composable
fun TestModelScreen(
    onBack: () -> Unit,
    onGazeClick: () -> Unit,
    onBlinkTestClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.appBackground)
    ) {
        TopBackBar(onBackClick = onBack)
        Column(
            modifier = Modifier
                .screenHorizontalPaddingLarge()
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EyeText(text = "模型测试", style = typography.subtitle, color = colors.textPrimary)
            EyeText(text = "选择要进行的实验测试", style = typography.body, color = colors.textSecondary)
            Spacer(Modifier.height(4.dp))
            EyeSelectCard(content = "视线估计", onClick = onGazeClick)
            EyeSelectCard(content = "眨眼检测", onClick = onBlinkTestClick)
        }
    }
}
