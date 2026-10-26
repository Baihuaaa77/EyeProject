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
        // 资料里只要求长期信息；症状等改为测前现问
        assertEquals(listOf("出生年份"), SubjectProfiles.missing("DRY_EYE", blank, now))
        assertEquals(listOf("出生年份", "平时戴什么眼镜？", "知道自己有以下情况吗？"), SubjectProfiles.missing("PRESBYOPIA", blank, now))
        assertEquals(listOf("出生年份"), SubjectProfiles.missing("STRABISMUS", blank, now))
        // 年龄不在适用范围
        val child = member("CHILD", mapOf("出生年份" to "2020", "日常矫正方式" to "不戴眼镜", "屈光情况" to "近视"))
        assertTrue(SubjectProfiles.missing("PRESBYOPIA", child, now).single().contains("18 岁以上"))
        assertTrue(SubjectProfiles.missing("STRABISMUS", child, now).isEmpty())
    }

    /** 测前快问：干眼、眼位各两三题（报告了症状才追问频率），老视没有这一步；资料编辑页不再出现这些题。 */
    @Test fun preTestCheckAsksOnlyAFewChangingQuestions() {
        assertEquals(listOf("干眼症状", "近期用眼情况", "近期眼部用药"), PreTestCheck.visible("DRY_EYE", emptyMap()).map { it.key })
        val symptoms = mapOf("干眼症状" to "怕光")
        assertEquals(listOf("干眼症状", "症状频率", "近期用眼情况", "近期眼部用药"), PreTestCheck.visible("DRY_EYE", symptoms).map { it.key })
        assertFalse(PreTestCheck.complete("DRY_EYE", symptoms + ("近期眼部用药" to "没有") + ("近期用眼情况" to "差不多")), "报告了症状还要答频率")
        val noSymptom = mapOf("干眼症状" to "都没有", "症状频率" to "经常", "近期用眼情况" to "差不多", "近期眼部用药" to "没有")
        assertTrue(PreTestCheck.complete("DRY_EYE", noSymptom))
        assertFalse("症状频率" in PreTestCheck.clean("DRY_EYE", noSymptom), "改成都没有后不保留之前选的频率")
        assertEquals(listOf("近期重影", "协助测试者"), PreTestCheck.visible("STRABISMUS", emptyMap()).map { it.key })
        assertFalse(PreTestCheck.needed("PRESBYOPIA"))
        assertTrue(PreTestCheck.visible("DRY_EYE", emptyMap()).size <= 3 && PreTestCheck.questions("STRABISMUS").size <= 3, "平时只问两三题")
        val editorKeys = SubjectProfiles.questions.map { it.key }.toSet()
        assertTrue(PreTestCheck.VOLATILE_KEYS.none { it in editorKeys }, "随时变化的问题不放在资料里")
        // 旧资料里存的症状不带入测试
        val old = member(fields = mapOf("出生年份" to "1990", "干眼症状" to "怕光", "近期用眼情况" to "多很多"))
        assertEquals(setOf("出生年份", "年龄"), SubjectProfiles.testFields(old, now).keys)
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
            assertFalse(controller.useSubject(ready), "眼位选好人后先答测前快问")
            assertTrue("近期重影" in controller.checkQuestionsJson("{}"))
            assertFalse(controller.confirmCheck("""{"近期重影":"没有"}"""), "没答完不能开始")
            assertTrue(controller.confirmCheck("""{"近期重影":"没有","协助测试者":"家长"}"""))
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
