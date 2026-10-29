package com.zkyjss.eyeproject.server

import java.util.Base64

/**
 * 全部配置来自环境变量，密钥不入仓库。生产模式缺少数据密钥或审计盐时拒绝启动。
 * 变量说明见 SERVER.md。
 */
data class ServerConfig(
    val production: Boolean,
    val port: Int,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    /** key id → 32 字节 AES 密钥。 */
    val dataKeys: Map<String, ByteArray>,
    val activeKeyId: String,
    val auditSalt: ByteArray,
    /** 位于反向代理之后时信任 X-Forwarded-* 头。 */
    val trustProxy: Boolean,
    val requireHttps: Boolean,
    val pbkdf2Iterations: Int,
    val authRatePerMinute: Int,
    val accessTokenTtlMs: Long,
    val refreshTokenTtlMs: Long,
    /** 记录保留天数；null 表示不自动过期，直到用户删除。 */
    val recordRetentionDays: Int?,
    val auditRetentionDays: Int,
    val maxSubjectsPerAccount: Int,
    /** AI 解读（DeepSeek 等 OpenAI 兼容接口）；没有密钥时解读接口返回 ai_unavailable，其余功能不受影响。 */
    val llmApiKey: String? = null,
    val llmBaseUrl: String = "https://api.deepseek.com",
    val llmModel: String = "deepseek-v4-flash",
    /** 每个账号每天调用大模型的次数上限（命中缓存不计）。 */
    val llmDailyLimit: Int = 20,
    val llmTimeoutMs: Long = 30_000,
    /** 请求里带 `thinking: disabled`（DeepSeek V4 默认开启思考，解读不需要）；换不支持该字段的模型时设 false。 */
    val llmDisableThinking: Boolean = true,
    /** 管理网页口令；为 null 时不开放管理入口（/admin 返回 404）。 */
    val adminPassword: String? = null,
) {
    init {
        require(activeKeyId in dataKeys) { "EYE_ACTIVE_KEY_ID 不在 EYE_DATA_KEYS 中" }
        require(dataKeys.values.all { it.size == 32 }) { "数据密钥必须是 32 字节（Base64 编码）" }
    }

    companion object {
        /** 仅供开发与测试；生产模式禁止使用。 */
        private val DEV_KEY = ByteArray(32) { (it * 7 + 3).toByte() }

        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            val production = env["EYE_ENV"] == "production"
            fun required(name: String): String = env[name]?.takeIf { it.isNotBlank() }
                ?: error("生产模式必须设置 $name")
            val keys = env["EYE_DATA_KEYS"]?.takeIf { it.isNotBlank() }?.let(::parseKeys)
                ?: if (production) required("EYE_DATA_KEYS").let(::parseKeys) else mapOf("dev" to DEV_KEY)
            val salt = env["EYE_AUDIT_SALT"]?.takeIf { it.isNotBlank() }?.encodeToByteArray()
                ?: if (production) required("EYE_AUDIT_SALT").encodeToByteArray() else "dev-audit-salt".encodeToByteArray()
            return ServerConfig(
                production = production,
                port = env["EYE_PORT"]?.toInt() ?: 8080,
                dbUrl = env["EYE_DB_URL"] ?: if (production) required("EYE_DB_URL") else
                    "jdbc:h2:file:./data/eyeproject;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
                dbUser = env["EYE_DB_USER"] ?: "sa",
                dbPassword = env["EYE_DB_PASSWORD"] ?: "",
                dataKeys = keys,
                activeKeyId = env["EYE_ACTIVE_KEY_ID"] ?: keys.keys.first(),
                auditSalt = salt,
                trustProxy = env["EYE_TRUST_PROXY"]?.toBoolean() ?: false,
                requireHttps = env["EYE_REQUIRE_HTTPS"]?.toBoolean() ?: production,
                pbkdf2Iterations = env["EYE_PBKDF2_ITERATIONS"]?.toInt() ?: 600_000,
                authRatePerMinute = env["EYE_AUTH_RATE_PER_MINUTE"]?.toInt() ?: 20,
                accessTokenTtlMs = 15 * 60_000L,
                refreshTokenTtlMs = 30 * 24 * 3_600_000L,
                recordRetentionDays = env["EYE_RECORD_RETENTION_DAYS"]?.toInt(),
                auditRetentionDays = env["EYE_AUDIT_RETENTION_DAYS"]?.toInt() ?: 3 * 365,
                maxSubjectsPerAccount = 10,
                llmApiKey = (env["DEEPSEEK_API_KEY"] ?: env["EYE_LLM_API_KEY"])?.takeIf { it.isNotBlank() }
                    ?: localLlmKey(env["EYE_LLM_KEY_FILE"] ?: "llm.properties"),
                llmBaseUrl = env["EYE_LLM_BASE_URL"]?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: "https://api.deepseek.com",
                llmModel = env["EYE_LLM_MODEL"]?.takeIf { it.isNotBlank() } ?: "deepseek-v4-flash",
                llmDailyLimit = env["EYE_LLM_DAILY_LIMIT"]?.toInt() ?: 20,
                llmTimeoutMs = env["EYE_LLM_TIMEOUT_MS"]?.toLong() ?: 30_000,
                llmDisableThinking = env["EYE_LLM_DISABLE_THINKING"]?.toBoolean() ?: true,
                adminPassword = env["EYE_ADMIN_PASSWORD"]?.takeIf { it.isNotBlank() }?.also {
                    if (production) require(it.length >= AdminService.MIN_PASSWORD_LENGTH) {
                        "EYE_ADMIN_PASSWORD 至少 ${AdminService.MIN_PASSWORD_LENGTH} 位"
                    }
                },
            )
        }

        /**
         * 本机试用：工作目录（backend/server）下未入库的 `llm.properties` 里的 `apiKey=...`。
         * 环境变量优先；文件不存在时返回 null。
         */
        private fun localLlmKey(path: String): String? = runCatching {
            val file = java.io.File(path)
            if (!file.isFile) return null
            java.util.Properties().apply { file.reader(Charsets.UTF_8).use(::load) }.getProperty("apiKey")?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()

        /** 格式：`k1:<base64>,k2:<base64>`。 */
        fun parseKeys(text: String): Map<String, ByteArray> = text.split(',').filter { it.isNotBlank() }.associate { item ->
            val (id, key) = item.trim().split(':', limit = 2).also { require(it.size == 2) { "EYE_DATA_KEYS 格式应为 id:base64" } }
            id to Base64.getDecoder().decode(key)
        }

        /** 测试用：内存 H2、低迭代次数，库名区分避免用例互相干扰。 */
        fun forTest(dbName: String) = fromEnv(mapOf(
            "EYE_DB_URL" to "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "EYE_PBKDF2_ITERATIONS" to "1000",
            "EYE_AUTH_RATE_PER_MINUTE" to "1000",
            "EYE_LLM_KEY_FILE" to "",
        ))
    }
}
