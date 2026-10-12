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

    /** 首次进入自动开始监测只做一次：切 Tab 回来不应把用户手动停掉的监测又拉起来。 */
    private var autoStartHandled = false

    /** 跳去系统页申请精确闹钟权限后，回到前台要接着把提醒开起来。 */
    private var pendingExactAlarm = false

    private fun observeMonitor() {
        viewModelScope.launch {
            monitor.isRunning.collect { running ->
                _uiState.update { it.copy(monitoring = running) }
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
            monitor.error.collect { error ->
                _uiState.update { it.copy(error = error) }
            }
        }
    }

    /** 页面回到前台：同步可能在应用外被改变的状态（系统设置授权、通知栏关闭滤镜、提醒已触发）。 */
    fun refresh() {
        if (_uiState.value.cameraPermissionDenied && hasCameraPermission()) {
            _uiState.update { it.copy(cameraPermissionDenied = false) }
        }
        refreshColorTemperature()
        if (pendingExactAlarm) {
            pendingExactAlarm = false
            if (reminderController.canScheduleExactAlarms()) startReminder()
        }
        refreshReminderState()
    }

    /** 返回 true 表示本次应执行首次自动开始（之后恒为 false）。 */
    fun consumeAutoStart(): Boolean {
        if (autoStartHandled) return false
        autoStartHandled = true
        return true
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    // region 监测

    fun startMonitoring() {
        if (!hasCameraPermission()) {
            _permissionEvents.tryEmit(FatiguePermissionEvent.Camera)
            return
        }
        _uiState.update { it.copy(cameraPermissionDenied = false) }
        serviceLauncher.start()
    }

    fun stopMonitoring() {
        serviceLauncher.stop()
    }

    fun onCameraPermissionResult(granted: Boolean) {
        if (granted) {
            startMonitoring()
        } else {
            _uiState.update { it.copy(cameraPermissionDenied = true) }
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
        _uiState.update { it.copy(notice = null) }
        colorTemperatureController.setMode(mode)
        refreshColorTemperature()
    }

    fun applyPendingColorTemperature(mode: ColorTemperatureMode) {
        if (colorTemperatureController.hasOverlayPermission()) {
            colorTemperatureController.setMode(mode)
            _uiState.update { it.copy(notice = null) }
        } else {
            _uiState.update { it.copy(notice = "未授予悬浮窗权限，无法开启色温滤镜") }
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
            _uiState.update { it.copy(notice = null) }
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
            _permissionEvents.tryEmit(FatiguePermissionEvent.ExactAlarm)
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
