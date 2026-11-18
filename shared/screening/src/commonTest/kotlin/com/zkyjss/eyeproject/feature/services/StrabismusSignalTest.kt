package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.model.*
import kotlin.test.*

class StrabismusSignalTest {
    @Test fun blinkHoldsLastEstimateAndRejectsTheFirstReopeningFrames() {
        val smoother = GazeSmoother()
        val blink = GazeBlinkGate()
        val stable = GazePoint(0.45f, 0.5f)
        repeat(3) { smoother.update(1_000L + it * 33,stable) }
        assertTrue(blink.isBlinking(1_100,0.12f))
        assertEquals(stable,smoother.heldDuringBlink(1_190))
        assertTrue(blink.isBlinking(1_200,0.19f))
        assertTrue(blink.isBlinking(1_233,0.19f))
        assertFalse(blink.isBlinking(1_266,0.20f))
        // 闭眼超过常规掉帧重置窗口，恢复后的首帧异常仍应被抑制。
        assertEquals(stable,smoother.update(1_340,GazePoint(0.9f,0.1f)))
        assertNull(smoother.heldDuringBlink(1_691))
    }

    @Test fun highFrequencySpikeIsSuppressedAndNewFixationSettles() {
        val smoother = GazeSmoother()
        val start = GazePoint(0.5f,0.5f)
        repeat(3) { smoother.update(it * 33L,start) }
        assertEquals(start,smoother.update(99,GazePoint(0.9f,0.1f)))
        assertEquals(start,smoother.update(132,start))
        val end = GazePoint(0.8f,0.3f)
        var filtered = start
        // 五帧窗口会延后约两帧，实际转向仍应在约 0.6 秒内稳定。
        repeat(20) { filtered = smoother.update(165L + it * 33,end)!! }
        assertEquals(end.x,filtered.x,0.025f)
        assertEquals(end.y,filtered.y,0.025f)
    }

    @Test fun codeChoosesModulatedPositionAcrossIrisMotionAndRejectsStableBrightSpot() {
        val verifier = ReflectionCodeVerifier()
        fun eye(x: Float) = EyeLandmarks("LEFT", emptyList(),listOf(Point2f(x,40f)),24f)
        fun optics(x: Float, on: Boolean) = OpticalEyeMeasurement(
            Point2f(x,40f),Point2f(x-5,40f),24f,25f,125f,null,irisVisibilityContrast = 25f,
            reflexCandidates = listOf(ReflexCandidate(Point2f(x-5,40f),125f,220f)) +
                if (on) listOf(ReflexCandidate(Point2f(x+4,40f),62f,151f)) else emptyList())
        val stage = ScreeningStep.FIXATION
        verifier.begin(stage)
        repeat(16) { chip ->
            val start = chip * ReflectionCodeVerifier.CHIP_MS
            val on = ReflectionCodeVerifier.lightOn(start)
            verifier.present(stage,start,on,start)
            repeat(4) { frame ->
                val x = 50f + (chip + frame) % 7
                val face = FaceMetrics(BoundingBox(0f,0f,100f,100f),eye(x),eye(x),optics(x,on),optics(x,false))
                verifier.observe(stage,start + 115 + frame * 35,face)
            }
        }
        val left = assertNotNull(verifier.measure(stage,eye(55f),optics(55f,true),true))
        assertEquals(Point2f(59f,40f),left.cornealReflex)
        assertTrue(left.reflexCodeMatched)
        val right = assertNotNull(verifier.measure(stage,eye(55f),optics(55f,true),false))
        assertFalse(right.reflexCodeMatched)
        assertNull(verifier.measure(stage,eye(55f),optics(55f,false),true)?.cornealReflex)
        assertFalse(assertNotNull(verifier.measure(ScreeningStep.FAR_FIXATION,eye(55f),optics(55f,true),true)).reflexCodeMatched)
    }

    @Test fun noCalibrationAndShortGapsRetainPositiveAlignmentEvidence() {
        val samples = (0..65).filter { it % 10 < 6 }.map { frame ->
            EyeMotionSample(frame * 50L,ScreeningStep.FIXATION,0f,0f,0f,0f,33f,leftNasal = 0.15f,
                rightNasal = 0.05f,leftVertical = 0f,rightVertical = 0f,
                gazeOnTarget = if (frame % 3 == 0) false else null,reflectionCodeMatched = true)
        }
        val result = ScreeningAssessmentModel.strabismus(ScreeningState(kind = ScreeningKind.STRABISMUS),samples,100,40)
        assertEquals(ScreeningRiskLevel.HIGH,result.riskLevel)
        assertEquals(1.0,result.features["risk_evidence_confidence"])
        assertTrue(result.evidence.first { it.key == "riskConfidence" }.details.any { "标定" in it })
    }
}
