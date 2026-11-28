package com.zkyjss.strabismuslab

import com.zkyjss.eyeproject.strabismuslab.LabCodingScheme
import com.zkyjss.eyeproject.strabismuslab.LabProtocolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabConfigTest {
    @Test
    fun defaultConfigUses080SearchRadiusAndV8Coding() {
        val cfg = LabProtocolConfig()
        assertEquals(0.80f, cfg.searchRadiusFraction, 0.0001f)
        assertEquals(LabCodingScheme.M_SEQUENCE, cfg.codingScheme)
        assertEquals(4, cfg.activeStages.size)
        assertTrue(LabExportFormat.entries.any { it == LabExportFormat.BUNDLE_ZIP })
        assertTrue(LabExportFormat.entries.any { it == LabExportFormat.DISPLAY_EVENTS_JSONL })
    }
}
