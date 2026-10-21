package com.zkyjss.eyeproject.core.ui.component

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * 页面操作的轻触觉（测试中的作答反馈另有分级，见 feature:services 的 TestFeedbackEffect）。
 * 点按 / 开关轻触，操作成功与失败各一种；跟随系统「触感反馈」设置，不需要振动权限。与苹果端 EyeHaptics 同一分级。
 */
class EyeHaptics internal constructor(private val view: View) {
    /** 点按入口、按钮、选项。 */
    fun tap() { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }

    /** 开关切换。 */
    fun toggle(on: Boolean) {
        view.performHapticFeedback(when {
            Build.VERSION.SDK_INT >= 34 -> if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
            else -> HapticFeedbackConstants.KEYBOARD_TAP
        })
    }

    /** 保存、登录、同步等操作成功。 */
    fun success() {
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /** 操作失败或输入有误。 */
    fun failure() {
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    }
}

@Composable
fun rememberEyeHaptics(): EyeHaptics {
    val view = LocalView.current
    return remember(view) { EyeHaptics(view) }
}
