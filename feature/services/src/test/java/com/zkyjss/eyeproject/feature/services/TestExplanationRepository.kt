package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.data.explanation.AiExplanationState
import com.zkyjss.eyeproject.core.data.explanation.ExplanationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** 测试替身：固定返回 [state]（默认无法解读，结果页不显示 AI 卡片）。 */
class TestExplanationRepository(state: AiExplanationState = AiExplanationState.Unsupported) : ExplanationRepository {
    val state = MutableStateFlow(state)
    val generated = mutableListOf<Pair<String, Boolean>>()
    override fun explanation(fileName: String): Flow<AiExplanationState> = state
    override suspend fun generate(fileName: String, agreeFirst: Boolean) { generated += fileName to agreeFirst }
}
