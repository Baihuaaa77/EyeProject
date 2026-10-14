package com.zkyjss.eyeproject.feature.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.zkyjss.eyeproject.core.common.formatTime
import com.zkyjss.eyeproject.core.domain.ReminderController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.*

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val reminderController: ReminderController,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private val _permissionEvents = MutableSharedFlow<HomePermissionEvent>(extraBufferCapacity = 1)
    val permissionEvents: SharedFlow<HomePermissionEvent> = _permissionEvents.asSharedFlow()
    private var pendingExactAlarm = false

    init { refreshReminderState() }

    fun refresh() {
        if (pendingExactAlarm) {
            pendingExactAlarm = false
            if (reminderController.canScheduleExactAlarms()) startReminder()
        }
        refreshReminderState()
    }

    fun toggleReminder() {
        if (reminderController.isEnabled()) {
            reminderController.cancel()
            _uiState.update { it.copy(notice = null) }
            refreshReminderState()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _permissionEvents.tryEmit(HomePermissionEvent.Notification)
            return
        }
        startReminder()
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        if (granted) {
            startReminder()
        } else {
            _uiState.update { it.copy(notice = "未授予通知权限，无法发送休息提醒") }
        }
    }

    private fun startReminder() {
        if (!reminderController.canScheduleExactAlarms() ||
            !reminderController.schedule(_uiState.value.reminderIntervalMinutes)
        ) {
            pendingExactAlarm = true
            _uiState.update { it.copy(notice = "请允许「闹钟和提醒」权限，以便准时提醒休息") }
            _permissionEvents.tryEmit(HomePermissionEvent.ExactAlarm)
            return
        }
        _uiState.update { it.copy(notice = null) }
        refreshReminderState()
    }

    fun selectReminderInterval(minutes: Int) {
        reminderController.setIntervalMinutes(minutes)
        refreshReminderState()
    }

    private fun refreshReminderState() {
        val enabled = reminderController.isEnabled()
        val interval = reminderController.intervalMinutes()
        val nextTimeText = if (enabled) {
            val remaining = (reminderController.nextTriggerElapsed() - SystemClock.elapsedRealtime())
                .coerceAtLeast(0L)
            formatTime(System.currentTimeMillis() + remaining)
        } else {
            ""
        }
        _uiState.update {
            it.copy(
                reminderEnabled = enabled,
                reminderIntervalMinutes = interval,
                reminderNextTimeText = nextTimeText
            )
        }
    }

    // endregion
}
