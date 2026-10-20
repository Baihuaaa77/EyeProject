package com.zkyjss.eyeproject.feature.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.util.getTopStatusBarPadding
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingLarge
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeChoiceList
import com.zkyjss.eyeproject.core.ui.component.EyeSectionCard
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.EyeTextField
import com.zkyjss.eyeproject.core.ui.component.TopBackBar
import com.zkyjss.eyeproject.feature.services.Question
import com.zkyjss.eyeproject.feature.services.QuestionInput
import com.zkyjss.eyeproject.feature.services.SubjectProfile
import com.zkyjss.eyeproject.feature.services.SubjectProfiles

/** 被测者资料编辑页：原先每项测试开头的问卷都在这里填写一次，测试时只需选人。 */
@Composable
fun SubjectEditScreen(onBack: () -> Unit, viewModel: SubjectEditViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val draft = state.draft ?: return
    SubjectEditContent(
        draft = draft, isNew = state.isNew, error = state.error,
        onBack = onBack,
        onNickname = viewModel::setNickname, onRelation = viewModel::setRelation, onField = viewModel::setField,
        onSave = { viewModel.save(onBack) },
        onDelete = { viewModel.delete(onBack) },
    )
}

@Composable
internal fun SubjectEditContent(
    draft: SubjectProfile,
    isNew: Boolean,
    error: String?,
    onBack: () -> Unit,
    onNickname: (String) -> Unit,
    onRelation: (String) -> Unit,
    onField: (String, String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val self = draft.relation == "SELF"
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(colors.appBackground).padding(top = getTopStatusBarPadding())) {
        TopBackBar(title = if (isNew) "添加家庭成员" else "${draft.nickname.ifBlank { "成员" }}的资料", onBackClick = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).screenHorizontalPaddingLarge(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EyeText("测试时只需选择成员，不用每次填写问卷。都可以先不填，测试会提示缺少哪些必需项。",
                style = typography.bodySecondary, color = colors.textSecondary)
            EyeSectionCard(title = "成员") {
                EyeTextField(draft.nickname, onNickname, label = "称呼", placeholder = if (self) "我" else "如：小明、妈妈")
                if (!self) {
                    Spacer(Modifier.height(12.dp))
                    EyeText("与你的关系", style = typography.subtitle, color = colors.textPrimary)
                    Spacer(Modifier.height(8.dp))
                    val relations = SubjectProfiles.RELATIONS.filter { it.first != "SELF" }
                    EyeChoiceList(relations.map { it.second }, SubjectProfiles.relationLabel(draft.relation), multiple = false,
                        onChange = { label -> relations.firstOrNull { it.second == label }?.let { onRelation(it.first) } })
                }
            }
            SubjectProfiles.sections.forEach { section ->
                val visible = section.questions.filter { it.visibleWhen(draft.fields) }
                EyeSectionCard(title = section.title) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        visible.forEach { q -> ProfileQuestion(q, draft.fields[q.key].orEmpty()) { onField(q.key, it) } }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Column(Modifier.fillMaxWidth().screenHorizontalPaddingLarge().padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (error != null) EyeText(error, style = typography.body, color = colors.danger)
            if (confirmDelete) EyeText("将删除该成员的资料；已保存的测试记录保留在本机。再点一次「确认删除」继续。",
                style = typography.bodySecondary, color = colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!self && !isNew) EyeButton(if (confirmDelete) "确认删除" else "删除成员", EyeButtonRole.DANGER,
                    { if (confirmDelete) onDelete() else confirmDelete = true }, Modifier.weight(1f))
                EyeButton("保存", EyeButtonRole.PRIMARY, onSave, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ProfileQuestion(question: Question, value: String, onChange: (String) -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val options = question.options
        if (options == null) {
            EyeTextField(value, { onChange(question.filterInput(it)) }, label = question.title,
                placeholder = if (question.key == SubjectProfiles.BIRTH_YEAR) "如：1980" else question.placeholder,
                keyboardType = when (question.inputKind) {
                    QuestionInput.INTEGER -> KeyboardType.Number
                    QuestionInput.DECIMAL -> KeyboardType.Text // 需要可输入正负号，部分数字键盘不提供。
                    QuestionInput.DATE, QuestionInput.TEXT -> KeyboardType.Text
                })
        } else {
            EyeText(question.title + if (question.multiple) "（可多选）" else "", style = typography.subtitle, color = colors.textPrimary)
            EyeChoiceList(options, value, question.multiple, onChange, question.hints, question.exclusive)
        }
    }
}
