package com.zkyjss.eyeproject.core.data

import androidx.test.core.app.ApplicationProvider
import com.zkyjss.eyeproject.core.data.account.AuthorizedCaller
import com.zkyjss.eyeproject.core.data.account.SecretBox
import com.zkyjss.eyeproject.core.data.account.Session
import com.zkyjss.eyeproject.core.data.account.SessionStore
import com.zkyjss.eyeproject.core.data.record.OfflineFirstScreeningRecordRepository
import com.zkyjss.eyeproject.core.data.sync.SyncScheduler
import com.zkyjss.eyeproject.core.network.EyeNetworkDataSource
import com.zkyjss.eyeproject.core.network.NetworkResult
import com.zkyjss.eyeproject.core.sync.AccountDto
import com.zkyjss.eyeproject.core.sync.ConsentDto
import com.zkyjss.eyeproject.core.sync.ConsentInput
import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.sync.ErrorCodes
import com.zkyjss.eyeproject.core.sync.LoginRequest
import com.zkyjss.eyeproject.core.sync.RecordQuality
import com.zkyjss.eyeproject.core.sync.RecordSummaryDto
import com.zkyjss.eyeproject.core.sync.RecordUpload
import com.zkyjss.eyeproject.core.sync.RegisterRequest
import com.zkyjss.eyeproject.core.sync.Sha256
import com.zkyjss.eyeproject.core.sync.SyncStopReason
import com.zkyjss.eyeproject.core.sync.TokenResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ScreeningRecordRepositoryTest {
    /** 单测不用 Keystore：原样存取（仅用于验证仓库逻辑）。 */
    private object PlainBox : SecretBox {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray) = sealed
    }

    private class FakeScheduler : SyncScheduler {
        var requests = 0
        override fun requestSync() { requests++ }
    }

    /** 按记录 id 编排的假云端：默认上传成功，可指定某个令牌已失效。 */
    private class FakeNetwork : EyeNetworkDataSource {
        override val isConfigured = true
        val uploads = mutableListOf<Pair<String, RecordUpload>>()
        var rejectToken: String? = null
        var uploadError: NetworkResult.HttpError? = null
        var refreshes = 0
        override suspend fun register(request: RegisterRequest) = error("未使用")
        override suspend fun login(request: LoginRequest) = error("未使用")
        override suspend fun refresh(refreshToken: String): NetworkResult<TokenResponse> {
            refreshes++
            return NetworkResult.Success(TokenResponse("acc", "access-$refreshes", Long.MAX_VALUE, "refresh-$refreshes", Long.MAX_VALUE), 200)
        }
        override suspend fun logout(accessToken: String, refreshToken: String) = NetworkResult.Success(Unit, 204)
        override suspend fun account(accessToken: String): NetworkResult<AccountDto> = error("未使用")
        override suspend fun consents(accessToken: String): NetworkResult<List<ConsentDto>> = error("未使用")
        override suspend fun recordConsent(accessToken: String, input: ConsentInput): NetworkResult<List<ConsentDto>> = error("未使用")
        override suspend fun uploadRecord(accessToken: String, recordId: String, upload: RecordUpload): NetworkResult<RecordSummaryDto> {
            if (accessToken == rejectToken) return NetworkResult.HttpError(401, ErrorCodes.INVALID_TOKEN, "过期")
            uploadError?.let { return it }
            uploads += recordId to upload
            return NetworkResult.Success(RecordSummaryDto(recordId, "s", "DRY_EYE", riskLevel = "LOW",
                quality = RecordQuality.SUFFICIENT, testedAt = upload.testedAt, uploadedAt = 0), 201)
        }
        override suspend fun exportAll(accessToken: String): NetworkResult<String> = error("未使用")
        override suspend fun deleteAccount(accessToken: String): NetworkResult<Unit> = error("未使用")
    }

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var directory: File
    private lateinit var sessions: SessionStore
    private val network = FakeNetwork()
    private val scheduler = FakeScheduler()

    @Before
    fun setUp() {
        directory = File(context.cacheDir, "records-${System.nanoTime()}").apply { mkdirs() }
        sessions = SessionStore(context, PlainBox).apply { save(null) }
    }

    private fun repository() = OfflineFirstScreeningRecordRepository(directory, network, sessions,
        AuthorizedCaller(network, sessions), scheduler) { 1_760_500_000_000L }

    private fun signIn(consent: Boolean, accountId: String = "acc") = sessions.save(Session(accountId, "a@example.com", "subject", "access-0",
        Long.MAX_VALUE, "refresh-0", Long.MAX_VALUE, mapOf(ConsentType.HEALTH_DATA_SYNC to consent)))

    @Test
    fun savesLocallyAndStaysOfflineWithoutConsent() = runTest {
        val repo = repository()
        repo.saveRecord("dryeye_1760000000000.json", """{"kind":"DRY_EYE"}""")
        assertTrue(File(directory, "dryeye_1760000000000.json").exists())
        assertEquals(1, repo.syncStatus.first().pending)
        assertEquals(0, scheduler.requests)
        assertNull(repo.syncNow())

        signIn(consent = false)
        assertNull(repo.syncNow())
        assertTrue(network.uploads.isEmpty())
    }

    @Test
    fun localRecordsListNewestFirstAndRefreshAfterSaving() = runTest {
        File(directory, "presbyopia_1750000000000.json").writeText(
            """{"kind":"PRESBYOPIA","assessment":{"riskLevel":"LOW"},"summary":["近点 20 cm"]}""")
        File(directory, "notes.json").writeText("{}")
        val repo = repository()
        assertEquals(listOf("老视自测"), repo.localRecords.first().map { it.title })
        repo.saveRecord("dryeye_1760000000000.json", """{"kind":"DRY_EYE","valid":false,"riskLevel":"UNKNOWN","validityReasons":["距离不稳"]}""")
        val records = repo.localRecords.first()
        assertEquals(listOf("dryeye_1760000000000.json", "presbyopia_1750000000000.json"), records.map { it.fileName })
        assertEquals("需要重测", records.first().badge)
        assertEquals(listOf("距离不稳"), records.first().lines)
        assertEquals("老视低风险", records.last().badge)
    }

    @Test
    fun uploadsSavedAndPreexistingRecordsOnceConsented() = runTest {
        File(directory, "presbyopia_1750000000000.json").writeText("""{"kind":"PRESBYOPIA"}""")
        File(directory, "notes.json").writeText("{}")
        signIn(consent = true)
        val repo = repository()
        val payload = """{"kind":"DRY_EYE"}"""
        repo.saveRecord("dryeye_1760000000000.json", payload)
        assertEquals(1, scheduler.requests)

        val report = repo.syncNow()!!
        assertEquals(2, report.uploaded)
        assertEquals(listOf(1_750_000_000_000L, 1_760_000_000_000L), network.uploads.map { it.second.testedAt })
        assertEquals(Sha256.hex(payload), network.uploads.last().second.payloadSha256)
        val status = repo.syncStatus.first()
        assertEquals(2, status.synced)
        assertEquals(0, status.pending)

        // 发件箱持久化：新实例读到相同状态，不会重复上传
        assertEquals(0, repository().syncNow()!!.uploaded)
        assertEquals(2, network.uploads.size)
    }

    @Test
    fun expiredTokenIsRefreshedOnceAndRetried() = runTest {
        signIn(consent = true)
        network.rejectToken = "access-0"
        val repo = repository()
        repo.saveRecord("strabismus_1760000000000.json", """{"kind":"STRABISMUS"}""")
        assertEquals(1, repo.syncNow()!!.uploaded)
        assertEquals(1, network.refreshes)
        assertEquals("access-1", sessions.session.value!!.accessToken)
    }

    @Test
    fun withdrawnConsentStopsAndUpdatesLocalCache() = runTest {
        signIn(consent = true)
        network.uploadError = NetworkResult.HttpError(403, ErrorCodes.CONSENT_REQUIRED, "未同意")
        val repo = repository()
        repo.saveRecord("dryeye_1760000000000.json", "{}")
        assertEquals(SyncStopReason.CONSENT_REQUIRED, repo.syncNow()!!.stopReason)
        assertFalse(sessions.session.value!!.consents.getValue(ConsentType.HEALTH_DATA_SYNC))
        assertEquals(1, repo.syncStatus.first().pending)
    }

    @Test
    fun switchingAccountsReuploadsUnderFreshIds() = runTest {
        signIn(consent = true, accountId = "first")
        val repo = repository()
        repo.saveRecord("dryeye_1760000000000.json", "{}")
        assertEquals(1, repo.syncNow()!!.uploaded)

        // 注销后重新注册（或换账号登录）：已上传状态不适用于新账号
        signIn(consent = true, accountId = "second")
        assertEquals(1, repo.syncNow()!!.uploaded)
        assertEquals(2, network.uploads.size)
        assertTrue(network.uploads[0].first != network.uploads[1].first)
        assertEquals(1, repo.syncStatus.first().synced)
    }

    @Test
    fun requestSyncOnlyWhenEligible() = runTest {
        val repo = repository()
        repo.requestSync()
        assertEquals(0, scheduler.requests)
        signIn(consent = true)
        repo.requestSync()
        assertEquals(1, scheduler.requests)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnexpectedFileNames() = runTest {
        repository().saveRecord("../escape.json", "{}")
    }
}
