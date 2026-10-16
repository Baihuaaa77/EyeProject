package com.zkyjss.eyeproject.server

import com.zkyjss.eyeproject.core.sync.AccountDto
import com.zkyjss.eyeproject.core.sync.ComparisonDto
import com.zkyjss.eyeproject.core.sync.ConsentDto
import com.zkyjss.eyeproject.core.sync.ConsentInput
import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.sync.ErrorBody
import com.zkyjss.eyeproject.core.sync.ErrorCodes
import com.zkyjss.eyeproject.core.sync.ExportBundle
import com.zkyjss.eyeproject.core.sync.HistoryDto
import com.zkyjss.eyeproject.core.sync.LoginRequest
import com.zkyjss.eyeproject.core.sync.RecordDetailDto
import com.zkyjss.eyeproject.core.sync.RecordPage
import com.zkyjss.eyeproject.core.sync.RecordQuality
import com.zkyjss.eyeproject.core.sync.RecordSummaryDto
import com.zkyjss.eyeproject.core.sync.RecordUpload
import com.zkyjss.eyeproject.core.sync.RefreshRequest
import com.zkyjss.eyeproject.core.sync.RegisterRequest
import com.zkyjss.eyeproject.core.sync.Sha256
import com.zkyjss.eyeproject.core.sync.SubjectDto
import com.zkyjss.eyeproject.core.sync.SubjectInput
import com.zkyjss.eyeproject.core.sync.SubjectRelation
import com.zkyjss.eyeproject.core.sync.SyncContract
import com.zkyjss.eyeproject.core.sync.TokenResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiTest {
    private var now = 1_760_500_000_000L

    private fun record(kind: String = "STRABISMUS", risk: String = "MEDIUM", age: Int = 30, hde: Double = 4.2) =
        """{"protocolVersion":5,"kind":"$kind","assessment":{"modelVersion":"protocol-9.18-rules-1","quality":"可用于探索性评估",
           "riskLevel":"$risk","features":{"fixation_hde_prism":$hde,"bsi":0.8}},"profile":{"年龄":"$age"},"eyeSamples":[{"leftX":0.1}]}"""

    private fun upload(payload: String, subjectId: String? = null, sha: String = Sha256.hex(payload)) =
        RecordUpload(subjectId, now, payload, sha, "Android", "1.0")

    private fun api(
        config: ServerConfig = ServerConfig.forTest(UUID.randomUUID().toString()),
        block: suspend ApplicationTestBuilder.(HttpClient, Services) -> Unit,
    ) {
        val services = Services(config) { now }
        try {
            testApplication {
                application { eyeProjectModule(config, services, runJobs = false) }
                val client = createClient { install(ContentNegotiation) { json(SyncContract.json) } }
                block(client, services)
            }
        } finally {
            services.close()
        }
    }

    private suspend fun HttpClient.register(email: String = "parent@example.com"): TokenResponse =
        post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email, "secret-123", "家长", SyncContract.PRIVACY_POLICY_VERSION))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }.body()

    private suspend fun HttpClient.consent(token: String, type: ConsentType, subjectId: String? = null, granted: Boolean = true) =
        post("/v1/me/consents") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(ConsentInput(type, SyncContract.PRIVACY_POLICY_VERSION, granted, subjectId))
        }

    private suspend fun HttpClient.putRecord(token: String, id: String, body: RecordUpload): HttpResponse =
        put("/v1/records/$id") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpResponse.error(): String = body<ErrorBody>().error

    @Test fun registerCreatesDefaultSubjectAndPrivacyConsent() = api { client, _ ->
        val tokens = client.register()
        val me: AccountDto = client.get("/v1/me") { bearerAuth(tokens.accessToken) }.body()
        assertEquals("parent@example.com", me.email)
        val subjects: List<SubjectDto> = client.get("/v1/subjects") { bearerAuth(tokens.accessToken) }.body()
        assertEquals(listOf(me.defaultSubjectId), subjects.map { it.id })
        assertEquals(SubjectRelation.SELF, subjects.single().relation)
        val consents: List<ConsentDto> = client.get("/v1/me/consents") { bearerAuth(tokens.accessToken) }.body()
        assertEquals(listOf(ConsentType.PRIVACY_POLICY), consents.map { it.type })

        val again = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("Parent@Example.com", "secret-123", null, "v"))
        }
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals(ErrorCodes.EMAIL_TAKEN, again.error())
        val weak = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("b@example.com", "12345678", null, "v"))
        }
        assertEquals(HttpStatusCode.BadRequest, weak.status)
    }

    @Test fun loginRejectsWrongPasswordAndUnknownEmailAlike() = api { client, _ ->
        client.register()
        for (request in listOf(LoginRequest("parent@example.com", "wrong-123"), LoginRequest("nobody@example.com", "secret-123"))) {
            val response = client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody(request) }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCodes.INVALID_CREDENTIALS, response.error())
        }
        val ok = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json); setBody(LoginRequest(" PARENT@example.com ", "secret-123"))
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me") { bearerAuth("forged") }.status)
    }

    @Test fun uploadRequiresConsentAndIsIdempotent() = api { client, _ ->
        val token = client.register().accessToken
        val id = UUID.randomUUID().toString()
        val payload = record()
        val denied = client.putRecord(token, id, upload(payload))
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        assertEquals(ErrorCodes.CONSENT_REQUIRED, denied.error())

        client.consent(token, ConsentType.HEALTH_DATA_SYNC)
        val created = client.putRecord(token, id, upload(payload))
        assertEquals(HttpStatusCode.Created, created.status)
        val summary: RecordSummaryDto = created.body()
        assertEquals("STRABISMUS", summary.kind)
        assertEquals("MEDIUM", summary.riskLevel)
        assertEquals(RecordQuality.SUFFICIENT, summary.quality)
        assertEquals(4.2, summary.features["fixation_hde_prism"])

        assertEquals(HttpStatusCode.OK, client.putRecord(token, id, upload(payload)).status)
        val conflict = client.putRecord(token, id, upload(record(hde = 9.0)))
        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertEquals(HttpStatusCode.BadRequest, client.putRecord(token, UUID.randomUUID().toString(), upload(payload, sha = "00")).status)
        val invalid = client.putRecord(token, UUID.randomUUID().toString(), upload("""{"kind":"GLAUCOMA"}"""))
        assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
        assertEquals(ErrorCodes.INVALID_RECORD, invalid.error())
        assertEquals(HttpStatusCode.BadRequest, client.putRecord(token, "not-a-uuid", upload(payload)).status)

        // 撤回同意后不能再上传
        client.consent(token, ConsentType.HEALTH_DATA_SYNC, granted = false)
        assertEquals(HttpStatusCode.Forbidden, client.putRecord(token, UUID.randomUUID().toString(), upload(payload)).status)
    }

    @Test fun childRecordsNeedGuardianConsentPerSubject() = api { client, _ ->
        val token = client.register().accessToken
        client.consent(token, ConsentType.HEALTH_DATA_SYNC)
        val child: SubjectDto = client.post("/v1/subjects") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(SubjectInput(SubjectRelation.CHILD, "小明", 2019, 5))
        }.body()
        val payload = record(age = 7)
        val blocked = client.putRecord(token, UUID.randomUUID().toString(), upload(payload, child.id))
        assertEquals(ErrorCodes.GUARDIAN_CONSENT_REQUIRED, blocked.error())
        // 家长账号「本人」名下误传儿童问卷年龄，同样需要监护人同意
        val selfBlocked = client.putRecord(token, UUID.randomUUID().toString(), upload(payload))
        assertEquals(ErrorCodes.GUARDIAN_CONSENT_REQUIRED, selfBlocked.error())

        client.consent(token, ConsentType.GUARDIAN, child.id)
        assertEquals(HttpStatusCode.Created, client.putRecord(token, UUID.randomUUID().toString(), upload(payload, child.id)).status)
        assertEquals(HttpStatusCode.BadRequest, client.consent(token, ConsentType.GUARDIAN).status)
    }

    @Test fun historyCompareAndDetailStayWithinAccount() = api { client, services ->
        val token = client.register().accessToken
        client.consent(token, ConsentType.HEALTH_DATA_SYNC)
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        client.putRecord(token, first, upload(record(hde = 3.0)))
        now += 60_000L // 访问令牌 15 分钟有效，只推进 1 分钟
        val payload = record(hde = 11.0, risk = "HIGH")
        client.putRecord(token, second, upload(payload))

        val page: RecordPage = client.get("/v1/records?kind=STRABISMUS&limit=1") { bearerAuth(token) }.body()
        assertEquals(listOf(second), page.items.map { it.id })
        val older: RecordPage = client.get("/v1/records?limit=1&before=${page.nextBefore}&beforeId=${page.nextBeforeId}") {
            bearerAuth(token)
        }.body()
        assertEquals(listOf(first), older.items.map { it.id })
        assertEquals(null, older.nextBefore)

        val comparison: ComparisonDto = client.get("/v1/records/$second/compare") { bearerAuth(token) }.body()
        assertEquals(first, comparison.previousRecordId)
        assertEquals(8.0, comparison.deltas.first { it.key == "fixation_hde_prism" }.delta)

        val me: AccountDto = client.get("/v1/me") { bearerAuth(token) }.body()
        val history: HistoryDto = client.get("/v1/subjects/${me.defaultSubjectId}/history?kind=STRABISMUS") { bearerAuth(token) }.body()
        assertEquals(listOf("MEDIUM", "HIGH"), history.points.map { it.riskLevel })

        val detail: RecordDetailDto = client.get("/v1/records/$second") { bearerAuth(token) }.body()
        assertEquals(payload, detail.payload)

        // 原文在库内为密文
        val raw = services.db.tx { single("SELECT payload_cipher FROM records WHERE id = ?", second) { it.getBytes(1) } }!!
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("STRABISMUS"))

        val other = client.register("other@example.com").accessToken
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/records/$second") { bearerAuth(other) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/v1/records/$second") { bearerAuth(other) }.status)
        assertEquals(HttpStatusCode.NotFound,
            client.get("/v1/subjects/${me.defaultSubjectId}/history?kind=STRABISMUS") { bearerAuth(other) }.status)
        val otherPage: RecordPage = client.get("/v1/records") { bearerAuth(other) }.body()
        assertTrue(otherPage.items.isEmpty())
    }

    @Test fun paginationKeepsRecordsSharingTheSameTestTime() = api { client, _ ->
        val token = client.register().accessToken
        client.consent(token, ConsentType.HEALTH_DATA_SYNC)
        val ids = List(3) { UUID.randomUUID().toString() }
        ids.forEachIndexed { i, id -> client.putRecord(token, id, upload(record(hde = i.toDouble()))) }
        val seen = mutableListOf<String>()
        var query = "/v1/records?limit=1"
        while (true) {
            val page: RecordPage = client.get(query) { bearerAuth(token) }.body()
            seen += page.items.map { it.id }
            val before = page.nextBefore ?: break
            query = "/v1/records?limit=1&before=$before&beforeId=${page.nextBeforeId}"
        }
        assertEquals(ids.sortedDescending(), seen)
    }

    @Test fun refreshRotatesAndReuseRevokesFamily() = api { client, _ ->
        val first = client.register()
        val second: TokenResponse = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json); setBody(RefreshRequest(first.refreshToken))
        }.body()
        assertNotEquals(first.refreshToken, second.refreshToken)
        assertEquals(HttpStatusCode.OK, client.get("/v1/me") { bearerAuth(second.accessToken) }.status)

        val replay = client.post("/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody(RefreshRequest(first.refreshToken)) }
        assertEquals(HttpStatusCode.Unauthorized, replay.status)
        // 重用检测后整族吊销：新签发的令牌也失效
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me") { bearerAuth(second.accessToken) }.status)
        val stolen = client.post("/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody(RefreshRequest(second.refreshToken)) }
        assertEquals(HttpStatusCode.Unauthorized, stolen.status)
    }

    @Test fun accessTokenExpires() = api { client, _ ->
        val tokens = client.register()
        now += 16 * 60_000L
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me") { bearerAuth(tokens.accessToken) }.status)
    }

    @Test fun exportThenDeleteAccountErasesData() = api { client, services ->
        val token = client.register().accessToken
        client.consent(token, ConsentType.HEALTH_DATA_SYNC)
        val id = UUID.randomUUID().toString()
        val payload = record()
        client.putRecord(token, id, upload(payload))

        val export: ExportBundle = client.get("/v1/me/export") { bearerAuth(token) }.body()
        assertEquals(payload, export.records.single().payload)
        assertEquals(setOf(ConsentType.PRIVACY_POLICY, ConsentType.HEALTH_DATA_SYNC), export.consents.map { it.type }.toSet())

        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/me") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me") { bearerAuth(token) }.status)
        services.db.tx {
            assertEquals(0, single("SELECT COUNT(*) FROM records") { it.getInt(1) })
            assertEquals(0, single("SELECT COUNT(*) FROM subjects") { it.getInt(1) })
            assertEquals(0, single("SELECT COUNT(*) FROM auth_tokens") { it.getInt(1) })
            val actions = query("SELECT action FROM audit_events ORDER BY id") { it.getString(1) }
            assertEquals(listOf("REGISTER", "CONSENT_CHANGE", "RECORD_UPLOAD", "EXPORT", "ACCOUNT_DELETE"), actions)
            assertNull(single("SELECT 1 FROM audit_events WHERE ip_hash = '127.0.0.1' OR ip_hash = 'localhost'") { true })
        }
    }

    @Test fun subjectsCanBeManagedButSelfIsProtected() = api { client, _ ->
        val token = client.register().accessToken
        val me: AccountDto = client.get("/v1/me") { bearerAuth(token) }.body()
        assertEquals(HttpStatusCode.BadRequest, client.delete("/v1/subjects/${me.defaultSubjectId}") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/v1/subjects") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(SubjectInput(SubjectRelation.SELF, "我"))
        }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/v1/subjects") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(SubjectInput(SubjectRelation.CHILD, "", 2019, 13))
        }.status)
        val child: SubjectDto = client.post("/v1/subjects") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(SubjectInput(SubjectRelation.CHILD, "小红", 2018))
        }.body()
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/subjects/${child.id}") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/v1/subjects/${child.id}") { bearerAuth(token) }.status)
    }

    @Test fun retentionPurgesExpiredRecordsWhenConfigured() {
        val config = ServerConfig.forTest(UUID.randomUUID().toString()).copy(recordRetentionDays = 30)
        api(config) { client, services ->
            val token = client.register().accessToken
            client.consent(token, ConsentType.HEALTH_DATA_SYNC)
            client.putRecord(token, UUID.randomUUID().toString(), upload(record()))
            now += 31L * 86_400_000L
            val result = services.retention.runOnce()
            assertEquals(1, result.records)
            // 访问令牌已过期超过 1 天被清理；刷新令牌 30 天有效，恰在边界上保留
            assertEquals(1, result.tokens)
        }
    }

    @Test fun httpsIsEnforcedWhenRequired() {
        val config = ServerConfig.forTest(UUID.randomUUID().toString()).copy(requireHttps = true)
        api(config) { client, _ ->
            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json); setBody(LoginRequest("a@example.com", "secret-123"))
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals(ErrorCodes.HTTPS_REQUIRED, response.error())
        }
    }
}

