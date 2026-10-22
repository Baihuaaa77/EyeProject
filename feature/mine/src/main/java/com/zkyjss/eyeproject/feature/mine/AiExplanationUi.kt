package com.zkyjss.eyeproject.feature.mine

import com.zkyjss.eyeproject.core.data.explanation.AiExplanationState
import com.zkyjss.eyeproject.core.ui.component.AiCardState
import com.zkyjss.eyeproject.core.ui.component.AiSource

/** 测试记录里的 AI 解读卡片（与结果页同一映射；feature 之间不能互相依赖，故各保留一份）。 */
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
