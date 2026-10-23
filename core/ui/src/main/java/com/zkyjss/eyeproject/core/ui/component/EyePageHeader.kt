package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.R
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography

/** 右上角账户按钮的显示状态：未登录画人像轮廓，登录后显示邮箱首字母与在线小绿点。由 app 按账号状态提供。 */
@Immutable
data class EyeAccountBadge(val signedIn: Boolean = false, val initial: String = "")

val LocalEyeAccountBadge = compositionLocalOf { EyeAccountBadge() }

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

/** 账户按钮（齿轮左侧）：点开完整的账户页（登录、注册、退出、注销）；与苹果端 AccountButton 一致。 */
@Composable
fun EyeAccountButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalEyeColors.current
    val badge = LocalEyeAccountBadge.current
    val haptics = rememberEyeHaptics()
    Box(modifier.size(44.dp)) {
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .background(if (badge.signedIn) colors.primarySoft else colors.panelBackground)
                .border(1.dp, colors.borderSubtle, CircleShape)
                .clickable(role = Role.Button) { haptics.tap(); onClick() }
                .semantics { contentDescription = if (badge.signedIn) "账户（已登录）" else "账户（未登录）" },
            contentAlignment = Alignment.Center,
        ) {
            if (badge.signedIn && badge.initial.isNotBlank()) {
                EyeText(badge.initial.take(1).uppercase(), style = LocalEyeTypography.current.subtitle, color = colors.primary)
            } else {
                PersonGlyph(Modifier.size(22.dp))
            }
        }
        if (badge.signedIn) Box(
            Modifier.align(Alignment.BottomEnd).offset((-1).dp, (-1).dp).size(12.dp).clip(CircleShape)
                .background(colors.panelBackground).padding(2.dp).clip(CircleShape).background(colors.success)
        )
    }
}

/** 人像线稿（与底部导航「我的」同一笔画）。 */
@Composable
fun PersonGlyph(modifier: Modifier = Modifier) {
    val color = LocalEyeColors.current.primary
    Canvas(modifier) {
        val k = size.minDimension / 24f
        val line = Stroke(1.9f * k, cap = StrokeCap.Round)
        drawCircle(color, 3.8f * k, Offset(12f * k, 8.5f * k), style = line)
        drawArc(color, 200f, 140f, false, Offset(4.5f * k, 14f * k), Size(15f * k, 12f * k), style = line)
    }
}

/** 顶层页右上角的一组按钮：账户 + 系统设置。 */
@Composable
fun EyeHeaderActions(onOpenAccount: (() -> Unit)?, onOpenSettings: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onOpenAccount != null) EyeAccountButton(onOpenAccount)
        EyeSettingsGear(onOpenSettings)
    }
}

/** 顶层页标题行：大标题（可带一行副标题）+ 右上角账户与系统设置（「服务」「我的」共用，与苹果端 PageHeader 一致）。 */
@Composable
fun EyePageHeader(
    title: String,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenAccount: (() -> Unit)? = null,
    subtitle: String? = null,
) {
    Row(modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            EyeText(title, style = LocalEyeTypography.current.headline, color = LocalEyeColors.current.textPrimary)
            if (subtitle != null) EyeText(subtitle, style = LocalEyeTypography.current.bodySecondary, color = LocalEyeColors.current.textSecondary)
        }
        EyeHeaderActions(onOpenAccount, onOpenSettings)
    }
}
