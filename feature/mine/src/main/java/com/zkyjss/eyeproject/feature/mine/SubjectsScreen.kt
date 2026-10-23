package com.zkyjss.eyeproject.feature.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import com.zkyjss.eyeproject.core.ui.component.EyeIcon
import com.zkyjss.eyeproject.core.ui.component.rememberEyeHaptics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.ui.component.EyeHelpButton
import com.zkyjss.eyeproject.core.ui.component.EyeListGroup
import com.zkyjss.eyeproject.core.ui.component.EyeListRow
import com.zkyjss.eyeproject.core.ui.component.EyeSubPage
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.feature.services.SubjectProfile
import com.zkyjss.eyeproject.feature.services.SubjectProfiles

internal const val MEMBERS_HELP = "每位家人一份资料（年龄、戴镜、眼病史等）。测试开始时只需选「这次测谁」，资料自动带入，结果也归到这个人名下。"

/** 「我的 → 家庭成员与资料」完整页面：每人一行（头像字 · 称呼 · 关系与年龄），点开编辑；末行添加成员。与苹果端同一排版。 */
@Composable
fun SubjectsScreen(onBack: () -> Unit, onEdit: (String?) -> Unit, viewModel: MineViewModel = hiltViewModel()) {
    val subjects by viewModel.subjects.collectAsStateWithLifecycle()
    SubjectsContent(subjects, onBack, onEdit)
}

@Composable
internal fun SubjectsContent(subjects: List<SubjectProfile>, onBack: () -> Unit, onEdit: (String?) -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    EyeSubPage("家庭成员", onBack) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
            EyeText("共 ${subjects.size} 人", style = typography.body, color = colors.textSecondary)
            EyeHelpButton("家庭成员", MEMBERS_HELP)
        }
        EyeListGroup {
            subjects.forEach { s ->
                val name = s.nickname.ifBlank { SubjectProfiles.relationLabel(s.relation) }
                MemberRow(name, summary(s)) { onEdit(s.id) }
            }
            EyeListRow("添加家庭成员", onClick = { onEdit(null) }, titleColor = colors.primary, divider = false,
                trailing = { Box(Modifier.size(36.dp).clip(CircleShape).background(colors.primarySoft), contentAlignment = Alignment.Center) {
                    EyeText("+", style = typography.title, color = colors.primary)
                } })
        }
    }
}

/** 成员行：首字头像 + 称呼 + 一行摘要 + 箭头，与列表行同一高度与分隔线。 */
@Composable
private fun MemberRow(name: String, summary: String, onClick: () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { haptics.tap(); onClick() }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(colors.primarySoft), contentAlignment = Alignment.Center) {
                EyeText(name.take(1), style = typography.subtitle, color = colors.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                EyeText(name, style = typography.body, color = colors.textPrimary)
                EyeText(summary, style = typography.bodySecondary, color = colors.textSecondary)
            }
            EyeIcon(painterResource(com.zkyjss.eyeproject.core.designsystem.R.drawable.ic_arrow_right), null, Modifier.size(16.dp), tint = colors.gray)
        }
        Box(Modifier.padding(start = 68.dp).fillMaxWidth().height(1.dp).background(colors.borderSubtle))
    }
}

/** 一行摘要：关系 · 年龄；还没填过资料时提示「待完善」。 */
internal fun summary(s: SubjectProfile, nowMs: Long = System.currentTimeMillis()): String =
    listOfNotNull(SubjectProfiles.relationLabel(s.relation), SubjectProfiles.age(s.fields, nowMs)?.let { "$it 岁" },
        "资料待完善".takeIf { s.fields.values.none { it.isNotBlank() } }).joinToString(" · ")

/** 本人资料的完善程度（年龄、职业、视力、眼病史、性别）：已填项数 / 5。 */
internal fun profileCompleteness(s: SubjectProfile?): Int {
    val f = s?.fields ?: return 0
    return listOf(f["出生年份"], f["职业"], f["基础视力"] ?: f["日常矫正方式"], f["既往眼病"], f["性别"]).count { !it.isNullOrBlank() }
}
