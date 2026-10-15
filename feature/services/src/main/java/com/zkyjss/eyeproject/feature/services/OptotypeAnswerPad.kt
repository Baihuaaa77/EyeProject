package com.zkyjss.eyeproject.feature.services

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole

/** 四个方向对应视标的四个方位；保留实测物理尺寸和每题的作答门控。 */
@Composable
internal fun OptotypeAnswerPad(direction: Int, size: Dp, visible: Boolean, onAnswer: (Int) -> Unit) {
    val colors = LocalEyeColors.current
    val buttonDecoration = Modifier.shadow(2.dp, EyeShapes.medium,
        ambientColor = colors.primary.copy(alpha = 0.06f), spotColor = colors.primary.copy(alpha = 0.06f))
        .border(1.dp, colors.primary.copy(alpha = 0.2f), EyeShapes.medium)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.fillMaxWidth().height(maxOf(256.dp, size + 168.dp)).background(colors.white),
            contentAlignment = Alignment.Center,
        ) {
            FramedOptotype(direction, size, visible)
            EyeButton("↑", EyeButtonRole.SECONDARY, { onAnswer(3) },
                Modifier.align(Alignment.TopCenter).size(56.dp).then(buttonDecoration).semantics { contentDescription = "向上" }, visible)
            EyeButton("←", EyeButtonRole.SECONDARY, { onAnswer(2) },
                Modifier.align(Alignment.CenterStart).size(56.dp).then(buttonDecoration).semantics { contentDescription = "向左" }, visible)
            EyeButton("→", EyeButtonRole.SECONDARY, { onAnswer(0) },
                Modifier.align(Alignment.CenterEnd).size(56.dp).then(buttonDecoration).semantics { contentDescription = "向右" }, visible)
            EyeButton("↓", EyeButtonRole.SECONDARY, { onAnswer(1) },
                Modifier.align(Alignment.BottomCenter).size(56.dp).then(buttonDecoration).semantics { contentDescription = "向下" }, visible)
        }
        EyeButton("看不清 / 跳过", EyeButtonRole.SECONDARY, { onAnswer(-1) }, Modifier.fillMaxWidth().then(buttonDecoration), visible)
    }
}

/** 固定留白环，视标仍按原物理像素尺寸绘制。 */
@Composable
internal fun FramedOptotype(direction: Int, size: Dp, visible: Boolean = true) {
    val colors = LocalEyeColors.current
    Box(Modifier.size(maxOf(96.dp, size + 40.dp)).border(2.dp, colors.primary.copy(alpha = 0.72f), CircleShape),
        contentAlignment = Alignment.Center) {
        if (visible) TumblingE(direction, size, colors.black, Modifier.testTag("answer-optotype"))
    }
}
