package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography

data class SegmentedOption<T>(val value: T, val label: String)

/**
 * 自定义分段选择器：浅色轨道 + 主色滑块，选中项变化时滑块以弹簧滑到新位置。
 * 每项是 Role.RadioButton，整组是 selectableGroup，读屏可正确播报「已选中」。
 */
@Composable
fun <T> EyeSegmentedRow(
    options: List<SegmentedOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val selectedIndex = options.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(EyeShapes.medium)
            .background(colors.surfaceVariant)
            .padding(4.dp)
    ) {
        val segmentWidth = maxWidth / options.size.coerceAtLeast(1)
        val indicatorOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = EyeMotion.dpSpring,
            label = "分段滑块"
        )
        Box(
            Modifier
                .offset(x = indicatorOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .clip(EyeShapes.small)
                .background(colors.primary)
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight().selectableGroup()) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selectedIndex
                val textColor by animateColorAsState(
                    if (isSelected) colors.onPrimary else colors.textPrimary,
                    animationSpec = tween(EyeMotion.MEDIUM),
                    label = "分段文字色"
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(EyeShapes.small)
                        .selectable(selected = isSelected, role = Role.RadioButton) {
                            if (!isSelected) onSelect(option.value)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    EyeText(option.label, style = typography.button, color = textColor, textAlign = TextAlign.Center, maxLines = 1)
                }
            }
        }
    }
}
