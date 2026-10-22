package com.zkyjss.eyeproject.core.data.explanation

import android.content.Context
import com.zkyjss.eyeproject.core.data.account.AccountRepository
import com.zkyjss.eyeproject.core.data.account.AccountResult
import com.zkyjss.eyeproject.core.data.account.AuthorizedCaller
import com.zkyjss.eyeproject.core.data.account.SessionStore
import com.zkyjss.eyeproject.core.data.record.OfflineFirstScreeningRecordRepository
import com.zkyjss.eyeproject.core.network.EyeNetworkDataSource
import com.zkyjss.eyeproject.core.network.NetworkResult
import com.zkyjss.eyeproject.core.network.ServerAddressStore
import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.sync.ErrorCodes
import com.zkyjss.eyeproject.core.sync.ExplanationDto
import com.zkyjss.eyeproject.core.sync.ExplanationRequest
import com.zkyjss.eyeproject.core.sync.Explanations
import com.zkyjss.eyeproject.core.sync.SyncContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 一条记录的 AI 解读状态。 */
sealed interface AiExplanationState {
    /** 记录读不到或格式无法识别。 */
    data object Unsupported : AiExplanationState
    /** 需要云端账号（未配置服务器或未登录）；[message] 说明如何开启。 */
    data class NeedsAccount(val message: String) : AiExplanationState
    /** 已登录但还没同意把去标识结果发给大模型；[consentText] 为同意说明。 */
    data class NeedsConsent(val consentText: String) : AiExplanationState
    data object Ready : AiExplanationState
    data object Loading : AiExplanationState
    data class Done(val explanation: ExplanationDto) : AiExplanationState
    data class Failed(val message: String) : AiExplanationState
}

/**
 * AI 解读（设计文档 §5.4）：风险等级由规则给出，云端大模型只做通俗解释。
 * 输入由 [Explanations.fromRecord] 从本机记录抽取，不含身份信息；生成过的解读按输入哈希保存在本机，
 * 离线或退出登录后仍可查看，同一结果不重复请求。
 */
interface ExplanationRepository {
    /** [fileName] 为本机记录文件名（如 `dryeye_<ms>.json`）。 */
    fun explanation(fileName: String): Flow<AiExplanationState>
    /**
     * 生成解读，结果经 [explanation] 发出。[agreeFirst] 为 true 时先记录「AI 解读」同意
     * （与「我的 → 云端同步」中的开关是同一项同意）；同意未能保存则不发送任何数据。
     */
    suspend fun generate(fileName: String, agreeFirst: Boolean = false)
}

@Singleton
internal class DefaultExplanationRepository(
    private val directory: File,
    private val network: EyeNetworkDataSource,
    private val sessions: SessionStore,
    private val caller: AuthorizedCaller,
    private val accounts: AccountRepository,
    private val serverAddress: StateFlow<String>,
) : ExplanationRepository {
    @Inject constructor(
        @ApplicationContext context: Context,
        network: EyeNetworkDataSource,
        sessions: SessionStore,
        caller: AuthorizedCaller,
        accounts: AccountRepository,
        addresses: ServerAddressStore,
    ) : this(context.filesDir, network, sessions, caller, accounts, addresses.address)

    private val cacheFile = File(directory, CACHE_FILE)
    private val cacheLock = Mutex()
    private val cache = MutableStateFlow<Map<String, ExplanationDto>>(emptyMap())
    private var cacheLoaded = false
    /** 进行中或失败的请求，按输入哈希。 */
    private val progress = MutableStateFlow<Map<String, AiExplanationState>>(emptyMap())
    /** 记录不可变：文件名 → 解读输入只算一次。 */
    private val requests = ConcurrentHashMap<String, ExplanationRequest>()

    override fun explanation(fileName: String): Flow<AiExplanationState> = flow {
        val request = request(fileName)
        if (request == null) {
            emit(AiExplanationState.Unsupported)
            return@flow
        }
        loadCache()
        val key = Explanations.cacheKey(request)
        emitAll(combine(cache, progress, sessions.session, serverAddress) { cached, running, session, address ->
            cached[key]?.let { return@combine AiExplanationState.Done(it) }
            running[key]?.let { return@combine it }
            when {
                address.isBlank() -> AiExplanationState.NeedsAccount("连接云端服务并登录后，可用 AI 解读这次结果")
                session == null -> AiExplanationState.NeedsAccount("在「我的 → 云端同步」登录后，可用 AI 解读这次结果")
                session.consents[ConsentType.AI_EXPLANATION] != true -> AiExplanationState.NeedsConsent(Explanations.CONSENT_TEXT)
                else -> AiExplanationState.Ready
            }
        })
    }

    override suspend fun generate(fileName: String, agreeFirst: Boolean) {
        val request = request(fileName) ?: return
        loadCache()
        val key = Explanations.cacheKey(request)
        if (key in cache.value || progress.value[key] == AiExplanationState.Loading) return
        progress.update { it + (key to AiExplanationState.Loading) }
        if (agreeFirst) {
            val consent = accounts.setConsent(ConsentType.AI_EXPLANATION, true)
            if (consent is AccountResult.Failure) {
                progress.update { it + (key to AiExplanationState.Failed(consent.message)) }
                return
            }
        }
        val result = caller.call { token -> network.explain(token, request) }
        val failure: AiExplanationState? = when (result) {
            is NetworkResult.Success -> { store(key, result.value); null }
            is NetworkResult.HttpError -> when {
                // 同意已在别处撤回：回到「需要同意」
                result.code == ErrorCodes.CONSENT_REQUIRED -> {
                    sessions.update { it.copy(consents = it.consents + (ConsentType.AI_EXPLANATION to false)) }
                    null
                }
                // 登录失效时会话已清除，状态自然回到「需要账号」
                result.status == 401 -> null
                else -> AiExplanationState.Failed(result.message)
            }
            is NetworkResult.Unreachable -> AiExplanationState.Failed("无法连接云端服务，请检查网络后重试")
        }
        progress.update { if (failure == null) it - key else it + (key to failure) }
    }

    private suspend fun request(fileName: String): ExplanationRequest? {
        if (!OfflineFirstScreeningRecordRepository.RECORD_NAME.matches(fileName)) return null
        requests[fileName]?.let { return it }
        val text = withContext(Dispatchers.IO) { File(directory, fileName).takeIf(File::isFile)?.readText() } ?: return null
        return Explanations.fromRecord(text)?.also { requests[fileName] = it }
    }

    private suspend fun loadCache() = cacheLock.withLock {
        if (cacheLoaded) return@withLock
        cache.value = withContext(Dispatchers.IO) {
            runCatching { SyncContract.json.decodeFromString(SERIALIZER, cacheFile.readText()) }.getOrDefault(emptyMap())
        }
        cacheLoaded = true
    }

    private suspend fun store(key: String, explanation: ExplanationDto) = cacheLock.withLock {
        val next = (cache.value + (key to explanation)).entries
            .sortedByDescending { it.value.generatedAt }.take(MAX_CACHED).associate { it.key to it.value }
        withContext(Dispatchers.IO) {
            val temp = File(directory, "$CACHE_FILE.tmp")
            temp.writeText(SyncContract.json.encodeToString(SERIALIZER, next))
            if (!temp.renameTo(cacheFile)) { cacheFile.delete(); temp.renameTo(cacheFile) }
        }
        cache.value = next
    }

    companion object {
        const val CACHE_FILE = "ai_explanations.json"
        private const val MAX_CACHED = 200
        private val SERIALIZER = MapSerializer(String.serializer(), ExplanationDto.serializer())
    }
}
