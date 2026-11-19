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
import com.zkyjss.eyeproject.core.ui.component.eyeGlass
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeChoiceList
import com.zkyjss.eyeproject.core.ui.component.EyeOptionRow
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.EyeOfflineSession
import com.zkyjss.eyeproject.core.ui.component.EyeOfflineSessionPage

/**
 * 测试开始页「这次测谁？」：长期资料在「我的 → 家庭成员」维护，测试时自动带入；
 * 选人和编辑分开操作，缺项通过独立按钮补充。会随时间变化的几个问题选人后在下方现问（[PreTestQuestions]）。与苹果端一致。
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
    onContinue: () -> Unit = {},
    questions: @Composable () -> Unit = {},
) {
    var practiceOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val practice = when (kind) {
        "DRY_EYE" -> EyeOfflineSession.GARDEN
        "STRABISMUS" -> EyeOfflineSession.FIREFLY
        "COLOR_VISION" -> EyeOfflineSession.COLOR
        "PRESBYOPIA" -> EyeOfflineSession.POST
        else -> null
    }
    var skipped by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    var selectedVersion by remember(selectedId) { mutableStateOf(subjects.firstOrNull { it.id == selectedId }?.updatedAt) }
    LaunchedEffect(subjects,selectedId) {
        subjects.firstOrNull { it.id == selectedId }?.let { profile ->
            // 仅资料编辑/同步后刷新，不能清掉「换一种戴镜方式再测」保留的本次问卷。
            if (selectedVersion != null && selectedVersion != profile.updatedAt) onPick(profile)
            selectedVersion = profile.updatedAt
        }
    }
    Column(
        Modifier.fillMaxWidth().eyeGlass(colors, EyeShapes.panel).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EyeText("这次测谁？", style = typography.title, color = colors.textPrimary)
        subjects.forEach { s ->
            val missing = SubjectProfiles.missing(kind, s, nowMs)
            val name = s.nickname.ifBlank { SubjectProfiles.relationLabel(s.relation) }
            val detail = listOfNotNull(SubjectProfiles.relationLabel(s.relation), SubjectProfiles.age(s.fields, nowMs)?.let { "$it 岁" })
                .joinToString(" · ") + if (missing.isEmpty()) "" else "\n需补充：${missing.joinToString("、")}"
            EyeOptionRow(name, detail, selected = !skipped && s.id == selectedId, multiple = false) {
                skipped = false; onPick(s)
            }
            EyeButton(if ("出生年份" in missing) "补充出生年份" else "编辑资料", EyeButtonRole.SECONDARY, { onEdit(s.id) })
        }
        EyeButton("添加家庭成员", EyeButtonRole.SECONDARY, { onEdit(null) }, Modifier.fillMaxWidth())
        EyeOptionRow("访客测试", hint = null, selected = skipped, multiple = false) { skipped = true }
        if (skipped) EyeButton("继续", EyeButtonRole.PRIMARY, onSkip, Modifier.fillMaxWidth())
        else if (selectedId != null) {
            val profile = subjects.firstOrNull { it.id == selectedId }
            if (profile != null && SubjectProfiles.missing(kind,profile,nowMs).isEmpty()) {
                if (kind == "COLOR_VISION") EyeButton("继续", EyeButtonRole.PRIMARY, onContinue, Modifier.fillMaxWidth())
                else questions()
            }
        }
        if (practice != null) {
            EyeButton("无相机试玩", EyeButtonRole.SECONDARY, { practiceOpen = true }, Modifier.fillMaxWidth())
        }
    }
    if (practiceOpen && practice != null) EyeOfflineSessionPage(practice, { practiceOpen = false })
}

/**
 * 测前快问（[PreTestCheck]）：选好人后在开始页下方问两三个这几天的情况（老视为戴镜问卷），答完「开始测试」。
 * 答案只写入这次记录；换人时清空重答，[initial] 为预填答案（老视换一种戴镜方式再测时保留的前几题）。
 * 与苹果端 PreTestCheckView 同一排版与文案。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PreTestQuestions(kind: String, subjectId: String, initial: Map<String, String> = emptyMap(), onConfirm: (Map<String, String>) -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    var answers by remember(subjectId) { mutableStateOf(initial) }
    val requester = remember { BringIntoViewRequester() }
    Column(
        Modifier.fillMaxWidth().bringIntoViewRequester(requester).eyeGlass(colors, EyeShapes.panel).padding(20.dp),
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
