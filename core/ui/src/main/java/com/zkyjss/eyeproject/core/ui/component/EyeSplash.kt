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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.theme.rememberReducedMotion

/**
 * 启动动画（约 1.3 秒）：小瞳蹦出来打招呼 + 加载进度条 + 「你的主动眼健康助手」，结束后淡出。
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
            .background(Brush.verticalGradient(listOf(colors.primarySoft, colors.appBackground)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .semantics { contentDescription = "正在启动眼健康助手" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            XiaoTong(MascotMood.HAPPY, Modifier.size(168.dp), interactive = false, greet = true, headScale = 0.66f)
            Spacer(Modifier.height(12.dp))
            EyeText("眼健康助手", style = typography.title, color = colors.textPrimary)
            Spacer(Modifier.height(6.dp))
            EyeText("你的主动眼健康助手", style = typography.body, color = colors.textSecondary)
            Spacer(Modifier.height(28.dp))
            Canvas(Modifier.width(168.dp).height(6.dp)) {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(colors.surfaceVariant, cornerRadius = radius)
                drawRoundRect(Brush.horizontalGradient(colors.brandColors), size = Size(size.width * progress.value, size.height), cornerRadius = radius)
            }
        }
    }
}
