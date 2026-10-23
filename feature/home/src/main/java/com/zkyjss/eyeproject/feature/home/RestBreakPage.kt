package com.zkyjss.eyeproject.feature.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingLarge
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeFullScreenPage
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.MascotMood
import com.zkyjss.eyeproject.core.ui.component.TopBackBar
import com.zkyjss.eyeproject.core.ui.component.XiaoTong
import com.zkyjss.eyeproject.core.ui.component.rememberEyeHaptics
import com.zkyjss.eyeproject.core.ui.component.rememberMascotSounds
import kotlin.math.ceil

/** 休息秒数（20-20-20：每看屏约 20 分钟，看 6 米外 20 秒）。 */
private const val BREAK_SECONDS = 20

/**
 * 「现在休息 20 秒」：小瞳陪你闭目养神 / 看远处，圆环倒计时，结束时小瞳蹦一下并有提示音与轻触觉。
 * 盖在首页上的完整页面，返回键或「提前结束」可退出。苹果端 RestBreakView 同一流程。
 */
@Composable
internal fun RestBreakPage(onClose: () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    val sounds = rememberMascotSounds()
    val progress = remember { Animatable(0f) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(BREAK_SECONDS * 1_000, easing = LinearEasing))
        done = true
        sounds.play("eye_hop")
        haptics.success()
    }
    EyeFullScreenPage(onClose) {
        Column(Modifier.fillMaxSize()) {
            TopBackBar(title = "休息一下", noText = true, onBackClick = onClose)
            Column(Modifier.weight(1f).fillMaxWidth().screenHorizontalPaddingLarge(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val stroke = 10.dp.toPx()
                        val arc = Size(size.width - stroke, size.height - stroke)
                        drawArc(colors.surfaceVariant, 0f, 360f, false, Offset(stroke / 2, stroke / 2), arc, style = Stroke(stroke))
                        drawArc(colors.primary, -90f, 360f * progress.value, false, Offset(stroke / 2, stroke / 2), arc,
                            style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                    XiaoTong(if (done) MascotMood.HAPPY else MascotMood.SLEEPY, Modifier.size(170.dp), greet = done, headScale = 0.62f)
                }
                Spacer(Modifier.height(20.dp))
                val left = ceil(BREAK_SECONDS * (1f - progress.value)).toInt()
                EyeText(if (done) "做得好！" else "$left 秒", style = typography.headline, color = colors.textPrimary)
                Spacer(Modifier.height(8.dp))
                EyeText(if (done) "眼睛放松一下就舒服多了，继续保持。" else "看向 6 米外的远处，慢慢眨几下眼，放松肩膀。",
                    style = typography.body, color = colors.textSecondary, textAlign = TextAlign.Center)
            }
            Column(Modifier.fillMaxWidth().screenHorizontalPaddingLarge().padding(vertical = 16.dp).navigationBarsPadding()) {
                EyeButton(if (done) "完成" else "提前结束", if (done) EyeButtonRole.PRIMARY else EyeButtonRole.SECONDARY, onClose, Modifier.fillMaxWidth())
            }
        }
    }
}
