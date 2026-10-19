package com.zkyjss.eyeproject.feature.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 资料在「我的」维护，测试只选成员：必需项、年龄换算、记录写入与苹果端桥接。 */
class SubjectProfileTest {
    private val now = 1_792_000_000_000L // 2026 年
    private fun member(relation: String = "SELF", fields: Map<String, String>) = SubjectProfile("m1", relation, "妈妈", fields, 0L)

    @Test fun eachTestOnlyRequiresWhatItsRulesUse() {
        val blank = member(fields = emptyMap())
        assertEquals(listOf("出生年份", "最近眼睛有这些感觉吗？"), SubjectProfiles.missing("DRY_EYE", blank, now))
        assertEquals(listOf("出生年份", "平时戴什么眼镜？", "知道自己有以下情况吗？"), SubjectProfiles.missing("PRESBYOPIA", blank, now))
        assertEquals(listOf("出生年份"), SubjectProfiles.missing("STRABISMUS", blank, now))
        // 报告了症状就要填频率
        val symptoms = member(fields = mapOf("出生年份" to "1990", "干眼症状" to "怕光"))
        assertEquals(listOf("这些感觉多久出现一次？"), SubjectProfiles.missing("DRY_EYE", symptoms, now))
        assertTrue(SubjectProfiles.missing("DRY_EYE", member(fields = mapOf("出生年份" to "1990", "干眼症状" to "都没有")), now).isEmpty())
        // 年龄不在适用范围
        val child = member("CHILD", mapOf("出生年份" to "2020", "日常矫正方式" to "不戴眼镜", "屈光情况" to "近视"))
        assertTrue(SubjectProfiles.missing("PRESBYOPIA", child, now).single().contains("18 岁以上"))
        assertTrue(SubjectProfiles.missing("STRABISMUS", child, now).isEmpty())
    }

    @Test fun testFieldsAddAgeAndRoundTripThroughJson() {
        val profile = member(fields = mapOf("出生年份" to "1980", "日常矫正方式" to "老花镜", "近期用眼情况" to ""))
        val fields = SubjectProfiles.testFields(profile, now)
        assertEquals("46", fields["年龄"])
        assertFalse("近期用眼情况" in fields, "未填的项不写入记录")
        assertEquals(listOf(profile), SubjectProfiles.fromJson(SubjectProfiles.toJson(listOf(profile))))
        val editor = Json.parseToJsonElement(SubjectProfiles.editorJson("""{"眼部手术":"白内障手术"}""")).toString()
        assertTrue("人工晶体类型" in editor, "选了白内障手术才出现人工晶体类型")
        assertFalse("激光手术类型" in editor)
    }

    @Test fun bridgeStartsOnlyWithACompleteMemberAndRecordsTheSubject() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var saved = ""
        val controller = MobileScreeningController({}, { _, json -> saved = json })
        try {
            controller.configureDisplay(1170, 2532, 18f)
            controller.selectTest("STRABISMUS")
            val blank = """{"id":"kid","relation":"CHILD","nickname":"小明","fields":{},"updatedAt":0}"""
            assertEquals("""["出生年份"]""", controller.missingJson(blank))
            assertFalse(controller.useSubject(blank), "缺出生年份不能开始")
            val ready = """{"id":"kid","relation":"CHILD","nickname":"小明","fields":{"出生年份":"2019"},"updatedAt":0}"""
            assertEquals("[]", controller.missingJson(ready))
            assertTrue(controller.useSubject(ready))
            val state = Json.parseToJsonElement(controller.snapshotJson()).jsonObject
            assertEquals("ENVIRONMENT", state["phase"]!!.jsonPrimitive.content)
            assertEquals("小明", state["subjectName"]!!.jsonPrimitive.content)
            // 老视：选人后还要选戴镜方式
            controller.selectTest("PRESBYOPIA")
            val adult = """{"id":"me","relation":"SELF","nickname":"我","fields":{"出生年份":"1970","日常矫正方式":"老花镜","屈光情况":"老花"},"updatedAt":0}"""
            assertFalse(controller.useSubject(adult))
            assertFalse(controller.proceedFromProfile())
            controller.setMode(PRESBYOPIA_MODE_STANDARD)
            assertTrue(controller.proceedFromProfile())
        } finally { controller.close(); Dispatchers.resetMain() }
        assertEquals("", saved)
    }
}
