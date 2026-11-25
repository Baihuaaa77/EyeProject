package com.zkyjss.strabismuslab

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider
import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
import com.zkyjss.eyeproject.strabismuslab.LabCodingScheme
import com.zkyjss.eyeproject.strabismuslab.LabLiveState
import com.zkyjss.eyeproject.strabismuslab.LabProtocolConfig
import com.zkyjss.eyeproject.strabismuslab.LabSessionReport
import com.zkyjss.eyeproject.strabismuslab.LabStage
import com.zkyjss.eyeproject.strabismuslab.StrabismusLabEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LabDestination {
    CONSOLE,
    CAPTURE,
    DASHBOARD,
    HISTORY,
}

data class LabAppUiState(
    val destination: LabDestination = LabDestination.CONSOLE,
    val config: LabProtocolConfig = LabProtocolConfig(),
    val showPreviewOverlay: Boolean = true,
    val activeReport: LabSessionReport? = null,
    val history: List<LabSessionEntry> = emptyList(),
    val analyzing: Boolean = false,
    val cameraError: String? = null,
    val statusBanner: String? = null,
)

class LabViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LabRepository(application)
    private val intrinsics = CameraIntrinsicsProvider(application)
    private val engine = StrabismusLabEngine(
        platformName = "Android ${Build.VERSION.RELEASE}",
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
    )

    val live: StateFlow<LabLiveState> = engine.live

    private val _ui = MutableStateFlow(LabAppUiState())
    val ui: StateFlow<LabAppUiState> = _ui.asStateFlow()

    private val frameChannel = Channel<FaceLandmarkFrame>(capacity = Channel.CONFLATED)
    private var cachedFocalW = 0
    private var cachedFocalH = 0
    private var cachedFocalPx = 0f

    init {
        refreshHistory()
        viewModelScope.launch(Dispatchers.Default) {
            for (frame in frameChannel) {
                val focal = resolveFocalPx(frame.sourceWidth, frame.sourceHeight)
                val nowMs = frame.analysisElapsedRealtimeMs?.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
                val completed = engine.onFrame(frame, focal, nowMs)
                if (completed != null) {
                    handleCompleted(completed)
                }
            }
        }
    }

    fun onCameraFrame(frame: FaceLandmarkFrame) {
        frameChannel.trySend(frame)
    }

    fun onCameraError(message: String) {
        _ui.update { it.copy(cameraError = message) }
    }

    fun clearCameraError() {
        _ui.update { it.copy(cameraError = null) }
    }

    fun updateConfig(transform: (LabProtocolConfig) -> LabProtocolConfig) {
        _ui.update { cur ->
            val updated = transform(cur.config)
            engine.updatePreviewConfig(updated)
            cur.copy(config = updated)
        }
    }

    fun setCodingScheme(scheme: LabCodingScheme) = updateConfig { it.copy(codingScheme = scheme) }
    fun setSearchRadius(radius: Float) = updateConfig { it.copy(searchRadiusFraction = radius.coerceIn(0.55f, 0.95f)) }
    fun setChipDurationMs(ms: Long) = updateConfig { it.copy(chipDurationMs = ms.coerceIn(125L, 500L)) }
    fun setPreviewOverlay(enabled: Boolean) { _ui.update { it.copy(showPreviewOverlay = enabled) } }

    fun startCapture() {
        val cfg = _ui.value.config
        if (cfg.activeStages.isEmpty()) {
            _ui.update { it.copy(statusBanner = "请至少启用一个测试阶段") }
            return
        }
        engine.startSession(cfg, SystemClock.elapsedRealtime())
        _ui.update { it.copy(destination = LabDestination.CAPTURE, statusBanner = null, activeReport = null) }
    }

    fun onDisplayFrameTick(screenBrightness: Double): Pair<Float, Double> =
        engine.recordDisplayTick(SystemClock.elapsedRealtime(), screenBrightness)

    fun confirmFarReady() {
        engine.confirmFarReady(SystemClock.elapsedRealtime())
    }

    fun manualAdvanceCover() {
        engine.manualAdvanceCover(SystemClock.elapsedRealtime())
    }

    fun skipStage() {
        engine.skipCurrentStage(SystemClock.elapsedRealtime())
    }

    fun finishEarlyAndAnalyze() {
        if (_ui.value.analyzing) return
        _ui.update { it.copy(analyzing = true) }
        viewModelScope.launch(Dispatchers.Default) {
            val report = engine.stopAndBuildReport(SystemClock.elapsedRealtime())
            handleCompleted(report)
        }
    }

    fun cancelCapture() {
        engine.resetToIdle()
        _ui.update { it.copy(destination = LabDestination.CONSOLE, analyzing = false) }
    }

    fun openHistory() {
        refreshHistory()
        _ui.update { it.copy(destination = LabDestination.HISTORY) }
    }

    fun openReport(report: LabSessionReport) {
        _ui.update { it.copy(activeReport = report, destination = LabDestination.DASHBOARD) }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(sessionId)
            refreshHistory()
        }
    }

    fun backToConsole() {
        engine.resetToIdle()
        _ui.update { it.copy(destination = LabDestination.CONSOLE) }
    }

    fun buildExportIntent(report: LabSessionReport, format: LabExportFormat) =
        repository.shareExport(report, format)

    private suspend fun handleCompleted(report: LabSessionReport) {
        withContext(Dispatchers.IO) { repository.save(report) }
        val entries = withContext(Dispatchers.IO) { repository.listAll() }
        _ui.update {
            it.copy(
                activeReport = report,
                history = entries,
                destination = LabDestination.DASHBOARD,
                analyzing = false,
            )
        }
    }

    private fun refreshHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val entries = repository.listAll()
            _ui.update { it.copy(history = entries) }
        }
    }

    private fun resolveFocalPx(width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return cachedFocalPx
        if (width != cachedFocalW || height != cachedFocalH || cachedFocalPx <= 0f) {
            cachedFocalW = width
            cachedFocalH = height
            cachedFocalPx = intrinsics.focalLengthPx(width, height)
        }
        return cachedFocalPx
    }
}
