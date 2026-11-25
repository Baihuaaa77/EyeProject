package com.zkyjss.eyeproject.strabismuslab

import com.zkyjss.eyeproject.core.camera.EyeOpticalAlgorithm
import com.zkyjss.eyeproject.core.camera.GrayImage
import com.zkyjss.eyeproject.core.camera.ScreenPointOptics
import com.zkyjss.eyeproject.core.model.BoundingBox
import com.zkyjss.eyeproject.core.model.EyeLandmarks
import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
import com.zkyjss.eyeproject.core.model.FaceMetrics
import com.zkyjss.eyeproject.core.model.HeadPoseEstimate
import com.zkyjss.eyeproject.core.model.OpticalEyeMeasurement
import com.zkyjss.eyeproject.core.model.Point2f
import com.zkyjss.eyeproject.core.model.ReflexCandidate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
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
    fun glintDetectorSeparatesBrightScreenGlintOverlappingWideCeilingBand() {
        // 模拟实测宿舍环境：瞳孔上方横贯一条 24×5 px 浅亮白灯带（灰度 145），
        // 在灯带边缘 (32, 26) 叠加一颗 2×2 屏幕白点（灰度 250）：二次核心阈值 0.65 应将其精准摘出。
        val image = object : GrayImage {
            override val width = 64
            override val height = 64
            override fun grayRegion(left: Int, top: Int, width: Int, height: Int) = IntArray(width * height) { i ->
                val x = left + i % width
                val y = top + i / width
                when {
                    x in 31..32 && y in 25..26 -> 250
                    x in 20..44 && y in 23..27 -> 145
                    else -> 36
                }
            }
        }
        val measured = EyeOpticalAlgorithm.measureScreenPoint(image, eye, searchRadiusFraction = 0.80f)
        assertNotNull(measured?.cornealReflex, "叠加在浅亮灯带上的高亮屏幕白点不应被误杀")
        assertTrue(abs(measured.cornealReflex!!.x - 31.5f) < 1.5f, "x=${measured.cornealReflex!!.x}")
        assertTrue(abs(measured.cornealReflex!!.y - 25.5f) < 1.5f, "y=${measured.cornealReflex!!.y}")
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
        assertTrue(report.toFramesCsv().contains("hde_prism,vde_prism,hde_3d_prism,vde_3d_prism"))
    }

    @Test
    fun optics3dCompensatesOffAxisLightLargeAngleAndHeadRoll() {
        val intrinsics = LabCameraIntrinsics(
            fx = 2600.0,
            fy = 2600.0,
            cx = 1080.0,
            cy = 1920.0,
            lightOffsetXMm = 0.0,
            lightOffsetYMm = 22.0,
        )
        val rc = CornealOptics3D.CORNEA_RADIUS_MM
        val dc = CornealOptics3D.LIMBUS_TO_CORNEA_CENTER_MM
        val light = doubleArrayOf(intrinsics.lightOffsetXMm, intrinsics.lightOffsetYMm, 0.0)

        fun norm(v: DoubleArray): DoubleArray {
            val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            return doubleArrayOf(v[0] / l, v[1] / l, v[2] / l)
        }

        // 正向 3D 球面镜仿真：给定角膜曲率中心 C 与眼球光轴方向 g，求出精确图像映光与虹膜像素坐标
        fun project(p: DoubleArray): Pair<Double, Double> =
            (intrinsics.fx * p[0] / p[2] + intrinsics.cx) to (intrinsics.fy * p[1] / p[2] + intrinsics.cy)

        fun simulateEye(c: DoubleArray, devNasalRad: Double, nasalSign: Double): Pair<Pair<Double, Double>, Pair<Double, Double>> {
            // 反射点近似在 C + Rc * norm(norm(L - C) + norm(-C))
            val toL = norm(doubleArrayOf(light[0] - c[0], light[1] - c[1], light[2] - c[2]))
            val toO = norm(doubleArrayOf(-c[0], -c[1], -c[2]))
            var gPt = doubleArrayOf(c[0], c[1], c[2] - rc)
            for (k in 0 until 5) {
                val vl = norm(doubleArrayOf(light[0] - gPt[0], light[1] - gPt[1], light[2] - gPt[2]))
                val vo = norm(doubleArrayOf(-gPt[0], -gPt[1], -gPt[2]))
                val m = norm(doubleArrayOf(vl[0] + vo[0], vl[1] + vo[1], vl[2] + vo[2]))
                gPt = doubleArrayOf(c[0] + rc * m[0], c[1] + rc * m[1], c[2] + rc * m[2])
            }
            // 预期注视白点方向 + 水平偏斜（外斜为光轴向颞侧 -nasalSign 旋转 devNasalRad）
            val expDir = norm(doubleArrayOf(light[0] - c[0], light[1] - c[1], light[2] - c[2]))
            val baseYaw = kotlin.math.atan2(expDir[0], -expDir[2])
            val basePitch = kotlin.math.atan2(expDir[1], -expDir[2])
            val yaw = baseYaw - nasalSign * devNasalRad
            val gx = sin(yaw) * cos(basePitch)
            val gy = sin(basePitch)
            val gz = -cos(yaw) * cos(basePitch)
            val limbus = doubleArrayOf(c[0] + dc * gx, c[1] + dc * gy, c[2] + dc * gz)
            return project(limbus) to project(gPt)
        }

        val z = 330.0
        val leftC = doubleArrayOf(-31.5, 0.0, z)
        val rightC = doubleArrayOf(31.5, 0.0, z)
        val irisPx = CornealOptics3D.CORNEA_DIAMETER_MM * intrinsics.fx / (z - dc)

        // 1) 正视双眼 + 22mm 离轴光源：3D HDE/VDE 应接近 0
        val (lI0, lG0) = simulateEye(leftC, 0.0, nasalSign = 1.0)
        val (rI0, rG0) = simulateEye(rightC, 0.0, nasalSign = -1.0)
        val res0 = CornealOptics3D.solveBinocular(
            lI0.first, lI0.second, lG0.first, lG0.second, irisPx,
            rI0.first, rI0.second, rG0.first, rG0.second, irisPx,
            intrinsics = intrinsics, nasalSign = 1.0, farFixation = false,
        )
        assertNotNull(res0)
        assertTrue(abs(res0.hdeAsymmetryPrism) < 1.0, "ortho hde3d=${res0.hdeAsymmetryPrism}")
        assertTrue(abs(res0.vdePrism) < 1.0, "ortho vde3d=${res0.vdePrism}")

        // 2) 大角度单眼 25°（46.63Δ）外斜：验证 3D 球面反演精准恢复大角度非线性
        val exoRad = 25.0 * PI / 180.0
        val expectedPrism = 100.0 * tan(exoRad) // 46.63Δ
        val (lI25, lG25) = simulateEye(leftC, exoRad, nasalSign = 1.0)
        val res25 = CornealOptics3D.solveBinocular(
            lI25.first, lI25.second, lG25.first, lG25.second, irisPx,
            rI0.first, rI0.second, rG0.first, rG0.second, irisPx,
            intrinsics = intrinsics, nasalSign = 1.0, farFixation = false,
        )
        assertNotNull(res25)
        assertTrue(abs(res25.hdeAsymmetryPrism - expectedPrism) < 3.5, "got=${res25.hdeAsymmetryPrism}, expected=$expectedPrism")
        assertTrue(abs(res25.vdePrism) < 2.0, "vde should remain small under pure horizontal exo: ${res25.vdePrism}")
    }

    @Test
    fun pursuitBsiUses400msWindowResilientToFrameJitter() {
        val engine = StrabismusLabEngine(platformName = "Test", deviceModel = "Sim")
        val cfg = LabProtocolConfig(
            includeNear = false,
            includeFar = false,
            includeCover = false,
            includePursuit = true,
            pursuitDurationSec = 10,
        )
        engine.startSession(cfg, nowMs = 1_000L)
        val totalFrames = 300
        for (i in 0 until totalFrames) {
            val tMs = 1_000L + i * 33L
            val sec = (i * 33L) / 1000.0
            val phase = 2.0 * PI * (sec / 3.5)
            // 叠加明显高频逐帧抖动（±0.015），模拟 MediaPipe 33ms 抖动
            val jitterL = if (i % 2 == 0) 0.012f else -0.012f
            val jitterR = if (i % 2 == 0) -0.012f else 0.012f
            val dx = (14.0 * sin(phase)).toFloat()
            val dy = (10.0 * sin(phase)).toFloat()

            fun makeEye(label: String, cx: Float, cy: Float, jx: Float, jy: Float): EyeLandmarks {
                val ear = listOf(
                    Point2f(cx - 50f, cy), Point2f(cx - 25f, cy - 16f), Point2f(cx + 25f, cy - 16f),
                    Point2f(cx + 50f, cy), Point2f(cx + 25f, cy + 16f), Point2f(cx - 25f, cy + 16f),
                )
                return EyeLandmarks(label, ear, listOf(Point2f(cx + dx + jx * 100f, cy + dy + jy * 100f)), 90f)
            }
            val lEye = makeEye("LEFT", 400f, 500f, jitterL, jitterL * 0.5f)
            val rEye = makeEye("RIGHT", 700f, 500f, jitterR, jitterR * 0.5f)
            val face = FaceMetrics(
                faceBox = BoundingBox(200f, 300f, 900f, 900f),
                leftEye = lEye,
                rightEye = rEye,
                leftOptical = OpticalEyeMeasurement(lEye.irisPoints.first(), null, 90f, 30f, 0f, null, irisVisibilityContrast = 60f),
                rightOptical = OpticalEyeMeasurement(rEye.irisPoints.first(), null, 90f, 30f, 0f, null, irisVisibilityContrast = 60f),
                headPose = HeadPoseEstimate(0f, 0f, 0f),
            )
            val frame = FaceLandmarkFrame(
                faces = listOf(face),
                sourceWidth = 1080,
                sourceHeight = 1920,
                timestampMillis = tMs,
                detectionLatencyMillis = 8L,
                fps = 30f,
                analysisElapsedRealtimeMs = tMs,
            )
            engine.onFrame(frame, focalLengthPx = 2200f, nowMs = tMs)
        }
        val report = engine.stopAndBuildReport(1_000L + totalFrames * 33L)
        val pm = report.pursuitMetrics
        assertNotNull(pm)
        assertTrue((pm.rHorizontal ?: 0.0) > 0.75, "rH=${pm.rHorizontal}")
        assertTrue((pm.rVertical ?: 0.0) > 0.75, "rV=${pm.rVertical}")
        assertTrue((pm.bsi ?: 0.0) > 0.75, "bsi=${pm.bsi}")
    }

    @Test
    fun coverStageForcesSteadyStimulusAndRefinesUncoverOnsetWithTrace() {
        val engine = StrabismusLabEngine(platformName = "Test", deviceModel = "Sim")
        val cfg = LabProtocolConfig(
            codingScheme = LabCodingScheme.M_SEQUENCE,
            includeNear = false,
            includeFar = false,
            includeCover = true,
            includePursuit = false,
        )
        engine.startSession(cfg, nowMs = 10_000L)
        // 验证遮盖阶段始终保持白点常亮，消除 250ms 暗码盲区
        for (step in 0 until 20) {
            val (intensity, _) = engine.recordDisplayTick(10_000L + step * 100L)
            assertEquals(1.0f, intensity, 0.001f)
        }

        // 模拟：0-1s 双眼基线 → 1-3.5s 遮右眼（左眼保持稳态）→ 3.5-5.5s 移开右眼（右眼从 +0.25R 复位到 0.0R，模拟隐斜 22Δ）
        var tMs = 10_000L
        for (i in 0 until 180) {
            tMs = 10_000L + i * 33L
            val sec = (i * 33L) / 1000.0
            val rightCovered = sec in 1.0..3.5
            // 在 3.50s 移开遮盖，3.56s 故意加 1 帧单帧眨眼测试向前回溯精确拐点
            val singleBlink = sec in 3.55..3.59
            val rVis = when {
                rightCovered -> 2f
                singleBlink -> 10f
                else -> 60f
            }
            val rEarOffset = if (singleBlink) 4f else 18f
            val rRelX = when {
                sec < 3.5 -> 0.0f
                sec < 3.65 -> 0.24f // 刚去遮盖时偏心 0.24R (≈21Δ)
                sec < 3.85 -> (0.24f * (1.0 - (sec - 3.65) / 0.20)).toFloat() // 200ms 复位扫视
                else -> 0.0f
            }
            val lEye = EyeLandmarks(
                "LEFT",
                listOf(Point2f(350f, 500f), Point2f(375f, 482f), Point2f(425f, 482f), Point2f(450f, 500f), Point2f(425f, 518f), Point2f(375f, 518f)),
                listOf(Point2f(400f, 500f)),
                90f,
            )
            val rEye = EyeLandmarks(
                "RIGHT",
                listOf(Point2f(650f, 500f), Point2f(675f, 500f - rEarOffset), Point2f(725f, 500f - rEarOffset), Point2f(750f, 500f), Point2f(725f, 500f + rEarOffset), Point2f(675f, 500f + rEarOffset)),
                listOf(Point2f(700f, 500f)),
                90f,
            )
            val lGlint = Point2f(400f, 495f)
            val rGlint = if (!rightCovered && !singleBlink) Point2f(700f + rRelX * 45f, 495f) else null
            val face = FaceMetrics(
                faceBox = BoundingBox(200f, 300f, 900f, 900f),
                leftEye = lEye,
                rightEye = rEye,
                leftOptical = OpticalEyeMeasurement(
                    pupilCenter = lEye.irisPoints.first(),
                    cornealReflex = lGlint,
                    irisDiameterPx = 90f,
                    pupilContrast = 30f,
                    reflexContrast = 90f,
                    rejectionReason = null,
                    irisVisibilityContrast = 60f,
                    reflexCandidates = listOf(ReflexCandidate(lGlint, 90f, 220f, 0.85f, 16f, 3.0f, 0.9f)),
                    reflexConfidence = 0.85f,
                ),
                rightOptical = OpticalEyeMeasurement(
                    pupilCenter = rEye.irisPoints.first(),
                    cornealReflex = rGlint,
                    irisDiameterPx = 90f,
                    pupilContrast = 30f,
                    reflexContrast = if (rGlint != null) 90f else 0f,
                    rejectionReason = if (rGlint == null) "遮盖" else null,
                    irisVisibilityContrast = rVis,
                    reflexCandidates = rGlint?.let { listOf(ReflexCandidate(it, 90f, 220f, 0.85f, 16f, 3.0f, 0.9f)) } ?: emptyList(),
                    reflexConfidence = if (rGlint != null) 0.85f else null,
                ),
                headPose = HeadPoseEstimate(0f, 0f, 0f),
            )
            val frame = FaceLandmarkFrame(
                faces = listOf(face),
                sourceWidth = 1080,
                sourceHeight = 1920,
                timestampMillis = tMs,
                detectionLatencyMillis = 8L,
                fps = 30f,
                analysisElapsedRealtimeMs = tMs,
            )
            engine.onFrame(frame, focalLengthPx = 2200f, nowMs = tMs)
        }
        val report = engine.stopAndBuildReport(tMs + 33L)
        val coverEvent = report.coverRefixations.firstOrNull { it.eventType == "COVER_CONTRALATERAL" }
        val uncoverEvent = report.coverRefixations.firstOrNull { it.eventType == "UNCOVER_IPSILATERAL" }
        assertNotNull(coverEvent, "应记录遮盖瞬间对侧眼事件（显斜特征）")
        assertNotNull(uncoverEvent, "应记录去遮盖同侧眼事件（隐斜/IXT 特征）")
        assertTrue(coverEvent.amplitudePrism < 3.0, "对侧眼无显斜移动，amplitude=${coverEvent.amplitudePrism}")
        assertTrue(uncoverEvent.amplitudePrism > 14.0, "去遮盖同侧眼应捕获 ~21Δ 隐斜复位，amplitude=${uncoverEvent.amplitudePrism}")
        assertTrue(uncoverEvent.trace.size >= 8, "应生成完整毫秒级再注视轨迹曲线")
        assertTrue(report.coverClassification?.contains("隐斜") == true, "classification=${report.coverClassification}")
    }
}
