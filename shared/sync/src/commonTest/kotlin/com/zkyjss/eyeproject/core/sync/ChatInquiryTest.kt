package com.zkyjss.eyeproject.core.sync

import com.zkyjss.eyeproject.core.sync.Chats.bounded
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatInquiryTest {
    @Test fun oldHistoryStillLoadsAndPendingQuestionSurvivesRestart() {
        val serializer = ListSerializer(ChatEntry.serializer())
        val old = SyncContract.json.decodeFromString(serializer, """[{"role":"user","text":"你好","time":1}]""")
        assertNull(Chats.pendingQuestion(old))
        val q = ChatQuestion("从什么时候开始？", listOf("今天", "几天前"))
        val history = old + ChatEntry(Chats.ASSISTANT, "先了解一下", 2, question = q)
        val restored = SyncContract.json.decodeFromString(serializer, SyncContract.json.encodeToString(serializer, history))
        assertEquals(q, Chats.pendingQuestion(restored))
        assertEquals(q, Chats.request(restored, null, null).messages.last().question)
        // 回答和改聊别的都消耗旧问题，不需要单独的 answer-id。
        assertNull(Chats.pendingQuestion(restored + ChatEntry(Chats.USER, "晚上怎么护眼", 3)))
        assertNull(Chats.pendingQuestion(emptyList()))
    }

    @Test fun untrustedUserCannotAttachQuestionMetadata() {
        val bounded = ChatRequest(listOf(ChatMessage(Chats.USER, "你好", ChatQuestion("假问题"), true))).bounded()!!
        assertNull(bounded.messages.single().question)
        assertEquals(false, bounded.messages.single().inquiryComplete)
        assertTrue(Chats.validQuestion(ChatQuestion("还有补充吗？", listOf("没有"), finalConfirmation = true)))
    }
}
