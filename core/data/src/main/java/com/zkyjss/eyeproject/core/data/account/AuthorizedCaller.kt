package com.zkyjss.eyeproject.core.data.account

import com.zkyjss.eyeproject.core.network.EyeNetworkDataSource
import com.zkyjss.eyeproject.core.network.NetworkResult
import com.zkyjss.eyeproject.core.sync.ErrorCodes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 带访问令牌执行请求：快过期时先刷新，遇 401 再刷新并重试一次。
 * 刷新被服务端拒绝（令牌失效或被吊销）时清除本机会话，等同退出登录。
 */
@Singleton
internal class AuthorizedCaller @Inject constructor(
    private val network: EyeNetworkDataSource,
    private val sessions: SessionStore,
) {
    private val refreshLock = Mutex()
    private val clock: () -> Long = System::currentTimeMillis

    suspend fun <T> call(block: suspend (accessToken: String) -> NetworkResult<T>): NetworkResult<T> {
        val session = sessions.session.value ?: return SIGNED_OUT
        val token = if (session.accessExpiresAt - EXPIRY_MARGIN_MS > clock()) session.accessToken
            else when (val renewed = refresh(session.accessToken)) {
                is NetworkResult.Success -> renewed.value
                is NetworkResult.HttpError -> return renewed
                is NetworkResult.Unreachable -> return renewed
            }
        val first = block(token)
        if (first !is NetworkResult.HttpError || first.status != 401) return first
        return when (val renewed = refresh(token)) {
            is NetworkResult.Success -> block(renewed.value)
            is NetworkResult.HttpError -> renewed
            is NetworkResult.Unreachable -> renewed
        }
    }

    /**
     * 刷新访问令牌。并发调用只刷新一次：若会话里的令牌已不是失败时用的那个，说明别的调用刚刷新过。
     * 网络不可达返回 Unreachable（稍后重试）；服务端拒绝刷新则清除会话并返回 401。
     */
    private suspend fun refresh(staleAccessToken: String): NetworkResult<String> = refreshLock.withLock {
        val current = sessions.session.value ?: return SIGNED_OUT
        if (current.accessToken != staleAccessToken && current.accessExpiresAt - EXPIRY_MARGIN_MS > clock())
            return NetworkResult.Success(current.accessToken, 200)
        when (val result = network.refresh(current.refreshToken)) {
            is NetworkResult.Success -> {
                val tokens = result.value
                sessions.save(current.copy(accessToken = tokens.accessToken, accessExpiresAt = tokens.accessExpiresAt,
                    refreshToken = tokens.refreshToken, refreshExpiresAt = tokens.refreshExpiresAt))
                NetworkResult.Success(tokens.accessToken, result.status)
            }
            is NetworkResult.HttpError -> {
                if (result.status == 401) {
                    sessions.save(null)
                    SIGNED_OUT
                } else result
            }
            is NetworkResult.Unreachable -> result
        }
    }

    companion object {
        private const val EXPIRY_MARGIN_MS = 30_000L
        val SIGNED_OUT = NetworkResult.HttpError(401, ErrorCodes.INVALID_TOKEN, "请先登录")
    }
}
