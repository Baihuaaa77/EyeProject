package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors

/**
 * 盖在当前页面上的完整页面（替代底部弹窗）：从右侧滑入，铺满屏幕、延伸到状态栏下，系统返回键关闭。
 * 用于测试进行中打开的「系统设置」「本次测试数据」等不能离开当前页面的场景；其余二级页走导航。
 */
@Composable
fun EyeFullScreenPage(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val colors = LocalEyeColors.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val light = colors.appBackground.luminance() > 0.5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setDimAmount(0f)
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
        }
        val visible = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(visible, enter = slideInHorizontally(tween(EyeMotion.MEDIUM, easing = EyeMotion.Standard)) { it / 3 } + fadeIn(tween(EyeMotion.SHORT))) {
            Box(Modifier.fillMaxSize().background(colors.appBackground)) { content() }
        }
    }
}
