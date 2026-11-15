package com.zkyjss.eyeproject.core.camera

import com.zkyjss.eyeproject.core.model.*
import kotlin.math.hypot
import kotlin.test.*

class EyeVisibilityTest {
    @Test fun measuresVisibleIrisWithoutGlintAndUniformOccluderSeparately() {
        val eye = EyeLandmarks("LEFT", listOf(Point2f(20f, 40f), Point2f(40f, 30f), Point2f(80f, 30f),
            Point2f(100f, 40f), Point2f(80f, 50f), Point2f(40f, 50f)), listOf(Point2f(60f, 40f)), 24f)
        fun image(covered: Boolean) = object : GrayImage {
            override val width = 120
            override val height = 80
            override fun grayRegion(left: Int, top: Int, width: Int, height: Int) = IntArray(width * height) { i ->
                if (!covered && hypot((left + i % width - 60).toDouble(), (top + i / width - 40).toDouble()) < 12) 40 else 160
            }
        }
        val open = assertNotNull(EyeOpticalAlgorithm.measure(image(false), eye))
        assertFalse(open.usable, "无反光点时不能用于眼位光学测量")
        assertTrue(assertNotNull(open.irisVisibilityContrast) > 80f, "仍可独立确认虹膜像素可见")
        val hidden = assertNotNull(EyeOpticalAlgorithm.measure(image(true), eye))
        assertEquals(0f, hidden.irisVisibilityContrast, "均匀遮挡物不会被关键点预测冒充可见虹膜")
        val smallPrediction = assertNotNull(EyeOpticalAlgorithm.measure(image(true), eye.copy(irisDiameterPx = 8f)))
        assertFalse(smallPrediction.usable, "虹膜像素不足仍拒绝光学测量")
        assertEquals(0f, smallPrediction.irisVisibilityContrast, "光学拒判不丢掉独立的遮盖像素证据")
    }
}
