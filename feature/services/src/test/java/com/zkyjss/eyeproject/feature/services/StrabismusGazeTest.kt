package com.zkyjss.eyeproject.feature.services

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class StrabismusGazeTest {
    private fun calibration(mirrored: Boolean = false, onlyLeft: Boolean = false): List<GazeCalibrationSample> =
        StrabismusGaze.calibrationTargets.flatMapIndexed { index, p ->
            val target = GazePoint(0.1f + p.x * 0.8f, 0.3f + p.y * 0.4f)
            (0..7).map { i ->
                val jitter = (i - 4) * 0.0002f
                val eye = GazePoint((target.x - 0.5f) / (if (mirrored) -5f else 5f) + jitter,
                    (target.y - 0.5f) / 3f + 0.1f + jitter)
                GazeCalibrationSample(index, target, eye, if (onlyLeft) null else eye)
            }
        }

    @Test fun calibrationMapsScreenCoordinatesWithMirroredCameraAndOneUsableEye() {
        val model = StrabismusGaze.calibrate(calibration(mirrored = true, onlyLeft = true))!!
        val (left, right) = model.estimate(GazePoint(-0.04f, 0.15f), GazePoint(0.8f, 0.8f))
        assertEquals(0.7f, left!!.x, 0.01f)
        assertEquals(0.65f, left.y, 0.01f)
        assertNull(right)
        assertTrue(StrabismusGaze.onTarget(left, GazePoint(0.6f, 0.55f)))
        assertFalse(StrabismusGaze.onTarget(left, GazePoint(0.1f, 0.1f)))
        assertFalse(StrabismusGaze.onTarget(GazePoint(1.5f, 0.5f), GazePoint(1.3f, 0.5f)))
    }

    @Test fun nineWidePointsCoverTheCanvasAndRejectOneLookAway() {
        val targets = StrabismusGaze.calibrationTargets
        assertEquals(9, targets.size)
        assertTrue(targets.minOf { it.x } <= 0.1f && targets.maxOf { it.x } >= 0.9f)
        assertTrue(targets.minOf { it.y } <= 0.1f && targets.maxOf { it.y } >= 0.9f)
        // 第 6 个点期间看向别处：剔除离群点后映射仍应准确。
        val glanced = calibration().map { if (it.index == 5) it.copy(left = GazePoint(0.2f, -0.3f), right = GazePoint(0.2f, -0.3f)) else it }
        val model = StrabismusGaze.calibrate(glanced)!!
        val target = GazePoint(0.1f + 0.5f * 0.8f, 0.3f + 0.5f * 0.4f)
        val eye = GazePoint((target.x - 0.5f) / 5f, (target.y - 0.5f) / 3f + 0.1f)
        val estimate = model.estimate(eye, eye).first!!
        assertEquals(target.x, estimate.x, 0.01f)
        assertEquals(target.y, estimate.y, 0.01f)
    }

    @Test fun headTiltCrossTalkIsAbsorbedByTheAffineMapping() {
        // 头部轻微翻滚：水平眼动混入一部分垂直分量，反之亦然。
        val samples = StrabismusGaze.calibrationTargets.flatMapIndexed { index, p ->
            val hx = (p.x - 0.5f) / 5f; val vy = (p.y - 0.5f) / 3f
            val eye = GazePoint(hx + 0.15f * vy, vy - 0.2f * hx)
            List(6) { GazeCalibrationSample(index, p, eye, eye) }
        }
        val model = StrabismusGaze.calibrate(samples)!!
        val p = GazePoint(0.8f, 0.2f)
        val hx = (p.x - 0.5f) / 5f; val vy = (p.y - 0.5f) / 3f
        val estimate = model.estimate(GazePoint(hx + 0.15f * vy, vy - 0.2f * hx), null).first!!
        assertEquals(p.x, estimate.x, 0.005f)
        assertEquals(p.y, estimate.y, 0.005f)
    }

    @Test fun stationaryMissingAndNonFiniteCalibrationCannotPass() {
        assertNull(StrabismusGaze.calibrate(calibration().map { it.copy(left = GazePoint(0f, 0f), right = GazePoint(0f, 0f)) }))
        // 允许缺少个别点，但缺掉整列后边缘只能外推，必须拒绝。
        assertNotNull(StrabismusGaze.calibrate(calibration().filter { it.index != 4 }))
        val leftColumn = StrabismusGaze.calibrationTargets.indices.filter { StrabismusGaze.calibrationTargets[it].x < 0.3f }
        assertNull(StrabismusGaze.calibrate(calibration().filter { it.index !in leftColumn }))
        assertNull(StrabismusGaze.calibrate(calibration().filter { it.index !in listOf(1, 2, 3) }))
        assertNull(StrabismusGaze.calibrate(calibration().map { it.copy(left = GazePoint(Float.NaN, 0f), right = null) }))
    }

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
                targetScreenX = tx, targetScreenY = ty, leftGazeX = x, leftGazeY = y, rightGazeX = x, rightGazeY = y,
                gazeOnTarget = StrabismusGaze.onTarget(GazePoint(x, y), GazePoint(tx, ty)))
        }

    @Test fun followsActualTargetWithLatencyAndBriefMissingFrames() {
        val samples = pursuit(600).mapIndexed { i, s -> if (i % 25 == 0) s.copy(leftGazeX = null, leftGazeY = null,
            rightGazeX = null, rightGazeY = null, gazeOnTarget = null) else s }
        for (segment in 0..2) assertEquals("段 $segment", true, PursuitGazeCheck.follows(samples, segment))
    }

    @Test fun eitherEyeCanConfirmFollowingWithoutHidingTheOtherEyesAbnormalMotion() {
        val samples = pursuit().map { it.copy(rightGazeX = 0.9f, rightGazeY = 0.9f) }
        for (segment in 0..2) assertEquals(true, PursuitGazeCheck.follows(samples, segment))
        val rightOnly = pursuit().map { it.copy(leftGazeX = null, leftGazeY = null) }
        for (segment in 0..2) assertEquals(true, PursuitGazeCheck.follows(rightOnly, segment))
    }

    @Test fun synchronizedEyesStillCannotPassWithoutFollowingTheBall() {
        for (behavior in listOf("still", "jitter", "opposite", "elsewhere")) {
            val samples = pursuit(behavior = behavior)
            for (segment in 0..2) assertEquals("$behavior 段 $segment", false, PursuitGazeCheck.follows(samples, segment))
        }
    }

    @Test fun missingTargetOrInsufficientRecordingCannotProveFollowing() {
        assertNull(PursuitGazeCheck.follows(pursuit().take(10), 0))
        assertNull(PursuitGazeCheck.follows(pursuit().map { it.copy(targetScreenX = null) }, 0))
    }

    @Test fun invalidGazeMakesFinalAssessmentUnknownInsteadOfLowOrHighRisk() {
        val samples = pursuit(behavior = "still")
        val result = ScreeningAssessmentModel.strabismus(ScreeningState(gazeCalibrated = true), samples, 100, 100)
        assertEquals(ScreeningRiskLevel.UNKNOWN, result.riskLevel)
        assertTrue(result.findings.any { it.contains("跟随未获得有效轨迹") })
        assertTrue(PursuitGazeCheck.qualityReasons(pursuit(), calibrated = false).any { it.contains("校准未完成") })
    }
}
