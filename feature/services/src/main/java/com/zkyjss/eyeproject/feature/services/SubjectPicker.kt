package com.zkyjss.eyeproject.feature.services

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeChoiceList
import com.zkyjss.eyeproject.core.ui.component.EyeOptionRow
import com.zkyjss.eyeproject.core.ui.component.EyeText

/**
 * 测试开始页「这次测谁？」：长期资料在「我的 → 家庭成员」维护，测试时自动带入；
 * 缺少本测试必需项的成员点开即去补充。会随时间变化的几个问题选人后在下方现问（[PreTestQuestions]）。与苹果端一致。
 */
@Composable
internal fun SubjectPicker(
    kind: String,
    subjects: List<SubjectProfile>,
    selectedId: String?,
    onPick: (SubjectProfile) -> Unit,
    onEdit: (String?) -> Unit,
    onSkip: () -> Unit,
    nowMs: Long = System.currentTimeMillis(),
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(
        Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EyeText(if (kind == "STRABISMUS") "这次给谁测？" else "这次测谁？", style = typography.title, color = colors.textPrimary)
        EyeText("资料在「我的 → 家庭成员」中填写，测试时自动带入。", style = typography.bodySecondary, color = colors.textSecondary)
        subjects.forEach { s ->
            val missing = SubjectProfiles.missing(kind, s, nowMs)
            val name = s.nickname.ifBlank { SubjectProfiles.relationLabel(s.relation) }
            val detail = listOfNotNull(SubjectProfiles.relationLabel(s.relation), SubjectProfiles.age(s.fields, nowMs)?.let { "$it 岁" })
                .joinToString(" · ") + if (missing.isEmpty()) "" else "\n需补充：${missing.joinToString("、")}（点此去填写）"
            EyeOptionRow(name, detail, selected = s.id == selectedId, multiple = false) {
                if (missing.isEmpty()) onPick(s) else onEdit(s.id)
            }
        }
        EyeButton("添加家庭成员", EyeButtonRole.SECONDARY, { onEdit(null) }, Modifier.fillMaxWidth())
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onSkip).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center) {
                EyeText("暂不选择，直接测试", style = typography.body, color = colors.primary)
            }
            EyeText("不选成员时，依赖年龄和症状的判断会注明无法评估。", style = typography.bodySecondary, color = colors.textSecondary)
        }
    }
}

/**
 * 测前快问（[PreTestCheck]）：选好人后在开始页下方问两三个这几天的情况，答完「开始测试」。
 * 答案只写入这次记录；换人时清空重答。与苹果端 PreTestCheckView 同一排版与文案。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PreTestQuestions(kind: String, subjectId: String, onConfirm: (Map<String, String>) -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    var answers by remember(subjectId) { mutableStateOf(emptyMap<String, String>()) }
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(subjectId) { requester.bringIntoView() }
    Column(
        Modifier.fillMaxWidth().bringIntoViewRequester(requester).clip(EyeShapes.panel).background(colors.panelBackground).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        EyeText("这次的情况", style = typography.title, color = colors.textPrimary)
        PreTestCheck.visible(kind, answers).forEach { question ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EyeText(question.title + if (question.multiple) "（可多选）" else "", style = typography.subtitle, color = colors.textPrimary)
                EyeChoiceList(question.options.orEmpty(), answers[question.key].orEmpty(), question.multiple,
                    { value -> answers = answers + (question.key to value) }, question.hints, question.exclusive)
            }
        }
        EyeButton("开始测试", EyeButtonRole.PRIMARY, { onConfirm(PreTestCheck.clean(kind, answers)) }, Modifier.fillMaxWidth(),
            enabled = PreTestCheck.complete(kind, answers))
    }
}
