package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.zkyjss.eyeproject.core.designsystem.theme.eyeSoftSurface
import com.zkyjss.eyeproject.core.designsystem.theme.EyeSpacing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.R
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography

/**
 * 图标配色：PRIMARY 品牌蓝 / ACCENT 橙 / SUCCESS 绿 / BLUE 青 / VIOLET 靛紫 / DANGER 红 / NEUTRAL 灰。全部取自设计令牌。
 * 列表图标块用实心底 + 白色图标（[solid]），卡片标题等用浅底 + 彩色图标（[colors]）。
 */
enum class EyeTileTone { PRIMARY, ACCENT, SUCCESS, BLUE, VIOLET, DANGER, NEUTRAL }

/** 浅底配色：（浅底色, 图标 / 文字色）。 */
@Composable
fun EyeTileTone.colors(): Pair<Color, Color> {
    val c = LocalEyeColors.current
    return when (this) {
        EyeTileTone.PRIMARY -> c.primarySoft to c.primary
        EyeTileTone.ACCENT -> c.sunSoft to c.sun
        EyeTileTone.SUCCESS -> c.successSoft to c.success
        EyeTileTone.BLUE -> c.iceSoft to c.tileTeal
        EyeTileTone.VIOLET -> c.violetSoft to c.violet
        EyeTileTone.DANGER -> c.dangerSoft to c.danger
        EyeTileTone.NEUTRAL -> c.surfaceVariant to c.textSecondary
    }
}

/** 实心图标块底色（两套主题同值，上面放白色图标）。 */
@Composable
fun EyeTileTone.solid(): Color {
    val c = LocalEyeColors.current
    return when (this) {
        EyeTileTone.PRIMARY -> c.tileBlue
        EyeTileTone.ACCENT -> c.tileOrange
        EyeTileTone.SUCCESS -> c.tileGreen
        EyeTileTone.BLUE -> c.tileTeal
        EyeTileTone.VIOLET -> c.tileIndigo
        EyeTileTone.DANGER -> c.tileRed
        EyeTileTone.NEUTRAL -> c.tileGray
    }
}

/** 浮雕小图标：同色表面、语义色线稿。 */
@Composable
fun EyeIconTile(glyph: EyeGlyph, tone: EyeTileTone = EyeTileTone.PRIMARY, size: androidx.compose.ui.unit.Dp = 32.dp) {
    Box(Modifier.size(size).eyeSoftSurface(LocalEyeColors.current, EyeShapes.medium, compact = true), contentAlignment = Alignment.Center) {
        EyeGlyphIcon(glyph, Modifier.size(size * 0.6f), tone.solid())
    }
}

/** 分组列表：分区标题与独立浮雕行，以间距代替连续分隔线。 */
@Composable
fun EyeListGroup(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalEyeColors.current
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            EyeText(title, Modifier.padding(start = 4.dp, top = EyeSpacing.sm, bottom = EyeSpacing.md), style = LocalEyeTypography.current.title, color = colors.textHeading)
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(EyeSpacing.sm), content = content)
    }
}

/** 浮雕列表行，保留图标、标题、副标题、取值、箭头和可替换尾部控件。 */
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
    /** 自绘的左侧图标（放在 32dp 实心色块里，图标用白色），代替 [glyph]。 */
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(if (pressed) colors.surfaceVariant else colors.panelBackground, tween(EyeMotion.SHORT), label = "列表行按压")
    Column(modifier.fillMaxWidth().eyeSoftSurface(colors, EyeShapes.card, pressed = pressed, compact = true).background(background)
        .then(if (onClick != null) Modifier.clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button) { haptics.tap(); onClick() } else Modifier)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                Box(Modifier.size(40.dp).clip(EyeShapes.medium).background(colors.surfaceVariant), contentAlignment = Alignment.Center) { leading() }
                Spacer(Modifier.width(14.dp))
            } else if (glyph != null) {
                EyeIconTile(glyph, tone)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                EyeText(title, style = typography.body, color = if (enabled) titleColor ?: colors.textPrimary else colors.textSecondary)
                if (subtitle != null) EyeText(subtitle, style = typography.bodySecondary, color = colors.textSecondary)
            }
            if (trailing != null) trailing() else {
                if (value != null) EyeText(value, Modifier.padding(start = 8.dp), style = typography.body, color = valueColor ?: colors.textSecondary, maxLines = 1)
                if (onClick != null) EyeIcon(painterResource(R.drawable.ic_arrow_right), null, Modifier.padding(start = 6.dp).size(14.dp), tint = colors.primary)
            }
        }
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
    Column(modifier.fillMaxWidth().eyeSoftSurface(colors, EyeShapes.card, compact = true)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch) { haptics.toggle(it); onChange(it) }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
    }
}

/** 数据卡上的类别标签：类别色小图标 + 类别色 13 号半粗名称（「用屏时长」「环境光」）。 */
@Composable
fun EyeCategoryLabel(title: String, glyph: EyeGlyph, tint: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        EyeGlyphIcon(glyph, Modifier.size(16.dp), tint)
        Spacer(Modifier.width(6.dp))
        EyeText(title, style = LocalEyeTypography.current.chip, color = LocalEyeColors.current.textSecondary, maxLines = 1)
    }
}

/**
 * 卡片分类头：主色小图标 + 卡片标题（[subtitle] 字号、主文字色），右侧可放操作（胶囊按钮、开关等）。
 */
@Composable
fun EyeCategoryHeader(
    title: String,
    glyph: EyeGlyph,
    tone: EyeTileTone = EyeTileTone.PRIMARY,
    modifier: Modifier = Modifier,
    trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val (_, foreground) = tone.colors()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // 卡片标题：主色小图标 + 标题字号（18）的主文字，与正文、辅助信息拉开层级
        EyeGlyphIcon(glyph, Modifier.size(20.dp), foreground)
        Spacer(Modifier.width(8.dp))
        EyeText(title, Modifier.weight(1f), style = LocalEyeTypography.current.subtitle, color = LocalEyeColors.current.textPrimary)
        trailing()
    }
}
