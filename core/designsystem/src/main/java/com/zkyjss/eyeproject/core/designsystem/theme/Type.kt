package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import com.zkyjss.eyeproject.core.designsystem.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 数字字体：大号读数、分数、图表数值用等宽数字（tnum），数字跳动时宽度不变。
 * 目前用系统无衬线体；内置 Inter 字体文件后只改这一处。
 */
val EyeTextFamily = FontFamily(Font(R.font.eye_noto_regular,FontWeight.Normal), Font(R.font.eye_noto_semibold,FontWeight.SemiBold))
val EyeNumberFamily: FontFamily = EyeTextFamily
val EyeScoreFamily = FontFamily(Font(R.font.nunito_black, FontWeight.Black))

/** 等宽数字特性（tabular figures）。 */
const val EYE_TABULAR_NUMBERS = "tnum"

/** DESIGN.md 字阶：中文标题最多 SemiBold，正文保留 1.5 倍行高。 */
@Immutable
data class EyeTypography(
    /** 主卡里的大数字（眼健康指数等）。 */
    val display: TextStyle,
    /** 窄屏或大字号环境下的主分数，保留与正文的字号差。 */
    val displayCompact: TextStyle,
    val healthScore: TextStyle,
    val healthScoreCompact: TextStyle,
    val chatBody: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val subtitle: TextStyle,
    val body: TextStyle,
    val bodySecondary: TextStyle,
    /** 卡片类别、读数名称等灰色小标题。 */
    val label: TextStyle,
    val navigationTitle: TextStyle,
    val metricValue: TextStyle,
    /** 读数里的单位（「小时」「次/分」「lx」）：小一半、中等字重，颜色由调用处给次要文字色。 */
    val unit: TextStyle,
    val chip: TextStyle,
    val button: TextStyle,
    /** 最小一级：底栏名称、图表坐标（11 号半粗）。 */
    val caption: TextStyle,
    val tab: TextStyle,
)

val EyeTypographyDefaults = EyeTypography(
    label = TextStyle(fontFamily = EyeTextFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, lineHeight = 20.sp),
    navigationTitle = TextStyle(fontFamily = EyeTextFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 26.sp),
    healthScore = TextStyle(fontFamily = EyeScoreFamily, fontWeight = FontWeight.Black, fontSize = 42.sp,
        lineHeight = 68.sp, letterSpacing = (-1.2).sp, fontFeatureSettings = EYE_TABULAR_NUMBERS),
    healthScoreCompact = TextStyle(fontFamily = EyeScoreFamily, fontWeight = FontWeight.Black, fontSize = 36.sp,
        lineHeight = 56.sp, letterSpacing = (-0.96).sp, fontFeatureSettings = EYE_TABULAR_NUMBERS),
    chatBody = TextStyle(fontFamily = EyeTextFamily, fontSize = 15.sp, lineHeight = 24.sp),
    display = TextStyle(
        fontFamily = EyeNumberFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 42.sp,
        lineHeight = 50.sp,
        letterSpacing = (-1.2).sp,
        fontFeatureSettings = EYE_TABULAR_NUMBERS
    ),
    displayCompact = TextStyle(
        fontFamily = EyeNumberFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.96).sp,
        fontFeatureSettings = EYE_TABULAR_NUMBERS
    ),
    headline = TextStyle(
        fontFamily = EyeTextFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    title = TextStyle(
        fontFamily = EyeTextFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 30.sp
    ),
    subtitle = TextStyle(
        fontFamily = EyeTextFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp
    ),
    body = TextStyle(
        fontFamily = EyeTextFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodySecondary = TextStyle(
        fontFamily = EyeTextFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp
    ),
    metricValue = TextStyle(
        fontFamily = EyeNumberFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.6).sp,
        fontFeatureSettings = EYE_TABULAR_NUMBERS
    ),
    unit = TextStyle(
        fontFamily = EyeTextFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 20.sp
    ),
    chip = TextStyle(
        fontFamily = EyeTextFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 18.sp
    ),
    button = TextStyle(
        fontFamily = EyeTextFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    caption = TextStyle(
        fontFamily = EyeTextFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 18.sp
    ),
    tab = TextStyle(
        fontFamily = EyeTextFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp
    )
)

val LocalEyeTypography = staticCompositionLocalOf { EyeTypographyDefaults }
