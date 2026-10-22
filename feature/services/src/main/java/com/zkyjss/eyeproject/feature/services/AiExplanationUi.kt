package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.data.explanation.AiExplanationState
import com.zkyjss.eyeproject.core.data.explanation.ExplanationRepository
import com.zkyjss.eyeproject.core.ui.component.AiCardState
import com.zkyjss.eyeproject.core.ui.component.AiSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** 结果页 AI 解读卡片的状态：跟随当前保存的记录文件；记录尚未保存或无法解读时为 null（不显示卡片）。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun ExplanationRepository.cardFor(savedFile: Flow<String?>): Flow<AiCardState?> =
    savedFile.distinctUntilChanged().flatMapLatest { file ->
        if (file == null) flowOf(null) else explanation(file).map(AiExplanationState::toCard)
    }

internal fun AiExplanationState.toCard(): AiCardState? = when (this) {
    AiExplanationState.Unsupported -> null
    is AiExplanationState.NeedsAccount -> AiCardState.Locked(message)
    is AiExplanationState.NeedsConsent -> AiCardState.Idle(consentText)
    AiExplanationState.Ready -> AiCardState.Idle(null)
    AiExplanationState.Loading -> AiCardState.Loading
    is AiExplanationState.Failed -> AiCardState.Failed(message)
    is AiExplanationState.Done -> with(explanation) {
        AiCardState.Done(summary, meaning, suggestions, seeDoctor, sources.map { AiSource(it.title, it.publisher) }, disclaimer,
            urgent = riskLevel == "MEDIUM" || riskLevel == "HIGH")
    }
}
