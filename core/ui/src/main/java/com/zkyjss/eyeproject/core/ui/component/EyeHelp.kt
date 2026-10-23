package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography

/**
 * 标题旁的灰色圆圈问号：点开在旁边弹出一个小窗口简要说明，点空白处收起。
 * 用来替代标题后的括号注释和成段小字说明；与苹果端 HelpButton 同一外观与文案。
 */
@Composable
fun EyeHelpButton(title: String, text: String, modifier: Modifier = Modifier) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    var open by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, interactionSource = remember { MutableInteractionSource() }, indication = null) {
                haptics.tap(); open = true
            }
            .semantics { contentDescription = "$title 说明" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
            EyeText("?", style = typography.chip, color = colors.textSecondary, fontWeight = FontWeight.Bold)
        }
        if (open) HelpPopup(title, text) { open = false }
    }
}

@Composable
private fun HelpPopup(title: String, text: String, onDismiss: () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val margin = with(LocalDensity.current) { 16.dp.roundToPx() }
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    Popup(
        popupPositionProvider = remember(margin, gap) { HelpPositionProvider(margin, gap) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visibleState = visible,
            enter = fadeIn(tween(EyeMotion.SHORT)) + scaleIn(tween(EyeMotion.SHORT), initialScale = 0.92f),
            exit = fadeOut(tween(EyeMotion.SHORT)) + scaleOut(tween(EyeMotion.SHORT), targetScale = 0.92f),
        ) {
            Column(
                Modifier
                    .widthIn(max = 300.dp)
                    .shadow(12.dp, EyeShapes.large, clip = false)
                    .clip(EyeShapes.large)
                    .background(colors.panelBackground)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                EyeText(title, style = typography.body, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                EyeText(text, style = typography.body, color = colors.textSecondary)
            }
        }
    }
}

/** 小窗口优先出现在问号下方，放不下时出现在上方；左右不超出屏幕边距。 */
private class HelpPositionProvider(private val margin: Int, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2)
            .coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height - margin) below
            else (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(margin)
        return IntOffset(x, y)
    }
}

/** 标题 + 问号的一行（分区标题常用）。 */
@Composable
fun EyeTitleWithHelp(title: String, help: String?, modifier: Modifier = Modifier, helpTitle: String = title) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    androidx.compose.foundation.layout.Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        EyeText(title, style = typography.subtitle, color = colors.textPrimary)
        if (help != null) EyeHelpButton(helpTitle, help)
    }
}
