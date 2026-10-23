package com.zkyjss.eyeproject.feature.home

import com.zkyjss.eyeproject.core.eyecare.EyeCareEngine
import com.zkyjss.eyeproject.core.eyecare.EyeCareReport
import com.zkyjss.eyeproject.core.sync.AdviceRequest

/**
 * 首页状态：休息提醒 + 主动护眼报告（共用 EyeCareEngine 由多源数据算出）。
 * [report] 为 null 表示数据还在读取；[usageAccess] 为系统用屏统计的授权状态。
 */
data class HomeUiState(
    val notice: String? = null,
    val reminderEnabled: Boolean = false,
    val reminderIntervalMinutes: Int = 20,
    val reminderNextTimeText: String = "",
    val report: EyeCareReport? = null,
    val selfId: String? = null,
    val usageAccess: String = EyeCareEngine.ACCESS_DENIED,
    /** AI 综合建议的输入（共用 EyeCareAdvice 抽取的去标识概况） */
    val adviceRequest: AdviceRequest? = null,
    val screenBrightness: Float? = null,
    val ambientLux: Float? = null,
)

sealed interface HomePermissionEvent {
    data object Notification : HomePermissionEvent
    data object ExactAlarm : HomePermissionEvent
}
