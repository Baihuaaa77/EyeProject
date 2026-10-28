package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.model.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 色觉自测：检测图只刺激一种视锥、判定标准、完整流程与记录。 */
class ColorVisionTest {
    private class Platform : ScreeningPlatform {
        override val display = DisplayGeometry(1080, 2400, 420f, 420f)
        var saved: Pair<String, String>? = null
        var time = 1_000L
        override fun nowMs() = time
        override fun epochMs() = time
        override fun cameraGeometry(width: Int, height: Int) = CameraGeometry(width, height, 400f)
        override fun startCamera(onFrame: (FaceLandmarkFrame) -> Unit, onError: (Throwable) -> Unit) = Unit
        override fun stopCamera() = Unit
        override fun startLight(onLux: (Float) -> Unit) = false
        override suspend fun saveRecord(name: String, json: String) { saved = name to json }
        override suspend fun compareHistory(state: ScreeningState, assessment: ScreeningAssessment) = emptyList<String>()
    }

    @Test fun platesAreDeterministicAndOnlyChangeOneConeType() {
        val plates = ColorVisionProtocol.plates
        assertEquals(9, plates.size)
        assertEquals(plates.map { it.dots }, ColorVisionProtocol.plates.map { it.dots }, "固定种子，两端出图一致")
        plates.forEach { p ->
            assertTrue(p.dots.size > 600, "${p.id} 色点太少：${p.dots.size}")
            assertTrue(p.number in p.options && ColorVisionProtocol.NOT_SEEN == p.options.last())
        }
        // 红色觉图：数字与背景的 M/S、绿色觉图：L/S 比例分布相同（只差被检测的那一种视锥）
        fun ratio(p: ColorPlate, a: Int, b: Int, figure: Boolean): Double {
            val strokes = DigitFont.layout(p.number)
            val values = p.dots.filter { DigitFont.inside(strokes, it.x, it.y) == figure }.map { ColorMath.lmsOf(it.argb) }.map { it[a] / it[b] }
            return values.average()
        }
        val protan = plates.first { it.id == "p1" }
        assertTrue(abs(ratio(protan, 1, 2, true) / ratio(protan, 1, 2, false) - 1) < 0.02, "红色觉图不应改变 M 视锥")
        assertTrue(ratio(protan, 0, 2, true) / ratio(protan, 0, 2, false) > 1.2, "红色觉图的数字 L 视锥更高")
        val deutan = plates.first { it.id == "d1" }
        assertTrue(abs(ratio(deutan, 0, 2, true) / ratio(deutan, 0, 2, false) - 1) < 0.02, "绿色觉图不应改变 L 视锥")
        assertTrue(ratio(deutan, 1, 2, true) / ratio(deutan, 1, 2, false) > 1.2, "绿色觉图的数字 M 视锥更高")
    }

    private fun answers(wrongProtan: Int, wrongDeutan: Int): List<ColorPlateAnswer> {
        var p = 0; var d = 0
        return ColorVisionProtocol.screening.map { plate ->
            val wrong = when (plate.type) {
                ColorPlateType.PROTAN -> p++ < wrongProtan
                else -> d++ < wrongDeutan
            }
            ColorPlateAnswer(plate.id, plate.number, plate.type, plate.contrast, if (wrong) ColorVisionProtocol.NOT_SEEN else plate.number, 1_500)
        }
    }

    private val demoOk = ColorPlateAnswer("demo", "12", ColorPlateType.DEMO, 0f, "12", 900)

    @Test fun scoringFollowsTheStandard() {
        assertEquals(ScreeningRiskLevel.LOW, ColorVisionAssessment.evaluate(demoOk, answers(1, 0), true).riskLevel)
        assertEquals(ScreeningRiskLevel.MEDIUM, ColorVisionAssessment.evaluate(demoOk, answers(1, 2), true).riskLevel)
        val protan = ColorVisionAssessment.evaluate(demoOk, answers(4, 0), true)
        assertEquals(ScreeningRiskLevel.HIGH, protan.riskLevel)
        assertEquals("PROTAN", protan.type)
        assertEquals("DEUTAN", ColorVisionAssessment.evaluate(demoOk, answers(1, 4), true).type)
        assertEquals("UNCLEAR", ColorVisionAssessment.evaluate(demoOk, answers(2, 2), true).type)
        // 示例图读错：不下结论
        val badDemo = ColorVisionAssessment.evaluate(demoOk.copy(answer = "17"), answers(0, 0), true)
        assertFalse(badDemo.valid)
        assertEquals(ScreeningRiskLevel.UNKNOWN, badDemo.riskLevel)
    }

    @Test fun fullFlowSavesARecordTheCatalogCanRead() = runTest {
        val platform = Platform()
        val engine = ColorVisionEngine(platform, this, StandardTestDispatcher(testScheduler))
        engine.skipProfile(); engine.start()
        assertEquals(ColorVisionPhase.Demo, engine.uiState.value.phase)
        engine.answer("12")
        repeat(8) {
            val plate = engine.uiState.value.plate!!
            platform.time += 2_000
            engine.answer(if (plate.type == ColorPlateType.PROTAN) ColorVisionProtocol.NOT_SEEN else plate.number)
        }
        runCurrent()
        val s = engine.uiState.value
        assertEquals(ColorVisionPhase.Result, s.phase)
        assertEquals(ScreeningRiskLevel.HIGH, s.report!!.riskLevel)
        assertEquals("PROTAN", s.report!!.type)
        assertEquals("色觉异常可能", s.result!!.badge)
        val (name, json) = assertNotNull(platform.saved)
        assertTrue(name.startsWith("color_"))
        val entry = assertNotNull(LocalRecordCatalog.entry(name, json))
        assertEquals("色觉自测", entry.title)
        assertEquals("HIGH", entry.level)
        assertNull(com.zkyjss.eyeproject.core.sync.Explanations.fromRecord(json), "色觉自测暂不发送 AI 解读")
        assertEquals("COLOR_VISION", com.zkyjss.eyeproject.core.sync.RecordSummaries.parse(json).kind)
    }
}
