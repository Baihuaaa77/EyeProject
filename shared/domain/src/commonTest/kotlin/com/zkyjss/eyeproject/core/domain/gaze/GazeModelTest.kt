package com.zkyjss.eyeproject.core.domain.gaze

import com.zkyjss.eyeproject.core.model.BoundingBox
import com.zkyjss.eyeproject.core.model.EyeLandmarks
import com.zkyjss.eyeproject.core.model.FaceMetrics
import com.zkyjss.eyeproject.core.model.HeadPoseEstimate
import com.zkyjss.eyeproject.core.model.Point2f
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class GazeModelTest {
    private val pose = RelativePose(ScreenGazePoint(0f, 0f), 0.32f, HeadPoseEstimate(31f, -13f, 42f))
    private val targets = listOf(0.1f, 0.5f, 0.9f).flatMap { x ->
        listOf(0.1f, 0.5f, 0.9f).map { y -> ScreenGazePoint(x, y) }
    }

    @Test
    fun projectsUnseenTargetFromObliqueSingleEyeCalibration() {
        fun signal(point: ScreenGazePoint) = ScreenGazePoint(
            0.12f * point.x + 0.016f * point.y - 0.04f,
            -0.008f * point.x + 0.061f * point.y + 0.02f,
        )
        val anchors = targets.map { target ->
            GazeAnchor(target, List(7) { GazeObservation(pose, signal(target), null) })
        }
        val profile = requireNotNull(GazeProfile.fit(pose, anchors))
        val unseen = ScreenGazePoint(0.27f, 0.71f)
        assertTrue(requireNotNull(profile.project(GazeObservation(pose, signal(unseen), null))).distance(unseen) < 0.0001f)
        val outside = ScreenGazePoint(-0.15f, 0.55f)
        assertTrue(!requireNotNull(profile.project(GazeObservation(pose, signal(outside), null))).isOnScreen())
    }

    @Test
    fun rejectsCalibrationWithNoVerticalEyeMotion() {
        val anchors = targets.map { target ->
            GazeAnchor(target, List(7) { GazeObservation(pose, ScreenGazePoint(target.x / 10f, 0f), null) })
        }
        assertNull(GazeProfile.fit(pose, anchors))
    }

    @Test
    fun eyeCoordinatesIgnoreImageRotationAndRejectClosedEye() {
        val open = listOf(Point2f(70f, 100f), Point2f(82f, 93f), Point2f(98f, 93f),
            Point2f(110f, 100f), Point2f(98f, 107f), Point2f(82f, 107f))
        val iris = Point2f(94f, 102f)
        fun rotate(point: Point2f) = Point2f(200f - point.y, point.x)
        fun face(outline: List<Point2f>, center: Point2f): FaceMetrics {
            val eye = EyeLandmarks("LEFT", outline, listOf(center), 12f)
            return FaceMetrics(BoundingBox(45f, 45f, 155f, 155f), eye, null, headPose = pose.angles)
        }
        val normal = requireNotNull(GazeObservation.from(face(open, iris), 200, 200)?.left)
        val rotated = requireNotNull(GazeObservation.from(face(open.map(::rotate), rotate(iris)), 200, 200)?.left)
        assertEquals(normal.x, rotated.x, 0.00001f)
        assertEquals(normal.y, rotated.y, 0.00001f)
        assertNull(GazeObservation.from(face(open.map { it.copy(y = 100f) }, iris), 200, 200))
    }

    @Test
    fun poseDistanceWrapsRollAcrossFullTurn() {
        val first = pose.copy(angles = pose.angles.copy(rollDeg = 179f))
        val second = pose.copy(angles = pose.angles.copy(rollDeg = -179f))
        assertEquals(2f / 12f, first.distance(second), 0.00001f)
        assertTrue(pose.distance(second) > 1f)
    }
}
