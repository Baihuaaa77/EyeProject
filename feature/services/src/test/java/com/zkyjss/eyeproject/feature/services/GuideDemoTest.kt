package com.zkyjss.eyeproject.feature.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 防止遮眼图片与教学时序错配，尤其不能通过镜像把协助遮眼变成自行遮眼。 */
class GuideDemoTest {
    @Test fun selfCoverKeepsTwoSecondUncoverAndOneSecondAlternation() {
        val expected = listOf(GuidePose.LIGHTING_RIGHT, GuidePose.LIGHTING_RIGHT, GuidePose.LIGHTING_OPEN, GuidePose.LIGHTING_OPEN,
            GuidePose.LIGHTING_LEFT, GuidePose.LIGHTING_LEFT, GuidePose.LIGHTING_OPEN, GuidePose.LIGHTING_OPEN,
            GuidePose.LIGHTING_RIGHT, GuidePose.LIGHTING_LEFT, GuidePose.LIGHTING_RIGHT, GuidePose.LIGHTING_LEFT)
        assertEquals(12000, guideCycleMs(GuideMotion.COVER_SELF))
        expected.forEachIndexed { second, pose ->
            assertEquals("第 ${second + 1} 秒", pose, guidePoseAt(GuideMotion.COVER_SELF, (second + 0.5f) / 12))
        }
    }

    @Test fun helperChoiceNeverShowsHelperCoveringEyes() {
        for (helper in listOf("家长", "医生或护士", "其他", "自己完成")) {
            assertEquals(GuideMotion.COVER_SELF, guideMotion(ScreeningStep.COVER, mapOf("协助测试者" to helper)))
        }
        for (second in 0..11) {
            val phase = (second + 0.5f) / 12
            assertEquals(guidePoseAt(GuideMotion.COVER_SELF, phase), guidePoseAt(GuideMotion.COVER, phase))
            assertFalse(guidePoseLabel(guidePoseAt(GuideMotion.COVER, phase)).contains("协助者遮"))
        }
    }

    @Test fun nearVisionStartsWithLeftEyeCoveredAndBlinkingStopsInOpenPhase() {
        assertEquals(GuidePose.LEFT, guidePoseAt(GuideMotion.NEAR, 0.35f))
        assertEquals(GuidePose.OPEN, guidePoseAt(GuideMotion.NEAR, 0.65f))
        assertEquals(GuidePose.RIGHT, guidePoseAt(GuideMotion.NEAR, 0.8f))
        assertEquals(GuidePose.SIDE_NEAR, guidePoseAt(GuideMotion.MOVE, 0.5f))
        assertEquals(GuidePose.BLINK, guidePoseAt(GuideMotion.FVA, 0.2f))
        for (phase in listOf(0.7f, 0.8f, 0.9f)) assertEquals(GuidePose.OPEN, guidePoseAt(GuideMotion.FVA, phase))
        for (motion in listOf(GuideMotion.BLINK, GuideMotion.OPEN, GuideMotion.FVA)) {
            assertEquals(GuidePose.OPEN, guidePoseAt(motion, guideScene(motion).keyframe))
        }
    }
}
