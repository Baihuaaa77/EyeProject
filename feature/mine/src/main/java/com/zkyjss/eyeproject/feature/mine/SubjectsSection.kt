package com.zkyjss.eyeproject.feature.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeSectionCard
import com.zkyjss.eyeproject.core.ui.component.EyeSelectCard
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.feature.services.SubjectProfile
import com.zkyjss.eyeproject.feature.services.SubjectProfiles

/** 「家庭成员与资料」：每人一行（称呼 · 关系 · 年龄 · 已填项数），点开编辑；可添加家庭成员。与苹果端一致。 */
@Composable
internal fun SubjectsSection(subjects: List<SubjectProfile>, onEdit: (String?) -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    EyeSectionCard(title = "家庭成员与资料") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EyeText("测试前选择这次测谁，资料自动带入，不用每次填写问卷。", style = typography.bodySecondary, color = colors.textSecondary)
            subjects.forEach { s ->
                EyeSelectCard(content = s.nickname.ifBlank { SubjectProfiles.relationLabel(s.relation) }, subtitle = summary(s),
                    onClick = { onEdit(s.id) })
            }
            EyeButton("添加家庭成员", EyeButtonRole.SECONDARY, { onEdit(null) }, Modifier)
        }
    }
}

/** 一行摘要：关系 · 年龄 · 已填几项（空资料提示去完善）。 */
internal fun summary(s: SubjectProfile, nowMs: Long = System.currentTimeMillis()): String {
    val total = SubjectProfiles.questions.count { it.visibleWhen(s.fields) }
    val filled = SubjectProfiles.questions.count { it.visibleWhen(s.fields) && !s.fields[it.key].isNullOrBlank() }
    val age = SubjectProfiles.age(s.fields, nowMs)?.let { "$it 岁" }
    return listOfNotNull(SubjectProfiles.relationLabel(s.relation), age,
        if (filled == 0) "资料未填写" else "已填 $filled/$total 项").joinToString(" · ")
}
