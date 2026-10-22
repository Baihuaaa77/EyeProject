package com.zkyjss.eyeproject.core.data

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.zkyjss.eyeproject.core.data.account.AccountRepository
import com.zkyjss.eyeproject.core.data.account.AccountResult
import com.zkyjss.eyeproject.core.data.account.AccountState
import com.zkyjss.eyeproject.core.data.account.AuthorizedCaller
import com.zkyjss.eyeproject.core.data.account.Session
import com.zkyjss.eyeproject.core.data.account.SessionStore
import com.zkyjss.eyeproject.core.data.explanation.AiExplanationState
import com.zkyjss.eyeproject.core.data.explanation.DefaultExplanationRepository
import com.zkyjss.eyeproject.core.network.NetworkResult
import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.sync.ErrorCodes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ExplanationRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var directory: File
    private lateinit var sessions: SessionStore
    private val network = FakeNetwork()
    private val address = MutableStateFlow("http://10.0.2.2:8080")
    private val record = "dryeye_1760000000000.json"

    /** 只用到同意：直接改本机会话，与真实仓库保存同意后的效果一致。 */
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
        directory = File(context.cacheDir, "ai-${System.nanoTime()}").apply { mkdirs() }
        sessions = SessionStore(context, PlainBox).apply { save(null) }
        File(directory, record).writeText("""{"kind":"DRY_EYE","valid":true,"riskLevel":"MEDIUM",
            "report":{"level":"MEDIUM","badge":"干眼中风险","title":"有一些干眼迹象","metrics":[],"evidence":[]},
            "profile":{"年龄":"45"},"subject":{"id":"me","name":"小明"}}""")
    }

    private fun repository() = DefaultExplanationRepository(directory, network, sessions, AuthorizedCaller(network, sessions), accounts, address)

    private fun signIn(aiConsent: Boolean) = sessions.save(Session("acc", "a@example.com", "subject", "access-0",
        Long.MAX_VALUE, "refresh-0", Long.MAX_VALUE, mapOf(ConsentType.AI_EXPLANATION to aiConsent)))

    @Test
    fun needsAccountThenConsentBeforeSendingAnything() = runTest {
        val repo = repository()
        assertTrue(repo.explanation(record).first() is AiExplanationState.NeedsAccount)
        signIn(aiConsent = false)
        assertTrue(repo.explanation(record).first() is AiExplanationState.NeedsConsent)
        accounts.consentFails = true
        repo.generate(record, agreeFirst = true)
        assertTrue(network.explanations.isEmpty())
        assertTrue(repo.explanation(record).first() is AiExplanationState.Failed)
    }

    @Test
    fun agreeingGeneratesAndCachesOnDevice() = runTest {
        signIn(aiConsent = false)
        repository().generate(record, agreeFirst = true)
        assertEquals(1, network.explanations.size)
        assertEquals("40–49 岁", network.explanations.single().ageGroup)
        // 新实例从本机缓存读出；退出登录后仍可查看，同一结果不再请求
        sessions.save(null)
        val again = repository()
        val state = again.explanation(record).first()
        assertTrue(state is AiExplanationState.Done)
        again.generate(record)
        assertEquals(1, network.explanations.size)
    }

    @Test
    fun withdrawnConsentOnServerFallsBackToAsking() = runTest {
        signIn(aiConsent = true)
        network.explainError = NetworkResult.HttpError(403, ErrorCodes.CONSENT_REQUIRED, "需要同意")
        val repo = repository()
        repo.generate(record)
        assertTrue(repo.explanation(record).first() is AiExplanationState.NeedsConsent)
        network.explainError = NetworkResult.HttpError(503, ErrorCodes.AI_UNAVAILABLE, "服务器尚未开启 AI 解读")
        signIn(aiConsent = true)
        repo.generate(record)
        assertEquals(AiExplanationState.Failed("服务器尚未开启 AI 解读"), repo.explanation(record).first())
    }

    @Test
    fun unknownFilesAreUnsupported() = runTest {
        assertEquals(AiExplanationState.Unsupported, repository().explanation("../secret.json").first())
        assertEquals(AiExplanationState.Unsupported, repository().explanation("strabismus_1.json").first())
    }
}
