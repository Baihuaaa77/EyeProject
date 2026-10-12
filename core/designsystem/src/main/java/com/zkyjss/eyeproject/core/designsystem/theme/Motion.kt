package com.zkyjss.eyeproject.core.designsystem.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset

/**
 * 动效令牌：品牌动效「轻、柔、可预期」——
 * 交互反馈用低弹性弹簧，状态切换用标准缓动，演示动画循环周期统一。
 */
object EyeMotion {
    /** 微交互（按压、开关）。 */
    const val SHORT = 160

    /** 状态切换（展开收起、选中指示器）。 */
    const val MEDIUM = 280

    /** 数值过渡（仪表盘、读数）。 */
    const val LONG = 700

    /** 方法演示动画单次循环时长。 */
    const val DEMO_CYCLE = 3600

    /** 标准缓动：快出慢停。 */
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 柔和进出：用于演示中的往复运动。 */
    val Gentle = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

    fun <T> pressSpring() = spring<T>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
    fun <T> settleSpring() = spring<T>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
    val dpSpring get() = spring<Dp>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
    val offsetSpring get() = spring<IntOffset>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
}

/**
 * 系统「移除动画」（开发者选项 / 无障碍）打开时返回 true：
 * 演示动画停在关键帧、循环动效不再播放，避免对前庭敏感用户造成不适。
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}
