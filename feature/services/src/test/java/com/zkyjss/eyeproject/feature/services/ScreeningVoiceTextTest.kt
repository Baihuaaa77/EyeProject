package com.zkyjss.eyeproject.feature.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreeningVoiceTextTest {
    @Test fun durationRangeIsSpokenAsRange() {
        assertEquals("保持上一动作约一到两秒。", screeningSpeechText("保持上一动作约 1–2 秒。"))
        assertEquals("保持一到两秒。", screeningSpeechText("保持1-2秒。"))
    }

    @Test fun distanceAndToleranceAreSpokenNaturally() {
        assertEquals("请保持四十厘米，上下浮动四厘米，约一秒。", screeningSpeechText("请保持 40±4 cm 约 1 秒"))
        assertEquals("十五到六十厘米。", screeningSpeechText("15–60 cm"))
    }

    @Test fun decimalsAndOrdinalsAreKeptIntelligible() {
        assertEquals("请在三十九点五厘米停稳，重复第三次。", screeningSpeechText("请在 39.5 cm 停稳，重复第3次。"))
    }

    @Test fun twoBeforeMeasureWordsIsSpokenAsLiang() {
        assertEquals("保持两秒后，执行第二步。", screeningSpeechText("保持 2 秒后，执行第 2 步"))
        assertEquals("移开两厘米，重复两次。", screeningSpeechText("移开 2 cm，重复2次"))
        assertEquals("二十二秒。", screeningSpeechText("22 秒"))
    }

    @Test fun symbolsBecomePausesAndSentencesEndWithAFullStop() {
        assertEquals("水平八秒，垂直八秒，斜向六秒。", screeningSpeechText("水平 8 秒；垂直 8 秒；斜向 6 秒"))
        assertEquals("右眼，第一题。", screeningSpeechText("右眼 · 第 1 题"))
        assertEquals("遮右眼，然后移开。", screeningSpeechText("遮右眼 → 移开"))
        assertEquals("测试完成！", screeningSpeechText("测试完成！"))
    }

    @Test fun repeatedDistancePromptsVaryAndReturnToTheFirstPhrase() {
        val close = (0..3).map { distanceStatus(28f, true, 40f, 5f).spoken(it) }
        assertEquals(3, close.take(3).toSet().size)
        assertEquals(close[0], close[3])
        assertNull(distanceStatus(40f, true, 40f, 5f).spoken(1))
    }
}
