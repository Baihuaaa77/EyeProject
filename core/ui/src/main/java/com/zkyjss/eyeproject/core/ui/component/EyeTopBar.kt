package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography

/** 自定义顶栏：可选前置标志 + 标题/副标题 + 右侧状态徽标（可带实时呼吸点）或自定义内容（如系统设置齿轮）。 */
@Composable
fun EyeTopBar(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    statusText: String? = null,
    statusTone: StatusTone = StatusTone.NEUTRAL,
    statusLive: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 12.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 16.dp)
        ) {
            EyeText(
                text = title,
                style = typography.headline,
                color = colors.textPrimary
            )
            if (subtitle.isNotBlank()) EyeText(
                text = subtitle,
                style = typography.bodySecondary,
                color = colors.textSecondary
            )
        }
        when {
            trailing != null -> trailing()
            statusText != null -> EyeStatusChip(text = statusText, tone = statusTone, live = statusLive)
        }
    }
}
