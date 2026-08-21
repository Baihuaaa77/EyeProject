package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 基础按钮组件
 * @param modifier
 * @param shape[RectangleShape] 按钮的形状,默认矩形
 * @param containerColor[Color] 按钮背景色
 * @param disabledContainerColor[Color] 按钮禁用时背景色
 * @param border[BorderStroke] 按钮边框，默认无
 * @param contentPadding[PaddingValues] 按钮内部间距
 * @param enabled 按钮可点击，默认true
 * @param useDefaultIndication 使用compose默认点击动画默认true
 * @param contentColor 内容的颜色
 * @param onClick 点击事件
 * @param content 按钮内容
*/
@Composable
fun Button(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    containerColor: Color = Color.White,
    disabledContainerColor: Color = Color.Gray,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    enabled: Boolean = true,
    useDefaultIndication: Boolean = true,
    contentColor: Color = if (enabled) Color.White else Color.Gray,
    onClick: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .background(
                color = if (enabled) containerColor else disabledContainerColor,
                shape = shape
            )
            .then(
                if (border != null)
                    Modifier.border(border, shape)
                else Modifier
            )
            .clip(shape)
            .then(
                if (useDefaultIndication) {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                } else {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.padding(contentPadding)
        ) {
            content()
        }
    }
}