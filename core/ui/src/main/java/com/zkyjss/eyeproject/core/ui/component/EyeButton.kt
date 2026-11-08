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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import com.zkyjss.eyeproject.core.designsystem.theme.eyeSoftSurface
import com.zkyjss.eyeproject.core.designsystem.theme.rememberReducedMotion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography

enum class EyeButtonRole { PRIMARY, SECONDARY, DANGER }

/** 浮雕按钮：主操作下方带渐变短线，按下呈内凹，保留角色与禁用语义。 */
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
            EyeButtonRole.PRIMARY -> colors.buttonStart
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
            EyeButtonRole.DANGER -> colors.textPrimary
        },
        animationSpec = tween(EyeMotion.MEDIUM),
        label = "按钮文字色"
    )
    val reduced = rememberReducedMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && !reduced) 0.98f else 1f,
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
            .eyeSoftSurface(colors, EyeShapes.medium, SolidColor(backgroundColor), pressed = pressed && enabled, compact = true)
            .drawBehind {
                if (role == EyeButtonRole.PRIMARY && enabled) {
                    val y = size.height - 5.dp.toPx()
                    drawDataGlowBar(colors, Offset(size.width * 0.38f, y - 1.5.dp.toPx()), Size(size.width * 0.24f, 3.dp.toPx()))
                    drawLine(colors.flowBrush, Offset(size.width * 0.38f, y), Offset(size.width * 0.62f, y), 3.dp.toPx(), StrokeCap.Round)
                }
            }
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
