package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class SegmentedOption<T>(val value: T, val label: String)

/** 自定义分段选择器：选中的项高亮为主色。 */
@Composable
fun <T> EyeSegmentedRow(
    options: List<SegmentedOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth()) {
        for ((index, option) in options.withIndex()) {
            val isSelected = option.value == selected
            EyeButton(
                text = option.label,
                role = if (isSelected) EyeButtonRole.PRIMARY else EyeButtonRole.SECONDARY,
                onClick = { if (!isSelected) onSelect(option.value) },
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (index < options.lastIndex) {
                            Modifier.padding(end = 8.dp)
                        } else {
                            Modifier
                        }
                    )
            )
        }
    }
}
