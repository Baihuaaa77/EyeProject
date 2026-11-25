package com.zkyjss.strabismuslab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * StrabismusLab 独立临床光学实验室设计令牌（与 iOS LabTheme.swift 1:1 严格对齐）。
 * 精密暗空深色画布 + 1dp 刻线边框 + 冷光青蓝/琥珀金双目编码色 + 等宽表格数字。
 */
object LabColors {
    val canvas = Color(0xFF090C10)
    val stimulusBlack = Color(0xFF000000)
    val surface = Color(0xFF12171F)
    val surfaceElevated = Color(0xFF1A212C)
    val surfaceSubtle = Color(0xFF0E131A)
    val hairline = Color(0xFF242E3D)
    val hairlineStrong = Color(0xFF334155)

    val inkPrimary = Color(0xFFF1F5F9)
    val inkSecondary = Color(0xFF94A3B8)
    val inkTertiary = Color(0xFF64748B)

    /** 主交互 CTA 与左眼编码色 */
    val accent = Color(0xFF38BDF8)
    val accentSoft = Color(0x2638BDF8)
    val onAccent = Color(0xFF06131D)

    /** 右眼编码色（色盲友好的青蓝 vs 琥珀金双目对比） */
    val rightEye = Color(0xFFF59E0B)
    val rightEyeSoft = Color(0x26F59E0B)

    /** 语义状态色 */
    val verified = Color(0xFF22C55E)
    val verifiedSoft = Color(0x2622C55E)
    val caution = Color(0xFFFBBF24)
    val cautionSoft = Color(0x26FBBF24)
    val danger = Color(0xFFF43F5E)
    val dangerSoft = Color(0x26F43F5E)
}

object LabShapes {
    val card = RoundedCornerShape(16.dp)
    val control = RoundedCornerShape(12.dp)
    val pill = RoundedCornerShape(999.dp)
}

object LabType {
    val title = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp,
        color = LabColors.inkPrimary,
    )
    val section = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
        color = LabColors.inkPrimary,
    )
    val body = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
        color = LabColors.inkPrimary,
    )
    val caption = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        color = LabColors.inkSecondary,
    )
    val metricHero = TextStyle(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        fontFeatureSettings = "tnum",
        color = LabColors.inkPrimary,
    )
    val metricMono = TextStyle(
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
        fontFeatureSettings = "tnum",
        color = LabColors.inkPrimary,
    )
    val microMono = TextStyle(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
        fontFeatureSettings = "tnum",
        color = LabColors.inkSecondary,
    )
}

@Composable
fun StrabismusLabTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = LabColors.accent,
        onPrimary = LabColors.onAccent,
        secondary = LabColors.rightEye,
        background = LabColors.canvas,
        onBackground = LabColors.inkPrimary,
        surface = LabColors.surface,
        onSurface = LabColors.inkPrimary,
        surfaceVariant = LabColors.surfaceElevated,
        onSurfaceVariant = LabColors.inkSecondary,
        outline = LabColors.hairline,
        error = LabColors.danger,
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            titleLarge = LabType.title,
            titleMedium = LabType.section,
            bodyMedium = LabType.body,
            labelMedium = LabType.caption,
        ),
        content = content,
    )
}

enum class LabGlyph { BACK, PLAY, LIST, CLOSE, DELETE, SHARE }

/** 统一 1.8dp 描边宽度的原生精密矢量图标，零外部图标库依赖。 */
@Composable
fun LabVectorIcon(
    glyph: LabGlyph,
    tint: Color,
    size: Dp = 18.dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (glyph) {
            LabGlyph.BACK -> {
                val p = Path().apply {
                    moveTo(w * 0.62f, h * 0.20f)
                    lineTo(w * 0.30f, h * 0.50f)
                    lineTo(w * 0.62f, h * 0.80f)
                }
                drawPath(p, color = tint, style = stroke)
            }
            LabGlyph.PLAY -> {
                val p = Path().apply {
                    moveTo(w * 0.28f, h * 0.20f)
                    lineTo(w * 0.80f, h * 0.50f)
                    lineTo(w * 0.28f, h * 0.80f)
                    close()
                }
                drawPath(p, color = tint)
            }
            LabGlyph.LIST -> {
                listOf(0.28f, 0.50f, 0.72f).forEach { y ->
                    drawLine(tint, Offset(w * 0.20f, h * y), Offset(w * 0.82f, h * y), strokeWidth = stroke.width, cap = StrokeCap.Round)
                }
            }
            LabGlyph.CLOSE -> {
                drawLine(tint, Offset(w * 0.24f, h * 0.24f), Offset(w * 0.76f, h * 0.76f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.76f, h * 0.24f), Offset(w * 0.24f, h * 0.76f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            LabGlyph.DELETE -> {
                drawLine(tint, Offset(w * 0.22f, h * 0.28f), Offset(w * 0.78f, h * 0.28f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.40f, h * 0.18f), Offset(w * 0.60f, h * 0.18f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                val body = Path().apply {
                    moveTo(w * 0.28f, h * 0.28f)
                    lineTo(w * 0.33f, h * 0.82f)
                    lineTo(w * 0.67f, h * 0.82f)
                    lineTo(w * 0.72f, h * 0.28f)
                }
                drawPath(body, color = tint, style = stroke)
            }
            LabGlyph.SHARE -> {
                drawLine(tint, Offset(w * 0.50f, h * 0.60f), Offset(w * 0.50f, h * 0.18f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                val arrow = Path().apply {
                    moveTo(w * 0.32f, h * 0.34f)
                    lineTo(w * 0.50f, h * 0.16f)
                    lineTo(w * 0.68f, h * 0.34f)
                }
                drawPath(arrow, color = tint, style = stroke)
                val tray = Path().apply {
                    moveTo(w * 0.20f, h * 0.56f)
                    lineTo(w * 0.20f, h * 0.80f)
                    lineTo(w * 0.80f, h * 0.80f)
                    lineTo(w * 0.80f, h * 0.56f)
                }
                drawPath(tray, color = tint, style = stroke)
            }
        }
    }
}
