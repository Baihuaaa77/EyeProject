package com.zkyjss.eyeproject.feature.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DryEyeReliabilityTest {
    private fun session() = DryEyeSession(
        naturalSeconds = 60, baselineLevel = 0.3f, trials = emptyList(), blinks = emptyList(),
        validFrameRatio = 1.0, distanceInRangeRatio = 1.0, headTurnRatio = 0.0,
        resolutionLimited = false, completed = true, analysisFps = 30.0,
    )

    @Test fun missingMeasurementsAreNotTurnedIntoZeroVariation() {
        val rows = DryEyeReliability.rows(session()).associate { it.label to it.value }
        assertEquals("未记录", rows["平均估计距离"])
        assertEquals("未记录", rows["距离波动（标准差）"])
        assertEquals("未记录", rows["无距离读数比例"])
        val measured = DryEyeReliability.rows(session().copy(distanceSamplesCm = listOf(38f, 42f, 0f))).associate { it.label to it.value }
        assertEquals("40.0 cm", measured["平均估计距离"])
        assertEquals("2.0 cm", measured["距离波动（标准差）"])
        assertEquals("33%", measured["无距离读数比例"])
    }

    @Test fun lowFpsInterruptionsAndResolutionAreExplainedWithoutNewRiskRules() {
        val notes = DryEyeReliability.notes(session().copy(analysisFps = 10.0, resolutionLimited = true, completed = false,
            blinks = listOf(1f, 62f)), listOf("测试未完整完成"))
        assertTrue(notes.any { "未完整完成" in it })
        assertTrue(notes.any { "可能漏记眨眼" in it })
        assertTrue(notes.any { "屏幕像素限制" in it })
        assertTrue(notes.any { "1 次眨眼" in it })
        assertTrue(notes.none { "通过现有" in it })
        assertTrue(DryEyeReliability.notes(session().copy(analysisFps = null), emptyList()).any { "未记录分析帧率" in it })
    }

    private fun trial(onset: Float, answer: Int?, rt: Long? = 600, presented: Long? = null) =
        DryEyeTrial(DryEyeStage.NATURAL, onset, 0.5f, 0, answer, rt, 40f, 20f, presentDelayMs = presented)

    @Test fun unsureWrongAndTimeoutAreCountedSeparately() {
        val trials = listOf(trial(1f, 0), trial(2f, 2), trial(3f, DryEyeTrial.UNSURE_ANSWER), trial(4f, null, null))
        val rows = DryEyeReliability.rows(session().copy(trials = trials)).associate { it.label to it.value }
        assertEquals("4 题 · 答对 1 · 答错 1 · 看不清 1 · 超时 1", rows["自然眨眼段作答"])
        assertEquals("界面未回报，反应时从发出显示时起算", rows["视标呈现确认"])
        val shown = DryEyeReliability.rows(session().copy(trials = trials.map { it.copy(presentDelayMs = 30) }))
            .associate { it.label to it.value }
        assertEquals("4/4 题由界面确认已画出（中位延迟 30 毫秒）", shown["视标呈现确认"])
    }

    @Test fun blinkIntervalBinsDescribeSamplesAndNeverInventZeros() {
        // 眨眼在 2 秒；视标在 1 秒（首次眨眼前）、2.5 秒（0–1 秒箱）、6.5 秒（≥4 秒箱）
        val trials = listOf(trial(1f, 0, 500), trial(2.5f, 0, 400), trial(6.5f, 1, 900))
        val rows = DryEyeReliability.blinkIntervalRows(session().copy(trials = trials, blinks = listOf(2f))).associate { it.label to it.value }
        assertEquals("1 题 · 答对 100% · 正确作答平均 400 毫秒", rows["距上次眨眼 0–1 秒"])
        assertEquals("无数据", rows["距上次眨眼 1–2 秒"])
        assertEquals("1 题 · 答对 0%", rows["距上次眨眼 ≥ 4 秒"])
        assertEquals("1 题 · 答对 100% · 正确作答平均 500 毫秒", rows["首次眨眼前"])
        assertTrue(DryEyeReliability.blinkIntervalRows(session()).isEmpty(), "没有作答时不出这一组")
    }
}
