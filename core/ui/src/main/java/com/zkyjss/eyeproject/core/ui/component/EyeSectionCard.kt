package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography

/** 自定义分区卡片：圆角面板 + 细边框 + 可选标题。 */
@Composable
fun EyeSectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(EyeShapes.panel)
            .background(colors.panelBackground)
            .padding(20.dp)
    ) {
        if (title != null) {
            EyeText(
                text = title,
                style = typography.subtitle,
                color = colors.textPrimary
            )
            Spacer(Modifier.height(12.dp))
        }
        content()
    }
}
