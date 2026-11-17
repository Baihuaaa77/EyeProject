package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
import kotlinx.serialization.json.*
import kotlin.test.*

/** 失败帧与原始时钟必须可追溯，退出和重测不能串会话。 */
class StrabismusDiagnosticsTest {
    private class Platform : ScreeningPlatform {
        val rows = mutableListOf<JsonObject>()
        override val display = DisplayGeometry(1080, 2400, 400f, 400f)
        override fun nowMs() = 1000L
        override fun epochMs() = 2000L
        override fun cameraGeometry(width: Int, height: Int) = CameraGeometry(width, height, 500f)
        override fun startCamera(onFrame: (FaceLandmarkFrame) -> Unit, onError: (Throwable) -> Unit) = Unit
        override fun stopCamera() = Unit
        override fun startLight(onLux: (Float) -> Unit) = false
        override suspend fun saveRecord(name: String, json: String) = Unit
        override suspend fun compareHistory(state: ScreeningState, assessment: ScreeningAssessment) = emptyList<String>()
        override fun diagnosticEvent(json: String) { rows += Json.parseToJsonElement(json).jsonObject }
    }
    @Test fun rejectedFrameKeepsSourceClockAndNullEstimates() {
        val platform = Platform(); val audit = StrabismusDiagnostics(platform)
        audit.start(); val id = audit.sessionId; audit.start()
        val source = FaceLandmarkFrame(emptyList(), 640, 480, 9000, 12, 15f, analysisElapsedRealtimeMs = 5000)
        val adjusted = source.copy(analysisElapsedRealtimeMs = 4000)
        audit.reflectionLightOn = false
        audit.frame(adjusted, ScreeningState(kind = ScreeningKind.STRABISMUS, step = ScreeningStep.FIXATION),
            false, false, GazePoint(0.5f, 0.1f), null, null, false, true, 4000)
        audit.finish("中途退出"); audit.finish("重复结束")
        assertEquals(3, platform.rows.size)
        val frame = platform.rows[1]
        assertEquals(5000, frame["captureTimeMs"]!!.jsonPrimitive.long)
        assertEquals(4000, frame["sessionTimeMs"]!!.jsonPrimitive.long)
        assertEquals(JsonNull, frame["leftGaze"])
        assertFalse(frame["targetVisible"]!!.jsonPrimitive.boolean)
        assertEquals("white", frame["targetColor"]!!.jsonPrimitive.content)
        assertTrue(frame["reasons"]!!.jsonArray.any { it.jsonPrimitive.content == "无可靠注视标定" })
        assertEquals(id, platform.rows.last()["sessionId"]!!.jsonPrimitive.content)
        audit.start(); assertNotEquals(id, audit.sessionId)
    }
    @Test fun recordDeepLinkSurvivesBothCatalogFormats() {
        val record = """{"kind":"STRABISMUS","diagnosticSessionId":"strabismus_2000_0","assessment":{"riskLevel":"UNKNOWN"}}"""
        val parsed = LocalRecordCatalog.entry("strabismus_2000.json", record)!!
        assertEquals("strabismus_2000_0", parsed.diagnosticSessionId)
        val bridge = Json.parseToJsonElement(LocalRecordCatalog.entryJson(parsed.fileName, record)!!).jsonObject
        assertEquals(parsed.diagnosticSessionId, bridge["diagnosticSessionId"]!!.jsonPrimitive.content)
        assertNull(LocalRecordCatalog.entry("strabismus_1.json", """{"kind":"STRABISMUS"}""")!!.diagnosticSessionId)
    }
}
