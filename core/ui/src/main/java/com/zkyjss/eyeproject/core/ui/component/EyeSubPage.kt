package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingLarge

/**
 * 二级完整页面（替代底部弹窗）：返回顶栏 + 标题，内容竖向滚动、左右与顶层页同一边距。
 * 系统返回键由导航处理；苹果端用 NavigationStack 推入的同名页面，标题与排版一致。
 */
@Composable
fun EyeSubPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
    bottomBar: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalEyeColors.current
    Column(modifier.fillMaxSize().background(colors.appBackground)) {
        TopBackBar(title = title, noText = true, onBackClick = onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).screenHorizontalPaddingLarge().padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
            Spacer(Modifier.height(if (bottomBar == null) 28.dp else 8.dp))
            if (bottomBar == null) Spacer(Modifier.navigationBarsPadding())
        }
        if (bottomBar != null) {
            Column(Modifier.fillMaxWidth().screenHorizontalPaddingLarge().padding(vertical = 12.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = bottomBar)
        }
    }
}
