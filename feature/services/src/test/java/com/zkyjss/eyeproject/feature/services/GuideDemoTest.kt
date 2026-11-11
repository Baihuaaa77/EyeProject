package com.zkyjss.eyeproject.feature.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 防止遮眼图片与教学时序错配，尤其不能通过镜像把协助遮眼变成自行遮眼。 */
class GuideDemoTest {
    @Test fun selfCoverKeepsTwoSecondUncoverAndOneSecondAlternation() {
        val expected = listOf(GuidePose.RIGHT, GuidePose.RIGHT, GuidePose.OPEN, GuidePose.OPEN,
            GuidePose.LEFT, GuidePose.LEFT, GuidePose.OPEN, GuidePose.OPEN,
            GuidePose.RIGHT, GuidePose.LEFT, GuidePose.RIGHT, GuidePose.LEFT)
        assertEquals(12000, guideCycleMs(GuideMotion.COVER_SELF))
        expected.forEachIndexed { second, pose ->
            assertEquals("第 ${second + 1} 秒", pose, guidePoseAt(GuideMotion.COVER_SELF, (second + 0.5f) / 12))
        }
    }

    @Test fun helperRemainsInEveryCoverAndUncoverPicture() {
        val expected = listOf(GuidePose.HELPER_RIGHT, GuidePose.HELPER_OPEN, GuidePose.HELPER_LEFT, GuidePose.HELPER_OPEN)
        listOf(1f, 3f, 5f, 7f).forEachIndexed { index, second ->
            assertEquals(expected[index], guidePoseAt(GuideMotion.COVER, second / 12))
        }
        for (second in 0..11) assertFalse(guidePoseAt(GuideMotion.COVER, (second + 0.5f) / 12) in
            listOf(GuidePose.OPEN, GuidePose.LEFT, GuidePose.RIGHT))
    }

    @Test fun nearVisionStartsWithLeftEyeCoveredAndBlinkingStopsInOpenPhase() {
        assertEquals(GuidePose.LEFT, guidePoseAt(GuideMotion.NEAR, 0.35f))
        assertEquals(GuidePose.OPEN, guidePoseAt(GuideMotion.NEAR, 0.65f))
        assertEquals(GuidePose.RIGHT, guidePoseAt(GuideMotion.NEAR, 0.8f))
        assertEquals(GuidePose.LEFT, guidePoseAt(GuideMotion.MOVE, 0.5f))
        assertEquals(GuidePose.BLINK, guidePoseAt(GuideMotion.FVA, 0.2f))
        for (phase in listOf(0.7f, 0.8f, 0.9f)) assertEquals(GuidePose.OPEN, guidePoseAt(GuideMotion.FVA, phase))
        for (motion in listOf(GuideMotion.BLINK, GuideMotion.OPEN, GuideMotion.FVA)) {
            assertEquals(GuidePose.OPEN, guidePoseAt(motion, guideScene(motion).keyframe))
        }
    }
}
