package com.zkyjss.eyeproject.server

import java.sql.Connection

/** 审计动作。只记录「谁在何时对哪个对象做了什么、结果如何」，不写入健康数据内容。 */
enum class AuditAction {
    REGISTER, LOGIN, LOGIN_FAILED, LOGOUT, TOKEN_REUSE,
    CONSENT_CHANGE, SUBJECT_CREATE, SUBJECT_UPDATE, SUBJECT_DELETE, SUBJECT_PROFILE_UPDATE,
    RECORD_UPLOAD, RECORD_VIEW, RECORD_DELETE, EXPORT, ACCOUNT_DELETE, RETENTION_PURGE,
    /** AI 解读：结果为 OK / CACHED / QUOTA / FAILED / INVALID_OUTPUT，不记内容。 */
    AI_EXPLAIN,
    /** AI 综合建议：结果同上，不记内容。 */
    AI_ADVICE,
}

class AuditLog(private val ipHasher: IpHasher, private val clock: () -> Long) {
    fun write(
        connection: Connection,
        action: AuditAction,
        actor: String?,
        targetType: String? = null,
        targetId: String? = null,
        outcome: String = "OK",
        ip: String? = null,
    ) {
        connection.update(
            "INSERT INTO audit_events (at, actor, action, target_type, target_id, outcome, ip_hash) VALUES (?, ?, ?, ?, ?, ?, ?)",
            clock(), actor, action.name, targetType, targetId, outcome, ipHasher.hash(ip),
        )
    }
}
