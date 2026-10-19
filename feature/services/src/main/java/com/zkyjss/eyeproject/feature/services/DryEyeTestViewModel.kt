package com.zkyjss.eyeproject.feature.services

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zkyjss.eyeproject.core.camera.CameraController
import com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider
import com.zkyjss.eyeproject.core.data.record.ScreeningRecordRepository
import com.zkyjss.eyeproject.core.data.subject.SubjectRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** 安卓生命周期桥；完整测试流程与苹果共用。 */
@HiltViewModel
class DryEyeTestViewModel @Inject constructor(
    @param:ApplicationContext context: Context,
    camera: CameraController,
    intrinsics: CameraIntrinsicsProvider,
    records: ScreeningRecordRepository,
    subjectRepository: SubjectRepository,
) : ViewModel() {
    private val platform = AndroidScreeningPlatform(context, camera, intrinsics, records)
    val engine = DryEyeEngine(platform, viewModelScope)
    val uiState get() = engine.uiState
    fun feedbackCues(enabled: Boolean) = platform.feedbackSession.poll(screeningFeedbackFrame(uiState.value), platform.nowMs(), enabled)
    /** 本人与家庭成员：测试开始时选「这次测谁」，资料在「我的」维护。 */
    val subjects: StateFlow<List<SubjectProfile>> =
        subjectRepository.subjects.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun useSubject(profile: SubjectProfile) = engine.useSubject(profile)
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
