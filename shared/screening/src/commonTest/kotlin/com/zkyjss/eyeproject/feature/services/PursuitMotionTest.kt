package com.zkyjss.eyeproject.feature.services

import kotlin.test.*
import kotlin.math.sin

class PursuitMotionTest {
    private fun pursuit(delayMs: Long = 0, behavior: String = "follow"): List<EyeMotionSample> =
        (0 until 275).map { i ->
            val time = i * 80L
            val target = StrabismusProtocol.pursuitTarget(time / 1000f)
            val delayed = StrabismusProtocol.pursuitTarget((time - delayMs).coerceAtLeast(0L) / 1000f)
            val tx = 0.5f + target.first * 0.025f; val ty = 0.5f - target.second * 0.015f
            val dx = delayed.first * 0.025f; val dy = -delayed.second * 0.015f
            val (x, y) = when (behavior) {
                "still" -> 0.5f to 0.5f
                "jitter" -> (0.5f + sin(i * 1.2f) * 0.025f) to (0.5f + sin(i * 0.9f) * 0.02f)
                "opposite" -> (0.5f - dx) to (0.5f - dy)
                "elsewhere" -> (1.1f + dx) to (1.1f + dy)
                else -> (0.5f + dx) to (0.5f + dy)
            }
            EyeMotionSample(time, ScreeningStep.PURSUIT, x, y, x, y, 33f, pursuitSegment = target.third,
                targetScreenX = tx, targetScreenY = ty)
        }

    @Test fun followsActualTargetWithLatencyAndBriefMissingFrames() {
        val samples = pursuit(600).mapIndexed { i, s -> if (i % 25 == 0) s.copy(leftX = Float.NaN, leftY = Float.NaN, rightX = Float.NaN, rightY = Float.NaN) else s }
        for (segment in 0..2) assertEquals(true, PursuitMotionCheck.follows(samples, segment), "段 $segment")
    }

    @Test fun eitherEyeCanConfirmFollowingWithoutHidingTheOtherEyesAbnormalMotion() {
        val samples = pursuit().map { it.copy(rightX = 0.9f, rightY = 0.9f) }
        for (segment in 0..2) assertEquals(true, PursuitMotionCheck.follows(samples, segment))
        for (segment in 0..2) assertEquals(true, PursuitMotionCheck.follows(pursuit(behavior = "elsewhere"), segment))
        val rightOnly = pursuit().map { it.copy(leftX = Float.NaN, leftY = Float.NaN) }
        for (segment in 0..2) assertEquals(true, PursuitMotionCheck.follows(rightOnly, segment))
    }

    @Test fun synchronizedEyesStillCannotPassWithoutFollowingTheBall() {
        for (behavior in listOf("still", "jitter", "opposite")) {
            val samples = pursuit(behavior = behavior)
            for (segment in 0..2) assertEquals(false, PursuitMotionCheck.follows(samples, segment), "$behavior 段 $segment")
        }
    }

    @Test fun missingTargetOrInsufficientRecordingCannotProveFollowing() {
        assertNull(PursuitMotionCheck.follows(pursuit().take(10), 0))
        assertNull(PursuitMotionCheck.follows(pursuit().map { it.copy(targetScreenX = null) }, 0))
    }

    @Test fun oneEyeNotMovingIncreasesRiskWithoutAnyOpticalEvidence() {
        val samples = pursuit().map { it.copy(rightX = 0f, rightY = 0f) }
        val assessment = ScreeningAssessmentModel.strabismus(ScreeningState(kind = ScreeningKind.STRABISMUS), samples, 0, 0)
        assertEquals(ScreeningRiskLevel.MEDIUM, assessment.riskLevel)
        assertEquals(EvidenceStatus.ABNORMAL, assessment.evidence.first { it.key == "coordination" }.status)
        assertEquals(1.0, assessment.features["risk_evidence_confidence"])
        assertTrue(assessment.findings.first().contains("双眼运动明显不同步"))
    }

    @Test fun stationaryEyesWithoutOpticalEvidenceReportsUndeterminedRisk() {
        val samples = pursuit(behavior = "still")
        val result = ScreeningAssessmentModel.strabismus(ScreeningState(kind = ScreeningKind.STRABISMUS), samples, 100, 100)
        assertEquals(ScreeningRiskLevel.UNCERTAIN, result.riskLevel)
        assertEquals(EvidenceStatus.UNAVAILABLE, result.evidence.first { it.key == "coordination" }.status)
    }
}
