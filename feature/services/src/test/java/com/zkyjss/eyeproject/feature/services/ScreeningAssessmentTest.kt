package com.zkyjss.eyeproject.feature.services

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreeningAssessmentTest {

    // region 老视

    @Test fun missingMeasurementsAreRejected() {
        val result = ScreeningAssessmentModel.presbyopia(ScreeningState())
        assertEquals("不足", result.quality)
        assertEquals(ScreeningRiskLevel.UNKNOWN, result.riskLevel)
        assertEquals("暂无法评估，请复测", result.category)
    }

    private fun presbyopiaState(age: Int, level: Float, nearPointCm: Float, mode: String = "标准模式：远用矫正，无近用 Add"): ScreeningState {
        val answers = VisionEye.entries.flatMap { eye ->
            (0 until 4).map { VisionAnswer(ScreeningStep.NEAR_VISION, 40f, level, 0, 0, true, 800L, 20f, eye) }
        } + (0 until 8).map { index ->
            VisionAnswer(ScreeningStep.FLIPPER, if (index % 2 == 0) 50f else 25f, 0.2f, 0, 0, true, 800L, 20f)
        }
        return ScreeningState(
            mode = mode, fields = mapOf("年龄" to "$age"), answers = answers,
            nearPoints = listOf(NearPoint(nearPointCm, 3f, 50f), NearPoint(nearPointCm + 1f, 3f, 50f)),
            curvePoints = listOf(CurvePoint(60f, 0.6f), CurvePoint(33f, 0.5f), CurvePoint(25f, 0.4f)),
        )
    }

    @Test fun presbyopiaUsesHofstetterMinimumForAgeMatching() {
        // 45 岁：最小调节幅度 15 − 0.25×45 = 3.75 D
        val matched = ScreeningAssessmentModel.presbyopia(presbyopiaState(45, 0.8f, 25f))
        assertEquals(ScreeningRiskLevel.LOW, matched.riskLevel)
        assertEquals("调节能力与实际年龄基本匹配", matched.category)
        assertEquals(25.5, matched.features["near_point_cm"]!!, 0.001)
        assertEquals(3.75, matched.features["hofstetter_min_d"]!!, 0.001)

        val lower = ScreeningAssessmentModel.presbyopia(presbyopiaState(45, 0.8f, 42f))
        assertEquals(ScreeningRiskLevel.MEDIUM, lower.riskLevel)
        assertEquals("调节功能较同龄人偏低", lower.category)

        assertEquals(ScreeningRiskLevel.HIGH, ScreeningAssessmentModel.presbyopia(presbyopiaState(45, 0.4f, 42f)).riskLevel)
    }

    @Test fun earlyPresbyopiaLikeDeclineIsFlaggedUnder45() {
        // 40 岁、近点 25 cm → 约 3.9 D，不足 40 cm 舒适阅读所需约 5 D
        val result = ScreeningAssessmentModel.presbyopia(presbyopiaState(40, 0.8f, 25f))
        assertEquals("存在较早老视样功能下降", result.category)
        assertEquals(ScreeningRiskLevel.HIGH, result.riskLevel)
    }

    @Test fun dailyModeDoesNotReportAmplitudeAge() {
        val result = ScreeningAssessmentModel.presbyopia(presbyopiaState(50, 0.8f, 30f, mode = "日常模式：保持近用或渐进矫正"))
        assertEquals(null, result.features["amplitude_age_years"])
        assertTrue(result.limitations.any { it.contains("日常近用") })
    }

    // endregion

    // region 斜视

    @Test fun strabismusWithoutReliableReflexFramesIsRejected() {
        val result = ScreeningAssessmentModel.strabismus(ScreeningState(), emptyList(), 100, 10)
        assertEquals("不足", result.quality)
        assertEquals(ScreeningRiskLevel.UNKNOWN, result.riskLevel)
        assertEquals("测试质量不佳，请重新测试", result.category)
    }

    /** 构造三项任务的样本：注视鼻侧偏心差 [fixNasal]、遮盖解除后偏心变化 [refix]、追踪是否协调。 */
    private fun strabismusSamples(fixNasal: Float, refix: Float, coordinated: Boolean): Pair<List<EyeMotionSample>, List<CoverMark>> {
        val fixed = (0..39).map {
            EyeMotionSample(it * 100L, ScreeningStep.FIXATION, 0f, 0f, 0f, 0f, 33f,
                leftNasal = fixNasal + 0.1f, rightNasal = 0.1f, leftVertical = 0f, rightVertical = 0f)
        }
        val marks = listOf(
            CoverMark("移开遮盖，双眼注视", 5_000L, uncovered = VisionEye.RIGHT),
            CoverMark("移开遮盖，双眼注视", 6_000L, uncovered = VisionEye.LEFT),
        )
        val cover = listOf(5_000L, 6_000L).flatMap { t ->
            listOf(
                EyeMotionSample(t + 100, ScreeningStep.COVER, 0f, 0f, 0f, 0f, 33f, leftNasal = 0.1f, rightNasal = 0.1f, leftVertical = 0f, rightVertical = 0f),
                EyeMotionSample(t + 400, ScreeningStep.COVER, 0f, 0f, 0f, 0f, 33f, leftNasal = 0.1f + refix, rightNasal = 0.1f + refix, leftVertical = 0f, rightVertical = 0f),
            )
        }
        val pursuit = (0 until 60).map { i ->
            val seg = i / 20
            val a = sin(i * 0.3f) * 0.2f
            val b = if (coordinated) a else cos(i * 0.3f) * 0.2f
            val (lx, ly) = when (seg) { 0 -> a to 0f; 1 -> 0f to a; else -> a to a }
            val (rx, ry) = when (seg) { 0 -> b to 0f; 1 -> 0f to b; else -> b to b }
            EyeMotionSample(7_000L + i * 100L, ScreeningStep.PURSUIT, lx, ly, rx, ry, 33f, pursuitSegment = seg)
        }
        return fixed + cover + pursuit to marks
    }

    private fun strabismus(fixNasal: Float, refix: Float, coordinated: Boolean = true): ScreeningAssessment {
        val (samples, marks) = strabismusSamples(fixNasal, refix, coordinated)
        return ScreeningAssessmentModel.strabismus(ScreeningState(kind = ScreeningKind.STRABISMUS, coverMarks = marks), samples, 100, 90)
    }

    @Test fun fixationDeviationAboveTenPrismIsHighSuspicion() {
        // 0.08 虹膜直径 × 11.7 mm × 15Δ/mm ≈ 14Δ
        val result = strabismus(fixNasal = 0.08f, refix = 0f)
        assertEquals(14.04, result.features["fixation_hde_prism"]!!, 0.05)
        assertEquals(ScreeningRiskLevel.HIGH, result.riskLevel)
        assertEquals("高度疑似斜视", result.category)
    }

    @Test fun commonKappaOffsetIsNotMistakenForDeviation() {
        // 双眼映光同样偏鼻侧（κ 角）时，鼻侧坐标差为 0
        val result = strabismus(fixNasal = 0f, refix = 0f)
        assertEquals(0.0, result.features["fixation_hde_prism"]!!, 0.001)
        assertEquals(ScreeningRiskLevel.LOW, result.riskLevel)
        assertEquals("未见异常", result.category)
    }

    @Test fun coverRefixationAboveSixPrismIsSuspicious() {
        // 0.04 × 175.5 ≈ 7Δ ≥ 6Δ
        val result = strabismus(fixNasal = 0f, refix = 0.04f)
        assertEquals(ScreeningRiskLevel.MEDIUM, result.riskLevel)
        assertEquals("可疑阳性", result.category)
    }

    @Test fun bsiFollowsDocumentFormula() {
        val good = StrabismusMeasures.compute(strabismusSamples(0f, 0f, coordinated = true).first, emptyList())
        assertEquals(1.0, good.bsi!!, 0.01)
        val poor = StrabismusMeasures.compute(strabismusSamples(0f, 0f, coordinated = false).first, emptyList())
        assertTrue("BSI=${poor.bsi}", poor.bsi!! < StrabismusProtocol.BSI_ABNORMAL)
    }

    @Test fun pursuitFollowsDocumentTrajectory() {
        // 水平正弦周期 4 秒：1 秒时达到 +10°
        assertEquals(10f, StrabismusProtocol.pursuitTarget(1f).first, 0.01f)
        assertEquals(1, StrabismusProtocol.pursuitTarget(9f).third)
        // 斜向直线往复：沿 45° 方向，两个分量等幅反号
        val oblique = StrabismusProtocol.pursuitTarget(16.75f)
        assertEquals(2, oblique.third)
        assertEquals(7.07f, oblique.first, 0.01f)
        assertEquals(-7.07f, oblique.second, 0.01f)
    }

    // endregion
}
