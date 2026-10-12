package com.zkyjss.eyeproject.feature.fatigue

import com.zkyjss.eyeproject.core.model.ColorTemperatureMode
import com.zkyjss.eyeproject.core.model.MonitoringSample
import com.zkyjss.eyeproject.core.model.RealTimeMetrics

/** 疲劳监测仪表盘 UI 状态（单向数据流，由 FatigueViewModel 产出）。 */
data class FatigueUiState(
    val monitoring: Boolean = false,
    val realtime: RealTimeMetrics = RealTimeMetrics(),
    val samples: List<MonitoringSample> = emptyList(),
    val error: String? = null,
    /** 摄像头权限被拒：界面给出原因与「打开系统设置」入口。 */
    val cameraPermissionDenied: Boolean = false,
    /** 护眼设置操作的结果提示（如通知权限被拒），为 null 时不显示。 */
    val notice: String? = null,
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
