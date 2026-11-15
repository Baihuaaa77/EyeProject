package com.zkyjss.eyeproject.core.camera

import com.zkyjss.eyeproject.core.model.*
import kotlin.test.*

class FullIrisOpticalTest {
    @Test fun labFindsPeripheralGlintWithoutChangingMainAppSearch() {
        val eye = EyeLandmarks("LEFT", listOf(Point2f(5f, 30f), Point2f(20f, 10f), Point2f(40f, 10f),
            Point2f(55f, 30f), Point2f(40f, 50f), Point2f(20f, 50f)), listOf(Point2f(30f, 30f)), 40f)
        fun image(glintX: Int) = object : GrayImage {
            override val width = 64
            override val height = 64
            override fun grayRegion(left: Int, top: Int, width: Int, height: Int) = IntArray(width * height) { i ->
                if (left + i % width == glintX && top + i / width == 30) 245 else 40
            }
        }
        assertNull(EyeOpticalAlgorithm.measure(image(48), eye)?.cornealReflex)
        assertEquals(48f, assertNotNull(EyeOpticalAlgorithm.measureFullIris(image(48), eye)?.cornealReflex).x)
        assertNull(EyeOpticalAlgorithm.measureFullIris(image(52), eye)?.cornealReflex)
    }
}
