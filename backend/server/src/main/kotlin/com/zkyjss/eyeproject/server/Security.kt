package com.zkyjss.eyeproject.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private val random = SecureRandom()

fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }

/** PBKDF2-HMAC-SHA256；存储格式 `pbkdf2_sha256$迭代$盐$哈希`，迭代次数随记录保存便于日后加强。 */
class PasswordHasher(private val iterations: Int) {
    fun hash(password: String): String {
        val salt = randomBytes(16)
        val b64 = Base64.getEncoder()
        return "pbkdf2_sha256\$$iterations\$${b64.encodeToString(salt)}\$${b64.encodeToString(derive(password, salt, iterations))}"
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2_sha256") return false
        val b64 = Base64.getDecoder()
        val expected = b64.decode(parts[3])
        return MessageDigest.isEqual(expected, derive(password, b64.decode(parts[2]), parts[1].toInt()))
    }

    /** 账号不存在时也做一次同等耗时的计算，避免用响应时间探测邮箱是否注册。 */
    fun burn(password: String) { derive(password, ByteArray(16), iterations) }

    private fun derive(password: String, salt: ByteArray, rounds: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, rounds, 256)).encoded
}

/** 不透明令牌：256 位随机数，URL 安全 Base64；库内只存其 SHA-256。 */
object OpaqueTokens {
    fun generate(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32))
    fun hash(token: String): String = sha256Hex(token)
}

/**
 * 记录原文的 AES-256-GCM 加密。输出 = 12 字节随机 nonce ‖ 密文 ‖ 16 字节标签；
 * AAD 绑定记录 id 与账号 id，密文被挪到别的行或别的账号时解密失败。
 */
class PayloadCipher(private val keys: Map<String, ByteArray>, val activeKeyId: String) {
    fun encrypt(plain: String, aad: String): ByteArray {
        val nonce = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.getValue(activeKeyId), "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad.encodeToByteArray())
        return nonce + cipher.doFinal(plain.encodeToByteArray())
    }

    fun decrypt(keyId: String, sealed: ByteArray, aad: String): String {
        val key = keys[keyId] ?: error("缺少数据密钥 $keyId，无法解密")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, 12))
        cipher.updateAAD(aad.encodeToByteArray())
        return cipher.doFinal(sealed, 12, sealed.size - 12).decodeToString()
    }

    companion object {
        fun aad(recordId: String, accountId: String) = "record:$recordId|account:$accountId"
    }
}

/** 审计里的 IP 只存加盐 HMAC，既能关联同源请求又不保存原值。 */
class IpHasher(private val salt: ByteArray) {
    fun hash(ip: String?): String? = ip?.let {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        mac.doFinal(it.encodeToByteArray()).take(16).joinToString("") { b -> "%02x".format(b) }
    }
}
