package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography

/**
 * 入口卡片：可选前置图标（放在 [leadingTone] 色调的浅色圆角底上）+ 标题/副标题 + 右箭头，按下弹簧轻缩。
 */
@Composable
fun EyeSelectCard(
    modifier: Modifier = Modifier,
    content: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    /** 前置图标块的配色（底色取该色调的浅块）。 */
    leadingTone: EyeTileTone = EyeTileTone.PRIMARY,
    onClick: () -> Unit
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, EyeMotion.pressSpring(), label = "卡片按压")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .eyeGlass(colors, EyeShapes.panel)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .heightIn(min = 80.dp)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            Box(
                Modifier.size(48.dp).clip(EyeShapes.medium).background(leadingTone.colors().first),
                contentAlignment = Alignment.Center
            ) { leading() }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            // 没有说明小字时标题用大一号字（服务页四个自测入口）
            EyeText(text = content, style = if (subtitle == null) typography.title else typography.subtitle, color = colors.textPrimary)
            if (subtitle != null) {
                EyeText(text = subtitle, style = typography.bodySecondary, color = colors.textSecondary)
            }
        }
        EyeIcon(
            modifier = Modifier.padding(start = 8.dp).size(20.dp),
            painter = painterResource(com.zkyjss.eyeproject.core.designsystem.R.drawable.ic_arrow_right),
            contentDescription = null,
            tint = colors.primary
        )
    }
}
