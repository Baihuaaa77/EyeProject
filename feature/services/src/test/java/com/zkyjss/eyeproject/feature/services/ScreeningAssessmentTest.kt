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

    /** 每只眼 3 次移近：[nearPointCm]、+1 cm、[nearPointCm]。 */
    private fun presbyopiaState(age: Int, level: Float, nearPointCm: Float, path: PresbyopiaPath = PresbyopiaPath.A1): ScreeningState {
        val answers = VisionEye.entries.flatMap { eye ->
            (0 until 4).map { VisionAnswer(ScreeningStep.NEAR_VISION, 40f, level, 0, 0, true, 800L, 20f, eye) }
        } + (0 until 8).map { index ->
            VisionAnswer(ScreeningStep.FLIPPER, if (index % 2 == 0) 50f else 25f, 0.2f, 0, 0, true, 800L, 20f)
        }
        return ScreeningState(
            presbyopiaPath = path, fields = mapOf("年龄" to "$age"), answers = answers,
            nearPoints = if (!path.standardAmplitude) emptyList() else PresbyopiaProtocol.PUSH_EYES.flatMap { eye ->
                listOf(nearPointCm, nearPointCm + 1f, nearPointCm).map { NearPoint(it, 1.5f, 50f, eye = eye) } },
            curvePoints = listOf(CurvePoint(67f, 0.6f), CurvePoint(50f, 0.5f), CurvePoint(33f, 0.4f)),
        )
    }

    @Test fun presbyopiaUsesHofstetterMinimumForAgeMatching() {
        // 45 岁：最小调节幅度 15 − 0.25×45 = 3.75 D
        val matched = ScreeningAssessmentModel.presbyopia(presbyopiaState(45, 0.8f, 25f))
        assertEquals(ScreeningRiskLevel.LOW, matched.riskLevel)
        assertEquals("调节能力与实际年龄基本匹配", matched.category)
        // 每只眼 4 / 3.85 / 4 D 取算术平均，换回近点
        assertEquals(100.0 / ((8 + 100.0 / 26) / 3), matched.features["near_point_cm"]!!, 0.001)
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
        val result = ScreeningAssessmentModel.presbyopia(presbyopiaState(50, 0.8f, 30f, path = PresbyopiaPath.B))
        assertEquals(null, result.features["amplitude_age_years"])
        assertTrue(result.limitations.any { it.contains("近用眼镜") })
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
                leftNasal = fixNasal + 0.1f, rightNasal = 0.1f, leftVertical = 0f, rightVertical = 0f, gazeOnTarget = true)
        }
        val marks = listOf(
            CoverMark("移开遮盖，双眼注视", 5_000L, uncovered = VisionEye.RIGHT),
            CoverMark("移开遮盖，双眼注视", 6_000L, uncovered = VisionEye.LEFT),
        )
        val cover = listOf(5_000L, 6_000L).flatMap { t ->
            listOf(
                EyeMotionSample(t + 100, ScreeningStep.COVER, 0f, 0f, 0f, 0f, 33f, leftNasal = 0.1f, rightNasal = 0.1f, leftVertical = 0f, rightVertical = 0f, gazeOnTarget = true),
                EyeMotionSample(t + 400, ScreeningStep.COVER, 0f, 0f, 0f, 0f, 33f, leftNasal = 0.1f + refix, rightNasal = 0.1f + refix, leftVertical = 0f, rightVertical = 0f, gazeOnTarget = true),
            )
        }
        val pursuit = (0 until 72).map { i ->
            val seg = i / 24
            val a = sin(i * 0.3f) * 0.2f
            val b = if (coordinated) a else cos(i * 0.3f) * 0.2f
            val (lx, ly) = when (seg) { 0 -> a to 0f; 1 -> 0f to a; else -> a to a }
            val (rx, ry) = when (seg) { 0 -> b to 0f; 1 -> 0f to b; else -> b to b }
            EyeMotionSample(7_000L + i * 100L, ScreeningStep.PURSUIT, lx, ly, rx, ry, 33f, pursuitSegment = seg,
                targetScreenX = 0.5f + lx, targetScreenY = 0.5f + ly,
                leftGazeX = 0.5f + lx, leftGazeY = 0.5f + ly, rightGazeX = 0.5f + rx, rightGazeY = 0.5f + ry,
                gazeOnTarget = true)
        }
        return fixed + cover + pursuit to marks
    }

    private fun strabismus(fixNasal: Float, refix: Float, coordinated: Boolean = true): ScreeningAssessment {
        val (samples, marks) = strabismusSamples(fixNasal, refix, coordinated)
        return ScreeningAssessmentModel.strabismus(ScreeningState(kind = ScreeningKind.STRABISMUS, coverMarks = marks, gazeCalibrated = true), samples, 100, 90)
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
