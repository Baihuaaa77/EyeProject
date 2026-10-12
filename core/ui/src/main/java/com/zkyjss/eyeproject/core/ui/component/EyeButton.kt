package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography

enum class EyeButtonRole { PRIMARY, SECONDARY, DANGER }

/**
 * 自定义按钮，三态（primary/secondary/danger），不使用 Material Button。
 * 交互：按下以弹簧缩放到 96%，角色切换（如 开始↔停止）时底色平滑过渡。
 */
@Composable
fun EyeButton(
    text: String,
    role: EyeButtonRole,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // 全部走设计令牌，不在组件里硬写颜色
    val backgroundColor by animateColorAsState(
        targetValue = when (role) {
            EyeButtonRole.PRIMARY -> colors.primary
            EyeButtonRole.SECONDARY -> colors.surfaceVariant
            EyeButtonRole.DANGER -> colors.dangerSoft
        },
        animationSpec = tween(EyeMotion.MEDIUM),
        label = "按钮底色"
    )
    val contentColor by animateColorAsState(
        targetValue = when (role) {
            EyeButtonRole.PRIMARY -> colors.onPrimary
            EyeButtonRole.SECONDARY -> colors.textPrimary
            EyeButtonRole.DANGER -> colors.danger
        },
        animationSpec = tween(EyeMotion.MEDIUM),
        label = "按钮文字色"
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.96f else 1f,
        animationSpec = EyeMotion.pressSpring(),
        label = "按压缩放"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.5f
            }
            .clip(EyeShapes.medium)
            .background(backgroundColor)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        EyeText(
            text = text,
            style = typography.button,
            color = contentColor,
            textAlign = TextAlign.Center
        )
    }
}
