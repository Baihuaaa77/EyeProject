package com.zkyjss.gazelab

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zkyjss.eyeproject.core.domain.gaze.ScreenGazeSession
import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class GazeViewModel : ViewModel() {
    private data class Frame(val cameraSession: Long, val frame: FaceLandmarkFrame)

    private val engine = ScreenGazeSession()
    private val mutableState = MutableStateFlow(engine.state)
    val state = mutableState.asStateFlow()
    private val frames = Channel<Frame>(Channel.CONFLATED)
    private var cameraSession = 0L

    init {
        viewModelScope.launch {
            for ((session, frame) in frames) {
                val time = frame.analysisElapsedRealtimeMs ?: continue
                if (session == cameraSession) update {
                    consume(frame.faces.firstOrNull(), frame.sourceWidth, frame.sourceHeight, time, SystemClock.elapsedRealtime())
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(120)
                update { tick(SystemClock.elapsedRealtime()) }
            }
        }
    }

    fun beginCamera(): Long {
        update { beginCamera() }
        return ++cameraSession
    }

    fun stopCamera() {
        cameraSession++
        update { stopCamera() }
    }

    fun cameraError(session: Long): Boolean {
        if (session != cameraSession) return false
        cameraSession++
        update { cameraError("前置相机暂不可用") }
        return true
    }

    fun onFrame(session: Long, frame: FaceLandmarkFrame) {
        frames.trySend(Frame(session, frame))
    }

    fun setCanvasSize(width: Int, height: Int) = update { setCanvasSize(width, height) }

    fun startTargets(validation: Boolean) = update { startTargets(validation) }

    fun markTargetShown(session: Long, index: Int) = update {
        markTargetShown(session, index, SystemClock.elapsedRealtime())
    }

    fun cancelRun() = update { cancelRun() }

    private inline fun update(event: ScreenGazeSession.() -> Unit) {
        engine.event()
        mutableState.value = engine.state
    }
}
