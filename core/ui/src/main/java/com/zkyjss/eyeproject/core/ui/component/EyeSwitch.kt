package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors

/**
 * 自定义开关（Switch），基于 Box + 动画，不使用 Material 的 Switch。
 * 选中时滑块滑到右侧、轨道用主色；未选中时滑块在左、轨道用边框色。
 */
@Composable
fun EyeSwitch(
    checked: Boolean,
    /** 传 null 表示仅展示，由外层整行负责切换（避免一行两个点击目标）。 */
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = LocalEyeColors.current
    val trackColor by androidx.compose.animation.animateColorAsState(
        if (checked) colors.primary else colors.borderSubtle,
        label = "eyeSwitchTrack"
    )
    val thumbColor = colors.white

    // 滑块水平位移动画（轨道 52dp、滑块 28dp、两侧各 2dp padding）
    val thumbOffsetX by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        label = "eyeSwitchThumb"
    )

    Box(
        modifier = modifier
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                } else {
                    Modifier
                }
            )
            .padding(vertical = 8.dp)
            .size(width = 52.dp, height = 32.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedCornerShape(16.dp))
            .background(trackColor)

    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffsetX, y = 2.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}
