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

    @Test fun savedReportAndItsVersionAreUsedWithoutReassessment() {
        val record = """{"kind":"DRY_EYE","valid":true,"riskLevel":"UNCERTAIN","features":{"blink_rate_per_min":12},
            "subject":{"id":"kid-1","relation":"CHILD","name":"小明"},
            "report":{"version":"archived-rules-1","advice":"当时保存的建议","qualityNotes":["距离波动"],
            "metrics":[{"label":"存档指标","value":"原值"}],
            "sections":[{"title":"数据可靠性","rows":[{"label":"原始质量","value":"未记录"}]}]}}"""
        val entry = LocalRecordCatalog.entry("dryeye_123.json", record)!!
        assertEquals("archived-rules-1", entry.version)
        assertEquals("小明", entry.subjectLabel)
        assertEquals("当时保存的建议", entry.advice)
        assertTrue(entry.lines.none { "未见异常" in it })
        assertEquals(listOf("距离波动"), entry.qualityNotes)
        assertTrue(entry.sections.any { s -> s.rows.any { it.label == "存档指标" && it.value == "原值" } })
        val text = entry.reportText("2026-10-20 12:00:00 +0800")
        assertTrue("archived-rules-1" in text); assertTrue("未记录" in text); assertTrue("小明" in text)
        assertTrue("2026-10-20" in text)
        assertTrue(LocalRecordCatalog.entryJson("dryeye_123.json", record)!!.contains("\"qualityNotes\":[\"距离波动\"]"))
    }

    @Test fun oldRecordsKeepProfileAndMissingValuesWithoutInventingMeasurements() {
        val entry = LocalRecordCatalog.entry("presbyopia_1.json", """{"kind":"PRESBYOPIA",
            "profile":{"右眼球镜":"-2.50"},"assessment":{"riskLevel":"LOW","quality":"不足","modelVersion":"old-version","features":{}}}""")!!
        assertEquals("UNKNOWN", entry.level)
        assertEquals("需要重测", entry.badge)
        assertEquals("old-version", entry.version)
        assertTrue(entry.sections.any { s -> s.rows.any { it.label == "右眼球镜" && it.value == "-2.50" } })
        assertTrue(entry.sections.any { s -> s.rows.any { it.label == "调节幅度（D）" && it.value == "未记录" } })
    }
}
