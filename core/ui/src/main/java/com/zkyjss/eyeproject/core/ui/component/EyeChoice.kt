package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography

/**
 * 选项列表（单选 / 多选）：整行可点的大按钮。多选答案用「、」拼接存储，按完整选项匹配，
 * 不会把选项文案里的「、」误拆；选中 [exclusive] 中的「没有 / 不清楚」类选项时清空其他选项。
 */
@Composable
fun EyeChoiceList(
    options: List<String>,
    value: String,
    multiple: Boolean,
    onChange: (String) -> Unit,
    hints: Map<String, String> = emptyMap(),
    exclusive: Set<String> = emptySet(),
) {
    val stored = "、$value、"
    val selectedOptions = options.filter { "、$it、" in stored }.sortedBy { stored.indexOf("、$it、") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val selected = if (multiple) option in selectedOptions else value == option
            EyeOptionRow(option, hints[option], selected, multiple) {
                if (!multiple) onChange(if (selected) "" else option)
                else {
                    val updated = when {
                        selected -> selectedOptions - option
                        option in exclusive -> listOf(option)
                        else -> selectedOptions.filterNot { it in exclusive } + option
                    }
                    onChange(updated.joinToString("、"))
                }
            }
        }
    }
}

/** 整行可点的选项：左侧单选圆点 / 多选方框，选中时整行高亮。 */
@Composable
fun EyeOptionRow(label: String, hint: String?, selected: Boolean, multiple: Boolean, onClick: () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val bg by animateColorAsState(if (selected) colors.primarySoft else colors.surfaceVariant, tween(EyeMotion.SHORT), label = "选项底色")
    val border by animateColorAsState(if (selected) colors.primary else colors.surfaceVariant, tween(EyeMotion.SHORT), label = "选项边框")
    val selection = if (multiple) {
        Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
    } else {
        Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(EyeShapes.medium).background(bg)
            .border(2.dp, border, EyeShapes.medium).then(selection).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SelectionMark(selected, multiple)
        Column(Modifier.weight(1f)) {
            EyeText(label, style = typography.body, color = colors.textPrimary)
            if (hint != null) EyeText(hint, style = typography.bodySecondary, color = colors.textSecondary)
        }
    }
}

@Composable
private fun SelectionMark(selected: Boolean, multiple: Boolean) {
    val colors = LocalEyeColors.current
    Canvas(Modifier.size(22.dp)) {
        val s = size.minDimension
        val stroke = s * 0.09f
        if (multiple) {
            if (selected) {
                drawRoundRect(colors.primary, Offset.Zero, Size(s, s), CornerRadius(s * 0.25f))
                drawPath(Path().apply {
                    moveTo(s * 0.25f, s * 0.52f); lineTo(s * 0.43f, s * 0.7f); lineTo(s * 0.76f, s * 0.32f)
                }, colors.onPrimary, style = Stroke(stroke * 1.3f, cap = StrokeCap.Round))
            } else {
                drawRoundRect(colors.gray, Offset(stroke / 2, stroke / 2), Size(s - stroke, s - stroke), CornerRadius(s * 0.25f), style = Stroke(stroke))
            }
        } else {
            drawCircle(if (selected) colors.primary else colors.gray, s / 2 - stroke / 2, style = Stroke(stroke))
            if (selected) drawCircle(colors.primary, s * 0.26f)
        }
    }
}
