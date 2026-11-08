package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 数字字体：大号读数、分数、图表数值用等宽数字（tnum），数字跳动时宽度不变。
 * 目前用系统无衬线体；内置 Inter 字体文件后只改这一处。
 */
val EyeNumberFamily: FontFamily = FontFamily.SansSerif

/** 等宽数字特性（tabular figures）。 */
const val EYE_TABULAR_NUMBERS = "tnum"

/**
 * 排版令牌（2026-11-08 视觉改版）：页面大标题 [headline] 32 → 分区标题 [title] 20 → 卡片标题 [subtitle] 17 →
 * 正文 [body] 16 → 辅助 [bodySecondary] 14 → 类别标签 / 徽标 [chip] 13；大数字 [display] 56、读数 [metricValue] 28、
 * 读数单位 [unit] 14（约为数字一半、配次要文字色）、按钮 [button] 17。
 * 中文最粗用到 SemiBold，Bold 只留给页面大标题与数字。与苹果端 EyeStyle 字体一一对应。
 */
@Immutable
data class EyeTypography(
    /** 主卡里的大数字（眼健康指数等）。 */
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val subtitle: TextStyle,
    val body: TextStyle,
    val bodySecondary: TextStyle,
    val metricValue: TextStyle,
    /** 读数里的单位（「小时」「次/分」「lx」）：小一半、中等字重，颜色由调用处给次要文字色。 */
    val unit: TextStyle,
    val chip: TextStyle,
    val button: TextStyle,
    /** 最小一级：底栏名称、图表坐标（11 号半粗）。 */
    val caption: TextStyle
)

val EyeTypographyDefaults = EyeTypography(
    display = TextStyle(
        fontFamily = EyeNumberFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 56.sp,
        lineHeight = 60.sp,
        letterSpacing = (-2).sp,
        fontFeatureSettings = EYE_TABULAR_NUMBERS
    ),
    headline = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp
    ),
    title = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    subtitle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp
    ),
    body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodySecondary = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    metricValue = TextStyle(
        fontFamily = EyeNumberFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.6).sp,
        fontFeatureSettings = EYE_TABULAR_NUMBERS
    ),
    unit = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    chip = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    button = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp
    ),
    caption = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp
    )
)

val LocalEyeTypography = staticCompositionLocalOf { EyeTypographyDefaults }
