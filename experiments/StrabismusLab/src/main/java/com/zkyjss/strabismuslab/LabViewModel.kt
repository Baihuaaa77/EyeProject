package com.zkyjss.strabismuslab

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider
import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
import com.zkyjss.eyeproject.strabismuslab.LabCameraIntrinsics
import com.zkyjss.eyeproject.strabismuslab.LabCodingScheme
import com.zkyjss.eyeproject.strabismuslab.LabFrameCaptureMeta
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
import java.util.concurrent.atomic.AtomicInteger

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

private data class QueuedCameraFrame(
    val frame: FaceLandmarkFrame,
    val exposureSec: Double?,
    val exposureLocked: Boolean,
)

class LabViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LabRepository(application)
    private val intrinsics = CameraIntrinsicsProvider(application)
    private val engine = StrabismusLabEngine(
        platformName = "Android ${Build.VERSION.RELEASE}",
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
    ).apply {
        configureAuditContext(
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            gitCommit = BuildConfig.LAB_GIT_COMMIT,
            clockDomain = "android.os.SystemClock.elapsedRealtime",
            displayClockDomain = "android.os.SystemClock.elapsedRealtime",
        )
    }

    val live: StateFlow<LabLiveState> = engine.live

    private val _ui = MutableStateFlow(LabAppUiState())
    val ui: StateFlow<LabAppUiState> = _ui.asStateFlow()

    private val frameChannel = Channel<QueuedCameraFrame>(capacity = Channel.CONFLATED)
    private val enqueuedFrames = AtomicInteger(0)
    private val consumedFrames = AtomicInteger(0)
    private var cachedFocalW = 0
    private var cachedFocalH = 0
    private var cachedIntrinsics = LabCameraIntrinsics()
    private var cachedIntrinsicsSource = "UNKNOWN"

    init {
        refreshHistory()
        viewModelScope.launch(Dispatchers.Default) {
            for (item in frameChannel) {
                val frame = item.frame
                val totalArrived = enqueuedFrames.get()
                val totalConsumed = consumedFrames.incrementAndGet()
                val droppedSincePrev = (totalArrived - totalConsumed).coerceAtLeast(0).also { dropped ->
                    if (dropped > 0) consumedFrames.addAndGet(dropped)
                }
                val camIntrinsics = resolveIntrinsics(frame.sourceWidth, frame.sourceHeight)
                val nowMs = frame.analysisElapsedRealtimeMs?.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
                val captureMeta = LabFrameCaptureMeta(
                    exposureSec = item.exposureSec,
                    exposureSource = if (item.exposureSec != null) "CAMERA2_CAPTURE_RESULT" else "FPS_APPROX",
                    sourceTimestampMs = frame.sourceElapsedRealtimeMs,
                    intrinsicsSource = cachedIntrinsicsSource,
                    mirrored = false,
                    cropNote = "CameraX_RGBA_8888_${frame.sourceWidth}x${frame.sourceHeight}",
                    exposureLocked = item.exposureLocked,
                    droppedSincePrev = droppedSincePrev,
                )
                val completed = engine.onFrameWithAudit(frame, camIntrinsics, nowMs, captureMeta)
                if (completed != null) {
                    handleCompleted(completed)
                }
            }
        }
    }

    fun onCameraFrame(frame: FaceLandmarkFrame, exposureSec: Double? = null, exposureLocked: Boolean = false) {
        enqueuedFrames.incrementAndGet()
        frameChannel.trySend(QueuedCameraFrame(frame, exposureSec, exposureLocked))
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

    fun confirmStageReady() {
        engine.confirmStageReady(SystemClock.elapsedRealtime())
    }

    fun confirmFarReady() = confirmStageReady()

    fun manualAdvanceCover() {
        engine.manualAdvanceCover(SystemClock.elapsedRealtime())
        if (live.value.stage == LabStage.COMPLETED) {
            finishEarlyAndAnalyze()
        }
    }

    fun skipStage() {
        engine.skipCurrentStage(SystemClock.elapsedRealtime())
        if (live.value.stage == LabStage.COMPLETED) {
            finishEarlyAndAnalyze()
        }
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
        val nowMs = SystemClock.elapsedRealtime()
        val cancelled = engine.cancelAndBuildReport(nowMs, "USER_CANCELLED")
        _ui.update { it.copy(destination = LabDestination.CONSOLE, analyzing = false) }
        if (cancelled != null) {
            viewModelScope.launch(Dispatchers.IO) {
                repository.save(cancelled)
                val entries = repository.listAll()
                _ui.update { it.copy(history = entries) }
            }
        }
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

    private fun resolveIntrinsics(width: Int, height: Int): LabCameraIntrinsics {
        if (width <= 0 || height <= 0) return cachedIntrinsics
        if (width != cachedFocalW || height != cachedFocalH || !cachedIntrinsics.isValid) {
            cachedFocalW = width
            cachedFocalH = height
            val g = intrinsics.geometry(width, height)
            cachedIntrinsics = if (g != null) {
                cachedIntrinsicsSource = if (g.cameraId == "host") "HOST_CAMERA_HFOV_APPROX" else "CAMERA2_LENS_APPROX"
                LabCameraIntrinsics(
                    fx = g.focalLengthPx.toDouble(),
                    fy = g.focalLengthPx.toDouble(),
                    cx = g.principalX.toDouble(),
                    cy = g.principalY.toDouble(),
                    lightOffsetXMm = _ui.value.config.lightOffsetXMm,
                    lightOffsetYMm = _ui.value.config.lightOffsetYMm,
                )
            } else {
                cachedIntrinsicsSource = "UNAVAILABLE"
                LabCameraIntrinsics()
            }
        }
        return cachedIntrinsics
    }
}
