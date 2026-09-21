package com.zkyjss.eyeproject.feature.services

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zkyjss.eyeproject.feature.services.detection.DetectionContent

/** 斜视检测页。 */
@Composable
fun StrabismusScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    DetectionContent(
        title = "斜视检测",
        description = "基于双眼眼位与注视一致性评估斜视",
        onBack = onBack,
        modifier = modifier,
    )
}
