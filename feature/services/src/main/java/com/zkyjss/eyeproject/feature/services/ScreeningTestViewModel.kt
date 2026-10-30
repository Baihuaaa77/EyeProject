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
class ScreeningTestViewModel @Inject constructor(
    @param:ApplicationContext context: Context,
    camera: CameraController,
    intrinsics: CameraIntrinsicsProvider,
    records: ScreeningRecordRepository,
    subjectRepository: SubjectRepository,
    private val explanations: ExplanationRepository,
) : ViewModel() {
    private val platform = AndroidScreeningPlatform(context, camera, intrinsics, records)
    val engine = ScreeningEngine(platform, viewModelScope)
    val state get() = engine.state
    fun feedbackCues(enabled: Boolean) = platform.feedbackSession.poll(screeningFeedbackFrame(state.value, engine.coverRemainingMs()), platform.nowMs(), enabled)
    fun configure(kind: ScreeningKind) = engine.configure(kind)
    /** 本人与家庭成员：测试开始时选「这次测谁」，资料在「我的」维护。 */
    val subjects: StateFlow<List<SubjectProfile>> =
        subjectRepository.subjects.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun useSubject(profile: SubjectProfile) = engine.useSubject(profile)
    fun setMode(mode: String) = engine.setMode(mode)
    fun cameraPermissionDenied() = engine.cameraPermissionDenied()
    fun nextFromProfile() = engine.nextFromProfile()
    fun skipProfile() = engine.skipProfile()
    fun confirmCheck(answers: Map<String, String>) = engine.confirmCheck(answers)
    fun begin() = engine.begin()
    fun readyForNearEye() = engine.readyForNearEye()
    fun showTrial() = engine.showTrial()
    fun markTrialPresented(trialId: Int, timestampMs: Long) = engine.markTrialPresented(trialId, timestampMs)
    fun answer(direction: Int, byVoice: Boolean = false) = engine.answer(direction, byVoice)
    fun startPushTrial() = engine.startPushTrial()
    fun markPushBlur() = engine.markPushBlur()
    fun markCover() = engine.markCover()
    fun startFarCountdown() = engine.startFarCountdown()
    fun confirmGuide() = engine.confirmGuide()
    fun confirmGlassesRemoved() = engine.confirmGlassesRemoved()
    fun retryGazeCalibration() = engine.retryGazeCalibration()
    fun reportGazeTarget(xPx: Float, yPx: Float) = engine.reportGazeTarget(xPx, yPx)
    fun reportPursuitCapacity(maxXDeg: Float, maxYDeg: Float) = engine.reportPursuitCapacity(maxXDeg, maxYDeg)
    fun interruptTest() = engine.interruptTest()
    fun restart() = engine.restart()
    fun startCamera(owner: LifecycleOwner) { platform.owner = owner; engine.startCamera() }
    fun retryCamera(owner: LifecycleOwner) { platform.owner = owner; engine.retryCamera() }
    fun stopCamera() = engine.stopCamera()
    /** 结果页的 AI 解读卡片：跟随本次保存的记录；规则等级不变，模型只做解释。 */
    val explanation: StateFlow<AiCardState?> = explanations.cardFor(engine.state.map { it.savedFile })
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    /** [agreeFirst]：首次使用时在卡片内点了「同意并生成」。 */
    fun explain(agreeFirst: Boolean = false) {
        val file = state.value.savedFile ?: return
        viewModelScope.launch { explanations.generate(file, agreeFirst) }
    }
    override fun onCleared() { engine.close() }
}
