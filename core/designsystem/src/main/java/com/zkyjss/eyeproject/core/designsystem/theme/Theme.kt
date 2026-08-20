package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * 完全自定义的主题：只提供颜色/排版令牌，不依赖 MaterialTheme 或 Material 默认配色。
 */
@Composable
fun EyeProjectTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkEyeColors else LightEyeColors
    CompositionLocalProvider(
        LocalEyeColors provides colors,
        LocalEyeTypography provides EyeTypographyDefaults,
        content = content
    )
}

object EyeTheme {
    val colors: EyeColors
        @Composable
        @ReadOnlyComposable
        get() = LocalEyeColors.current

    val typography: EyeTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalEyeTypography.current
}
