package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography

/** 自定义指标卡：可选图标 + 标签 + 大数值 + 说明。 */
@Composable
fun EyeMetricCard(
    label: String,
    value: String,
    detail: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    valueColor: Color? = null
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(EyeShapes.large)
            .background(colors.surfaceVariant)
            .padding(12.dp)
    ) {
        if (icon != null) {
            EyeText(
                text = icon,
                style = typography.body,
                fontWeight = FontWeight.Bold,
                color = colors.primary
            )
        }
        EyeText(
            text = label,
            style = typography.subtitle,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 8.dp)
        )
        EyeText(
            text = value,
            style = typography.metricValue,
            color = valueColor ?: colors.textPrimary,
            modifier = Modifier.padding(top = 2.dp)
        )
        EyeText(
            text = detail,
            style = typography.body,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp),
            maxLines = 2
        )
    }
}
