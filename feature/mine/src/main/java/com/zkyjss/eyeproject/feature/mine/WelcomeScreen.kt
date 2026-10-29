package com.zkyjss.eyeproject.feature.mine

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingLarge
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.MascotMood
import com.zkyjss.eyeproject.core.ui.component.XiaoTong
import com.zkyjss.eyeproject.core.ui.component.rememberEyeHaptics

/**
 * 启动时的登录引导：未登录打开 App 时（启动动画之后）先显示，可以登录、注册，也可以跳过先用；
 * 登录成功自动进入首页，返回键等同跳过。与苹果端 WelcomeView 同一排版与文案。
 */
@Composable
fun WelcomeScreen(onDone: () -> Unit, viewModel: AccountViewModel = hiltViewModel()) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val busy by viewModel.busyState.collectAsStateWithLifecycle()
    val message by viewModel.messageState.collectAsStateWithLifecycle()
    val haptics = rememberEyeHaptics()
    LaunchedEffect(account?.email) { if (account?.email != null) { haptics.success(); onDone() } }
    BackHandler(onBack = onDone)
    WelcomeContent(busy, message, AccountActions(viewModel::signIn, viewModel::register), onDone)
}

@Composable
internal fun WelcomeContent(busy: Boolean, message: String?, actions: AccountActions, onSkip: () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(0f to colors.primarySoft, 0.5f to colors.appBackground))
            .verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().imePadding()
            .screenHorizontalPaddingLarge().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        XiaoTong(MascotMood.HAPPY, Modifier.size(150.dp), interactive = false, greet = true)
        EyeText("欢迎来到眼健康助手", style = typography.title, color = colors.textPrimary)
        EyeText("登录后，测试记录可以加密保存到云端，换手机也不丢；还能和小瞳聊聊今天的用眼感受。",
            style = typography.body, color = colors.textSecondary, textAlign = TextAlign.Center)
        SignInForm(busy, message, actions)
        EyeText("先跳过，随便看看", Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onSkip).padding(12.dp),
            style = typography.body, color = colors.primary, fontWeight = FontWeight.SemiBold)
        EyeText("不登录也能做全部自测，记录只保存在这台手机上。以后可在首页右上角的人像里登录。",
            style = typography.bodySecondary, color = colors.textSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
    }
}