class SecurityTest {
    @Test fun cipherBindsCiphertextToRecordAndAccount() {
        val cipher = PayloadCipher(mapOf("k1" to ByteArray(32) { 1 }), "k1")
        val sealed = cipher.encrypt("隐私", PayloadCipher.aad("r1", "a1"))
        assertEquals("隐私", cipher.decrypt("k1", sealed, PayloadCipher.aad("r1", "a1")))
        assertTrue(runCatching { cipher.decrypt("k1", sealed, PayloadCipher.aad("r1", "a2")) }.isFailure)
        assertNotEquals(sealed.toList(), cipher.encrypt("隐私", PayloadCipher.aad("r1", "a1")).toList())
    }

    @Test fun passwordsVerifyWithStoredParameters() {
        val stored = PasswordHasher(1000).hash("secret-123")
        assertTrue(PasswordHasher(5000).verify("secret-123", stored))
        assertFalse(PasswordHasher(1000).verify("secret-124", stored))
    }

    @Test fun productionRefusesMissingSecrets() {
        assertTrue(runCatching { ServerConfig.fromEnv(mapOf("EYE_ENV" to "production")) }.isFailure)
        val key = java.util.Base64.getEncoder().encodeToString(ByteArray(32))
        val config = ServerConfig.fromEnv(mapOf("EYE_ENV" to "production", "EYE_DATA_KEYS" to "k1:$key",
            "EYE_AUDIT_SALT" to "salt", "EYE_DB_URL" to "jdbc:postgresql://db/eye"))
        assertTrue(config.requireHttps)
        assertEquals("k1", config.activeKeyId)
    }
}
