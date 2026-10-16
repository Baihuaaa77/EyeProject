package com.zkyjss.eyeproject.feature.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandParserTest {
    private fun dir(text: String) = (VoiceCommandParser.parse(text) as? VoiceCommand.Direction)?.direction

    @Test fun directionsMatchOptotypeEncoding() {
        assertEquals(0, dir("右"))
        assertEquals(1, dir("向下"))
        assertEquals(2, dir("左边"))
        assertEquals(3, dir("开口朝上"))
    }

    @Test fun commonHomophonesAreAccepted() {
        assertEquals(3, dir("尚"))
        assertEquals(1, dir("夏"))
        assertEquals(2, dir("做"))
        assertEquals(0, dir("又"))
    }

    @Test fun lastMentionedDirectionWins() {
        assertEquals(2, dir("上，不对，左"))
    }

    @Test fun nonDirectionCommands() {
        assertEquals(VoiceCommand.Unsure, VoiceCommandParser.parse("看不清"))
        assertEquals(VoiceCommand.Unsure, VoiceCommandParser.parse("跳过"))
        assertEquals(VoiceCommand.Unsure, VoiceCommandParser.parse("跳过吧"))
        assertEquals(VoiceCommand.Blur, VoiceCommandParser.parse("模糊了"))
        assertEquals(VoiceCommand.Ready, VoiceCommandParser.parse("好了"))
        assertNull(VoiceCommandParser.parse("嗯"))
        assertNull(VoiceCommandParser.parse("  "))
    }
}
