package com.zkyjss.eyeproject.feature.home

import android.os.SystemClock
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zkyjss.eyeproject.core.camera.CameraController
import com.zkyjss.eyeproject.core.domain.LiveEyeMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 首页实时眼部检测视窗的界面状态。 */
data class LiveEyeUiState(
    val running: Boolean = false,
    val snapshot: LiveEyeMonitor.Snapshot? = null,
    val error: String? = null,
)

/**
 * 首页「实时眼部检测」：相机管线走 [CameraController]（与测试共用），每帧关键点喂给共用 [LiveEyeMonitor]
 * （眨眼判据与干眼测试同一份）；界面每 80 ms 取一次快照，不逐帧刷新整块卡片。与苹果端 EyeLiveModel 同一份逻辑。
 */
@HiltViewModel
class LiveEyeViewModel @Inject constructor(
    private val camera: CameraController,
) : ViewModel() {
    private val monitor = LiveEyeMonitor()
    private val _uiState = MutableStateFlow(LiveEyeUiState())
    val uiState: StateFlow<LiveEyeUiState> = _uiState.asStateFlow()
    private var generation = -1
    private var ticker: Job? = null

    /** 开启：绑定预览与分析（关键点在分析线程喂给监视器，监视器内部加锁）。 */
    fun start(owner: LifecycleOwner, preview: PreviewView) {
        if (_uiState.value.running) return
        monitor.reset()
        generation = camera.start(owner, preview,
            onFrame = { frame ->
                monitor.update(frame.faces.firstOrNull(), frame.sourceWidth, frame.sourceHeight,
                    frame.analysisElapsedRealtimeMs ?: SystemClock.elapsedRealtime())
            },
            onError = { error -> _uiState.update { it.copy(error = "相机启动失败：${error.message ?: "未知原因"}") } })
        _uiState.update { it.copy(running = true, error = null) }
        ticker = viewModelScope.launch {
            while (isActive) {
                _uiState.update { it.copy(snapshot = monitor.snapshot(SystemClock.elapsedRealtime())) }
                delay(PUBLISH_MS)
            }
        }
    }

    /** 关闭：只释放自己这一代绑定，测试页已接管相机时不受影响。 */
    fun stop() {
        if (!_uiState.value.running) return
        ticker?.cancel(); ticker = null
        camera.stopIfGeneration(generation)
        _uiState.update { it.copy(running = false) }
    }

    override fun onCleared() { stop() }

    private companion object { const val PUBLISH_MS = 80L }
}
