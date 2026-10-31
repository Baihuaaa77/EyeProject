package com.zkyjss.eyeproject.core.data.advice

import android.content.Context
import com.zkyjss.eyeproject.core.data.account.AccountRepository
import com.zkyjss.eyeproject.core.data.account.AccountResult
import com.zkyjss.eyeproject.core.data.account.AuthorizedCaller
import com.zkyjss.eyeproject.core.data.account.SessionStore
import com.zkyjss.eyeproject.core.network.EyeNetworkDataSource
import com.zkyjss.eyeproject.core.network.NetworkResult
import com.zkyjss.eyeproject.core.network.ServerAddressStore
import com.zkyjss.eyeproject.core.sync.AdviceDto
import com.zkyjss.eyeproject.core.sync.AdviceRequest
import com.zkyjss.eyeproject.core.sync.Advices
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** AI 综合建议的状态。 */
sealed interface AiAdviceState {
    /** 需要云端账号（未配置服务器或未登录）。 */
    data class NeedsAccount(val message: String) : AiAdviceState
    /** 已登录但还没同意把去标识的用眼概况发给大模型。 */
    data class NeedsConsent(val consentText: String) : AiAdviceState
    data object Ready : AiAdviceState
    data object Loading : AiAdviceState
    data class Done(val advice: AdviceDto) : AiAdviceState
    data class Failed(val message: String) : AiAdviceState
}

/**
 * AI 综合建议：用眼负荷等级与主要原因由共用 EyeCareEngine 在本机算出，云端大模型只结合去标识概况
 * （共用 EyeCareAdvice 抽取，用户已确认的清单）给建议。需要单独的「AI 综合建议」同意；
 * 生成过的建议按概况哈希保存在本机，同样的概况不重复请求。与苹果端 CloudClient.adviceJson / advise 同一规则。
 */
interface AdviceRepository {
    fun advice(request: AdviceRequest): Flow<AiAdviceState>
    /** 生成建议；[agreeFirst] 为 true 时先记录「AI 综合建议」同意，同意未能保存则不发送任何数据。 */
    suspend fun generate(request: AdviceRequest, agreeFirst: Boolean = false)
}

@Singleton
internal class DefaultAdviceRepository(
    private val directory: File,
    private val network: EyeNetworkDataSource,
    private val sessions: SessionStore,
    private val caller: AuthorizedCaller,
    private val accounts: AccountRepository,
    private val serverAddress: StateFlow<String>,
) : AdviceRepository {
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
    private val cache = MutableStateFlow<Map<String, AdviceDto>>(emptyMap())
    private var cacheLoaded = false
    private val progress = MutableStateFlow<Map<String, AiAdviceState>>(emptyMap())

    override fun advice(request: AdviceRequest): Flow<AiAdviceState> = flow {
        loadCache()
        val key = Advices.cacheKey(request)
        emitAll(combine(cache, progress, sessions.session, serverAddress) { cached, running, session, address ->
            cached[key]?.let { return@combine AiAdviceState.Done(it) }
            running[key]?.let { return@combine it }
            when {
                address.isBlank() -> AiAdviceState.NeedsAccount("连接云端服务并登录后，爱科可以结合大模型给你综合建议")
                session == null -> AiAdviceState.NeedsAccount("登录账户后，爱科可以结合大模型给你综合建议")
                session.consents[ConsentType.AI_ADVICE] != true -> AiAdviceState.NeedsConsent(Advices.CONSENT_TEXT)
                else -> AiAdviceState.Ready
            }
        })
    }

    override suspend fun generate(request: AdviceRequest, agreeFirst: Boolean) {
        loadCache()
        val key = Advices.cacheKey(request)
        if (key in cache.value || progress.value[key] == AiAdviceState.Loading) return
        progress.update { it + (key to AiAdviceState.Loading) }
        if (agreeFirst) {
            val consent = accounts.setConsent(ConsentType.AI_ADVICE, true)
            if (consent is AccountResult.Failure) {
                progress.update { it + (key to AiAdviceState.Failed(consent.message)) }
                return
            }
        }
        val failure: AiAdviceState? = when (val result = caller.call { token -> network.advise(token, request) }) {
            is NetworkResult.Success -> { store(key, result.value); null }
            is NetworkResult.HttpError -> when {
                // 同意已在别处撤回：回到「需要同意」
                result.code == ErrorCodes.CONSENT_REQUIRED -> {
                    sessions.update { it.copy(consents = it.consents + (ConsentType.AI_ADVICE to false)) }
                    null
                }
                result.status == 401 -> null
                else -> AiAdviceState.Failed(result.message)
            }
            is NetworkResult.Unreachable -> AiAdviceState.Failed("无法连接云端服务，请检查网络后重试")
        }
        progress.update { if (failure == null) it - key else it + (key to failure) }
    }

    private suspend fun loadCache() = cacheLock.withLock {
        if (cacheLoaded) return@withLock
        cache.value = withContext(Dispatchers.IO) {
            runCatching { SyncContract.json.decodeFromString(SERIALIZER, cacheFile.readText()) }.getOrDefault(emptyMap())
        }
        cacheLoaded = true
    }

    private suspend fun store(key: String, advice: AdviceDto) = cacheLock.withLock {
        val next = (cache.value + (key to advice)).entries
            .sortedByDescending { it.value.generatedAt }.take(MAX_CACHED).associate { it.key to it.value }
        withContext(Dispatchers.IO) {
            val temp = File(directory, "$CACHE_FILE.tmp")
            temp.writeText(SyncContract.json.encodeToString(SERIALIZER, next))
            if (!temp.renameTo(cacheFile)) { cacheFile.delete(); temp.renameTo(cacheFile) }
        }
        cache.value = next
    }

    companion object {
        const val CACHE_FILE = "ai_advice.json"
        private const val MAX_CACHED = 30
        private val SERIALIZER = MapSerializer(String.serializer(), AdviceDto.serializer())
    }
}
