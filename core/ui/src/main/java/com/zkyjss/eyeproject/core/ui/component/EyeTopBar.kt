package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography

/** 自定义顶栏：Logo + 标题/副标题 + 状态徽标。 */
@Composable
fun EyeTopBar(
    title: String,
    subtitle: String,
    statusText: String,
    statusTone: StatusTone,
    modifier: Modifier = Modifier
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(EyeShapes.small)
                .background(colors.primary),
            contentAlignment = Alignment.Center
        ) {
            EyeText(
                text = "EP",
                style = typography.bodySecondary,
                fontWeight = FontWeight.Bold,
                color = colors.textInverse
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            EyeText(
                text = title,
                style = typography.title,
                color = colors.textPrimary
            )
            EyeText(
                text = subtitle,
                style = typography.bodySecondary,
                color = colors.textSecondary
            )
        }
        EyeStatusChip(text = statusText, tone = statusTone)
    }
}
