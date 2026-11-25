package com.zkyjss.eyeproject.strabismuslab

import com.zkyjss.eyeproject.core.camera.EyeOpticalAlgorithm
import com.zkyjss.eyeproject.core.camera.GrayImage
import com.zkyjss.eyeproject.core.camera.ScreenPointOptics
import com.zkyjss.eyeproject.core.model.EyeLandmarks
import com.zkyjss.eyeproject.core.model.Point2f
import com.zkyjss.eyeproject.core.model.ReflexCandidate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrabismusLabAlgorithmTest {
    private val eye = EyeLandmarks(
        label = "LEFT",
        earPoints = listOf(
            Point2f(2f, 32f), Point2f(18f, 12f), Point2f(46f, 12f),
            Point2f(62f, 32f), Point2f(46f, 52f), Point2f(18f, 52f),
        ),
        irisPoints = listOf(Point2f(32f, 32f)),
        irisDiameterPx = 48f, // radius = 24px -> 0.60R = 14.4px, 0.80R = 19.2px
    )

    private fun syntheticImage(points: List<Triple<Int, Int, Int>>) = object : GrayImage {
        override val width = 64
        override val height = 64
        override fun grayRegion(left: Int, top: Int, width: Int, height: Int) = IntArray(width * height) { i ->
            val x = left + i % width
            val y = top + i / width
            points.firstOrNull { it.first == x && it.second == y }?.third ?: 36
        }
    }

    @Test
    fun researchSearchRadius080CapturesLargeAngleStrabismusGlintRejectedBy060() {
        // 偏离虹膜中心 18 px = 0.75R：旧版 0.60R 会裁掉，新版 0.80R 应稳定检出。
        val image = syntheticImage(listOf(Triple(50, 32, 248)))
        val legacy = EyeOpticalAlgorithm.measureScreenPoint(image, eye, searchRadiusFraction = 0.60f)
        val research = EyeOpticalAlgorithm.measureScreenPoint(image, eye, searchRadiusFraction = ScreenPointOptics.RESEARCH_SEARCH_RADIUS)
        assertNull(legacy?.cornealReflex)
        assertNotNull(research?.cornealReflex)
        assertEquals(50f, research.cornealReflex!!.x, 0.5f)
        assertTrue(research.reflexCandidates.first().snr > 4.5f)
        assertTrue(research.reflexCandidates.first().diameterPx > 0f)
        // 超出 0.80R（21px = 0.875R）仍应拒绝
        val outside = EyeOpticalAlgorithm.measureScreenPoint(syntheticImage(listOf(Triple(53, 32, 248))), eye, searchRadiusFraction = 0.80f)
        assertNull(outside?.cornealReflex)
    }

    @Test
    fun v8AnalyzerSeparatesCodedGlintFromBrighterConstantWindowReflectionAndFindsLag() {
        val chipSec = 0.25
        val durationSec = 16.0
        val startSec = 100.0
        val trueLagSec = 2.0 / 30.0 // 约 66.7ms 延迟
        val scheme = LabCodingScheme.M_SEQUENCE

        val events = mutableListOf<LabDisplayEvent>()
        var t = 0.0
        while (t < durationSec) {
            val code = StimulusCodeV8.codeValue(t, chipSec, scheme)
            val intensity = StimulusCodeV8.intensity(t, chipSec, scheme)
            events += LabDisplayEvent(
                hostTimeSec = startSec + t,
                callbackHostTimeSec = startSec + t,
                intensity = intensity,
                code = code,
                chip = (t / chipSec).toInt(),
                elapsedSec = t,
            )
            t += 1.0 / 60.0
        }

        val obsFrames = mutableListOf<GlintObservationFrame>()
        val totalFrames = (durationSec * 30).toInt()
        for (i in 0 until totalFrames) {
            val host = startSec + i / 30.0
            val codeAtCam = StimulusCodeV8.codeValue((host - startSec - trueLagSec).coerceAtLeast(0.0), chipSec, scheme)
            val lightOn = codeAtCam > 0.0
            val cands = mutableListOf<ReflexCandidate>()
            // 始终存在的更亮窗户干扰反光（偏右下角，对比度高达 140）
            cands += ReflexCandidate(
                point = Point2f(118f, 112f),
                contrast = 140f,
                brightness = 240f,
                quality = 0.90f,
                snr = 22f,
                diameterPx = 3.2f,
                shapeScore = 0.90f,
            )
            // 随屏幕光码亮灭的真实角膜映光（偏上方）
            if (lightOn) {
                cands += ReflexCandidate(
                    point = Point2f(102f, 92f),
                    contrast = 85f,
                    brightness = 205f,
                    quality = 0.78f,
                    snr = 15f,
                    diameterPx = 2.6f,
                    shapeScore = 0.88f,
                )
            }
            obsFrames += GlintObservationFrame(
                frameIndex = i + 1,
                hostTimeSec = host,
                irisCenterX = 100.0,
                irisCenterY = 100.0,
                irisRadiusPx = 30.0,
                distanceCm = 33.0,
                exposureSec = 0.015,
                visible = true,
                candidates = cands,
            )
        }

        val result = CornealCodeV8Analyzer.analyze(
            eye = "LEFT",
            frames = obsFrames,
            displayEvents = events,
            startHostTimeSec = startSec,
            durationSec = durationSec,
            chipSec = chipSec,
            scheme = scheme,
            stimulusDiameterMm = 34.0,
            searchRadiusFraction = 0.80f,
            fps = 30,
        )

        assertTrue(result.codeVerified, "应通过 v0.8 编码校验: train=${result.trainCorrelation}, held=${result.heldOutCorrelation}, wrong=${result.maxWrongCodeCorrelation}")
        assertTrue((result.heldOutCorrelation ?: 0.0) > 0.80)
        assertTrue((result.maxWrongCodeCorrelation ?: 1.0) < 0.45)
        assertEquals(63, result.shiftNullCorrelations.size)
        // 验证延迟拟合命中真实延迟（误差 <= 35ms）
        assertTrue(abs((result.lagMs ?: -999.0) - trueLagSec * 1000.0) <= 35.0, "lagMs=${result.lagMs}")
        // 验证锚点落在真实编码点 (relX≈0.067, relY≈-0.267)，而非恒定更亮的窗户反光 (relX=0.6, relY=0.4)
        assertTrue(abs((result.referenceRelX ?: 1.0) - 2.0 / 30.0) < 0.12)
        assertTrue(abs((result.referenceRelY ?: 1.0) - (-8.0 / 30.0)) < 0.12)
        // 发射审计中灭灯帧经过门控后不应再计为最终测量
        assertEquals(0, result.emissionAudit?.finalOffMeasurements)
    }

    @Test
    fun darkControlNeverPassesCodeVerification() {
        val chipSec = 0.25
        val durationSec = 10.0
        val startSec = 50.0
        val events = (0 until 300).map { i ->
            val t = i / 30.0
            LabDisplayEvent(startSec + t, startSec + t, 0.0, -1.0, (t / chipSec).toInt(), t)
        }
        val obs = (0 until 300).map { i ->
            GlintObservationFrame(
                frameIndex = i + 1,
                hostTimeSec = startSec + i / 30.0,
                irisCenterX = 100.0,
                irisCenterY = 100.0,
                irisRadiusPx = 30.0,
                distanceCm = 33.0,
                exposureSec = 0.015,
                visible = true,
                candidates = listOf(ReflexCandidate(Point2f(104f, 96f), 60f, 180f, 0.7f, 12f, 2.5f, 0.8f)),
            )
        }
        val result = CornealCodeV8Analyzer.analyze(
            eye = "RIGHT",
            frames = obs,
            displayEvents = events,
            startHostTimeSec = startSec,
            durationSec = durationSec,
            chipSec = chipSec,
            scheme = LabCodingScheme.DARK,
            stimulusDiameterMm = 34.0,
        )
        assertFalse(result.codeVerified)
        assertEquals(0, result.emissionAudit?.finalOffMeasurements)
    }

    @Test
    fun reportJsonAndCsvExportRoundTrip() {
        val cfg = LabProtocolConfig(subjectId = "AE-TEST", searchRadiusFraction = 0.80f)
        val report = LabSessionReport(
            sessionId = "AE-TEST_1",
            createdAtMs = 1_700_000_000_000L,
            config = cfg,
            riskGrade = LabRiskGrade.NORMAL,
            confidenceScore = 0.91,
            headline = "测试通过",
            summaryNotes = listOf("备注 1"),
            frames = listOf(
                LabFrameLog(
                    index = 1,
                    timestampMs = 1000L,
                    elapsedSec = 0.5,
                    stage = LabStage.NEAR_FIXATION,
                    valid = true,
                    distanceCm = 33.2f,
                    fps = 30f,
                    hdePrism = 1.2,
                    vdePrism = 0.3,
                ),
            ),
            totalFrames = 1,
            validFrames = 1,
        )
        val decoded = LabSessionReport.fromJson(report.toJson())
        assertEquals(report.sessionId, decoded.sessionId)
        assertEquals(0.80f, decoded.config.searchRadiusFraction)
        assertTrue(report.toFramesJsonl().contains("\"stage\":\"NEAR_FIXATION\""))
        assertTrue(report.toFramesCsv().contains("hde_prism,vde_prism"))
    }
}
