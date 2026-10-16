package com.zkyjss.eyeproject.core.data.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.sync.SyncContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** 已登录会话：令牌与同意状态缓存。整体加密后保存，不单独落盘任何字段。 */
@Serializable
internal data class Session(
    val accountId: String,
    val email: String,
    val defaultSubjectId: String,
    val accessToken: String,
    val accessExpiresAt: Long,
    val refreshToken: String,
    val refreshExpiresAt: Long,
    /** 服务端最新生效的账号级同意；GUARDIAN 为默认被测者（本人）的监护人同意。 */
    val consents: Map<ConsentType, Boolean> = emptyMap(),
)

/** 对称加密盒：生产用 Android Keystore 中不可导出的 AES 密钥；单测可替换。 */
internal interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

internal class KeystoreSecretBox : SecretBox {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "eyeproject_session_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** 会话持久化：SharedPreferences 只存密文；解密失败（如密钥被系统清除）视为未登录。 */
@Singleton
internal class SessionStore(context: Context, private val box: SecretBox) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, KeystoreSecretBox())

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _session = MutableStateFlow(load())
    val session: StateFlow<Session?> = _session.asStateFlow()

    @Synchronized
    fun save(session: Session?) {
        val editor = prefs.edit()
        if (session == null) editor.remove(KEY) else {
            val plain = SyncContract.json.encodeToString(Session.serializer(), session).encodeToByteArray()
            editor.putString(KEY, Base64.encodeToString(box.seal(plain), Base64.NO_WRAP))
        }
        editor.apply()
        _session.value = session
    }

    @Synchronized
    fun update(transform: (Session) -> Session) { _session.value?.let { save(transform(it)) } }

    private fun load(): Session? = prefs.getString(KEY, null)?.let { stored ->
        runCatching {
            val plain = box.open(Base64.decode(stored, Base64.NO_WRAP)).decodeToString()
            SyncContract.json.decodeFromString(Session.serializer(), plain)
        }.getOrNull()
    }

    private companion object {
        const val PREFS = "eyeproject_session"
        const val KEY = "sealed_session"
    }
}
