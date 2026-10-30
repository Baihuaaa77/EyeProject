package com.zkyjss.eyeproject.feature.services

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.MascotBubble
import com.zkyjss.eyeproject.core.ui.component.MascotMood
import com.zkyjss.eyeproject.core.ui.component.XiaoTong
import com.zkyjss.eyeproject.core.ui.component.rememberEyeHaptics

/**
 * 测试中点返回：屏幕中间弹出确认框，小瞳在上方冒气泡挽留。「继续测试」关闭弹窗，「确认退出」才离开。
 * 四项测试共用；苹果端 TestExitConfirm 同一文案。
 */
@Composable
internal fun TestExitDialog(onStay: () -> Unit, onExit: () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    Dialog(onDismissRequest = onStay) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            MascotBubble(EXIT_BUBBLE)
            XiaoTong(MascotMood.CONCERNED, Modifier.size(96.dp), interactive = false)
            Column(Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EyeText(EXIT_TITLE, style = typography.title, color = colors.textPrimary)
                EyeText(EXIT_BODY, style = typography.body, color = colors.textSecondary, textAlign = TextAlign.Center)
                EyeButton("继续测试", EyeButtonRole.PRIMARY, { haptics.tap(); onStay() }, Modifier.fillMaxWidth())
                EyeButton("确认退出", EyeButtonRole.SECONDARY, { haptics.tap(); onExit() }, Modifier.fillMaxWidth())
            }
        }
    }
}

internal const val EXIT_BUBBLE = "很快就要测完了哦，再坚持一下～"
internal const val EXIT_TITLE = "确定要退出测试吗？"
internal const val EXIT_BODY = "退出后这次测试不会保存，下次要从头开始。"
