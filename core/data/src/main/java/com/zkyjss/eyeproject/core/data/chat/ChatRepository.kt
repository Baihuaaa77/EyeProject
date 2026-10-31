package com.zkyjss.eyeproject.core.data.chat

import android.content.Context
import com.zkyjss.eyeproject.core.data.account.AccountRepository
import com.zkyjss.eyeproject.core.data.account.AccountResult
import com.zkyjss.eyeproject.core.data.account.AuthorizedCaller
import com.zkyjss.eyeproject.core.data.account.SessionStore
import com.zkyjss.eyeproject.core.data.advice.AdviceRepository
import com.zkyjss.eyeproject.core.data.advice.AiAdviceState
import com.zkyjss.eyeproject.core.network.EyeNetworkDataSource
import com.zkyjss.eyeproject.core.network.NetworkResult
import com.zkyjss.eyeproject.core.network.ServerAddressStore
import com.zkyjss.eyeproject.core.sync.AdviceRequest
import com.zkyjss.eyeproject.core.sync.ChatEntry
import com.zkyjss.eyeproject.core.sync.Chats
import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.sync.ErrorCodes
import com.zkyjss.eyeproject.core.sync.SyncContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 对话页能做什么：需要账号 / 需要同意 / 可以聊。 */
enum class ChatAccess { NEEDS_ACCOUNT, NEEDS_CONSENT, READY }

data class ChatState(
    val access: ChatAccess,
    /** NEEDS_ACCOUNT 时的说明 */
    val accountMessage: String? = null,
    /** 本机保存的对话（时间升序） */
    val messages: List<ChatEntry> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
    /** 发送前是否弹同意说明：未同意时总要弹；已同意且勾过「以后不再提示」就不弹 */
    val askConsent: Boolean = true,
)

/**
 * AI 综合建议的对话（和爱科聊聊）：用户自由描述用眼感受或提问，云端大模型结合同一份去标识概况与知识库回答；
 * 已生成的综合建议作为开场一起发给模型。需要单独的「AI 对话」同意。对话记录只存在本机（`ai_chat.json`），可清空。
 * 与苹果端 CloudClient.chatJson / sendChat / clearChat 同一规则。
 */
interface ChatRepository {
    val state: Flow<ChatState>
    /**
     * 发一条消息；[context] 为当下概况（没有时为 null）。[agreeFirst] 为在弹窗里点了「同意」（还没同意过时先保存同意，
     * 未能保存则不发送任何内容）；[dontAskAgain] 为弹窗里勾了「以后不再提示」。
     */
    suspend fun send(text: String, context: AdviceRequest?, agreeFirst: Boolean = false, dontAskAgain: Boolean = false)
    suspend fun clear()
}

@Singleton
internal class DefaultChatRepository(
    private val directory: File,
    private val network: EyeNetworkDataSource,
    private val sessions: SessionStore,
    private val caller: AuthorizedCaller,
    private val accounts: AccountRepository,
    private val advice: AdviceRepository,
    private val serverAddress: StateFlow<String>,
    private val clock: () -> Long = System::currentTimeMillis,
) : ChatRepository {
    @Inject constructor(
        @ApplicationContext context: Context,
        network: EyeNetworkDataSource,
        sessions: SessionStore,
        caller: AuthorizedCaller,
        accounts: AccountRepository,
        advice: AdviceRepository,
        addresses: ServerAddressStore,
    ) : this(context.filesDir, network, sessions, caller, accounts, advice, addresses.address)

    private val file = File(directory, FILE)
    private val noPromptFile = File(directory, NO_PROMPT_FILE)
    private val noPrompt = MutableStateFlow(false)
    private val lock = Mutex()
    private val history = MutableStateFlow<List<ChatEntry>>(emptyList())
    private var loaded = false
    private val sending = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    override val state: Flow<ChatState> = flow {
        load()
        val status = combine(sending, error, noPrompt) { busy, failure, quiet -> Triple(busy, failure, !quiet) }
        emitAll(combine(history, status, sessions.session, serverAddress) { messages, (busy, failure, ask), session, address ->
            val (access, message) = when {
                address.isBlank() -> ChatAccess.NEEDS_ACCOUNT to "连接云端服务并登录后，就可以和爱科聊天了"
                session == null -> ChatAccess.NEEDS_ACCOUNT to "登录账户后，就可以和爱科聊聊今天的用眼感受"
                session.consents[ConsentType.AI_CHAT] != true -> ChatAccess.NEEDS_CONSENT to null
                else -> ChatAccess.READY to null
            }
            ChatState(access, message, messages, busy, failure, ask)
        })
    }

    override suspend fun send(text: String, context: AdviceRequest?, agreeFirst: Boolean, dontAskAgain: Boolean) {
        val question = text.trim().take(Chats.INPUT_LIMIT)
        if (question.isEmpty() || sending.value) return
        load()
        if (dontAskAgain && !noPrompt.value) {
            withContext(Dispatchers.IO) { noPromptFile.writeText("1") }
            noPrompt.value = true
        }
        sending.value = true; error.value = null
        append(ChatEntry(Chats.USER, question, clock(), urgent = Chats.redFlag(question)))
        error.value = run {
            if (agreeFirst) {
                val consent = accounts.setConsent(ConsentType.AI_CHAT, true)
                if (consent is AccountResult.Failure) return@run consent.message
            }
            // 已生成的综合建议作为开场（取综合建议数据流的当前值；没生成过就不带）
            val opening = context?.let { (advice.advice(it).first() as? AiAdviceState.Done)?.advice }
            when (val result = caller.call { token -> network.chat(token, Chats.request(history.value, opening, context)) }) {
                is NetworkResult.Success -> {
                    val r = result.value
                    append(ChatEntry(Chats.ASSISTANT, r.reply, clock(), r.urgent, r.sources))
                    null
                }
                is NetworkResult.HttpError -> when {
                    // 同意已在别处撤回：回到「需要同意」
                    result.code == ErrorCodes.CONSENT_REQUIRED -> {
                        sessions.update { it.copy(consents = it.consents + (ConsentType.AI_CHAT to false)) }
                        "需要先同意 AI 对话"
                    }
                    result.status == 401 -> "登录已失效，请重新登录"
                    else -> result.message
                }
                is NetworkResult.Unreachable -> "无法连接云端服务，请检查网络后重试"
            }
        }
        sending.value = false
    }

    override suspend fun clear() = lock.withLock {
        withContext(Dispatchers.IO) { file.delete() }
        history.value = emptyList()
        error.value = null
    }

    private suspend fun load() = lock.withLock {
        if (loaded) return@withLock
        history.value = withContext(Dispatchers.IO) {
            runCatching { SyncContract.json.decodeFromString(SERIALIZER, file.readText()) }.getOrDefault(emptyList())
        }
        noPrompt.value = withContext(Dispatchers.IO) { noPromptFile.exists() }
        loaded = true
    }

    private suspend fun append(entry: ChatEntry) = lock.withLock {
        val next = (history.value + entry).takeLast(Chats.MAX_STORED)
        withContext(Dispatchers.IO) {
            val temp = File(directory, "$FILE.tmp")
            temp.writeText(SyncContract.json.encodeToString(SERIALIZER, next))
            if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
        }
        history.value = next
    }

    companion object {
        const val FILE = "ai_chat.json"
        /** 勾过「以后不再提示」的标记（清空对话不影响） */
        const val NO_PROMPT_FILE = "ai_chat_no_prompt"
        private val SERIALIZER = ListSerializer(ChatEntry.serializer())
    }
}
