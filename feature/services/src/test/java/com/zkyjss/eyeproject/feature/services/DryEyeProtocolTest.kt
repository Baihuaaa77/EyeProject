package com.zkyjss.eyeproject.feature.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DryEyeProtocolTest {

    @Test fun staircaseFollowsDocumentRule() {
        val stair = DryEyeStaircase()
        assertEquals(0.3f, stair.level)
        stair.record(true)
        assertEquals("答对一次：同大小再显示一次", 0.3f, stair.level)
        stair.record(true)
        assertEquals("连续答对两次：缩小一档", 0.4f, stair.level)
        stair.record(false)
        assertEquals("答错或无反应：增大一档", 0.3f, stair.level)
        stair.record(true)
        stair.record(false)
        assertEquals("答对后再错：仍增大一档", 0.25f, stair.level)
    }

    @Test fun staircaseIsCappedByDisplayableSize() {
        val stair = DryEyeStaircase(startIndex = 6)
        stair.record(true, maxIndex = 6)
        stair.record(true, maxIndex = 6)
        assertEquals(0.4f, stair.level)
    }

    @Test fun longestOpenIntervalIncludesPhaseEdges() {
        assertEquals(12.0, DryEyeAssessment.longestOpenInterval(listOf(3f, 15f), 20.0), 0.001)
        assertEquals(20.0, DryEyeAssessment.longestOpenInterval(emptyList(), 20.0), 0.001)
    }

    private fun trials(stage: DryEyeStage, from: Float, to: Float, level: (Float) -> Float = { 0.5f }) =
        generateSequence(from) { it + 2.3f }.takeWhile { it < to }
            .map { DryEyeTrial(stage, it, level(it), 0, 0, 900L, 40f, 30f) }.toList()

    private fun session(blinks: List<Float>, openLevel: (Float) -> Float = { 0.5f }, completed: Boolean = true) = DryEyeSession(
        naturalSeconds = 60, baselineLevel = 0.5f,
        trials = trials(DryEyeStage.NATURAL, 0f, 60f) + trials(DryEyeStage.OPEN, 60f, 80f, openLevel),
        blinks = blinks, validFrameRatio = 0.95, distanceInRangeRatio = 0.9, headTurnRatio = 0.05,
        resolutionLimited = false, completed = completed,
    )

    @Test fun normalSessionIsLowRisk() {
        // 自然眨眼 12 次/分钟；睁眼阶段只在 15 秒时眨一次 → 最长持续睁眼 15 秒
        val blinks = (1..12).map { it * 5f - 2f } + listOf(75f)
        val report = DryEyeAssessment.evaluate(session(blinks), emptyMap())
        assertTrue(report.valid)
        assertEquals(ScreeningRiskLevel.LOW, report.riskLevel)
        assertEquals(12.0, report.blinkRatePerMin!!, 0.001)
        assertEquals(15.0, report.longestOpenSec!!, 0.001)
        assertEquals(1.0, report.vmr!!, 0.001)
    }

    @Test fun frequentBlinkingShortOpenAndDeclineAreHighRisk() {
        val blinks = (1..25).map { it * 2.4f } + listOf(63f, 67f, 71f, 75f)
        // 睁眼阶段视标逐渐放大到低 3 档
        val report = DryEyeAssessment.evaluate(session(blinks, openLevel = { t -> if (t < 66f) 0.5f else 0.25f }), emptyMap())
        assertEquals(ScreeningRiskLevel.HIGH, report.riskLevel)
        assertTrue(report.abnormal.any { it.contains("眨眼") })
        assertTrue(report.abnormal.any { it.contains("最长持续睁眼") })
        assertTrue(report.declineSec != null)
    }

    @Test fun incompleteSessionIsInvalid() {
        val report = DryEyeAssessment.evaluate(session(emptyList(), completed = false), emptyMap())
        assertFalse(report.valid)
        assertEquals(ScreeningRiskLevel.UNKNOWN, report.riskLevel)
        assertTrue(report.validityReasons.contains("测试未完整完成"))
    }

    @Test fun questionnaireCoversDocumentItems() {
        val labels = dryEyeQuestions.map { it.label }
        listOf("年龄", "性别", "眼部疾病", "眼手术或外伤史", "佩戴隐形眼镜史", "近期眼部用药", "屈光矫正状态",
            "基础视力", "近期用眼情况", "每日电子产品使用", "是否眼部化妆", "睡眠时长", "全身疾病", "干眼症状")
            .forEach { assertTrue("缺少 $it", it in labels) }
    }
}
