package com.zkyjss.eyeproject.feature.services

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeText

/**
 * 逐题问卷：一屏一题，顶部进度条；选项为整行可点的大按钮，单选题选完自动进入下一题。
 * 所有题答完后可追加 [extraPage]（如老视的「戴哪副眼镜」）。题目可见性随已答内容动态变化。
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
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val visible = questions.filter { it.visibleWhen(fields) }
    var page by rememberSaveable(stateKey) { mutableIntStateOf(0) }
    var forward by rememberSaveable(stateKey) { mutableStateOf(true) }
    val lastPage = visible.size - 1 + if (extraPage != null) 1 else 0
    val current = page.coerceIn(0, lastPage)
    fun go(to: Int) { forward = to > current; page = to }
    val progress by animateFloatAsState((current + 1f) / (lastPage + 1f), tween(EyeMotion.MEDIUM), label = "问卷进度")

    Column(
        Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EyeText("第 ${current + 1} 题 · 共 ${lastPage + 1} 题", style = typography.bodySecondary, color = colors.textSecondary)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(EyeShapes.small).background(colors.neutralSoft)) {
                Box(Modifier.fillMaxWidth(progress).height(6.dp).clip(EyeShapes.small).background(colors.primary))
            }
        }
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                val dir = if (forward) 1 else -1
                (slideInHorizontally(tween(EyeMotion.MEDIUM)) { it / 5 * dir } + fadeIn(tween(EyeMotion.MEDIUM))) togetherWith
                    (slideOutHorizontally(tween(EyeMotion.SHORT)) { -it / 5 * dir } + fadeOut(tween(EyeMotion.SHORT)))
            },
            label = "问卷翻页"
        ) { index ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (index < visible.size) {
                    val q = visible[index]
                    QuestionField(q, fields[q.key].orEmpty(), onChange = { onSetField(q.key, it) }, onSingleChosen = {
                        if (index < lastPage) go(index + 1)
                    })
                } else {
                    extraPage?.invoke()
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (current > 0) EyeButton("上一题", EyeButtonRole.SECONDARY, { go(current - 1) }, Modifier.weight(1f))
            val canContinue = if (current < visible.size) {
                val q = visible[current]
                val value = fields[q.key].orEmpty()
                (q.optional && value.isBlank()) || (value.isNotBlank() && q.validate(value))
            } else extraPageComplete
            EyeButton(
                when {
                    current < lastPage -> "下一题"
                    else -> "完成"
                },
                EyeButtonRole.PRIMARY,
                { if (current < lastPage) go(current + 1) else onFinish() },
                Modifier.weight(1f),
                canContinue
            )
        }
    }
}

@Composable
private fun QuestionField(question: Question, value: String, onChange: (String) -> Unit, onSingleChosen: () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EyeText(question.title, Modifier.weight(1f, fill = false), style = typography.title, color = colors.textPrimary)
        if (question.multiple) Tag("可多选")
        if (question.optional) Tag("可不填")
    }
    val options = question.options
    if (options == null) {
        BasicTextField(
            value = value, onValueChange = onChange, textStyle = typography.title.copy(color = colors.textPrimary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = if (question.numeric) KeyboardType.Number else KeyboardType.Text),
            modifier = Modifier.fillMaxWidth().clip(EyeShapes.medium).background(colors.surfaceVariant).padding(16.dp),
            decorationBox = { inner ->
                Box { if (value.isEmpty()) EyeText(if (question.numeric) "请输入数字" else "请输入", style = typography.title, color = colors.textSecondary); inner() }
            }
        )
        return
    }
    // 单选题：选完稍作停顿再翻页，让人看清自己选了什么
    var pendingAdvance by rememberSaveable(question.key) { mutableStateOf(false) }
    LaunchedEffect(pendingAdvance) {
        if (pendingAdvance) {
            kotlinx.coroutines.delay(280)
            pendingAdvance = false
            onSingleChosen()
        }
    }
    // 按完整选项匹配，避免把选项文案里的「、」误拆成多条答案；保留作答顺序。
    val storedOptions = "、$value、"
    val selectedOptions = options.filter { "、$it、" in storedOptions }
        .sortedBy { storedOptions.indexOf("、$it、") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val selected = if (question.multiple) option in selectedOptions else value == option
            OptionRow(option, question.hints[option], selected, question.multiple) {
                if (!question.multiple) {
                    onChange(option)
                    pendingAdvance = true
                } else {
                    val current = selectedOptions
                    val updated = when {
                        selected -> current - option
                        option in question.exclusive -> listOf(option)
                        else -> current.filterNot { it in question.exclusive } + option
                    }
                    onChange(updated.joinToString("、"))
                }
            }
        }
    }
}

/** 整行可点的选项：左侧单选圆点 / 多选方框，选中时整行高亮。 */
@Composable
internal fun OptionRow(label: String, hint: String?, selected: Boolean, multiple: Boolean, onClick: () -> Unit) {
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

@Composable
private fun Tag(text: String) {
    val colors = LocalEyeColors.current
    EyeText(
        text, style = LocalEyeTypography.current.chip, color = colors.primary,
        modifier = Modifier.clip(EyeShapes.chip).background(colors.primarySoft).padding(horizontal = 10.dp, vertical = 4.dp)
    )
}
