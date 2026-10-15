package com.zkyjss.eyeproject.feature.services

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.ui.component.EyeSectionCard

/** 只装饰测试舞台，保留原有内容尺寸与白色刺激背景。 */
@Composable
internal fun TestStageCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalEyeColors.current
    EyeSectionCard(
        modifier = Modifier.shadow(3.dp, EyeShapes.panel, ambientColor = colors.primary.copy(alpha = 0.08f),
            spotColor = colors.primary.copy(alpha = 0.08f))
            .border(1.dp, colors.primary.copy(alpha = 0.16f), EyeShapes.panel),
        title = title, content = content,
    )
}
