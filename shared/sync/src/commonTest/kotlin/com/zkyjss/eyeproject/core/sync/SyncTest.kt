package com.zkyjss.eyeproject.core.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val STRABISMUS_RECORD = """{"protocolVersion":5,"kind":"STRABISMUS","assessment":{"modelVersion":"protocol-9.18-rules-1",
"quality":"可用于探索性评估","riskLevel":"MEDIUM","riskScope":"近距离","category":"可疑阳性",
"features":{"age_years":7.0,"fixation_hde_prism":4.2,"bsi":0.66,"pursuit_r_v":null},"findings":[],"limitations":[]},
"profile":{"年龄":"7"},"eyeSamples":[]}"""

internal const val DRY_EYE_RECORD = """{"protocolVersion":1,"kind":"DRY_EYE","assessmentVersion":"dryeye-9.18-rules-1",
"riskLevel":"UNKNOWN","valid":false,"validityReasons":["测试未完整完成"],"features":{"blink_rate_per_min":12.0,"vmr":null}}"""

class RecordSummaryTest {
    @Test fun serverAddressMayCarryAPathPrefix() {
        // 局域网 IP、域名、部署在主页域名下的子路径都可以；不接受查询串、空格和非 http(s)
        listOf("http://192.168.1.5:8080", "https://eye.example.com", "https://geometrylearning.com/eyeapi", "https://example.com/a/b")
            .forEach { assertTrue(SyncContract.SERVER_ADDRESS.matches(it), it) }
        listOf("ftp://example.com", "example.com", "https://", "https://example.com/eyeapi?x=1", "https://exa mple.com")
            .forEach { assertFalse(SyncContract.SERVER_ADDRESS.matches(it), it) }
    }

    @Test fun parsesScreeningAssessment() {
        val s = RecordSummaries.parse(STRABISMUS_RECORD)
        assertEquals("STRABISMUS", s.kind)
        assertEquals(5, s.protocolVersion)
        assertEquals("protocol-9.18-rules-1", s.assessmentVersion)
        assertEquals("MEDIUM", s.riskLevel)
        assertEquals(RecordQuality.SUFFICIENT, s.quality)
        assertEquals(4.2, s.features["fixation_hde_prism"])
        assertTrue("pursuit_r_v" in s.features)
        assertNull(s.features["pursuit_r_v"])
        assertEquals(7, s.ageYears)
        assertTrue(RecordSummaries.isMinor(s.ageYears))
        assertEquals(false, RecordSummaries.isMinor(null))
    }

    @Test fun parsesDryEyeTopLevelFields() {
        val s = RecordSummaries.parse(DRY_EYE_RECORD)
        assertEquals("DRY_EYE", s.kind)
        assertEquals("dryeye-9.18-rules-1", s.assessmentVersion)
        assertEquals(RecordQuality.INSUFFICIENT, s.quality)
        assertEquals(12.0, s.features["blink_rate_per_min"])
    }

    @Test fun rejectsMalformedOrContradictoryRecords() {
        assertFailsWith<InvalidRecordException> { RecordSummaries.parse("[]") }
        assertFailsWith<InvalidRecordException> { RecordSummaries.parse("""{"kind":"GLAUCOMA"}""") }
        assertFailsWith<InvalidRecordException> {
            RecordSummaries.parse("""{"kind":"DRY_EYE","riskLevel":"HIGH","valid":false,"features":{}}""")
        }
        assertFailsWith<InvalidRecordException> {
            RecordSummaries.parse("""{"kind":"PRESBYOPIA","assessment":{"quality":"可用于探索性评估","riskLevel":"SEVERE"}}""")
        }
    }

    @Test fun comparesSharedKeysOnly() {
        val deltas = RecordSummaries.compare(mapOf("a" to 1.0, "b" to null), mapOf("a" to 1.5, "b" to 2.0, "c" to 3.0))
        assertEquals(listOf(FeatureDelta("a", 1.0, 1.5, 0.5), FeatureDelta("b", null, 2.0, null)), deltas)
    }
}

class Sha256Test {
    @Test fun matchesKnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc"))
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))
        // 多字节 UTF-8 与跨块长度
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", Sha256.hex("a".repeat(1_000_000)))
    }
}

class RecordSyncEngineTest {
    private class FakeOutbox(entries: List<OutboxEntry>, private val files: Map<String, String>) : RecordOutbox {
        val state = entries.associateBy { it.recordId }.toMutableMap()
        override suspend fun entries() = state.values.toList()
        override suspend fun payload(entry: OutboxEntry) = files[entry.fileName]
        override suspend fun update(entry: OutboxEntry) { state[entry.recordId] = entry }
    }

