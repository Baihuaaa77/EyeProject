package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.R
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography

/** 图标色块的配色：主色 / 琥珀 / 成功绿 / 靛蓝 / 警示 / 中性。全部取自设计令牌。 */
enum class EyeTileTone { PRIMARY, ACCENT, SUCCESS, BLUE, DANGER, NEUTRAL }

@Composable
private fun EyeTileTone.colors(): Pair<Color, Color> {
    val c = LocalEyeColors.current
    return when (this) {
        EyeTileTone.PRIMARY -> c.primarySoft to c.primary
        EyeTileTone.ACCENT -> c.accentSoft to c.warning
        EyeTileTone.SUCCESS -> c.successSoft to c.success
        EyeTileTone.BLUE -> c.surfaceVariant to c.blue
        EyeTileTone.DANGER -> c.dangerSoft to c.danger
        EyeTileTone.NEUTRAL -> c.surfaceVariant to c.textSecondary
    }
}

/** 圆角色块上的线性图标（列表行、服务入口共用）。 */
@Composable
fun EyeIconTile(glyph: EyeGlyph, tone: EyeTileTone = EyeTileTone.PRIMARY, size: androidx.compose.ui.unit.Dp = 36.dp) {
    val (background, foreground) = tone.colors()
    Box(Modifier.size(size).clip(EyeShapes.small).background(background), contentAlignment = Alignment.Center) {
        EyeGlyphIcon(glyph, Modifier.size(size * 0.58f), foreground)
    }
}

/** 分组列表：可选组标题 + 白色圆角面板，行与行之间细分隔线（从图标右侧开始）。 */
@Composable
fun EyeListGroup(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalEyeColors.current
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            EyeText(title, Modifier.padding(start = 4.dp, bottom = 8.dp), style = LocalEyeTypography.current.chip, color = colors.textSecondary)
        }
        Column(Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground), content = content)
    }
}

/**
 * 列表行：图标色块 + 标题（可带一行副标题）+ 右侧取值 + 箭头；按下时整行浅底高亮。
 * [divider] 为 true 时在行底部画分隔线（组内最后一行传 false）。[trailing] 替换右侧取值与箭头（如开关）。
 */
@Composable
fun EyeListRow(
    title: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    glyph: EyeGlyph? = null,
    tone: EyeTileTone = EyeTileTone.PRIMARY,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color? = null,
    divider: Boolean = true,
    titleColor: Color? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(if (pressed) colors.surfaceVariant else colors.panelBackground, tween(EyeMotion.SHORT), label = "列表行按压")
    Column(modifier.fillMaxWidth().background(background)
        .then(if (onClick != null) Modifier.clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button) { haptics.tap(); onClick() } else Modifier)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (glyph != null) {
                EyeIconTile(glyph, tone)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                EyeText(title, style = typography.body, color = if (enabled) titleColor ?: colors.textPrimary else colors.textSecondary)
                if (subtitle != null) EyeText(subtitle, style = typography.bodySecondary, color = colors.textSecondary)
            }
            if (trailing != null) trailing() else {
                if (value != null) EyeText(value, Modifier.padding(start = 8.dp), style = typography.body, color = valueColor ?: colors.textSecondary, maxLines = 1)
                if (onClick != null) EyeIcon(painterResource(R.drawable.ic_arrow_right), null, Modifier.padding(start = 6.dp).size(16.dp), tint = colors.gray)
            }
        }
        if (divider) Box(Modifier.padding(start = if (glyph != null) 66.dp else 16.dp).fillMaxWidth().height(1.dp).background(colors.borderSubtle))
    }
}

/** 列表里的开关行：整行可点，开关只做展示。 */
@Composable
fun EyeSwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    glyph: EyeGlyph? = null,
    tone: EyeTileTone = EyeTileTone.PRIMARY,
    subtitle: String? = null,
    enabled: Boolean = true,
    divider: Boolean = true,
    help: Pair<String, String>? = null,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    Column(modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Switch) { haptics.toggle(!checked); onChange(!checked) }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (glyph != null) {
                EyeIconTile(glyph, tone)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EyeText(title, style = typography.body, color = colors.textPrimary)
                    if (help != null) EyeHelpButton(help.first, help.second)
                }
                if (subtitle != null) EyeText(subtitle, style = typography.bodySecondary, color = colors.textSecondary)
            }
            EyeSwitch(checked, null, enabled = enabled)
        }
        if (divider) Box(Modifier.padding(start = if (glyph != null) 66.dp else 16.dp).fillMaxWidth().height(1.dp).background(colors.borderSubtle))
    }
}
