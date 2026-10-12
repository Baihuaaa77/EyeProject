package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.R
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography

/** 次级信息默认收起；展开以高度+透明度过渡，箭头旋转指示状态，展开状态可由无障碍服务读取。 */
@Composable
fun EyeDisclosure(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val colors = LocalEyeColors.current
    val chevronAngle by animateFloatAsState(if (expanded) 90f else 0f, tween(EyeMotion.MEDIUM, easing = EyeMotion.Standard), label = "展开箭头")
    EyeSectionCard {
        Row(
            Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .clickable(role = Role.Button) { expanded = !expanded }.heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EyeText(title, Modifier.weight(1f), style = LocalEyeTypography.current.body, color = colors.textPrimary)
            EyeIcon(
                painter = painterResource(R.drawable.ic_arrow_right),
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(18.dp).rotate(chevronAngle)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(EyeMotion.MEDIUM, easing = EyeMotion.Standard)) + fadeIn(tween(EyeMotion.MEDIUM)),
            exit = shrinkVertically(tween(EyeMotion.MEDIUM, easing = EyeMotion.Standard)) + fadeOut(tween(EyeMotion.SHORT)),
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            }
        }
    }
}
