package com.zkyjss.eyeproject.server

import com.zkyjss.eyeproject.core.sync.SyncContract
import com.zkyjss.eyeproject.core.sync.client.CloudClient
import com.zkyjss.eyeproject.core.sync.client.CloudPlatform
import com.zkyjss.eyeproject.core.sync.client.HttpCallback
import com.zkyjss.eyeproject.core.sync.client.HttpTransport
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 苹果端云端客户端（shared/sync client，Swift 只实现网络、钥匙串与文件三个端口）对接真实服务端的端到端测试：
 * 注册登录、同意、成员与资料双向同步、记录按成员归属上传、儿童待监护人同意、令牌过期刷新、换设备拉取。
 */
class CloudClientEndToEndTest {
    private var now = 1_760_500_000_000L

    /** 用 Ktor 测试客户端实现平台网络层，相当于 Swift 的 URLSession。 */
    private class KtorTransport(private val client: HttpClient, private val scope: CoroutineScope) : HttpTransport {
        override fun send(method: String, url: String, headers: Map<String, String>, body: String?, callback: HttpCallback) {
            scope.launch {
                try {
                    val response = client.request(url) {
                        this.method = HttpMethod.parse(method)
                        headers.filterKeys { it != "Content-Type" }.forEach { (k, v) -> header(k, v) }
                        if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
                    }
                    callback.onResponse(response.status.value, response.bodyAsText())
                } catch (error: Exception) {
                    callback.onFailure(error.message.orEmpty())
                }
            }
        }
    }

