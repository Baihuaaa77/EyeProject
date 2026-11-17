package com.zkyjss.eyeproject.core.eyecare

import kotlin.test.*

class V16LedgerTest {
    @Test fun oldLedgerLoadsAndNewNotesAndCompletedTimersSurviveRoundTrip() {
        val original = LedgerData.fromJson("""{"version":1,"checkIns":[{"day":"2026-11-16","symptoms":[],"atMs":1}]}""")
        assertEquals("",original.checkIns.single().note)
        val ledger = original.withCheckIn("2026-11-17",listOf("眼睛疲劳"),2," 今天加班后疲劳 ")
            .withCareCheckIn("2026-11-17","远眺20秒",3)
            .withCareCheckIn("2026-11-17","远眺20秒",4)
        val restored = LedgerData.fromJson(ledger.toJson())
        assertEquals("今天加班后疲劳",restored.checkIns.last().note)
        assertEquals(listOf("眼睛疲劳"),restored.checkIns.last().symptoms)
        assertEquals(2,restored.careCheckIns.size,"同一天完成多次也要保留实际记录")
        assertTrue(restored.careCheckIns.all { it.durationSeconds == 20 })
        assertEquals(restored,restored.withCareCheckIn("2026-11-17","未知活动",5))
    }
}
