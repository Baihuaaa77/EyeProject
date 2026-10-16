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
class DryEyeTestViewModel @Inject constructor(
    @param:ApplicationContext context: Context,
    camera: CameraController,
    intrinsics: CameraIntrinsicsProvider,
) : ViewModel() {
    private val platform = AndroidScreeningPlatform(context, camera, intrinsics)
    val engine = DryEyeEngine(platform, viewModelScope)
    val uiState get() = engine.uiState
    fun feedbackCues(enabled: Boolean) = platform.feedbackSession.poll(screeningFeedbackFrame(uiState.value), platform.nowMs(), enabled)
    fun setField(key: String, value: String) = engine.setField(key, value)
    fun finishProfile() = engine.finishProfile()
    fun skipProfile() = engine.skipProfile()
    fun permissionDenied() = engine.permissionDenied()
    fun startTest() = engine.startTest()
    fun confirmPracticeGuide() = engine.confirmPracticeGuide()
    fun confirmFormalGuide() = engine.confirmFormalGuide()
    fun startExtended() = engine.startExtended()
    fun startRestRetest() = engine.startRestRetest()
    fun answer(direction: Int, byVoice: Boolean = false) = engine.answer(direction, byVoice)
    fun backToSetup(message: String? = null) = engine.backToSetup(message)
    fun restartAll() = engine.restartAll()
    fun startMonitoring(lifecycleOwner: LifecycleOwner) { platform.owner = lifecycleOwner; engine.startMonitoring() }
    fun stopMonitoring() = engine.stopMonitoring()
    override fun onCleared() { engine.close() }
}
