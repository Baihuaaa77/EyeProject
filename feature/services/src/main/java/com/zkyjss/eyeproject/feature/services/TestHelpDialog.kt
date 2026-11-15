package com.zkyjss.eyeproject.feature.services

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zkyjss.eyeproject.core.designsystem.theme.*
import com.zkyjss.eyeproject.core.ui.component.*

/** 当前环节的方法回看；短屏与大字号时可滚动，返回或点外部也恢复测试。 */
@Composable
internal fun TestHelpDialog(help: TestHelpContent, onClose: () -> Unit) {
    val colors = LocalEyeColors.current
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).clip(EyeShapes.panel)
            .background(colors.panelBackground).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EyeText("当前环节演示", style = LocalEyeTypography.current.title, color = colors.textPrimary)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                TestGuide(GuideMotion.valueOf(help.motion), help.title, help.cue)
            }
            EyeButton("看懂了，继续", EyeButtonRole.PRIMARY, onClose, Modifier.fillMaxWidth())
        }
    }
}
