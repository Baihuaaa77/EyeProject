package com.zkyjss.eyeproject.feature.agent

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val AGENT_REPLY_PLACEHOLDER = "开发中"

/** 智能体对话 ViewModel：维护消息列表与输入框状态，发送后以「开发中」占位回复。 */
@HiltViewModel
class AgentViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(
        AgentUiState(
            messages = listOf(
                AgentMessage(AgentMessageRole.AGENT, "你好，我是眼健康智能体，有什么可以帮你？")
            )
        )
    )
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    fun onInputChange(text: String) {
        _uiState.value = _uiState.value.copy(input = text)
    }

    fun send() {
        val current = _uiState.value
        val text = current.input.trim()
        if (text.isEmpty()) return
        _uiState.value = current.copy(
            messages = current.messages +
                AgentMessage(AgentMessageRole.USER, text) +
                AgentMessage(AgentMessageRole.AGENT, AGENT_REPLY_PLACEHOLDER),
            input = "",
        )
    }
}
