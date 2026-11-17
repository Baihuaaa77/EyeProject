package com.zkyjss.eyeproject.core.camera

import com.zkyjss.eyeproject.core.model.*
import kotlin.test.*

class ScreenReflectionTest {
    private val eye = EyeLandmarks("LEFT",listOf(Point2f(5f,32f),Point2f(20f,18f),Point2f(44f,18f),
        Point2f(59f,32f),Point2f(44f,46f),Point2f(20f,46f)),listOf(Point2f(32f,32f)),48f)
    private fun image(points: List<Triple<Int,Int,Int>>) = object : GrayImage {
        override val width = 64; override val height = 64
        override fun grayRegion(left: Int,top: Int,width: Int,height: Int) = IntArray(width*height) { i ->
            points.firstOrNull { it.first==left+i%width && it.second==top+i/width }?.third ?: 40
        }
    }
    @Test fun acceptsBrightCompactReflectionAndFollowsIrisTranslation() {
        val tracker = ScreenOpticsForTest()
        val first = assertNotNull(tracker.measure(image(listOf(Triple(34,28,245))),eye))
        assertTrue(first.usable)
        assertEquals(34f,first.cornealReflex?.x)
        val moved = eye.copy(irisPoints = eye.irisPoints.map { it.copy(x = it.x+2) },
            earPoints = eye.earPoints.map { it.copy(x = it.x+2) })
        assertEquals(36f,tracker.measure(image(listOf(Triple(36,28,245))),moved)?.cornealReflex?.x)
    }
    @Test fun eyelidAndSixtyPercentAreaMaskRejectOutsidePoints() {
        val tracker = ScreenOpticsForTest()
        assertNull(tracker.measure(image(listOf(Triple(53,32,245))),eye)?.cornealReflex)
        assertNull(tracker.measure(image(listOf(Triple(32,14,245))),eye)?.cornealReflex)
    }
    @Test fun blankAndDimReflectionsRemainUnmeasurable() {
        val tracker = ScreenOpticsForTest()
        assertNull(tracker.measure(image(emptyList()),eye)?.cornealReflex)
        assertNull(tracker.measure(image(listOf(Triple(26,30,135))),eye)?.cornealReflex)
        assertNotNull(tracker.measure(image(listOf(Triple(26,30,245))),eye)?.cornealReflex)
        assertNull(tracker.measure(image(emptyList()),eye)?.cornealReflex)
    }
    private class ScreenOpticsForTest {
        fun measure(image: GrayImage,eye: EyeLandmarks) = EyeOpticalAlgorithm.measureScreenPoint(image,eye)
    }
}
