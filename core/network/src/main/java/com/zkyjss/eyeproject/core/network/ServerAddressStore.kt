package com.zkyjss.eyeproject.core.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云端服务器地址：可在「我的」页填写（与苹果端一致，局域网 IP 变了不必重新打包），
 * 未填写时用构建期的 `eyeproject.apiBaseUrl`；两者都为空则不联网。
 */
@Singleton
class ServerAddressStore internal constructor(private val read: () -> String?, private val write: (String?) -> Unit, private val fallback: String) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        read = { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) },
        write = { value -> context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (value == null) remove(KEY) else putString(KEY, value) }.apply() },
        fallback = BuildConfig.API_BASE_URL,
    )

    private val _address = MutableStateFlow(read() ?: fallback)
    /** 当前生效的地址（不含末尾斜杠）；空字符串表示未配置。 */
    val address: StateFlow<String> = _address.asStateFlow()

    /** 保存地址；只接受 http(s)://主机[:端口]，空字符串表示关闭云端。返回 false 表示格式不对。 */
    fun set(url: String): Boolean {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isNotEmpty() && !VALID.matches(trimmed)) return false
        write(trimmed)
        _address.value = trimmed
        return true
    }

    private companion object {
        const val PREFS = "eye_server"
        const val KEY = "api_base_url"
        val VALID = Regex("""^https?://[^\s/]+(:\d+)?$""")
    }
}
