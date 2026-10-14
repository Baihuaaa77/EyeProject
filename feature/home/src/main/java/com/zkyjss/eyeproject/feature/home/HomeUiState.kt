package com.zkyjss.eyeproject.feature.home

data class HomeUiState(
    val notice: String? = null,
    val reminderEnabled: Boolean = false,
    val reminderIntervalMinutes: Int = 20,
    val reminderNextTimeText: String = "",
)

sealed interface HomePermissionEvent {
    data object Notification : HomePermissionEvent
    data object ExactAlarm : HomePermissionEvent
}
