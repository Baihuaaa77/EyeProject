package com.zkyjss.eyeproject.core.data.account

import android.content.Context
import android.net.Uri
import com.zkyjss.eyeproject.core.data.sync.SyncScheduler
import com.zkyjss.eyeproject.core.network.EyeNetworkDataSource
import com.zkyjss.eyeproject.core.network.NetworkResult
import com.zkyjss.eyeproject.core.network.ServerAddressStore
import com.zkyjss.eyeproject.core.sync.ConsentDto
import com.zkyjss.eyeproject.core.sync.ConsentInput
import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.sync.LoginRequest
import com.zkyjss.eyeproject.core.sync.RegisterRequest
import com.zkyjss.eyeproject.core.sync.SyncContract
import com.zkyjss.eyeproject.core.sync.TokenResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 云端账号的对外状态。cloudConfigured 为 false 时一切数据只在本机。 */
data class AccountState(
    val cloudConfigured: Boolean,
    val email: String? = null,
    /** 同意将测试记录加密存储到云端（上传的前提）。 */
    val healthDataSync: Boolean = false,
    /** 监护人同意：允许上传在本人名下为未满 14 岁孩子所做的测试。 */
    val guardian: Boolean = false,
    /** 可选：同意去标识数据用于眼健康研究。 */
    val researchUse: Boolean = false,
    /** 当前服务器地址；空表示未配置。 */
    val serverUrl: String = "",
)

sealed interface AccountResult {
    data object Success : AccountResult
    data class Failure(val message: String) : AccountResult
}

/** 账号、同意与云端数据权利（导出、注销）。读为数据流，写为挂起函数。 */
interface AccountRepository {
    val accountState: Flow<AccountState>
    /** 设置服务器地址（与苹果端一致可在「我的」修改）；换地址时退出登录。空字符串表示关闭云端。 */
    suspend fun setServerAddress(url: String): AccountResult
    suspend fun register(email: String, password: String): AccountResult
    suspend fun signIn(email: String, password: String): AccountResult
    /** 退出登录只清本机会话；本机测试记录保留。 */
    suspend fun signOut()
    suspend fun setConsent(type: ConsentType, granted: Boolean): AccountResult
    /** 把云端全部数据（含解密原文）导出到用户选择的文件。 */
    suspend fun exportCloudData(target: Uri): AccountResult
    /** 注销云端账号并删除全部云端数据；本机记录保留。 */
    suspend fun deleteCloudAccount(): AccountResult
}

@Singleton
internal class DefaultAccountRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val network: EyeNetworkDataSource,
    private val sessions: SessionStore,
    private val caller: AuthorizedCaller,
    private val scheduler: SyncScheduler,
    private val addresses: ServerAddressStore,
) : AccountRepository {

    override val accountState: Flow<AccountState> = combine(sessions.session, addresses.address) { session, address ->
        AccountState(
            cloudConfigured = address.isNotBlank(),
            serverUrl = address,
            email = session?.email,
            healthDataSync = session?.consents?.get(ConsentType.HEALTH_DATA_SYNC) == true,
            guardian = session?.consents?.get(ConsentType.GUARDIAN) == true,
            researchUse = session?.consents?.get(ConsentType.RESEARCH_USE) == true,
        )
    }

    override suspend fun setServerAddress(url: String): AccountResult {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed == addresses.address.value) return AccountResult.Success
        // 会话属于原服务器：换地址先退出登录
        val previous = sessions.session.value
        if (!addresses.set(trimmed)) return AccountResult.Failure("地址格式应为 http://IP:端口 或 https://域名")
        if (previous != null) sessions.save(null)
        return AccountResult.Success
    }

    override suspend fun register(email: String, password: String): AccountResult =
        establish(network.register(RegisterRequest(email.trim(), password, null, SyncContract.PRIVACY_POLICY_VERSION)))

    override suspend fun signIn(email: String, password: String): AccountResult =
        establish(network.login(LoginRequest(email.trim(), password)))

    override suspend fun signOut() {
        val session = sessions.session.value ?: return
        // 尽力吊销服务端令牌；离线时直接清本机会话。
        caller.call { token -> network.logout(token, session.refreshToken) }
        sessions.save(null)
    }

    override suspend fun setConsent(type: ConsentType, granted: Boolean): AccountResult {
        val session = sessions.session.value ?: return AccountResult.Failure("请先登录")
        val subject = if (type == ConsentType.GUARDIAN) session.defaultSubjectId else null
        val result = caller.call { token ->
            network.recordConsent(token, ConsentInput(type, SyncContract.PRIVACY_POLICY_VERSION, granted, subject))
        }
        return when (result) {
            is NetworkResult.Success -> {
                sessions.update { it.copy(consents = consentMap(result.value, it.defaultSubjectId)) }
                // 开启云端存储或补上监护人同意后，把积压的记录传上去
                if (granted && type != ConsentType.RESEARCH_USE) requestSyncIfConsented()
                AccountResult.Success
            }
            else -> result.failure()
        }
    }

    override suspend fun exportCloudData(target: Uri): AccountResult =
        when (val result = caller.call(network::exportAll)) {
            is NetworkResult.Success -> withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(target, "wt")!!.use { it.write(result.value.encodeToByteArray()) }
                }.fold({ AccountResult.Success }, { AccountResult.Failure("导出文件写入失败") })
            }
            else -> result.failure()
        }

    override suspend fun deleteCloudAccount(): AccountResult = when (val result = caller.call(network::deleteAccount)) {
        is NetworkResult.Success -> {
            sessions.save(null)
            AccountResult.Success
        }
        else -> result.failure()
    }

    /** 拿到令牌后读取账号与同意，组成完整会话再保存，避免出现「已登录但不知道默认被测者」的中间态。 */
    private suspend fun establish(login: NetworkResult<TokenResponse>): AccountResult {
        val tokens = when (login) {
            is NetworkResult.Success -> login.value
            else -> return login.failure()
        }
        val account = network.account(tokens.accessToken)
        val consents = network.consents(tokens.accessToken)
        if (account !is NetworkResult.Success) return account.failure()
        if (consents !is NetworkResult.Success) return consents.failure()
        sessions.save(Session(
            accountId = tokens.accountId,
            email = account.value.email,
            defaultSubjectId = account.value.defaultSubjectId,
            accessToken = tokens.accessToken,
            accessExpiresAt = tokens.accessExpiresAt,
            refreshToken = tokens.refreshToken,
            refreshExpiresAt = tokens.refreshExpiresAt,
            consents = consentMap(consents.value, account.value.defaultSubjectId),
        ))
        requestSyncIfConsented()
        return AccountResult.Success
    }

    private fun requestSyncIfConsented() {
        if (sessions.session.value?.consents?.get(ConsentType.HEALTH_DATA_SYNC) == true) scheduler.requestSync()
    }

    private fun consentMap(consents: List<ConsentDto>, defaultSubjectId: String): Map<ConsentType, Boolean> =
        consents.filter { it.subjectId == null || (it.type == ConsentType.GUARDIAN && it.subjectId == defaultSubjectId) }
            .associate { it.type to it.granted }
}

internal fun NetworkResult<*>.failure(): AccountResult.Failure = when (this) {
    is NetworkResult.HttpError -> AccountResult.Failure(message)
    is NetworkResult.Unreachable -> AccountResult.Failure("无法连接云端服务，请检查网络后重试")
    is NetworkResult.Success -> AccountResult.Failure("未知错误")
}
