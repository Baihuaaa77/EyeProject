package com.zkyjss.eyeproject.feature.services

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zkyjss.eyeproject.feature.services.detection.DetectionContent

/** 老花眼检测页。 */
@Composable
fun PresbyopiaScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    DetectionContent(
        title = "老花眼检测",
        description = "基于用眼距离与调节能力评估老花",
        onBack = onBack,
        modifier = modifier,
    )
}
