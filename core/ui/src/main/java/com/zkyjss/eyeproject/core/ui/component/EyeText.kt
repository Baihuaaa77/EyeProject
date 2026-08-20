package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

/**
 * 自定义文字原语，基于 Foundation 的 BasicText，不使用 Material 的 Text。
 * 所有视觉样式由传入的 [style]/[color]/[fontWeight] 完全控制。
 */
@Composable
fun EyeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE
) {
    var resolved = style
    if (color != Color.Unspecified) resolved = resolved.copy(color = color)
    if (fontWeight != null) resolved = resolved.copy(fontWeight = fontWeight)
    if (textAlign != null) resolved = resolved.copy(textAlign = textAlign)
    BasicText(
        text = text,
        modifier = modifier,
        style = resolved,
        maxLines = maxLines
    )
}
