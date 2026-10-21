package com.zkyjss.eyeproject.feature.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.ui.component.EyeIcon
import com.zkyjss.eyeproject.core.ui.component.EyeSectionCard
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.rememberEyeHaptics
import com.zkyjss.eyeproject.feature.services.SubjectProfile
import com.zkyjss.eyeproject.feature.services.SubjectProfiles

/** 「家庭成员」：每人一行（头像字 · 称呼 · 关系与年龄），点开编辑资料；末行添加成员。与苹果端同一排版。 */
@Composable
internal fun SubjectsSection(subjects: List<SubjectProfile>, onEdit: (String?) -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    EyeSectionCard(title = "家庭成员") {
        Column {
            subjects.forEach { s ->
                val name = s.nickname.ifBlank { SubjectProfiles.relationLabel(s.relation) }
                MemberRow(onClick = { haptics.tap(); onEdit(s.id) }) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(colors.primarySoft), contentAlignment = Alignment.Center) {
                        EyeText(name.take(1), style = typography.subtitle, color = colors.primary)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        EyeText(name, style = typography.body, color = colors.textPrimary)
                        EyeText(summary(s), style = typography.bodySecondary, color = colors.textSecondary)
                    }
                    EyeIcon(painterResource(com.zkyjss.eyeproject.core.designsystem.R.drawable.ic_arrow_right),
                        contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.gray)
                }
            }
            MemberRow(onClick = { haptics.tap(); onEdit(null) }) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
                    EyeText("+", style = typography.title, color = colors.primary)
                }
                EyeText("添加家庭成员", Modifier.weight(1f).padding(start = 12.dp), style = typography.body, color = colors.primary)
            }
        }
    }
}

@Composable
private fun MemberRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) { content() }
}

/** 一行摘要：关系 · 年龄；还没填过资料时提示「待完善」。 */
internal fun summary(s: SubjectProfile, nowMs: Long = System.currentTimeMillis()): String =
    listOfNotNull(SubjectProfiles.relationLabel(s.relation), SubjectProfiles.age(s.fields, nowMs)?.let { "$it 岁" },
        "资料待完善".takeIf { s.fields.values.none { it.isNotBlank() } }).joinToString(" · ")
