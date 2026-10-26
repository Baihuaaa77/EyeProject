package com.zkyjss.eyeproject.feature.home.live

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.zkyjss.eyeproject.core.camera.CameraController
import com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider
import com.zkyjss.eyeproject.core.data.eyecare.EyeCareRepository
import com.zkyjss.eyeproject.core.domain.LiveEyeMonitor
import com.zkyjss.eyeproject.feature.home.LiveEyeUiState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 实时眼部检测的唯一持有者（进程级单例）：打开后一直检测到用户关掉开关、点通知里的「停止」或从最近任务划掉应用。
 *
 * - 首页可见时相机绑定到首页生命周期并接上预览（[attach]）；离开首页或退到后台时改为无预览、只分析，
 *   绑定到前台服务 [LiveEyeService] 的生命周期（[detach]），通知栏常驻提示。
 * - 熄屏时暂停相机（看不到人脸，只会白白耗电），亮屏解锁后自动继续。
 * - 监视器 [LiveEyeMonitor] 跨页面、跨前后台持续累计；每 [FLUSH_MS] 或跨天时把这一段统计记入主动护眼账本，关闭时记最后一段。
 *
 * 测试页接管相机时（[CameraController] 代号变化）这里的绑定自动作废，回到首页再接上。
 */
@Singleton
class LiveEyeTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val camera: CameraController,
    private val intrinsics: CameraIntrinsicsProvider,
    private val eyeCare: EyeCareRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val monitor = LiveEyeMonitor()
    private val _state = MutableStateFlow(LiveEyeUiState())
    val state: StateFlow<LiveEyeUiState> = _state.asStateFlow()

    private var generation = -1
    private var ticker: Job? = null
    /** 首页正显示预览 */
    private var attached = false
    /** 熄屏暂停中 */
    private var screenOff = false
    private var service: LifecycleOwner? = null
    private var lastFlushMs = 0L
    private var day = LocalDate.now()

    /** 打开：从零开始统计，先绑定到首页（带预览），同时启动前台服务以便退到后台后继续。 */
    fun start(owner: LifecycleOwner, preview: PreviewView) {
        if (_state.value.running) { attach(owner, preview); return }
        monitor.reset()
        lastFlushMs = SystemClock.elapsedRealtime()
        day = LocalDate.now()
        _state.update { it.copy(running = true, error = null, snapshot = null) }
        runCatching { ContextCompat.startForegroundService(context, Intent(context, LiveEyeService::class.java)) }
            .onFailure { error -> _state.update { it.copy(error = "后台检测启动失败：${error.message ?: "未知原因"}") } }
        attach(owner, preview)
        tick()
    }

    /** 首页回到前台：相机改绑到首页并接上预览。 */
    fun attach(owner: LifecycleOwner, preview: PreviewView) {
        if (!_state.value.running) return
        attached = true
        bind(owner, preview)
    }

    /** 离开首页或退到后台：改为无预览、只分析，绑定到前台服务（服务还没起来时等它就绪再绑）。 */
    fun detach() {
        if (!_state.value.running || !attached) return
        attached = false
        bindBackground()
    }

    /** 关闭：释放相机、记下最后一段统计、结束前台服务。 */
    fun stop() {
        if (!_state.value.running) return
        ticker?.cancel(); ticker = null
        camera.stopIfGeneration(generation)
        attached = false
        _state.update { it.copy(running = false) }
        flush(force = true)
        context.stopService(Intent(context, LiveEyeService::class.java))
    }

    internal fun onServiceReady(owner: LifecycleOwner) {
        service = owner
        if (_state.value.running && !attached) bindBackground()
    }

    internal fun onServiceGone() { service = null }

    /** 熄屏：暂停相机（首页不可见时才会发生）。 */
    internal fun onScreenOff() {
        screenOff = true
        if (_state.value.running && !attached) camera.stopIfGeneration(generation)
    }

    /** 亮屏解锁：在后台继续检测。 */
    internal fun onScreenOn() {
        screenOff = false
        if (_state.value.running && !attached) bindBackground()
    }

    private fun bindBackground() {
        val owner = service ?: return
        if (screenOff) return
        bind(owner, null)
    }

    private fun bind(owner: LifecycleOwner, preview: PreviewView?) {
        generation = camera.start(owner, preview,
            onFrame = { frame ->
                monitor.update(frame.faces.firstOrNull(), frame.sourceWidth, frame.sourceHeight,
                    frame.analysisElapsedRealtimeMs ?: SystemClock.elapsedRealtime(),
                    intrinsics.focalLengthPx(frame.sourceWidth, frame.sourceHeight))
            },
            onError = { error -> _state.update { it.copy(error = "相机启动失败：${error.message ?: "未知原因"}") } })
    }

    /** 首页可见时每 80 ms 刷新一次界面快照；后台时每秒检查一次是否该分段记账。 */
    private fun tick() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                if (attached) _state.update { it.copy(snapshot = monitor.snapshot(SystemClock.elapsedRealtime())) }
                flush(force = false)
                delay(if (attached) PUBLISH_MS else BACKGROUND_TICK_MS)
            }
        }
    }

    private fun flush(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val today = LocalDate.now()
        if (!force && now - lastFlushMs < FLUSH_MS && today == day) return
        lastFlushMs = now
        day = today
        monitor.takeSession()?.let { session -> scope.launch { eyeCare.addLiveSession(session) } }
    }

    private companion object {
        const val PUBLISH_MS = 80L
        const val BACKGROUND_TICK_MS = 1_000L
        /** 后台长时间检测时每 10 分钟记一段 */
        const val FLUSH_MS = 10 * 60_000L
    }
}
