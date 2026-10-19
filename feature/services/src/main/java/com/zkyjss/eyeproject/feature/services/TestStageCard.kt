package com.zkyjss.eyeproject.feature.services

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.ui.component.EyeSectionCard
import com.zkyjss.eyeproject.core.ui.component.EyeText

/** 只装饰测试舞台，保留原有内容尺寸与白色刺激背景。 */
@Composable
internal fun TestStageCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalEyeColors.current
    EyeSectionCard(
        modifier = Modifier.shadow(3.dp, EyeShapes.panel, ambientColor = colors.primary.copy(alpha = 0.08f),
            spotColor = colors.primary.copy(alpha = 0.08f))
            .border(1.dp, colors.primary.copy(alpha = 0.16f), EyeShapes.panel),
        title = title, content = content,
    )
}

/** 任务中的质量提醒：浅色胶囊淡入展开，消失时收起，避免文字突然出现挤动舞台。 */
@Composable
internal fun TestNotice(text: String?, modifier: Modifier = Modifier) {
    val colors = LocalEyeColors.current
    // 收起动画期间保留最后一条文字，避免内容先变空再收起。
    var last by remember { mutableStateOf(text.orEmpty()) }
    if (text != null) last = text
    AnimatedVisibility(
        visible = text != null, modifier = modifier,
        enter = fadeIn(tween(EyeMotion.MEDIUM)) + expandVertically(tween(EyeMotion.MEDIUM, easing = EyeMotion.Standard)),
        exit = fadeOut(tween(EyeMotion.SHORT)) + shrinkVertically(tween(EyeMotion.MEDIUM, easing = EyeMotion.Standard)),
    ) {
        EyeText(last, style = LocalEyeTypography.current.body, color = colors.warning, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(EyeShapes.chip).background(colors.warningSoft)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

/**
 * 注视类任务（校准、注视、遮盖、追踪）用的提示位：始终占住固定高度，提示出现或消失都不挤动下方画布。
 * 画布一移动，屏幕上的校准点和注视目标就跟着移动，受试者刚才看的位置与记录的位置不再一致。
 */
@Composable
internal fun TestNoticeSlot(text: String?) {
    val colors = LocalEyeColors.current
    var last by remember { mutableStateOf(text.orEmpty()) }
    if (text != null) last = text
    Box(Modifier.fillMaxWidth().height(NOTICE_SLOT_HEIGHT), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = text != null, enter = fadeIn(tween(EyeMotion.MEDIUM)), exit = fadeOut(tween(EyeMotion.SHORT))) {
            EyeText(last, style = LocalEyeTypography.current.body, color = colors.warning, textAlign = TextAlign.Center, maxLines = 2,
                modifier = Modifier.fillMaxWidth().clip(EyeShapes.chip).background(colors.warningSoft)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

/** 两行正文 + 上下内边距。 */
private val NOTICE_SLOT_HEIGHT = 64.dp
