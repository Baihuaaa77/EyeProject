package com.zkyjss.eyeproject.core.data

import androidx.test.core.app.ApplicationProvider
import com.zkyjss.eyeproject.core.data.account.AuthorizedCaller
import com.zkyjss.eyeproject.core.data.account.Session
import com.zkyjss.eyeproject.core.data.account.SessionStore
import com.zkyjss.eyeproject.core.data.subject.OfflineFirstSubjectRepository
import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.sync.SubjectRelation
import com.zkyjss.eyeproject.feature.services.SubjectProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SubjectRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var directory: File
    private lateinit var sessions: SessionStore
    private val network = FakeNetwork()
    private val scheduler = FakeScheduler()
    private var now = 1_760_500_000_000L

    @Before
    fun setUp() {
        directory = File(context.cacheDir, "subjects-${System.nanoTime()}").apply { mkdirs() }
        sessions = SessionStore(context, PlainBox).apply { save(null) }
    }

    private fun repository() = OfflineFirstSubjectRepository(directory, network, sessions,
        AuthorizedCaller(network, sessions), scheduler) { now }

    private fun signIn(consent: Boolean = true, accountId: String = "acc") = sessions.save(Session(accountId, "a@example.com",
        "remote-self", "access-0", Long.MAX_VALUE, "refresh-0", Long.MAX_VALUE, mapOf(ConsentType.HEALTH_DATA_SYNC to consent)))

    @Test
    fun firstUseHasBlankSelfAndEditsStayOnDeviceWithoutConsent() = runTest {
        val repo = repository()
        val self = repo.subjects.first().single()
        assertEquals("SELF", self.relation)
        assertTrue(self.fields.isEmpty())
        repo.save(self.copy(nickname = "妈妈", fields = mapOf("出生年份" to "1980")))
        assertEquals(mapOf("出生年份" to "1980"), repository().subjects.first().single().fields)
        assertEquals(0, scheduler.requests)
        assertFalse(repo.sync())
        assertEquals(0, network.subjectCalls)
    }

    @Test
    fun syncBindsSelfUploadsMembersAndWaitsForGuardianConsent() = runTest {
        signIn()
        val repo = repository()
        val self = repo.subjects.first().single()
        repo.save(self.copy(fields = mapOf("出生年份" to "1980", "性别" to "女", "日常矫正方式" to "老花镜")))
        repo.save(SubjectProfile("child-local", "CHILD", "小明", mapOf("出生年份" to "2018"), 0L))
        network.guardianRequired = true

        assertFalse(repo.sync())
        assertEquals(mapOf("出生年份" to "1980", "性别" to "女", "日常矫正方式" to "老花镜"), network.subjects["remote-self"]!!.profile)
        assertEquals(1980, network.subjects["remote-self"]!!.birthYear)
        assertEquals("FEMALE", network.subjects["remote-self"]!!.sex)
        // 孩子：成员已建（沿用本机编号），资料等监护人同意
        assertEquals(SubjectRelation.CHILD, network.subjects["child-local"]!!.relation)
        assertNull(network.subjects["child-local"]!!.profile)
        assertEquals(mapOf(self.id to "remote-self", "child-local" to "child-local"), repo.remoteIds.first())

        network.guardianRequired = false
        assertTrue(repo.sync())
        assertEquals(mapOf("出生年份" to "2018"), network.subjects["child-local"]!!.profile)
    }

    @Test
    fun newerCloudProfileWinsAndDeletionsReachTheCloud() = runTest {
        signIn()
        val repo = repository()
        repo.save(SubjectProfile("dad", "PARENT", "爸爸", mapOf("出生年份" to "1955"), 0L))
        assertTrue(repo.sync())
        // 另一台手机改得更新
        network.subjects["remote-self"] = network.subjects["remote-self"]!!.copy(profile = mapOf("出生年份" to "1979"),
            profileUpdatedAt = now + 60_000)
        assertTrue(repo.sync())
        assertEquals(mapOf("出生年份" to "1979"), repo.subjects.first().first { it.relation == "SELF" }.fields)

        repo.delete("dad")
        assertTrue(repo.sync())
        assertFalse("dad" in network.subjects)
        assertEquals(listOf("SELF"), repo.subjects.first().map { it.relation })
        // 「本人」不能删除
        repo.delete(repo.subjects.first().single().id)
        assertEquals(1, repo.subjects.first().size)
    }
}
