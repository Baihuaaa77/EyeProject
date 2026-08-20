package com.zkyjss.eyeproject.feature.home

import com.zkyjss.eyeproject.core.ui.component.StatusTone
import com.zkyjss.eyeproject.core.model.AnalysisResolution
import com.zkyjss.eyeproject.core.model.ColorTemperatureMode
import com.zkyjss.eyeproject.core.model.DistanceEstimate
import com.zkyjss.eyeproject.core.model.FaceEyeDetection

/** 主仪表盘 UI 状态（单向数据流，由 HomeViewModel 产出）。 */
data class HomeUiState(
    val cameraRunning: Boolean = false,
    val statusMessage: String = "智能用眼监测",
    val statusTone: StatusTone = StatusTone.NEUTRAL,
    val detections: List<FaceEyeDetection> = emptyList(),
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val fps: Float = 0f,
    val latencyMillis: Long = 0L,
    val faceCount: Int = 0,
    val distanceEstimate: DistanceEstimate? = null,
    val screenUsageText: String = "--",
    val screenUsageDetail: String = "今日屏幕使用时间",
    val brightnessPercent: Int? = null,
    val brightnessAutomatic: Boolean? = null,
    val ambientLux: Float? = null,
    val lightSensorAvailable: Boolean = false,
    val analysisResolution: AnalysisResolution = AnalysisResolution.BALANCED,
    val colorTemperatureMode: ColorTemperatureMode = ColorTemperatureMode.OFF,
    val reminderEnabled: Boolean = false,
    val reminderIntervalMinutes: Int = 20,
    val reminderNextTimeText: String = ""
) {
    val distanceTooClose: Boolean
        get() = distanceEstimate?.estimatedDistanceCm?.let { it < 30f } == true
}

/** 需要 Activity 层处理的特殊权限请求（一次性事件）。 */
sealed interface PermissionEvent {
    data object Camera : PermissionEvent
    data object UsageAccess : PermissionEvent
    data class Overlay(val mode: ColorTemperatureMode) : PermissionEvent
    data object ExactAlarm : PermissionEvent
    data object Notification : PermissionEvent
}
