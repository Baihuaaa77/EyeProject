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
import com.zkyjss.eyeproject.core.designsystem.theme.eyeSoftSurface
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.EyeSpacing
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

/** 右上角白天 / 夜晚切换：当前是否夜晚 + 点击切换（为 null 时不显示按钮）。由 app 按主题偏好提供。 */
@Immutable
data class EyeThemeToggle(val isDark: Boolean = false, val onToggle: (() -> Unit)? = null)

val LocalEyeThemeToggle = compositionLocalOf { EyeThemeToggle() }

/** 白天 / 夜晚切换按钮（系统设置齿轮左侧）：白天显示月亮（点了进夜晚），夜晚显示太阳（点了回白天）。与苹果端 ThemeToggleButton 一致。 */
@Composable
fun EyeThemeButton(modifier: Modifier = Modifier) {
    val toggle = LocalEyeThemeToggle.current
    val onToggle = toggle.onToggle ?: return
    val colors = LocalEyeColors.current
    val haptics = rememberEyeHaptics()
    Box(
        modifier.size(48.dp).eyeSoftSurface(colors, EyeShapes.medium, compact = true)
            .clickable(role = Role.Button) { haptics.tap(); onToggle() }
            .semantics { contentDescription = if (toggle.isDark) "切换到白天模式" else "切换到夜晚模式" },
        contentAlignment = Alignment.Center,
    ) {
        EyeGlyphIcon(if (toggle.isDark) EyeGlyph.SUN else EyeGlyph.MOON, Modifier.size(22.dp), colors.primary)
    }
}

/** 「系统设置」齿轮（36dp 浅填充圆钮，与测试页顶栏同一图标）；与苹果端 SettingsGear 一致。 */
@Composable
fun EyeSettingsGear(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalEyeColors.current
    val haptics = rememberEyeHaptics()
    Box(
        modifier.size(48.dp).eyeSoftSurface(colors, EyeShapes.medium, compact = true).clickable(role = Role.Button) { haptics.tap(); onClick() }
            .semantics { contentDescription = "系统设置" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            EyeIcon(painterResource(R.drawable.ic_settings_gear), null, Modifier.size(20.dp), tint = colors.primary)
        }
    }
}

/**
 * 账户头像（顶层页右上角唯一的按钮）：登录后品牌蓝实心圆 + 白色首字母，未登录为浅填充圆 + 人像线稿；
 * 点开完整的账户页（登录、注册、退出、注销）。与苹果端 AccountButton 一致。
 */
@Composable
fun EyeAccountButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalEyeColors.current
    val badge = LocalEyeAccountBadge.current
    val haptics = rememberEyeHaptics()
    val signedIn = badge.signedIn && badge.initial.isNotBlank()
    Box(
        modifier.size(48.dp).eyeSoftSurface(colors, EyeShapes.medium, compact = true).clickable(role = Role.Button) { haptics.tap(); onClick() }
            .semantics { contentDescription = if (badge.signedIn) "账户（已登录）" else "账户（未登录）" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(36.dp),
            contentAlignment = Alignment.Center) {
            if (signedIn) {
                EyeText(badge.initial.take(1).uppercase(), style = LocalEyeTypography.current.subtitle, color = colors.onPrimary)
            } else {
                PersonGlyph(Modifier.size(20.dp))
            }
        }
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

/** 顶层页右上角：有账户入口时只放账户头像，否则放系统设置齿轮（白天 / 夜晚改在「我的 → 深色模式」）。 */
@Composable
fun EyeHeaderActions(onOpenAccount: (() -> Unit)?, onOpenSettings: (() -> Unit)?) {
    when {
        onOpenAccount != null -> EyeAccountButton(onOpenAccount)
        onOpenSettings != null -> EyeSettingsGear(onOpenSettings)
    }
}

/** 顶层标题行：22 号标题在上，日期在下，右侧一个浮雕图标按钮。 */
@Composable
fun EyePageHeader(
    title: String,
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onOpenAccount: (() -> Unit)? = null,
    subtitle: String? = null,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Row(modifier.fillMaxWidth().padding(top = EyeSpacing.lg, bottom = EyeSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            EyeText(title, style = typography.headline, color = colors.textHeading)
            if (subtitle != null) EyeText(subtitle, Modifier.padding(top = EyeSpacing.xxs), style = typography.label, color = colors.textSecondary)
        }
        EyeHeaderActions(onOpenAccount, onOpenSettings)
    }
}
