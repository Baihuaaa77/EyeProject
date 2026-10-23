package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.R
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography

/** 顶层页右上角常驻的「系统设置」齿轮（与测试页顶栏同一图标）；与苹果端 SettingsGear 一致。 */
@Composable
fun EyeSettingsGear(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalEyeColors.current
    val haptics = rememberEyeHaptics()
    Box(
        modifier.size(44.dp).clip(CircleShape).background(colors.panelBackground)
            .border(1.dp, colors.borderSubtle, CircleShape)
            .clickable(role = Role.Button) { haptics.tap(); onClick() }
            .semantics { contentDescription = "系统设置" },
        contentAlignment = Alignment.Center,
    ) {
        EyeIcon(painterResource(R.drawable.ic_settings_gear), null, Modifier.size(22.dp), tint = colors.primary)
    }
}

/** 顶层页标题行：大标题 + 右上角系统设置齿轮（「服务」「我的」共用，与苹果端 PageHeader 一致）。 */
@Composable
fun EyePageHeader(title: String, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        EyeText(title, style = LocalEyeTypography.current.headline, color = LocalEyeColors.current.textPrimary,
            modifier = Modifier.weight(1f))
        EyeSettingsGear(onOpenSettings)
    }
}
