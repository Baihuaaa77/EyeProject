package com.zkyjss.eyeproject.feature.services

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreeningVoiceTextTest {
    @Test fun durationRangeIsSpokenAsRange() {
        assertEquals("保持上一动作约一到两秒。", screeningSpeechText("保持上一动作约 1–2 秒。"))
        assertEquals("保持一到两秒。", screeningSpeechText("保持1-2秒。"))
    }

    @Test fun distanceAndToleranceAreSpokenNaturally() {
        assertEquals("请保持四十厘米，上下浮动四厘米，约一秒", screeningSpeechText("请保持 40±4 cm 约 1 秒"))
        assertEquals("十五到六十厘米", screeningSpeechText("15–60 cm"))
    }

    @Test fun decimalsAndOrdinalsAreKeptIntelligible() {
        assertEquals("请在三十九点五厘米停稳，重复第三次。", screeningSpeechText("请在 39.5 cm 停稳，重复第3次。"))
    }
}
