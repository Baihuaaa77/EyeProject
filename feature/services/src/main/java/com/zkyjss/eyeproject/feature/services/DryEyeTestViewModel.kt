package com.zkyjss.eyeproject.feature.services

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zkyjss.eyeproject.core.camera.CameraController
import com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider
import com.zkyjss.eyeproject.core.data.explanation.ExplanationRepository
import com.zkyjss.eyeproject.core.data.record.ScreeningRecordRepository
import com.zkyjss.eyeproject.core.data.subject.SubjectRepository
import com.zkyjss.eyeproject.core.ui.component.AiCardState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    private val explanations: ExplanationRepository,
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
    fun confirmCheck(answers: Map<String, String>) = engine.confirmCheck(answers)
    fun permissionDenied() = engine.permissionDenied()
    fun markTrialPresented(trialId: Int, timestampMs: Long) = engine.markTrialPresented(trialId, timestampMs)
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
    /** 结果页的 AI 解读卡片：跟随本次保存的记录；规则等级不变，模型只做解释。 */
    val explanation: StateFlow<AiCardState?> = explanations.cardFor(engine.uiState.map { it.savedFile })
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    /** [agreeFirst]：首次使用时在卡片内点了「同意并生成」。 */
    fun explain(agreeFirst: Boolean = false) {
        val file = uiState.value.savedFile ?: return
        viewModelScope.launch { explanations.generate(file, agreeFirst) }
    }
    override fun onCleared() { engine.close() }
}
