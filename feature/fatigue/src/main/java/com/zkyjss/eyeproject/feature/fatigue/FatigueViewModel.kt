package com.zkyjss.eyeproject.feature.fatigue

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zkyjss.eyeproject.core.common.formatTime
import com.zkyjss.eyeproject.core.domain.ColorTemperatureController
import com.zkyjss.eyeproject.core.domain.ReminderController
import com.zkyjss.eyeproject.core.model.ColorTemperatureMode
import com.zkyjss.eyeproject.core.ui.component.StatusTone
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class FatigueViewModel @Inject constructor(
    private val monitor: FatigueMonitor,
    private val serviceLauncher: MonitoringServiceLauncher,
    private val reminderController: ReminderController,
    private val colorTemperatureController: ColorTemperatureController,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        FatigueUiState(
            colorTemperatureMode = colorTemperatureController.currentMode()
        )
    )
    val uiState: StateFlow<FatigueUiState> = _uiState.asStateFlow()

    private val _permissionEvents = MutableSharedFlow<FatiguePermissionEvent>(extraBufferCapacity = 1)
    val permissionEvents: SharedFlow<FatiguePermissionEvent> = _permissionEvents.asSharedFlow()

    init {
        observeMonitor()
        refreshReminderState()
    }

    private fun observeMonitor() {
        viewModelScope.launch {
            monitor.isRunning.collect { running ->
                _uiState.update {
                    it.copy(
                        monitoring = running,
                        statusMessage = if (running) "眼健康监测运行中" else it.statusMessage,
                        statusTone = if (running) StatusTone.SUCCESS else it.statusTone
                    )
                }
            }
        }
        viewModelScope.launch {
            monitor.realTime.collect { realtime ->
                _uiState.update { it.copy(realtime = realtime) }
            }
        }
        viewModelScope.launch {
            monitor.samples.collect { samples ->
                _uiState.update { it.copy(samples = samples) }
            }
        }
        viewModelScope.launch {
            monitor.latestFrame.collect { frame ->
                _uiState.update { it.copy(fps = frame?.fps ?: 0f) }
            }
        }
        viewModelScope.launch {
            monitor.error.collect { error ->
                _uiState.update { it.copy(error = error) }
            }
        }
    }

    // region 监测

    fun startMonitoring() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _permissionEvents.tryEmit(FatiguePermissionEvent.Camera)
            return
        }
        serviceLauncher.start()
        _uiState.update {
            it.copy(statusMessage = "眼健康监测运行中", statusTone = StatusTone.SUCCESS)
        }
    }

    fun stopMonitoring() {
        serviceLauncher.stop()
        _uiState.update {
            it.copy(statusMessage = "监测已停止", statusTone = StatusTone.NEUTRAL)
        }
    }

    fun onCameraPermissionResult(granted: Boolean) {
        if (granted) {
            startMonitoring()
        } else {
            _uiState.update {
                it.copy(statusMessage = "摄像头权限被拒绝，无法监测", statusTone = StatusTone.DANGER)
            }
        }
    }

    // endregion

    // region 色温

    fun selectColorTemperature(mode: ColorTemperatureMode) {
        if (mode == ColorTemperatureMode.OFF) {
            colorTemperatureController.setMode(mode)
            refreshColorTemperature()
            return
        }
        if (!colorTemperatureController.hasOverlayPermission()) {
            _permissionEvents.tryEmit(FatiguePermissionEvent.Overlay(mode))
            return
        }
        colorTemperatureController.setMode(mode)
        refreshColorTemperature()
    }

    fun applyPendingColorTemperature(mode: ColorTemperatureMode) {
        if (colorTemperatureController.hasOverlayPermission()) {
            colorTemperatureController.setMode(mode)
        }
        refreshColorTemperature()
    }

    private fun refreshColorTemperature() {
        _uiState.update { it.copy(colorTemperatureMode = colorTemperatureController.currentMode()) }
    }

    // endregion

    // region 提醒

    fun toggleReminder() {
        if (reminderController.isEnabled()) {
            reminderController.cancel()
            refreshReminderState()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _permissionEvents.tryEmit(FatiguePermissionEvent.Notification)
            return
        }
        startReminder()
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        if (granted) startReminder()
    }

    fun onExactAlarmPermissionResult() {
        startReminder()
    }

    private fun startReminder() {
        if (!reminderController.canScheduleExactAlarms()) {
            _permissionEvents.tryEmit(FatiguePermissionEvent.ExactAlarm)
            return
        }
        val ok = reminderController.schedule(_uiState.value.reminderIntervalMinutes)
        if (!ok) {
            _permissionEvents.tryEmit(FatiguePermissionEvent.ExactAlarm)
        }
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
