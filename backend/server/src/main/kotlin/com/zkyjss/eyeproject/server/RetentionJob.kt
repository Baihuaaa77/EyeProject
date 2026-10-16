package com.zkyjss.eyeproject.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** 每日清理：过期令牌、超过保留期的审计，以及（若配置）超过保留期的记录。 */
class RetentionJob(
    private val db: Database,
    private val config: ServerConfig,
    private val audit: AuditLog,
    private val clock: () -> Long,
) {
    private val log = LoggerFactory.getLogger(RetentionJob::class.java)

    data class Result(val tokens: Int, val auditEvents: Int, val records: Int)

    fun runOnce(): Result = db.tx {
        val now = clock()
        val day = 24 * 3_600_000L
        val tokens = update("DELETE FROM auth_tokens WHERE expires_at < ?", now - day)
        val events = update("DELETE FROM audit_events WHERE at < ?", now - config.auditRetentionDays * day)
        val records = config.recordRetentionDays?.let { update("DELETE FROM records WHERE tested_at < ?", now - it * day) } ?: 0
        if (records > 0) audit.write(this, AuditAction.RETENTION_PURGE, null, "records", null, "DELETED_$records")
        Result(tokens, events, records)
    }

    fun start(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            runCatching(::runOnce)
                .onSuccess { log.info("保留期清理：令牌 {}，审计 {}，记录 {}", it.tokens, it.auditEvents, it.records) }
                .onFailure { log.error("保留期清理失败", it) }
            delay(24 * 3_600_000L)
        }
    }
}
