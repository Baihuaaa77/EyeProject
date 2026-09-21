package com.zkyjss.eyeproject.feature.agent

enum class AgentMessageRole { USER, AGENT }

/** 智能体对话中的一条消息。 */
data class AgentMessage(
    val role: AgentMessageRole,
    val text: String,
)

/** 智能体对话 UI 状态。 */
data class AgentUiState(
    val messages: List<AgentMessage> = emptyList(),
    val input: String = "",
)
