package com.zkyjss.eyeproject.feature.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zkyjss.eyeproject.core.common.formatDuration
import com.zkyjss.eyeproject.core.common.formatTime
import com.zkyjss.eyeproject.core.data.AmbientLightMonitor
import com.zkyjss.eyeproject.core.data.AppSettings
import com.zkyjss.eyeproject.core.data.ScreenBrightnessReader
import com.zkyjss.eyeproject.core.data.ScreenUsageRepository
import com.zkyjss.eyeproject.core.data.UsageAccessHelper
import com.zkyjss.eyeproject.core.domain.ColorTemperatureController
import com.zkyjss.eyeproject.core.domain.DistanceEstimator
import com.zkyjss.eyeproject.core.domain.ReminderController
import com.zkyjss.eyeproject.core.model.AnalysisResolution
import com.zkyjss.eyeproject.core.model.ColorTemperatureMode
import com.zkyjss.eyeproject.core.model.DistanceEstimate
import com.zkyjss.eyeproject.core.model.FaceEyeDetection
import com.zkyjss.eyeproject.core.model.FrameResult
import com.zkyjss.eyeproject.core.ui.component.StatusTone
import com.zkyjss.eyeproject.feature.camera.CameraController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val cameraController: CameraController,
    private val settings: AppSettings,
    private val usageRepository: ScreenUsageRepository,
    private val ambientLightMonitor: AmbientLightMonitor,
    private val colorTemperatureController: ColorTemperatureController,
    private val reminderController: ReminderController,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val distanceEstimator = DistanceEstimator(
        calibratedEyeDistancePx = settings.calibratedEyeDistancePx
    )

    private val _uiState = MutableStateFlow(
        HomeUiState(
            analysisResolution = settings.analysisResolution,
            colorTemperatureMode = colorTemperatureController.currentMode(),
            lightSensorAvailable = ambientLightMonitor.isAvailable
        )
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _permissionEvents = MutableSharedFlow<PermissionEvent>(extraBufferCapacity = 1)
    val permissionEvents: SharedFlow<PermissionEvent> = _permissionEvents.asSharedFlow()

    private var latestFrameResult: FrameResult? = null
    private var latestFaceDetection: FaceEyeDetection? = null

    private var usageBaselineMillis = 0L
    private var usageBaselineRealtimeMillis = 0L
    private var lastUsageStatsRefreshRealtimeMillis = 0L

    private var activeLifecycleOwner: LifecycleOwner? = null
    private var activePreviewView: PreviewView? = null

    init {
        observeFrames()
        observeAmbientLight()
        startUsageLoop()
        refreshUsage()
        refreshBrightness()
        refreshReminderState()
    }

    override fun onCleared() {
        cameraController.stop()
        super.onCleared()
    }

    // region 摄像头

    fun startDetection(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        if (cameraController.isRunning) {
            return
        }
        activeLifecycleOwner = lifecycleOwner
        activePreviewView = previewView

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _permissionEvents.tryEmit(PermissionEvent.Camera)
            return
        }
        if (!UsageAccessHelper.hasUsageAccess(context)) {
            _permissionEvents.tryEmit(PermissionEvent.UsageAccess)
            return
        }

        startCamera(lifecycleOwner, previewView)
    }

    fun onCameraPermissionResult(granted: Boolean) {
        if (!granted) {
            setStatus("摄像头权限被拒绝。", StatusTone.DANGER)
            return
        }
        val owner = activeLifecycleOwner
        val view = activePreviewView
        if (owner != null && view != null) {
            startDetection(owner, view)
        }
    }

    fun stopDetection() {
        cameraController.stop()
        latestFrameResult = null
        latestFaceDetection = null
        _uiState.update {
            it.copy(
                cameraRunning = false,
                detections = emptyList(),
                distanceEstimate = null,
                faceCount = 0,
                fps = 0f,
                statusMessage = "检测已停止。",
                statusTone = StatusTone.NEUTRAL
            )
        }
    }

    private fun startCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        cameraController.start(
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            resolution = _uiState.value.analysisResolution,
            onError = { throwable ->
                _uiState.update {
                    it.copy(
                        cameraRunning = false,
                        statusMessage = "摄像头或眼部检测失败：${throwable.message}",
                        statusTone = StatusTone.DANGER
                    )
                }
            }
        )
        _uiState.update {
            it.copy(
                cameraRunning = true,
                statusMessage = "前置摄像头与眼部检测运行中。",
                statusTone = StatusTone.SUCCESS
            )
        }
    }

    private fun observeFrames() {
        viewModelScope.launch {
            cameraController.resultFlow.collect { result -> processFrame(result) }
        }
    }

    private fun processFrame(result: FrameResult) {
        latestFrameResult = result
        val first = result.detections.firstOrNull()
        latestFaceDetection = first

        val estimate = if (first != null) {
            distanceEstimator.estimate(
                leftEyeCenter = first.leftEye?.irisCenter ?: first.leftEye?.center,
                rightEyeCenter = first.rightEye?.irisCenter ?: first.rightEye?.center,
                sourceWidth = result.sourceWidth,
                faceCount = result.faceCount
            )
        } else {
            null
        }

        _uiState.update {
            it.copy(
                detections = result.detections,
                sourceWidth = result.sourceWidth,
                sourceHeight = result.sourceHeight,
                fps = result.fps,
                latencyMillis = result.detectionLatencyMillis,
                faceCount = result.faceCount,
                distanceEstimate = estimate,
                statusMessage = when {
                    first == null -> "未检测到人脸"
                    estimate?.state == com.zkyjss.eyeproject.core.model.DistanceState.TOO_CLOSE -> "距离过近"
                    else -> "检测中"
                },
                statusTone = when {
                    first == null -> StatusTone.WARNING
                    estimate?.state == com.zkyjss.eyeproject.core.model.DistanceState.TOO_CLOSE -> StatusTone.DANGER
                    else -> StatusTone.SUCCESS
                }
            )
        }
    }

    // endregion

    // region 标定与分辨率

    fun calibrateDistance() {
        val detection = latestFaceDetection
        if (detection == null) {
            _uiState.update { it.copy(statusMessage = "标定失败：未检测到人脸。", statusTone = StatusTone.WARNING) }
            return
        }
        val eyeDistancePx = distanceEstimator.calculateEyeDistancePx(
            leftEyeCenter = detection.leftEye?.irisCenter ?: detection.leftEye?.center,
            rightEyeCenter = detection.rightEye?.irisCenter ?: detection.rightEye?.center
        )
        if (eyeDistancePx == null) {
            _uiState.update { it.copy(statusMessage = "标定失败：需同时检测到双眼。", statusTone = StatusTone.WARNING) }
            return
        }
        distanceEstimator.setCalibration(eyeDistancePx)
        settings.calibratedEyeDistancePx = eyeDistancePx
        _uiState.update {
            it.copy(
                statusMessage = "已按 40cm 标定（${eyeDistancePx.roundToInt()} px）。",
                statusTone = StatusTone.SUCCESS
            )
        }
    }

    fun selectResolution(resolution: AnalysisResolution) {
        if (_uiState.value.analysisResolution == resolution) {
            return
        }
        settings.analysisResolution = resolution
        _uiState.update { it.copy(analysisResolution = resolution) }

        val owner = activeLifecycleOwner
        val view = activePreviewView
        if (cameraController.isRunning && owner != null && view != null) {
            cameraController.stop()
            startCamera(owner, view)
        }
    }

    // endregion

    // region 使用时长 / 亮度 / 光照

    private fun startUsageLoop() {
        viewModelScope.launch {
            while (true) {
                updateUsageTick()
                refreshBrightness()
                delay(1_000L)
            }
        }
    }

    private fun updateUsageTick() {
        if (!UsageAccessHelper.hasUsageAccess(context)) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastUsageStatsRefreshRealtimeMillis >= USAGE_REBASE_INTERVAL_MS) {
            refreshUsage()
        } else {
            updateLiveUsage()
        }
    }

    private fun refreshUsage() {
        if (!UsageAccessHelper.hasUsageAccess(context)) {
            _uiState.update {
                it.copy(
                    screenUsageText = "--",
                    screenUsageDetail = "需要开启使用情况访问权限"
                )
            }
            return
        }
        val items = usageRepository.getTodayUsage()
        val total = items.sumOf { it.totalTimeInForegroundMillis }
        val live = maxOf(total, currentLiveUsageMillis())
        usageBaselineMillis = live
        usageBaselineRealtimeMillis = SystemClock.elapsedRealtime()
        lastUsageStatsRefreshRealtimeMillis = usageBaselineRealtimeMillis
        _uiState.update {
            it.copy(
                screenUsageText = formatDuration(live),
                screenUsageDetail = if (items.isEmpty()) "实时计时中" else "实时计时中，已记录 ${items.size} 个应用"
            )
        }
    }

    private fun updateLiveUsage() {
        if (!UsageAccessHelper.hasUsageAccess(context)) {
            return
        }
        val live = currentLiveUsageMillis()
        _uiState.update {
            it.copy(screenUsageText = formatDuration(live), screenUsageDetail = "实时计时中")
        }
    }

    private fun currentLiveUsageMillis(): Long {
        val baseline = usageBaselineMillis
        val baselineRealtime = usageBaselineRealtimeMillis
        if (baselineRealtime <= 0L) {
            return baseline
        }
        return baseline + (SystemClock.elapsedRealtime() - baselineRealtime).coerceAtLeast(0L)
    }

    private fun refreshBrightness() {
        val reading = ScreenBrightnessReader.read(context)
        _uiState.update {
            it.copy(
                brightnessPercent = reading.percent,
                brightnessAutomatic = reading.automatic
            )
        }
    }

    private fun observeAmbientLight() {
        viewModelScope.launch {
            ambientLightMonitor.luxFlow().collect { lux ->
                _uiState.update { it.copy(ambientLux = lux) }
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
            _permissionEvents.tryEmit(PermissionEvent.Overlay(mode))
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
            _permissionEvents.tryEmit(PermissionEvent.Notification)
            return
        }
        startReminder()
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        if (granted) {
            startReminder()
        }
    }

    private fun startReminder() {
        if (!reminderController.canScheduleExactAlarms()) {
            _permissionEvents.tryEmit(PermissionEvent.ExactAlarm)
            return
        }
        val ok = reminderController.schedule(_uiState.value.reminderIntervalMinutes)
        if (!ok) {
            _permissionEvents.tryEmit(PermissionEvent.ExactAlarm)
        }
        refreshReminderState()
    }

    fun onExactAlarmPermissionResult() {
        startReminder()
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

    private fun setStatus(message: String, tone: StatusTone) {
        _uiState.update { it.copy(statusMessage = message, statusTone = tone) }
    }

    companion object {
        private const val USAGE_REBASE_INTERVAL_MS = 30_000L
    }
}
