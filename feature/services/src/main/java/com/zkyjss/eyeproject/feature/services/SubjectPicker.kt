package com.zkyjss.eyeproject.feature.services

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import com.zkyjss.eyeproject.core.ui.component.EyeOptionRow
import com.zkyjss.eyeproject.core.ui.component.EyeText

/**
 * 测试开始页「这次测谁？」：替代原先的逐题问卷。资料在「我的 → 家庭成员」维护；
 * 缺少本测试必需项的成员点开即去补充，补好返回本页即可开始。与苹果端一致。
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
