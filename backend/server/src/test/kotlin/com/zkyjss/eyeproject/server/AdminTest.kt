package com.zkyjss.eyeproject.server

import com.zkyjss.eyeproject.core.sync.ConsentInput
import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.sync.RecordUpload
import com.zkyjss.eyeproject.core.sync.RegisterRequest
import com.zkyjss.eyeproject.core.sync.Sha256
import com.zkyjss.eyeproject.core.sync.SyncContract
import com.zkyjss.eyeproject.core.sync.TokenResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 管理网页：口令登录、只含同意研究用途的账号、去标识、导出与审计。 */
class AdminTest {
    private var now = 1_760_500_000_000L
    private val password = "admin-password-123"

    /** 干眼、色觉的结果在顶层；老视、眼位在 assessment 里（与 RecordSummaries.parse 一致）。 */
    private fun record(kind: String, risk: String) = if (kind == "DRY_EYE" || kind == "COLOR_VISION")
        """{"protocolVersion":5,"kind":"$kind","valid":true,"assessmentVersion":"dryeye-9.18-rules-2","riskLevel":"$risk",
           "features":{"fixation_hde_prism":4.2,"bsi":0.8},"profile":{"年龄":"30"}}"""
    else
        """{"protocolVersion":5,"kind":"$kind","assessment":{"modelVersion":"rules-2","quality":"可用于探索性评估",
           "riskLevel":"$risk","features":{"fixation_hde_prism":4.2,"bsi":0.8}},"profile":{"年龄":"30"}}"""

    private fun admin(adminPassword: String? = password, block: suspend ApplicationTestBuilder.(HttpClient, Services) -> Unit) {
        val config = ServerConfig.forTest(UUID.randomUUID().toString()).copy(adminPassword = adminPassword)
        val services = Services(config) { now }
        try {
            testApplication {
                application { eyeProjectModule(config, services, runJobs = false) }
                val client = createClient {
                    install(ContentNegotiation) { json(SyncContract.json) }
                    followRedirects = false
                }
                block(client, services)
            }
        } finally {
            services.close()
        }
    }

    /** 注册一个上传了记录的用户；[research] 为 null 表示从未表态，false 表示同意后又撤回。 */
    private suspend fun HttpClient.user(email: String, research: Boolean?, vararg records: Pair<String, String>) {
        val token = post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email, "secret-123", "昵称$email", SyncContract.PRIVACY_POLICY_VERSION))
        }.body<TokenResponse>().accessToken
        suspend fun consent(type: ConsentType, granted: Boolean) = post("/v1/me/consents") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(ConsentInput(type, SyncContract.PRIVACY_POLICY_VERSION, granted, null))
        }
        consent(ConsentType.HEALTH_DATA_SYNC, true)
        if (research != null) { consent(ConsentType.RESEARCH_USE, true); if (!research) consent(ConsentType.RESEARCH_USE, false) }
        records.forEach { (kind, risk) ->
            val payload = record(kind, risk)
            now += 60_000
            val status = put("/v1/records/${UUID.randomUUID()}") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody(RecordUpload(null, now, payload, Sha256.hex(payload), "iOS", "1.0"))
            }.status
            assertEquals(HttpStatusCode.Created, status)
        }
    }

    private suspend fun HttpClient.login(secret: String = password) = post("/admin/api/login") {
        contentType(ContentType.Application.Json); setBody(AdminLoginRequest(secret))
    }

    @Test fun adminIsHiddenWithoutAPassword() = admin(adminPassword = null) { client, _ ->
        assertEquals(HttpStatusCode.NotFound, client.get("/admin/").status)
        assertEquals(HttpStatusCode.NotFound, client.login("anything").status)
    }

    @Test fun showsOnlyResearchConsentedDataWithoutIdentifiers() = admin { client, services ->
        client.user("alice@example.com", research = true, "DRY_EYE" to "HIGH", "STRABISMUS" to "LOW", "DRY_EYE" to "UNCERTAIN")
        client.user("bob@example.com", research = null, "DRY_EYE" to "LOW")
        client.user("carol@example.com", research = false, "PRESBYOPIA" to "MEDIUM")

        // 页面：不带斜杠先补斜杠；带安全头
        val redirect = client.get("/admin")
        assertEquals(HttpStatusCode.Found, redirect.status)
        assertEquals("admin/", redirect.headers[HttpHeaders.Location])
        val page = client.get("/admin/")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.headers["Content-Security-Policy"]!!.contains("default-src 'self'"))
        assertTrue(page.bodyAsText().contains("admin.js"))
        assertEquals(HttpStatusCode.OK, client.get("/admin/admin.js").status)

        // 口令
        assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/api/overview").status)
        assertEquals(HttpStatusCode.Unauthorized, client.login("wrong-password").status)
        val token = client.login().body<AdminLoginResponse>().token

        val overview = client.get("/admin/api/overview") { bearerAuth(token) }.body<AdminOverview>()
        assertEquals(3, overview.accounts)
        assertEquals(1, overview.researchAccounts, "从未表态与撤回的都不算")
        assertEquals(3, overview.records)
        assertEquals(mapOf("HIGH" to 1, "UNCERTAIN" to 1), overview.byKind.single { it.kind == "DRY_EYE" }.byRisk)
        assertEquals(mapOf("iOS" to 3), overview.byPlatform)
        assertEquals(30, overview.daily.size)
        assertEquals(3, overview.daily.last().count)

        val response = client.get("/admin/api/records?kind=DRY_EYE&limit=1") { bearerAuth(token) }
        val text = response.bodyAsText()
        assertFalse(text.contains("alice") || text.contains("昵称"), "不出现邮箱与昵称")
        val page1 = response.body<AdminRecordPage>()
        assertEquals(2, page1.total)
        val row = page1.rows.single()
        assertEquals("UNCERTAIN", row.riskLevel, "按测试时间倒序")
        assertTrue(row.user.matches(Regex("U-[0-9a-f]{8}")))
        assertEquals(4.2, row.features["fixation_hde_prism"])
        val all = client.get("/admin/api/records") { bearerAuth(token) }.body<AdminRecordPage>()
        assertEquals(1, all.rows.map { it.user }.distinct().size, "同一人同一假名")
        assertEquals(0, client.get("/admin/api/records?from=${now + 1}") { bearerAuth(token) }.body<AdminRecordPage>().total)

        val csv = client.get("/admin/api/records.csv?risk=HIGH") { bearerAuth(token) }.bodyAsText()
        assertTrue(csv.startsWith("﻿用户,被测者,关系"))
        assertTrue(csv.lines().first().endsWith("bsi,fixation_hde_prism"))
        assertEquals(2, csv.trim().lines().size, "表头 + 1 条高风险")

        val actions = services.db.tx { query("SELECT action FROM audit_events WHERE actor = 'admin' ORDER BY id") { it.getString(1) } }
        assertEquals(listOf("ADMIN_LOGIN_FAILED", "ADMIN_LOGIN", "ADMIN_VIEW", "ADMIN_VIEW", "ADMIN_VIEW", "ADMIN_VIEW", "ADMIN_EXPORT"), actions)

        // 退出后令牌失效
        assertEquals(HttpStatusCode.NoContent, client.post("/admin/api/logout") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/api/overview") { bearerAuth(token) }.status)
    }

    @Test fun ageBands() {
        assertEquals("14 岁以下", AdminService.ageBand(8))
        assertEquals("14–17", AdminService.ageBand(16))
        assertEquals("30–39", AdminService.ageBand(35))
        assertEquals("60 岁及以上", AdminService.ageBand(72))
    }
}
