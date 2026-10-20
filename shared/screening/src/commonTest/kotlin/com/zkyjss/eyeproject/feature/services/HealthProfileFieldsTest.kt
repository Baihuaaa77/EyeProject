package com.zkyjss.eyeproject.feature.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HealthProfileFieldsTest {
    @Test fun signedRefractionAndTextAreNotStrippedByTheEditor() {
        val sphere = SubjectProfiles.questions.first { it.key == "右眼球镜" }
        assertEquals("-2.50", sphere.filterInput("-2.50"))
        assertTrue(sphere.validate("-2.50"))
        assertTrue(sphere.validate("+3.00"))
        assertFalse(sphere.validate("NaN"))
        assertFalse(sphere.validate("--2.5"))
        assertFalse(sphere.validate("300"))
        val note = SubjectProfiles.questions.first { it.key == "过敏史" }
        assertEquals("某眼药水过敏", note.filterInput("某眼药水过敏"))
        val axis = SubjectProfiles.questions.first { it.key == "左眼轴位" }
        assertTrue(axis.validate("0")); assertTrue(axis.validate("180")); assertFalse(axis.validate("181"))
        val acuity = SubjectProfiles.questions.first { it.key == "右眼裸眼视力" }
        assertTrue(acuity.validate("0.5")); assertFalse(acuity.validate("4.7"))
    }

    @Test fun datesAndMedicationOrderAreValidatedOnBothPlatforms() {
        assertTrue(HealthProfileFields.validDate("2024-02-29"))
        assertFalse(HealthProfileFields.validDate("2026-02-29"))
        assertFalse(HealthProfileFields.validDate("2026-04-31"))
        assertFalse(HealthProfileFields.validDate("2026-1-01"))
        val fields = mapOf("近期眼部用药" to "人工泪液", "眼药水开始日期" to "2026-10-20", "眼药水结束日期" to "2026-10-19")
        assertEquals("眼药水结束日期不能早于开始日期", SubjectProfiles.validationError(fields))
        assertNull(SubjectProfiles.validationError(fields + ("眼药水结束日期" to "2026-10-20")))
        assertNull(SubjectProfiles.validationError(fields + ("近期眼部用药" to "没有")), "隐藏的历史输入不阻止清理和保存")
        val profile = SubjectProfile("me", "SELF", "我", fields, 0)
        val json = SubjectProfiles.toJson(listOf(profile))
        assertEquals(profile, SubjectProfiles.fromJson(json).single())
        assertNotNull(SubjectProfiles.validationErrorJson("""{"右眼球镜":"NaN"}"""))
    }

    @Test fun optionalFieldsFitTheSyncContractAndDoNotChangeTestRequirements() {
        assertTrue(SubjectProfiles.questions.size + 1 <= 64, "预留派生年龄后也不能超过服务端 64 项上限")
        assertEquals(SubjectProfiles.questions.size, SubjectProfiles.questions.map { it.key }.toSet().size)
        assertNull(SubjectProfiles.validationError(emptyMap()))
        val old = SubjectProfile("old", "SELF", "我", mapOf("出生年份" to "1980", "干眼症状" to "都没有"), 0)
        assertTrue(SubjectProfiles.missing("DRY_EYE", old, 1_792_000_000_000L).isEmpty())
        val editor = SubjectProfiles.editorJson("""{"近期眼部用药":"人工泪液","佩戴隐形眼镜史":"现在在戴"}""")
        assertTrue("眼药水名称" in editor); assertTrue("隐形眼镜类型" in editor)
        assertTrue("\"inputKind\":\"DECIMAL\"" in editor)
        assertFalse("眼药水名称" in SubjectProfiles.editorJson("{}"))
    }
}