    private class ScriptedRemote(private val results: Map<String, UploadResult>) : RecordRemote {
        val calls = mutableListOf<Pair<String, RecordUpload>>()
        override suspend fun upload(recordId: String, upload: RecordUpload): UploadResult {
            calls += recordId to upload
            return results.getValue(recordId)
        }
    }

    private fun entry(id: String, at: Long, state: SyncState = SyncState.PENDING) = OutboxEntry(id, "$id.json", at, state)

    @Test fun uploadsInTestTimeOrderWithHashAndMarksOutcomes() = runTest {
        val outbox = FakeOutbox(
            listOf(entry("b", 2), entry("a", 1), entry("c", 3), entry("d", 4), entry("done", 0, SyncState.SYNCED)),
            mapOf("a.json" to STRABISMUS_RECORD, "b.json" to DRY_EYE_RECORD, "c.json" to "{}", "d.json" to "{}"),
        )
        val remote = ScriptedRemote(mapOf("a" to UploadResult.Created, "b" to UploadResult.Duplicate,
            "c" to UploadResult.Conflict, "d" to UploadResult.Rejected("记录无效")))
        val report = RecordSyncEngine(outbox, remote, clock = { 99L }, subjectFor = { "s1" }).pushPending()

        assertEquals(listOf("a", "b", "c", "d"), remote.calls.map { it.first })
        assertEquals(Sha256.hex(STRABISMUS_RECORD), remote.calls.first().second.payloadSha256)
        assertEquals("s1", remote.calls.first().second.subjectId)
        assertEquals(SyncReport(uploaded = 1, duplicates = 1, conflicts = 1, rejected = 1), report)
        assertEquals(SyncState.SYNCED, outbox.state.getValue("a").state)
        assertEquals(99L, outbox.state.getValue("a").syncedAt)
        assertEquals(SyncState.CONFLICT, outbox.state.getValue("c").state)
        assertEquals("记录无效", outbox.state.getValue("d").lastError)
    }

    @Test fun stopsWholeRoundOnTransientFailureAndKeepsPending() = runTest {
        val outbox = FakeOutbox(listOf(entry("a", 1), entry("b", 2)), mapOf("a.json" to "{}", "b.json" to "{}"))
        val remote = ScriptedRemote(mapOf("a" to UploadResult.Transient("超时"), "b" to UploadResult.Created))
        val report = RecordSyncEngine(outbox, remote, clock = { 0L }).pushPending()

        assertEquals(SyncStopReason.TRANSIENT, report.stopReason)
        assertTrue(report.shouldRetry)
        assertEquals(2, report.remaining)
        assertEquals(1, remote.calls.size)
        assertEquals(SyncState.PENDING, outbox.state.getValue("a").state)
        assertEquals(1, outbox.state.getValue("a").attempts)
    }

    @Test fun consentOrAuthProblemsStopWithoutRetry() = runTest {
        for ((result, reason) in listOf(UploadResult.ConsentRequired to SyncStopReason.CONSENT_REQUIRED,
            UploadResult.Unauthorized to SyncStopReason.UNAUTHORIZED)) {
            val outbox = FakeOutbox(listOf(entry("a", 1)), mapOf("a.json" to "{}"))
            val report = RecordSyncEngine(outbox, ScriptedRemote(mapOf("a" to result)), clock = { 0L }).pushPending()
            assertEquals(reason, report.stopReason)
            assertEquals(false, report.shouldRetry)
            assertEquals(SyncState.PENDING, outbox.state.getValue("a").state)
        }
    }

    @Test fun guardianConsentSkipsOnlyThatRecord() = runTest {
        val outbox = FakeOutbox(listOf(entry("child", 1), entry("adult", 2)), mapOf("child.json" to "{}", "adult.json" to "{}"))
        val remote = ScriptedRemote(mapOf("child" to UploadResult.GuardianConsentRequired, "adult" to UploadResult.Created))
        val report = RecordSyncEngine(outbox, remote, clock = { 0L }).pushPending()
        assertEquals(SyncReport(uploaded = 1, awaitingGuardian = 1), report)
        assertEquals(SyncState.PENDING, outbox.state.getValue("child").state)
        assertEquals(RecordSyncEngine.GUARDIAN_PENDING, outbox.state.getValue("child").lastError)
    }

    @Test fun missingLocalFileIsRejectedWithoutCallingRemote() = runTest {
        val outbox = FakeOutbox(listOf(entry("gone", 1)), emptyMap())
        val remote = ScriptedRemote(emptyMap())
        val report = RecordSyncEngine(outbox, remote, clock = { 0L }).pushPending()
        assertEquals(1, report.rejected)
        assertTrue(remote.calls.isEmpty())
        assertEquals(SyncState.REJECTED, outbox.state.getValue("gone").state)
    }
}
