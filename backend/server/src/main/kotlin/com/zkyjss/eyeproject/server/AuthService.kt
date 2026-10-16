package com.zkyjss.eyeproject.server

import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.sync.ErrorCodes
import com.zkyjss.eyeproject.core.sync.LoginRequest
import com.zkyjss.eyeproject.core.sync.RegisterRequest
import com.zkyjss.eyeproject.core.sync.SubjectRelation
import com.zkyjss.eyeproject.core.sync.SyncContract
import com.zkyjss.eyeproject.core.sync.TokenResponse
import io.ktor.http.HttpStatusCode
import java.sql.Connection
import java.util.UUID

/** 账号注册、登录与令牌生命周期。 */
class AuthService(
    private val db: Database,
    private val config: ServerConfig,
    private val audit: AuditLog,
    private val clock: () -> Long,
) {
    private val passwords = PasswordHasher(config.pbkdf2Iterations)

    suspend fun register(request: RegisterRequest, ip: String?): TokenResponse {
        val email = normalizeEmail(request.email)
        validatePassword(request.password)
        if (request.privacyPolicyVersion.isBlank()) throw ApiException.consentRequired("注册前需要同意隐私政策")
        val displayName = request.displayName?.trim()?.take(64)?.ifBlank { null }
        val hash = passwords.hash(request.password)
        return db.transaction {
            if (single("SELECT 1 FROM accounts WHERE email = ?", email) { true } == true)
                throw ApiException(HttpStatusCode.Conflict, ErrorCodes.EMAIL_TAKEN, "该邮箱已注册")
            val now = clock()
            val accountId = UUID.randomUUID().toString()
            update("INSERT INTO accounts (id, email, password_hash, display_name, created_at) VALUES (?, ?, ?, ?, ?)",
                accountId, email, hash, displayName, now)
            update("""INSERT INTO subjects (id, account_id, relation, nickname, is_default, created_at)
                      VALUES (?, ?, ?, ?, TRUE, ?)""",
                UUID.randomUUID().toString(), accountId, SubjectRelation.SELF.name, displayName ?: "本人", now)
            update("INSERT INTO consents (account_id, type, policy_version, granted, recorded_at) VALUES (?, ?, ?, TRUE, ?)",
                accountId, ConsentType.PRIVACY_POLICY.name, request.privacyPolicyVersion.take(32), now)
            audit.write(this, AuditAction.REGISTER, accountId, "account", accountId, ip = ip)
            issue(accountId, UUID.randomUUID().toString())
        }
    }

    suspend fun login(request: LoginRequest, ip: String?): TokenResponse {
        val email = request.email.trim().lowercase()
        return db.transaction {
            val row = single("SELECT id, password_hash FROM accounts WHERE email = ?", email) { it.getString(1) to it.getString(2) }
            if (row == null) passwords.burn(request.password)
            if (row == null || !passwords.verify(request.password, row.second)) {
                audit.write(this, AuditAction.LOGIN_FAILED, row?.first, outcome = "DENIED", ip = ip)
                commit()
                throw ApiException(HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS, "邮箱或密码不正确")
            }
            audit.write(this, AuditAction.LOGIN, row.first, ip = ip)
            issue(row.first, UUID.randomUUID().toString())
        }
    }

    /** 刷新令牌轮换：旧令牌作废；已作废的令牌再次出现视为泄露，整族吊销。 */
    suspend fun refresh(refreshToken: String, ip: String?): TokenResponse = db.transaction {
        val now = clock()
        val row = single("SELECT account_id, family_id, expires_at, revoked_at FROM auth_tokens WHERE token_hash = ? AND kind = 'REFRESH'",
            OpaqueTokens.hash(refreshToken)) { RefreshRow(it.getString(1), it.getString(2), it.getLong(3), it.longOrNull("revoked_at")) }
            ?: throw ApiException.unauthorized()
        if (row.revokedAt != null) {
            revokeFamily(row.familyId, now)
            audit.write(this, AuditAction.TOKEN_REUSE, row.accountId, "token_family", row.familyId, "REVOKED", ip)
            commit()
            throw ApiException.unauthorized()
        }
        if (row.expiresAt <= now) throw ApiException.unauthorized()
        update("UPDATE auth_tokens SET revoked_at = ? WHERE token_hash = ?", now, OpaqueTokens.hash(refreshToken))
        issue(row.accountId, row.familyId)
    }

    suspend fun logout(refreshToken: String, accountId: String, ip: String?) = db.transaction {
        val family = single("SELECT family_id FROM auth_tokens WHERE token_hash = ? AND account_id = ?",
            OpaqueTokens.hash(refreshToken), accountId) { it.getString(1) }
        if (family != null) revokeFamily(family, clock())
        audit.write(this, AuditAction.LOGOUT, accountId, ip = ip)
    }

    /** 校验访问令牌，返回账号 id；无效返回 null。在 IO 线程查库，不阻塞请求线程。 */
    suspend fun authenticate(accessToken: String): String? = db.transaction {
        single("SELECT account_id FROM auth_tokens WHERE token_hash = ? AND kind = 'ACCESS' AND revoked_at IS NULL AND expires_at > ?",
            OpaqueTokens.hash(accessToken), clock()) { it.getString(1) }
    }

    private fun Connection.issue(accountId: String, familyId: String): TokenResponse {
        val now = clock()
        val access = OpaqueTokens.generate()
        val refresh = OpaqueTokens.generate()
        val accessExpires = now + config.accessTokenTtlMs
        val refreshExpires = now + config.refreshTokenTtlMs
        val sql = "INSERT INTO auth_tokens (token_hash, account_id, kind, family_id, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?)"
        update(sql, OpaqueTokens.hash(access), accountId, "ACCESS", familyId, accessExpires, now)
        update(sql, OpaqueTokens.hash(refresh), accountId, "REFRESH", familyId, refreshExpires, now)
        return TokenResponse(accountId, access, accessExpires, refresh, refreshExpires)
    }

    private fun Connection.revokeFamily(familyId: String, now: Long) =
        update("UPDATE auth_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL", now, familyId)

    private data class RefreshRow(val accountId: String, val familyId: String, val expiresAt: Long, val revokedAt: Long?)

    companion object {
        private val EMAIL = Regex("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$")

        fun normalizeEmail(raw: String): String {
            val email = raw.trim().lowercase()
            if (email.length > 254 || !EMAIL.matches(email)) throw ApiException.badRequest("邮箱格式不正确")
            return email
        }

        fun validatePassword(password: String) {
            if (password.length < SyncContract.MIN_PASSWORD_LENGTH || password.length > 128)
                throw ApiException.badRequest("密码长度需为 ${SyncContract.MIN_PASSWORD_LENGTH}–128 位")
            if (password.all(Char::isDigit) || password.all(Char::isLetter))
                throw ApiException.badRequest("密码需同时包含字母和数字或符号")
        }
    }
}
