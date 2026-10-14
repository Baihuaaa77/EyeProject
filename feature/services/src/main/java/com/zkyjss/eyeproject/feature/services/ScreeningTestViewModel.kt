package com.zkyjss.eyeproject.feature.services

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zkyjss.eyeproject.core.camera.CameraController
import com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** 安卓生命周期桥；完整测试流程与苹果共用。 */
@HiltViewModel
class ScreeningTestViewModel @Inject constructor(
    @param:ApplicationContext context: Context,
    camera: CameraController,
    intrinsics: CameraIntrinsicsProvider,
) : ViewModel() {
    private val platform = AndroidScreeningPlatform(context, camera, intrinsics)
    val engine = ScreeningEngine(platform, viewModelScope)
    val state get() = engine.state
    fun configure(kind: ScreeningKind) = engine.configure(kind)
    fun setField(key: String, value: String) = engine.setField(key, value)
    fun setMode(mode: String) = engine.setMode(mode)
    fun cameraPermissionDenied() = engine.cameraPermissionDenied()
    fun nextFromProfile() = engine.nextFromProfile()
    fun begin() = engine.begin()
    fun readyForNearEye() = engine.readyForNearEye()
    fun showTrial() = engine.showTrial()
    fun markTrialPresented(trialId: Int, timestampMs: Long) = engine.markTrialPresented(trialId, timestampMs)
    fun answer(direction: Int, byVoice: Boolean = false) = engine.answer(direction, byVoice)
    fun startPushTrial() = engine.startPushTrial()
    fun markPushBlur() = engine.markPushBlur()
    fun markCover() = engine.markCover()
    fun confirmGuide() = engine.confirmGuide()
    fun retryGazeCalibration() = engine.retryGazeCalibration()
    fun reportGazeTarget(xPx: Float, yPx: Float) = engine.reportGazeTarget(xPx, yPx)
    fun reportPursuitCapacity(maxXDeg: Float, maxYDeg: Float) = engine.reportPursuitCapacity(maxXDeg, maxYDeg)
    fun interruptTest() = engine.interruptTest()
    fun restart() = engine.restart()
    fun startCamera(owner: LifecycleOwner) { platform.owner = owner; engine.startCamera() }
    fun retryCamera(owner: LifecycleOwner) { platform.owner = owner; engine.retryCamera() }
    fun stopCamera() = engine.stopCamera()
    override fun onCleared() { engine.close() }
}
