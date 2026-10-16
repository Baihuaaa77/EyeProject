package com.zkyjss.eyeproject.feature.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 「我的 → 本机测试记录」两端共用的摘要解析。 */
class LocalRecordCatalogTest {
    @Test fun eachKindGetsTitleTimeBadgeAndLines() {
        val dry = LocalRecordCatalog.entry("dryeye_1792135044762.json",
            """{"kind":"DRY_EYE","valid":true,"riskLevel":"MEDIUM","abnormal":["眨眼偏少"],"evidence":[{"details":["每分钟 5 次"]}]}""")!!
        assertEquals("干眼自测", dry.title)
        assertEquals(1_792_135_044_762L, dry.testedAtMs)
        assertEquals("干眼中风险", dry.badge)
        assertEquals(listOf("眨眼偏少", "每分钟 5 次"), dry.lines)

        val strabismus = LocalRecordCatalog.entry("strabismus_1.json",
            """{"kind":"STRABISMUS","assessment":{"riskLevel":"HIGH","findings":["水平偏斜 12Δ"]}}""")!!
        assertEquals("高度疑似", strabismus.badge)
        assertEquals(listOf("水平偏斜 12Δ"), strabismus.lines)

        val invalid = LocalRecordCatalog.entry("presbyopia_2.json", """{"kind":"PRESBYOPIA","assessment":{"riskLevel":"UNKNOWN"}}""")!!
        assertEquals("需要重测", invalid.badge)
    }

    @Test fun unreadableRecordsAreSkipped() {
        assertNull(LocalRecordCatalog.entry("notes.json", "{}"))
        assertNull(LocalRecordCatalog.entry("dryeye_1.json", "not json"))
        assertTrue(LocalRecordCatalog.entryJson("dryeye_1.json", """{"kind":"DRY_EYE","valid":false}""")!!.contains("\"badge\":\"需要重测\""))
    }
}
