package com.zkyjss.eyeproject.core.data

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.zkyjss.eyeproject.core.data.account.AccountRepository
import com.zkyjss.eyeproject.core.data.account.AccountResult
import com.zkyjss.eyeproject.core.data.account.AccountState
import com.zkyjss.eyeproject.core.data.account.AuthorizedCaller
import com.zkyjss.eyeproject.core.data.account.Session
import com.zkyjss.eyeproject.core.data.account.SessionStore
import com.zkyjss.eyeproject.core.data.advice.DefaultAdviceRepository
import com.zkyjss.eyeproject.core.data.chat.ChatAccess
import com.zkyjss.eyeproject.core.data.chat.DefaultChatRepository
import com.zkyjss.eyeproject.core.network.NetworkResult
import com.zkyjss.eyeproject.core.sync.AdviceRequest
import com.zkyjss.eyeproject.core.sync.Chats
import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.sync.ErrorCodes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** AI 对话：需要账号与单独同意、开场综合建议与概况随请求发送、记录只存本机、危险信号标记。 */
@RunWith(RobolectricTestRunner::class)
class ChatRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var directory: File
    private lateinit var sessions: SessionStore
    private val network = FakeNetwork()
    private val address = MutableStateFlow("https://example.com/eyeapi")
    private val context0 = AdviceRequest(fatigueLevel = "MEDIUM", factors = listOf("连续用眼太久"))

    private inner class FakeAccounts : AccountRepository {
        var consentFails = false
        override val accountState: Flow<AccountState> = emptyFlow()
        override suspend fun setConsent(type: ConsentType, granted: Boolean): AccountResult {
            if (consentFails) return AccountResult.Failure("无法连接云端服务，请检查网络后重试")
            sessions.update { it.copy(consents = it.consents + (type to granted)) }
            return AccountResult.Success
        }
        override suspend fun setServerAddress(url: String) = error("未使用")
        override suspend fun register(email: String, password: String) = error("未使用")
        override suspend fun signIn(email: String, password: String) = error("未使用")
        override suspend fun signOut() = error("未使用")
        override suspend fun exportCloudData(target: Uri) = error("未使用")
        override suspend fun deleteCloudAccount() = error("未使用")
    }

    private val accounts by lazy { FakeAccounts() }

    @Before
    fun setUp() {
        directory = File(context.cacheDir, "chat-${System.nanoTime()}").apply { mkdirs() }
        sessions = SessionStore(context, PlainBox).apply { save(null) }
    }

    private fun caller() = AuthorizedCaller(network, sessions)
    private fun advice() = DefaultAdviceRepository(directory, network, sessions, caller(), accounts, address)
    private fun repository() = DefaultChatRepository(directory, network, sessions, caller(), accounts, advice(), address) { 1_000L }

    private fun signIn(vararg consents: ConsentType) = sessions.save(Session("acc", "a@example.com", "subject", "access-0",
        Long.MAX_VALUE, "refresh-0", Long.MAX_VALUE, consents.associateWith { true }))

    @Test
    fun needsAccountThenItsOwnConsentBeforeSendingAnything() = runTest {
        val repo = repository()
        assertEquals(ChatAccess.NEEDS_ACCOUNT, repo.state.first().access)
        signIn(ConsentType.AI_ADVICE)
        assertEquals(ChatAccess.NEEDS_CONSENT, repo.state.first().access)
        accounts.consentFails = true
        repo.send("眼睛干", context0, agreeFirst = true)
        assertTrue(network.chats.isEmpty())
        assertEquals("无法连接云端服务，请检查网络后重试", repo.state.first().error)
    }

    @Test
    fun sendsOpeningAdviceAndContextAndKeepsHistoryOnDevice() = runTest {
        signIn(ConsentType.AI_ADVICE)
        advice().generate(context0)
        val repo = repository()
        repo.send("下午盯电脑，眼睛又干又涩", context0, agreeFirst = true)
        val request = network.chats.single()
        assertEquals(context0, request.context)
        assertEquals(listOf(Chats.ASSISTANT, Chats.USER), request.messages.map { it.role })
        assertTrue(request.messages.first().text.startsWith("今天有点累"))
        val state = repo.state.first()
        assertEquals(ChatAccess.READY, state.access)
        assertEquals(listOf(Chats.USER, Chats.ASSISTANT), state.messages.map { it.role })
        // 新实例从本机文件读出；清空后文件也删掉
        assertEquals(2, repository().state.first().messages.size)
        repo.clear()
        assertTrue(repository().state.first().messages.isEmpty())
    }

    @Test
    fun dontAskAgainIsRememberedOnDeviceAndSurvivesClearing() = runTest {
        signIn(ConsentType.AI_CHAT)
        val repo = repository()
        assertTrue(repo.state.first().askConsent)
        repo.send("眼睛干", null)
        assertTrue(repo.state.first().askConsent)
        repo.send("还是干", null, dontAskAgain = true)
        assertFalse(repo.state.first().askConsent)
        repo.clear()
        assertFalse(repository().state.first().askConsent)
    }

    @Test
    fun marksRedFlagsAndFallsBackWhenConsentWasWithdrawn() = runTest {
        signIn(ConsentType.AI_CHAT)
        val repo = repository()
        repo.send("右眼突然看不清了", null)
        assertTrue(repo.state.first().messages.first().urgent)
        network.chatResult = NetworkResult.HttpError(403, ErrorCodes.CONSENT_REQUIRED, "需要同意")
        repo.send("还是看不清", null)
        val state = repo.state.first()
        assertEquals(ChatAccess.NEEDS_CONSENT, state.access)
        assertEquals("需要先同意 AI 对话", state.error)
    }

    @Test
    fun questionSurvivesRestartAndAnswerCarriesItsMetadata() = runTest {
        signIn(ConsentType.AI_CHAT)
        val q = com.zkyjss.eyeproject.core.sync.ChatQuestion("什么时候开始的？", listOf("今天", "几天前"))
        network.chatResult = NetworkResult.Success(status = 200, value = com.zkyjss.eyeproject.core.sync.ChatReplyDto(
            "先了解一下", model = "fake", generatedAt = 1, question = q))
        repository().send(Chats.START_INQUIRY, null)
        val restarted = repository()
        assertEquals(q, Chats.pendingQuestion(restarted.state.first().messages))
        network.chatResult = NetworkResult.Success(status = 200, value = com.zkyjss.eyeproject.core.sync.ChatReplyDto(
            "还有补充吗", model = "fake", generatedAt = 2,
            question = com.zkyjss.eyeproject.core.sync.ChatQuestion("还有补充吗？", listOf("没有"), finalConfirmation = true)))
        restarted.send("今天", null)
        assertEquals(q, network.chats.last().messages.dropLast(1).last().question)
        assertTrue(Chats.pendingQuestion(restarted.state.first().messages)!!.finalConfirmation)
        restarted.clear()
        assertEquals(null, Chats.pendingQuestion(repository().state.first().messages))
    }
}
