package com.zkyjss.eyeproject.feature.fatigue

import com.zkyjss.eyeproject.core.model.ColorTemperatureMode
import com.zkyjss.eyeproject.core.model.MonitoringSample
import com.zkyjss.eyeproject.core.model.RealTimeMetrics
import com.zkyjss.eyeproject.core.ui.component.StatusTone

/** 疲劳监测仪表盘 UI 状态（单向数据流，由 FatigueViewModel 产出）。 */
data class FatigueUiState(
    val monitoring: Boolean = false,
    val statusMessage: String = "眼健康监测",
    val statusTone: StatusTone = StatusTone.NEUTRAL,
    val realtime: RealTimeMetrics = RealTimeMetrics(),
    val samples: List<MonitoringSample> = emptyList(),
    val fps: Float = 0f,
    val error: String? = null,
    val colorTemperatureMode: ColorTemperatureMode = ColorTemperatureMode.OFF,
    val reminderEnabled: Boolean = false,
    val reminderIntervalMinutes: Int = 20,
    val reminderNextTimeText: String = ""
)

/** 需要 Activity 层处理的特殊权限请求（一次性事件）。 */
sealed interface FatiguePermissionEvent {
    data object Camera : FatiguePermissionEvent
    data object Notification : FatiguePermissionEvent
    data class Overlay(val mode: ColorTemperatureMode) : FatiguePermissionEvent
    data object ExactAlarm : FatiguePermissionEvent
}
