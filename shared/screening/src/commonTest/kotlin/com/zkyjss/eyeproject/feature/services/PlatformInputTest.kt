package com.zkyjss.eyeproject.feature.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 平台输入的容错：苹果逐帧内参抖动、TTS 读法。 */
class PlatformInputTest {
    @Test fun focalJitterDoesNotCountAsRecalibration() {
        assertTrue(focalChanged(0f, 1_000f))
        assertFalse(focalChanged(1_000f, 1_004f))
        assertFalse(focalChanged(1_000f, 985f))
        assertTrue(focalChanged(1_000f, 1_050f))
        assertFalse(focalChanged(1_000f, Float.NaN))
        assertFalse(focalChanged(1_000f, 0f))
    }

    @Test fun optotypeLetterIsSpokenAsOptotype() {
        assertEquals("看视标选方向，同时像平时一样自然眨眼。", screeningSpeechText("看 E 选方向，同时像平时一样自然眨眼"))
        assertEquals("需要更大的视标才能看清。", screeningSpeechText("需要更大的 E 才能看清"))
        // 英文单词里的 E 不受影响
        assertEquals("EYE。", screeningSpeechText("EYE"))
    }
}
