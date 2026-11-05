package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.shadow
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
 * 主按钮用按钮渐变底（深色带泛光）；一屏只放一个主按钮，其余用 SECONDARY 或文字。
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
            EyeButtonRole.SECONDARY -> colors.panelBackground
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
            .then(
                // 主按钮：深色主题蓝色泛光，浅色主题一道柔和投影
                if (role == EyeButtonRole.PRIMARY && enabled) Modifier.shadow(if (colors.hasGlow) 14.dp else 10.dp, EyeShapes.medium,
                    ambientColor = if (colors.hasGlow) colors.glow else colors.primary, spotColor = if (colors.hasGlow) colors.glow else colors.primary)
                else if (enabled) Modifier.shadow(3.dp, EyeShapes.medium, ambientColor = colors.shadow, spotColor = colors.shadow)
                else Modifier
            )
            .clip(EyeShapes.medium)
            .then(if (role == EyeButtonRole.PRIMARY) Modifier.background(colors.buttonBrush) else Modifier.background(backgroundColor))
            // 次要按钮：面板底 + 描边，与主按钮区分轻重又不发虚
            .then(if (role == EyeButtonRole.SECONDARY) Modifier.border(1.dp, colors.cardBorder, EyeShapes.medium) else Modifier)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
            .defaultMinSize(minHeight = 52.dp)
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