    /** 相当于 Swift 侧的钥匙串、沙盒文件、本机记录与成员资料。 */
    private inner class Device : CloudPlatform {
        val secrets = mutableMapOf<String, String>()
        val values = mutableMapOf<String, String>()
        val records = linkedMapOf<String, String>()
        val subjects = linkedMapOf<String, JsonObject>()
        override fun nowMs() = now
        override fun readSecret(key: String) = secrets[key]
        override fun writeSecret(key: String, value: String?) { if (value == null) secrets.remove(key) else secrets[key] = value }
        override fun readValue(key: String) = values[key]
        override fun writeValue(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        override fun recordFileNames() = records.keys.toList()
        override fun readRecord(fileName: String) = records[fileName]
        override fun subjectsJson() = JsonArray(subjects.values.toList()).toString()
        override fun upsertSubjectJson(json: String) {
            val o = SyncContract.json.parseToJsonElement(json).jsonObject
            subjects[o["id"]!!.jsonPrimitive.content] = o
        }
        override fun removeSubject(id: String) { subjects.remove(id) }
        fun member(id: String, relation: String, name: String, fields: Map<String, String>, updatedAt: Long) = upsertSubjectJson(
            """{"id":"$id","relation":"$relation","nickname":"$name","fields":${JsonObject(fields.mapValues { JsonPrimitive(it.value) })},"updatedAt":$updatedAt}""")
        fun fields(id: String) = subjects[id]!!["fields"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    private fun record(kind: String, subjectId: String, relation: String) =
        """{"protocolVersion":5,"kind":"$kind","assessment":{"modelVersion":"rules-2","quality":"可用于探索性评估","riskLevel":"LOW","features":{}},
           "profile":{"年龄":"30"},"subject":{"id":"$subjectId","relation":"$relation"}}"""

    @Test fun iosInquiryBridgePreservesQuestionAcrossRestartAndSendsAnswer() {
        val replies = ArrayDeque(listOf(
            """{"reply":"先了解一下","question":{"prompt":"从什么时候开始？","options":["今天","几天前"]}}""",
            """{"reply":"再确认一下","question":{"prompt":"还有没有其他需要补充的？","options":["没有"],"finalConfirmation":true}}""",
            """{"reply":"综合判断：信息仅供参考。就医建议：持续不适到眼科检查。就医前的缓解与注意事项：适当休息。需要警惕的信号：突然看不清及时就医。复诊与跟进：记录变化。","inquiryComplete":true}"""))
        val llm = object : LlmClient {
            override val model = "scripted"
            override suspend fun complete(system: String, user: String) = LlmReply(replies.removeFirst(), 10, 10)
        }
        val config = ServerConfig.forTest(UUID.randomUUID().toString())
        val services = Services(config, llm) { now }
        try {
            testApplication {
                application { eyeProjectModule(config, services, runJobs = false) }
                val http = createClient { }
                coroutineScope {
                    val device = Device()
                    val client = CloudClient(KtorTransport(http, this), device, this) { }
                    client.setServer("http://localhost")
                    client.register("inquiry@example.com", "secret-123"); client.awaitIdle()
                    client.sendChat(com.zkyjss.eyeproject.core.sync.Chats.START_INQUIRY, "", true, true); client.awaitIdle()
                    fun messages(c: CloudClient) = SyncContract.json.parseToJsonElement(c.chatJson()).jsonObject["messages"] as JsonArray
                    assertEquals("从什么时候开始？", messages(client).last().jsonObject["question"]!!.jsonObject["prompt"]!!.jsonPrimitive.content)
                    val restarted = CloudClient(KtorTransport(http, this), device, this) { }
                    assertEquals(messages(client), messages(restarted))
                    restarted.sendChat("今天", "", false, false); restarted.awaitIdle()
                    assertEquals("true", messages(restarted).last().jsonObject["question"]!!.jsonObject["finalConfirmation"]!!.jsonPrimitive.content)
                    restarted.sendChat("没有", "", false, false); restarted.awaitIdle()
                    assertEquals("true", messages(restarted).last().jsonObject["inquiryComplete"]!!.jsonPrimitive.content)
                    assertNull(messages(restarted).last().jsonObject["question"])
                    restarted.clearChat()
                    assertTrue(messages(restarted).isEmpty())
                    assertTrue(replies.isEmpty())
                }
            }
        } finally { services.close() }
    }

    @Test fun iosClientSyncsMembersProfilesAndRecordsWithTheRealServer() {
        val config = ServerConfig.forTest(UUID.randomUUID().toString())
        val services = Services(config) { now }
        try {
            testApplication {
                application { eyeProjectModule(config, services, runJobs = false) }
                val http = createClient { }
                coroutineScope {
                    val phone = Device()
                    val selfId = UUID.randomUUID().toString(); val kidId = UUID.randomUUID().toString()
                    phone.member(selfId, "SELF", "妈妈", mapOf("出生年份" to "1985", "性别" to "女", "日常矫正方式" to "老花镜"), now - 5_000)
                    phone.member(kidId, "CHILD", "小明", mapOf("出生年份" to "2018"), now - 4_000)
                    phone.records["presbyopia_1760499000000.json"] = record("PRESBYOPIA", selfId, "SELF")
                    phone.records["strabismus_1760499100000.json"] = record("STRABISMUS", kidId, "CHILD")
                    var changes = 0
                    val client = CloudClient(KtorTransport(http, this), phone, this) { changes++ }
                    assertTrue(client.setServer("http://localhost"))

                    client.register("mom@example.com", "secret-123"); client.awaitIdle()
                    var state = SyncContract.json.parseToJsonElement(client.snapshotJson()).jsonObject
                    assertEquals("true", state["signedIn"]!!.jsonPrimitive.content)
                    assertTrue(changes > 0)
                    // 未同意云端存储：什么都不上传
                    assertFalse(client.sync())
                    assertEquals(0, services.db.transaction { single("SELECT COUNT(*) FROM records") { it.getInt(1) } })

                    client.setConsent("HEALTH_DATA_SYNC", true); client.awaitIdle()
                    services.db.transaction {
                        // 本人资料加密上传、绑定默认成员；孩子用本机编号新建，资料待监护人同意
                        assertEquals(2, single("SELECT COUNT(*) FROM subjects") { it.getInt(1) })
                        assertTrue(single("SELECT profile_cipher FROM subjects WHERE is_default") { it.getBytes(1) } != null)
                        assertEquals("CHILD", single("SELECT relation FROM subjects WHERE id = ?", kidId) { it.getString(1) })
                        assertNull(single("SELECT profile_cipher FROM subjects WHERE id = ?", kidId) { it.getBytes(1) })
                        // 本人的记录已上传；孩子的记录待监护人同意
                        assertEquals(1, single("SELECT COUNT(*) FROM records") { it.getInt(1) })
                        assertEquals("PRESBYOPIA", single("SELECT kind FROM records") { it.getString(1) })
                    }
                    state = SyncContract.json.parseToJsonElement(client.snapshotJson()).jsonObject
                    assertEquals(1, state["synced"]!!.jsonPrimitive.content.toInt())
                    assertTrue(state["awaitingGuardian"]!!.jsonPrimitive.content.toInt() >= 1)

                    // 新记录保存后自动上传；访问令牌过期后自动刷新
                    now += 20 * 60_000
                    phone.records["dryeye_1760501000000.json"] = """{"kind":"DRY_EYE","valid":true,"riskLevel":"LOW","subject":{"id":"$selfId","relation":"SELF"}}"""
                    client.recordSaved("dryeye_1760501000000.json"); client.awaitIdle()
                    assertEquals(2, services.db.transaction { single("SELECT COUNT(*) FROM records") { it.getInt(1) } })

                    // 换一台手机登录：成员与资料拉到本机
                    val tablet = Device()
                    val other = CloudClient(KtorTransport(http, this), tablet, this) { }
                    other.setServer("http://localhost")
                    other.signIn("mom@example.com", "secret-123"); other.awaitIdle()
                    assertTrue(other.sync())
                    assertEquals(setOf("妈妈", "小明"), tablet.subjects.values.map { it["nickname"]!!.jsonPrimitive.content }.toSet())
                    val tabletSelf = tablet.subjects.values.first { it["relation"]!!.jsonPrimitive.content == "SELF" }["id"]!!.jsonPrimitive.content
                    assertEquals("老花镜", tablet.fields(tabletSelf)["日常矫正方式"])

                    // 平板改资料 → 手机同步后以较新的云端为准；手机删除孩子 → 云端删除
                    now += 60_000
                    tablet.member(tabletSelf, "SELF", "妈妈", mapOf("出生年份" to "1985", "日常矫正方式" to "渐进多焦点眼镜"), now)
                    assertTrue(other.sync())
                    client.sync()
                    assertEquals("渐进多焦点眼镜", phone.fields(selfId)["日常矫正方式"])
                    phone.removeSubject(kidId)
                    client.sync()
                    assertNull(services.db.transaction { single("SELECT id FROM subjects WHERE id = ?", kidId) { it.getString(1) } })

                    // 服务器连不上（换了网络、原服务器已关）：同步卡住时照样能退出，且立即生效、不等服务器
                    val refreshToken = SyncContract.json.parseToJsonElement(phone.secrets.values.single())
                        .jsonObject["refreshToken"]!!.jsonPrimitive.content
                    val unreachable = CoroutineScope(Job() + Dispatchers.Unconfined)
                    val offline = CloudClient(object : HttpTransport {
                        override fun send(method: String, url: String, headers: Map<String, String>, body: String?, callback: HttpCallback) = Unit
                    }, phone, unreachable) { }
                    offline.syncNow(userInitiated = true)
                    assertEquals("true", SyncContract.json.parseToJsonElement(offline.snapshotJson()).jsonObject["busy"]!!.jsonPrimitive.content)
                    offline.signOut()
                    val signedOut = SyncContract.json.parseToJsonElement(offline.snapshotJson()).jsonObject
                    assertEquals("false", signedOut["signedIn"]!!.jsonPrimitive.content)
                    assertEquals("false", signedOut["busy"]!!.jsonPrimitive.content)
                    assertEquals("已退出登录，本机记录仍保留", signedOut["message"]!!.jsonPrimitive.content)
                    assertTrue(phone.secrets.isEmpty())
                    unreachable.cancel()

                    // 服务器连得上：退出登录只清会话，本机资料与记录保留，并在后台吊销服务端令牌
                    client.signOut(); client.awaitIdle()
                    assertTrue(phone.secrets.isEmpty())
                    assertEquals(3, phone.records.size)
                    val reuse = http.request("/v1/auth/refresh") {
                        method = HttpMethod.Post; contentType(ContentType.Application.Json); setBody("""{"refreshToken":"$refreshToken"}""")
                    }
                    assertEquals(401, reuse.status.value, "退出后旧刷新令牌已吊销")
                }
            }
        } finally {
            services.close()
        }
    }
}
