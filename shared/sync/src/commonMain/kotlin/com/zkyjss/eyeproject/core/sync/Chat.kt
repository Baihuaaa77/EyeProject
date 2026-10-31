package com.zkyjss.eyeproject.core.sync

import kotlinx.serialization.Serializable

/*
 * 和爱科聊聊（AI 对话）：用户自由描述今天的用眼感受或提问，大模型结合「AI 综合建议」同一份去标识用眼概况
 * （[AdviceRequest]，用户已确认的清单）与眼科知识库回答。对话记录只存在手机本机，服务器只转发、不保存内容。
 * 需要单独的 AI_CHAT 同意（用户自己打的字可能含健康信息）。
 */

@Serializable
data class ChatMessage(
    /** [Chats.USER] 或 [Chats.ASSISTANT] */
    val role: String,
    val text: String,
)

@Serializable
data class ChatRequest(
    /** 最近的若干条对话（时间升序），最后一条必须是用户的提问 */
    val messages: List<ChatMessage>,
    /** 用眼概况；用户没有概况（如刚装好）时为空 */
    val context: AdviceRequest? = null,
)

@Serializable
data class ChatReplyDto(
    val reply: String,
    /** 提问里有眼痛、突然看不清等危险信号：界面醒目提示尽快就医（由规则判断，不依赖模型） */
    val urgent: Boolean = false,
    val sources: List<KnowledgeSourceDto> = emptyList(),
    val model: String,
    val generatedAt: Long,
    val disclaimer: String = Chats.DISCLAIMER,
)

/** 本机保存的一条对话（两端同一格式）。 */
@Serializable
data class ChatEntry(
    val role: String,
    val text: String,
    val time: Long,
    val urgent: Boolean = false,
    val sources: List<KnowledgeSourceDto> = emptyList(),
)

object Chats {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val DISCLAIMER = "AI 回答只作科普和生活建议，不是诊断，也不能代替眼科检查。"
    /** 首次发送时弹窗的标题与「以后不再提示」勾选项（两端一致）；同意说明平时不在对话页展示。 */
    const val CONSENT_TITLE = "和爱科聊天前"
    const val DONT_ASK_AGAIN = "以后不再提示"
    /** 同意说明（两端一致）。 */
    const val CONSENT_TEXT = "和爱科聊天时，会把你输入的文字、最近几轮对话，以及「AI 综合建议」用到的去标识用眼概况" +
        "（年龄段、用屏与休息、眨眼与距离、自测等级、今天的感受等）发送到云端服务，由第三方大模型（DeepSeek）回答；" +
        "不发送姓名、账号、照片或视频。服务器不保存对话内容，对话记录只存在这台手机上。可随时在「我的 → 云端同步」中关闭。"
    const val URGENT_NOTICE = "你提到的情况可能需要尽快就医：眼睛疼痛、突然看不清、突然重影、眼前闪光或黑影增多、眼外伤或化学物质溅入，" +
        "请不要等待，尽快到医院眼科（必要时急诊）检查。"
    /** 输入框一条消息的字数上限 */
    const val INPUT_LIMIT = 300
    /** 每次发给模型的最近对话条数 */
    const val MAX_HISTORY = 12
    /** 历史里一条回答最多带多少字（更早的长回答截断，控制费用） */
    const val MAX_HISTORY_TEXT = 600
    /** 本机最多保留的对话条数 */
    const val MAX_STORED = 200
    /** 打开对话页时的快捷提问 */
    val SUGGESTIONS = listOf("今天眼睛有点干涩", "看屏幕久了眼睛酸胀怎么办", "晚上玩手机怎样更护眼", "我的眨眼次数正常吗")

    private val RED_FLAGS = listOf("眼痛", "眼睛痛", "眼睛疼", "眼疼", "剧痛", "突然看不清", "突然看不见", "视力突然", "突然模糊",
        "突然重影", "重影", "闪光", "黑影", "飞蚊突然", "眼前有幕", "视野缺", "红肿", "外伤", "撞到眼", "戳到眼", "溅到眼", "溅入", "化学", "烧伤")

    /** 提问里是否有需要尽快就医的危险信号（两端与服务端同一判断）。 */
    fun redFlag(text: String): Boolean = RED_FLAGS.any { it in text }

    /** 把「AI 综合建议」整理成对话里的一段话（作为开场，也随历史发给模型）。 */
    fun openingText(advice: AdviceDto): String = buildString {
        append(advice.summary)
        if (advice.priorities.isNotEmpty()) append("\n先做：").append(advice.priorities.joinToString("；"))
        if (advice.habits.isNotEmpty()) append("\n习惯：").append(advice.habits.joinToString("；"))
        if (advice.seeDoctor.isNotBlank()) append("\n就医：").append(advice.seeDoctor)
    }

    /** 由本机记录组成请求：只取最近 [MAX_HISTORY] 条，长回答截断；[opening] 为开场的综合建议（有的话放在最前）。 */
    fun request(history: List<ChatEntry>, opening: AdviceDto?, context: AdviceRequest?): ChatRequest {
        val messages = buildList {
            opening?.let { add(ChatMessage(ASSISTANT, openingText(it))) }
            history.forEach { add(ChatMessage(it.role, it.text)) }
        }.takeLast(MAX_HISTORY)
        return ChatRequest(messages.map { it.copy(text = it.text.take(MAX_HISTORY_TEXT)) }, context)
    }

    /** 服务端校验与截断：角色只认两种，最后一条必须是用户的；用户消息不超过 [INPUT_LIMIT] 字。 */
    fun ChatRequest.bounded(): ChatRequest? {
        val kept = messages.filter { it.role == USER || it.role == ASSISTANT }.takeLast(MAX_HISTORY)
            .map { m -> m.copy(text = m.text.trim().take(if (m.role == USER) INPUT_LIMIT else MAX_HISTORY_TEXT)) }
            .filter { it.text.isNotEmpty() }
        if (kept.lastOrNull()?.role != USER) return null
        return ChatRequest(kept, context)
    }
}
