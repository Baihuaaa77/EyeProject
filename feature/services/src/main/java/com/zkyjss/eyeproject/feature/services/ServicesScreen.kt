package com.zkyjss.eyeproject.feature.services

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.theme.rememberReducedMotion
import com.zkyjss.eyeproject.core.designsystem.util.getTopStatusBarPadding
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingLarge
import com.zkyjss.eyeproject.core.ui.component.EyePageHeader
import com.zkyjss.eyeproject.core.ui.component.EyeSelectCard
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.drawMascot
import com.zkyjss.eyeproject.core.ui.component.drawTumblingE
import com.zkyjss.eyeproject.core.ui.component.leafEyePath
import kotlin.math.abs

/** 服务页：品牌欢迎卡 + 三个自测入口。 */
@Composable
fun ServicesScreen(
    onDryEyeClick: () -> Unit,
    onPresbyopiaClick: () -> Unit,
    onStrabismusClick: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.appBackground)
            .verticalScroll(rememberScrollState())
            .padding(top = getTopStatusBarPadding())
            .screenHorizontalPaddingLarge(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        EyePageHeader("服务", onOpenSettings)
        HeroCard()
        Spacer(Modifier.height(4.dp))
        EyeText("选择一项自测", style = typography.subtitle, color = colors.textPrimary)

        EyeSelectCard(
            content = "干眼自测",
            subtitle = "约 2 分钟 · 自然眨眼与睁眼观察",
            leading = { ServiceGlyph(ServiceKind.DRY_EYE) },
            onClick = onDryEyeClick
        )
        EyeSelectCard(
            content = "眼位自测",
            subtitle = "注视、遮眼、跟随 · 需一位协助者",
            leading = { ServiceGlyph(ServiceKind.ALIGNMENT) },
            onClick = onStrabismusClick,
        )
        EyeSelectCard(
            content = "老视自测",
            subtitle = "近视力与调节 · 看 E 选方向",
            leading = { ServiceGlyph(ServiceKind.PRESBYOPIA) },
            onClick = onPresbyopiaClick,
        )
        EyeText(
            "自测结果仅供参考，不能替代医院检查。",
            style = typography.bodySecondary,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
        )
    }
}

/** 欢迎卡：吉祥物小瞳待机（偶尔眨眼、左右张望）+ 一句引导。 */
@Composable
private fun HeroCard() {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val reduced = rememberReducedMotion()
    val idle = rememberInfiniteTransition(label = "小瞳待机").animateFloat(
        0f, 1f, infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "待机进度"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(EyeShapes.panel)
            .background(colors.primarySoft)
            .padding(start = 20.dp, top = 16.dp, bottom = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EyeText("我是小瞳", style = typography.title, color = colors.textPrimary)
            EyeText(
                "每项测试前，我会先示范一遍怎么做。跟着做就好。",
                style = typography.bodySecondary,
                color = colors.textSecondary
            )
        }
        Canvas(Modifier.size(112.dp)) {
            val p = if (reduced) 0f else idle.value
            val blink = maxOf(1f - abs(p - 0.3f) / 0.025f, 1f - abs(p - 0.36f) / 0.025f, 0f)
            // 张望：0.5~0.9 之间看向左、再看向右
            val gazeX = when {
                p < 0.5f -> 0f
                p < 0.65f -> -(p - 0.5f) / 0.15f
                p < 0.8f -> -1f + (p - 0.65f) / 0.15f * 2f
                p < 0.9f -> 1f - (p - 0.8f) / 0.1f
                else -> 0f
            }
            val s = size.minDimension
            drawMascot(colors, Offset(s / 2, s * 0.56f), s * 0.8f, gaze = Offset(gazeX, 0f), lid = blink)
        }
    }
}

private enum class ServiceKind { DRY_EYE, ALIGNMENT, PRESBYOPIA }

/** 入口卡图标：水滴（干眼）/ 双眼对位（眼位）/ 视标 E（老视），统一主色线性风格。 */
@Composable
private fun ServiceGlyph(kind: ServiceKind) {
    val colors = LocalEyeColors.current
    Canvas(Modifier.size(28.dp)) {
        val s = size.minDimension
        val stroke = Stroke(s * 0.09f, cap = StrokeCap.Round)
        when (kind) {
            ServiceKind.DRY_EYE -> {
                val drop = Path().apply {
                    moveTo(s * 0.5f, s * 0.08f)
                    cubicTo(s * 0.62f, s * 0.3f, s * 0.84f, s * 0.48f, s * 0.84f, s * 0.64f)
                    cubicTo(s * 0.84f, s * 0.84f, s * 0.68f, s * 0.94f, s * 0.5f, s * 0.94f)
                    cubicTo(s * 0.32f, s * 0.94f, s * 0.16f, s * 0.84f, s * 0.16f, s * 0.64f)
                    cubicTo(s * 0.16f, s * 0.48f, s * 0.38f, s * 0.3f, s * 0.5f, s * 0.08f)
                    close()
                }
                drawPath(drop, colors.primary, style = stroke)
                drawArc(colors.primary, 100f, 70f, false, Offset(s * 0.3f, s * 0.5f), Size(s * 0.34f, s * 0.3f), style = Stroke(s * 0.08f, cap = StrokeCap.Round))
            }
            ServiceKind.ALIGNMENT -> {
                for (side in listOf(-1, 1)) {
                    val cx = s * 0.5f + side * s * 0.24f
                    drawPath(leafEyePath(cx - s * 0.2f, cx + s * 0.2f, s * 0.5f, s * 0.13f), colors.primary, style = Stroke(s * 0.08f))
                    drawCircle(colors.primary, s * 0.06f, Offset(cx, s * 0.5f))
                }
                drawLine(colors.accent, Offset(s * 0.1f, s * 0.86f), Offset(s * 0.9f, s * 0.86f), s * 0.08f, StrokeCap.Round)
            }
            ServiceKind.PRESBYOPIA -> drawTumblingE(colors.primary, Offset(s / 2, s / 2), s * 0.62f, 0)
        }
    }
}
