package com.zkyjss.eyeproject.feature.services

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeSectionCard
import com.zkyjss.eyeproject.core.ui.component.EyeText

/**
 * 问卷题目。[options] 为 null 时是输入题（[numeric] 决定键盘）；
 * [optional] 题可留空继续；[note] 显示在题目下方的小字说明。
 * 多选题用「、」拼接存储；[exclusive] 中的选项与其他选项互斥（如「无」）。
 */
internal data class Question(
    val label: String,
    val options: List<String>? = null,
    val multiple: Boolean = false,
    val numeric: Boolean = false,
    val optional: Boolean = false,
    val note: String? = null,
    val exclusive: Set<String> = setOf("无", "无或不清楚", "不清楚"),
    /** 仅当返回 true 时出现（如手术细节只在选择了手术后出现）。 */
    val visibleWhen: (Map<String, String>) -> Boolean = { true },
    val validate: (String) -> Boolean = { true },
)

internal fun ageQuestion(range: IntRange, note: String? = null) = Question(
    label = "年龄", numeric = true, note = note,
    validate = { it.toIntOrNull()?.let { age -> age in range } == true },
)

/**
 * 逐题问卷：一屏一题，进度显示在卡片标题；所有题答完后可追加 [extraPage]（如老视模式选择）。
 * 题目可见性随已答内容动态变化。
 */
@Composable
internal fun QuestionnaireContent(
    questions: List<Question>,
    fields: Map<String, String>,
    onSetField: (String, String) -> Unit,
    onFinish: () -> Unit,
    extraPage: (@Composable () -> Unit)? = null,
    extraPageComplete: Boolean = true,
    stateKey: Any = Unit,
) {
    val visible = questions.filter { it.visibleWhen(fields) }
    var page by rememberSaveable(stateKey) { mutableIntStateOf(0) }
    val lastPage = visible.size - 1 + if (extraPage != null) 1 else 0
    val current = page.coerceIn(0, lastPage)
    EyeSectionCard(title = "${current + 1} / ${lastPage + 1}") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (current < visible.size) {
                val q = visible[current]
                QuestionField(q, fields[q.label].orEmpty()) { onSetField(q.label, it) }
            } else {
                extraPage?.invoke()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current > 0) EyeButton("上一步", EyeButtonRole.SECONDARY, { page = current - 1 }, Modifier.weight(1f))
                val canContinue = if (current < visible.size) {
                    val q = visible[current]
                    val value = fields[q.label].orEmpty()
                    (q.optional && value.isBlank()) || (value.isNotBlank() && q.validate(value))
                } else extraPageComplete
                EyeButton(
                    if (current < lastPage) "继续" else "完成",
                    EyeButtonRole.PRIMARY,
                    { if (current < lastPage) page = current + 1 else onFinish() },
                    Modifier.weight(1f),
                    canContinue
                )
            }
        }
    }
}

@Composable
private fun QuestionField(question: Question, value: String, onChange: (String) -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    EyeText(
        question.label + when {
            question.multiple -> "（可多选）"
            question.optional -> "（选填）"
            else -> ""
        },
        style = typography.subtitle, color = colors.textPrimary
    )
    question.note?.let { EyeText(it, style = typography.bodySecondary, color = colors.textSecondary) }
    val options = question.options
    if (options == null) {
        BasicTextField(
            value = value, onValueChange = onChange, textStyle = typography.body.copy(color = colors.textPrimary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = if (question.numeric) KeyboardType.Number else KeyboardType.Text),
            modifier = Modifier.fillMaxWidth().clip(EyeShapes.small).background(colors.surfaceVariant).padding(12.dp),
            decorationBox = { inner ->
                Box { if (value.isEmpty()) EyeText("请输入", style = typography.body, color = colors.textSecondary); inner() }
            }
        )
        return
    }
    options.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { option ->
                val selected = if (question.multiple) value.split("、").contains(option) else value == option
                EyeButton(option, if (selected) EyeButtonRole.PRIMARY else EyeButtonRole.SECONDARY, {
                    if (!question.multiple) onChange(option)
                    else {
                        val current = value.split("、").filter { it.isNotBlank() }
                        val updated = when {
                            selected -> current - option
                            option in question.exclusive -> listOf(option)
                            else -> current.filterNot { it in question.exclusive } + option
                        }
                        onChange(updated.joinToString("、"))
                    }
                }, Modifier.weight(1f))
            }
            if (pair.size == 1) Box(Modifier.weight(1f))
        }
    }
}
