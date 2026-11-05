package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.theme.rememberReducedMotion

/**
 * 启动画面（约 1.3 秒）：标志轻缓放大淡入 + 细进度线 +「眼健康助手」，结束后淡出。
 * 只盖在已经开始加载的首页上面，不推迟首页的创建；期间拦截点按。与苹果端 SplashView 同一节奏。
 */
@Composable
fun EyeSplash(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val reduced = rememberReducedMotion()
    val progress = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(if (reduced) 500 else 1_100, easing = EyeMotion.Standard))
        alpha.animateTo(0f, tween(EyeMotion.MEDIUM))
        onFinished()
    }
    Box(
        modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }
            .background(colors.appBackground)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .semantics { contentDescription = "正在启动眼健康助手" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val appear = (progress.value * 2.5f).coerceAtMost(1f)
            EyeBrandMark(Modifier.graphicsLayer {
                val k = 0.92f + 0.08f * appear
                scaleX = k; scaleY = k; this.alpha = appear
            }, size = 88.dp)
            Spacer(Modifier.height(20.dp))
            EyeText("眼健康助手", style = typography.title, color = colors.textPrimary)
            Spacer(Modifier.height(6.dp))
            EyeText("你的主动眼健康助手", style = typography.bodySecondary, color = colors.textSecondary)
            Spacer(Modifier.height(32.dp))
            Canvas(Modifier.width(120.dp).height(3.dp)) {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(colors.borderSubtle, cornerRadius = radius)
                drawRoundRect(colors.flowBrush, size = Size(size.width * progress.value, size.height), cornerRadius = radius)
            }
        }
    }
}
