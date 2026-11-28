package com.zkyjss.eyeproject.strabismuslab

import com.zkyjss.eyeproject.core.camera.EyeOpticalAlgorithm
import com.zkyjss.eyeproject.core.camera.GrayImage
import com.zkyjss.eyeproject.core.model.EyeLandmarks
import com.zkyjss.eyeproject.core.model.Point2f
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 真实 iPhone 采集数据离线回归测试（仅当环境变量 `STRABISMUS_LAB_REAL_DATA_DIR` 指向本地私有数据包时执行；
 * CI 与公开仓库未配置该目录时自动跳过，确保绝不把受试者视频与眼区原图纳入 Git）。
 */
class RealIphoneRegressionTest {

    private fun replayRoot(): File? {
        val env = System.getenv("STRABISMUS_LAB_REAL_DATA_DIR")?.takeIf { it.isNotBlank() } ?: return null
        val dir = File(env)
        return dir.takeIf { it.isDirectory }
    }

    private fun loadGrayPng(file: File): GrayImage {
        val img = ImageIO.read(file)
        val w = img.width
        val h = img.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val rgb = img.getRGB(x, y)
                val r = (rgb ushr 16) and 0xFF
                val g = (rgb ushr 8) and 0xFF
                val b = rgb and 0xFF
                pixels[y * w + x] = (r * 77 + g * 150 + b * 29) ushr 8
            }
        }
        return object : GrayImage {
            override val width = w
            override val height = h
            override fun grayRegion(left: Int, top: Int, width: Int, height: Int): IntArray {
                val out = IntArray(width * height)
                for (yy in 0 until height) {
                    val sy = (top + yy).coerceIn(0, h - 1)
                    for (xx in 0 until width) {
                        val sx = (left + xx).coerceIn(0, w - 1)
                        out[yy * width + xx] = pixels[sy * w + sx]
                    }
                }
                return out
            }
        }
    }

    /** 构造与 StrabismusLabVideoRecorder 纯净放大框几何对齐的 EyeLandmarks（中心位于盒子中央，半径=w/(2*2.35)）。 */
    private fun boxEyeLandmarks(label: String, w: Int, h: Int, irisRadiusPx: Float): EyeLandmarks {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val r = irisRadiusPx
        return EyeLandmarks(
            label = label,
            earPoints = listOf(
                Point2f(cx - r * 1.85f, cy),
                Point2f(cx - r * 0.75f, cy - r * 0.85f),
                Point2f(cx + r * 0.75f, cy - r * 0.85f),
                Point2f(cx + r * 1.85f, cy),
                Point2f(cx + r * 0.75f, cy + r * 0.85f),
                Point2f(cx - r * 0.75f, cy + r * 0.85f),
            ),
            irisPoints = listOf(Point2f(cx, cy)),
            irisDiameterPx = r * 2f,
        )
    }

    private fun readGrayPack(binFile: File, count: Int, w: Int, h: Int): List<GrayImage> {
        val bytes = binFile.readBytes()
        val frameSize = w * h
        return (0 until count).map { idx ->
            val offset = idx * frameSize
            object : GrayImage {
                override val width = w
                override val height = h
                override fun grayRegion(left: Int, top: Int, width: Int, height: Int): IntArray {
                    val out = IntArray(width * height)
                    for (yy in 0 until height) {
                        val sy = (top + yy).coerceIn(0, h - 1)
                        for (xx in 0 until width) {
                            val sx = (left + xx).coerceIn(0, w - 1)
                            out[yy * width + xx] = bytes[offset + sy * w + sx].toInt() and 0xFF
                        }
                    }
                    return out
                }
            }
        }
    }

    @Test
    fun realRun1PartialFingerCoverDropsVisibilityContrastToZeroWhileOpenEyeStaysHigh() {
        val root = replayRoot() ?: return
        val dir = File(root, "replay_cache/run1_cover_crops")
        if (!dir.isDirectory) return

        // 测试真实半遮盖帧（#1117、vid62s、vid64s）与正常睁眼帧（vid6s、vid54s）
        val partialCases = listOf("frame_1117_L.png", "vid_62s_L.png", "vid_64s_L.png")
        val openCases = listOf("frame_1117_R.png", "vid_6s_L.png", "vid_6s_R.png", "vid_54s_L.png", "vid_54s_R.png")

        val partialScores = partialCases.mapNotNull { name ->
            val f = File(dir, name).takeIf { it.exists() } ?: return@mapNotNull null
            val g = loadGrayPng(f)
            val r = g.width / (2f * 2.35f)
            val m = EyeOpticalAlgorithm.measureScreenPoint(g, boxEyeLandmarks("LEFT", g.width, g.height, r), 0.80f)
            name to (m?.irisVisibilityContrast ?: -1f)
        }
        val openScores = openCases.mapNotNull { name ->
            val f = File(dir, name).takeIf { it.exists() } ?: return@mapNotNull null
            val g = loadGrayPng(f)
            val r = g.width / (2f * 2.35f)
            val m = EyeOpticalAlgorithm.measureScreenPoint(g, boxEyeLandmarks("RIGHT", g.width, g.height, r), 0.80f)
            name to (m?.irisVisibilityContrast ?: -1f)
        }
        println("[RealRun1-Cover] partial=$partialScores | open=$openScores")
        assertTrue(partialScores.isNotEmpty() && openScores.isNotEmpty())
        for ((name, score) in partialScores) {
            assertTrue(score < 10f, "真实半遮盖帧 $name 仍算出高可见性=$score（旧版高达 88~150）")
        }
        for ((name, score) in openScores) {
            assertTrue(score >= 35f, "真实正常睁眼帧 $name 可见性被误伤=$score")
        }
    }

    @Test
    fun realRun1MSequenceNearPixelsPreserveHighCorrelationAndNormalAlignment() {
        val root = replayRoot() ?: return
        val sessionDir = File(root, "SUBJ-001_641258029")
        val cacheDir = File(root, "replay_cache/run1_mseq_near")
        if (!sessionDir.isDirectory || !cacheDir.isDirectory) return

        val report = LabSessionReport.fromJson(File(sessionDir, "session_summary.json").readText())
        val nearFrames = report.frames.filter { it.stage == LabStage.NEAR_FIXATION }
        val meta = File(cacheDir, "meta.txt").readLines().associate {
            val (k, v) = it.split("=", limit = 2)
            k to v
        }
        val count = meta["count"]!!.toInt()
        val w = meta["width"]!!.toInt()
        val h = meta["height"]!!.toInt()
        val leftImgs = readGrayPack(File(cacheDir, "left.bin"), count, w, h)
        val rightImgs = readGrayPack(File(cacheDir, "right.bin"), count, w, h)

        val n = min(count, nearFrames.size) - 1
        // 6 位 m-序列前 6 个码元均为 +1（共 6*chipSec = 1.50s），第 7 个码元跳变为 -1；
        // 用首个 -1 帧时间戳精确还原真实 stageStartSec，确保 shift=0 与 63 个错位码时间轴完全一致。
        val firstNegIdx = nearFrames.indexOfFirst { it.stimulusCode < 0.0 }.coerceAtLeast(1)
        val trueStageStartSec = nearFrames[firstNegIdx].timestampMs / 1000.0 - 6.0 * (report.config.chipDurationMs / 1000.0)
        val alignedFrames = nearFrames.subList(1, 1 + n)
        val endSec = alignedFrames.last().timestampMs / 1000.0
        val durSec = (endSec - trueStageStartSec).coerceAtLeast(2.0)
        val chipSec = report.config.chipDurationMs / 1000.0
        val scheme = report.config.codingScheme

        val events = mutableListOf<LabDisplayEvent>()
        var t = 0.0
        while (t <= durSec + 0.1) {
            events += LabDisplayEvent(
                hostTimeSec = trueStageStartSec + t,
                callbackHostTimeSec = trueStageStartSec + t,
                intensity = StimulusCodeV8.intensity(t, chipSec, scheme),
                code = StimulusCodeV8.codeValue(t, chipSec, scheme),
                chip = (t / chipSec).toInt(),
                elapsedSec = t,
            )
            t += 1.0 / 60.0
        }

        val boxRadius = (w / (2.0 * 2.35)).toFloat()
        fun extractObs(imgs: List<GrayImage>, isLeft: Boolean): List<GlintObservationFrame> = (0 until n).map { i ->
            val fr = alignedFrames[i]
            val img = imgs[i]
            val origIrisD = (if (isLeft) fr.leftIrisDiameterPx else fr.rightIrisDiameterPx)?.toDouble() ?: 118.0
            val origRadius = origIrisD * 0.5
            val expOrig = OpticalBudgetEstimate.expectedDiameterPx(
                distanceCm = fr.distanceCm.toDouble().coerceAtLeast(20.0),
                irisPixels = origIrisD,
                sourceMm = 22.0,
            ) ?: 3.5
            val zoom = boxRadius / origRadius
            val expBox = (expOrig * zoom).toFloat()
            val eye = boxEyeLandmarks(if (isLeft) "LEFT" else "RIGHT", w, h, boxRadius)
            val meas = EyeOpticalAlgorithm.measureScreenPoint(img, eye, 0.80f, expectedDiameterPx = expBox)
            val cands = (meas?.reflexCandidates ?: emptyList()).map { c ->
                val relX = (c.point.x - w * 0.5f) / boxRadius
                val relY = (c.point.y - h * 0.5f) / boxRadius
                val ix = (if (isLeft) fr.leftIrisX else fr.rightIrisX) ?: 1000f
                val iy = (if (isLeft) fr.leftIrisY else fr.rightIrisY) ?: 1600f
                c.copy(
                    point = Point2f((ix + relX * origRadius).toFloat(), (iy + relY * origRadius).toFloat()),
                    diameterPx = (c.diameterPx / zoom).toFloat(),
                )
            }
            GlintObservationFrame(
                frameIndex = fr.index,
                hostTimeSec = fr.timestampMs / 1000.0,
                irisCenterX = (if (isLeft) fr.leftIrisX else fr.rightIrisX)?.toDouble() ?: 1000.0,
                irisCenterY = (if (isLeft) fr.leftIrisY else fr.rightIrisY)?.toDouble() ?: 1600.0,
                irisRadiusPx = origRadius,
                distanceCm = fr.distanceCm.toDouble().coerceAtLeast(20.0),
                exposureSec = 0.015,
                visible = true,
                candidates = cands,
            )
        }

        val lNew = CornealCodeV8Analyzer.analyze(
            eye = "LEFT", frames = extractObs(leftImgs, true), displayEvents = events,
            startHostTimeSec = trueStageStartSec, durationSec = durSec, chipSec = chipSec,
            scheme = scheme, stimulusDiameterMm = 22.0, fps = 30,
        )
        val rNew = CornealCodeV8Analyzer.analyze(
            eye = "RIGHT", frames = extractObs(rightImgs, false), displayEvents = events,
            startHostTimeSec = trueStageStartSec, durationSec = durSec, chipSec = chipSec,
            scheme = scheme, stimulusDiameterMm = 22.0, fps = 30,
            lagPriorSec = lNew.takeIf { it.codeVerified }?.lagMs?.div(1000.0),
        )
        println(
            "[RealRun1-MSEQ] NEW L(ver=${lNew.codeVerified}, train=${lNew.trainCorrelation}, held=${lNew.heldOutCorrelation}, wrong=${lNew.maxWrongCodeCorrelation}, lagMs=${lNew.lagMs}, ref=(${lNew.referenceRelX},${lNew.referenceRelY}), ratio=${lNew.opticalBudget?.observedToExpectedRatio}) " +
                "| R(ver=${rNew.codeVerified}, train=${rNew.trainCorrelation}, held=${rNew.heldOutCorrelation}, wrong=${rNew.maxWrongCodeCorrelation}, lagMs=${rNew.lagMs}, ref=(${rNew.referenceRelX},${rNew.referenceRelY}), ratio=${rNew.opticalBudget?.observedToExpectedRatio})"
        )
        // 在 H.264 二次压缩缩略图上回放：验证左眼稳过 63 错位码，双眼均保持高相关（>=0.50）、锚点与原机真值误差 <0.04R、光学斑径比接近 1.0
        assertTrue(lNew.codeVerified, "Run #1 左眼应通过 63 错位码验证")
        assertTrue((lNew.trainCorrelation ?: 0.0) >= 0.70 && (lNew.heldOutCorrelation ?: 0.0) >= 0.50)
        assertTrue((rNew.trainCorrelation ?: 0.0) >= 0.50 && (rNew.heldOutCorrelation ?: 0.0) >= 0.45)
        assertTrue(abs((lNew.referenceRelX ?: 1.0) - (report.nearLeftCode?.referenceRelX ?: 0.0)) < 0.04)
        assertTrue(abs((rNew.referenceRelX ?: 1.0) - (report.nearRightCode?.referenceRelX ?: 0.0)) < 0.04)
        assertTrue((lNew.opticalBudget?.observedToExpectedRatio ?: 0.0) in 0.80..1.20, "左眼斑径比=${lNew.opticalBudget?.observedToExpectedRatio}")
        assertTrue((rNew.opticalBudget?.observedToExpectedRatio ?: 0.0) in 0.80..1.20, "右眼斑径比=${rNew.opticalBudget?.observedToExpectedRatio}")
    }

    @Test
    fun realRun2ManchesterTimelineRecoversFromTransitionCollapseAndRejectsWeakSignalWithoutFalsePositive() {
        val root = replayRoot() ?: return
        val sessionDir = File(root, "SUBJ-001_641458499")
        val cacheDir = File(root, "replay_cache/run2_manchester_near")
        if (!sessionDir.isDirectory || !cacheDir.isDirectory) return

        val report = LabSessionReport.fromJson(File(sessionDir, "session_summary.json").readText())
        val nearFrames = report.frames.filter { it.stage == LabStage.NEAR_FIXATION }
        val meta = File(cacheDir, "meta.txt").readLines().associate {
            val (k, v) = it.split("=", limit = 2)
            k to v
        }
        val count = meta["count"]!!.toInt()
        val w = meta["width"]!!.toInt()
        val h = meta["height"]!!.toInt()
        val leftImgs = readGrayPack(File(cacheDir, "left.bin"), count, w, h)
        val rightImgs = readGrayPack(File(cacheDir, "right.bin"), count, w, h)

        val n = min(count, nearFrames.size)
        val startSec = nearFrames.first().timestampMs / 1000.0
        val endSec = nearFrames[n - 1].timestampMs / 1000.0
        val durSec = (endSec - startSec).coerceAtLeast(2.0)
        val chipSec = report.config.chipDurationMs / 1000.0
        val scheme = report.config.codingScheme

        val events = mutableListOf<LabDisplayEvent>()
        var t = 0.0
        while (t <= durSec + 0.1) {
            events += LabDisplayEvent(
                hostTimeSec = startSec + t,
                callbackHostTimeSec = startSec + t,
                intensity = StimulusCodeV8.intensity(t, chipSec, scheme),
                code = StimulusCodeV8.codeValue(t, chipSec, scheme),
                chip = (t / chipSec).toInt(),
                elapsedSec = t,
            )
            t += 1.0 / 60.0
        }

        // 1) 用 Run #2 真实 480 帧主机时间戳 + 实测 iPhone 83.3ms 硬件延迟验证短脉宽时间线修复：
        //    旧版 v0.9.3 将 461/480 (96%) 帧判为 TRANSITION（仅 9 ON / 2 OFF）；新版应恢复 >100 ON 与 >100 OFF！
        val timeline = EmissionTimelineV8(events)
        val phases = nearFrames.take(n).map { fr ->
            timeline.phaseAt(
                timeSec = fr.timestampMs / 1000.0,
                exposureSec = 0.015,
                fps = 30,
                lagSec = 0.0833,
                chipSec = chipSec,
                scheme = scheme,
            )
        }
        val onCount = phases.count { it == LabEmissionPhase.ON }
        val offCount = phases.count { it == LabEmissionPhase.OFF }
        val transCount = phases.count { it == LabEmissionPhase.TRANSITION }
        val oldAudit = report.nearLeftCode?.emissionAudit
        println(
            "[RealRun2-Manchester-Timeline] OLD(on=${oldAudit?.stableOnFrames}, off=${oldAudit?.stableOffFrames}, trans=${oldAudit?.transitionFrames}) " +
                "-> NEW@83ms(on=$onCount, off=$offCount, trans=$transCount)"
        )
        assertTrue(onCount >= 90, "修复后 Run #2 真实时间戳稳定 ON 帧应显著恢复（旧版仅 9 帧），实际=$onCount")
        assertTrue(offCount >= 90, "修复后 Run #2 真实时间戳稳定 OFF 帧应显著恢复（旧版仅 2 帧），实际=$offCount")
        assertTrue(transCount < (oldAudit?.transitionFrames ?: 461) / 2, "过渡帧占比应大幅下降，实际=$transCount")

        // 2) 用 Run #2 真实弱信号眼区原图重跑，核查在缺乏有效物理闪烁信号时算法保持诚实拒判（绝不伪造 codeVerified=true 或漏出灭灯测量）
        val boxRadius = (w / (2.0 * 2.35)).toFloat()
        fun extractObs(imgs: List<GrayImage>, isLeft: Boolean): List<GlintObservationFrame> = (0 until n).map { i ->
            val fr = nearFrames[i]
            val img = imgs[i]
            val origIrisD = (if (isLeft) fr.leftIrisDiameterPx else fr.rightIrisDiameterPx)?.toDouble() ?: 118.0
            val origRadius = origIrisD * 0.5
            val expOrig = OpticalBudgetEstimate.expectedDiameterPx(
                distanceCm = fr.distanceCm.toDouble().coerceAtLeast(20.0),
                irisPixels = origIrisD,
                sourceMm = 22.0,
            ) ?: 3.5
            val zoom = boxRadius / origRadius
            val eye = boxEyeLandmarks(if (isLeft) "LEFT" else "RIGHT", w, h, boxRadius)
            val meas = EyeOpticalAlgorithm.measureScreenPoint(img, eye, 0.80f, expectedDiameterPx = (expOrig * zoom).toFloat())
            val cands = (meas?.reflexCandidates ?: emptyList()).map { c ->
                val relX = (c.point.x - w * 0.5f) / boxRadius
                val relY = (c.point.y - h * 0.5f) / boxRadius
                val ix = (if (isLeft) fr.leftIrisX else fr.rightIrisX) ?: 1000f
                val iy = (if (isLeft) fr.leftIrisY else fr.rightIrisY) ?: 1600f
                c.copy(
                    point = Point2f((ix + relX * origRadius).toFloat(), (iy + relY * origRadius).toFloat()),
                    diameterPx = (c.diameterPx / zoom).toFloat(),
                )
            }
            GlintObservationFrame(
                frameIndex = fr.index,
                hostTimeSec = fr.timestampMs / 1000.0,
                irisCenterX = (if (isLeft) fr.leftIrisX else fr.rightIrisX)?.toDouble() ?: 1000.0,
                irisCenterY = (if (isLeft) fr.leftIrisY else fr.rightIrisY)?.toDouble() ?: 1600.0,
                irisRadiusPx = origRadius,
                distanceCm = fr.distanceCm.toDouble().coerceAtLeast(20.0),
                exposureSec = 0.015,
                visible = true,
                candidates = cands,
            )
        }
        val lRaw = CornealCodeV8Analyzer.analyze(
            eye = "LEFT", frames = extractObs(leftImgs, true), displayEvents = events,
            startHostTimeSec = startSec, durationSec = durSec, chipSec = chipSec,
            scheme = scheme, stimulusDiameterMm = 22.0, fps = 30, lagPriorSec = 0.0833,
        )
        val rRaw = CornealCodeV8Analyzer.analyze(
            eye = "RIGHT", frames = extractObs(rightImgs, false), displayEvents = events,
            startHostTimeSec = startSec, durationSec = durSec, chipSec = chipSec,
            scheme = scheme, stimulusDiameterMm = 22.0, fps = 30, lagPriorSec = 0.0833,
        )
        println(
            "[RealRun2-Manchester-HonestReject] L(ver=${lRaw.codeVerified}, held=${lRaw.heldOutCorrelation}, offFinal=${lRaw.emissionAudit?.finalOffMeasurements}) " +
                "| R(ver=${rRaw.codeVerified}, held=${rRaw.heldOutCorrelation}, offFinal=${rRaw.emissionAudit?.finalOffMeasurements})"
        )
        assertTrue(!lRaw.codeVerified && !rRaw.codeVerified, "Run #2 原始视频本身无有效 4Hz 角膜闪烁信号，算法不应产生假阳性通过")
        assertTrue((lRaw.emissionAudit?.finalOffMeasurements ?: 1) == 0 && (rRaw.emissionAudit?.finalOffMeasurements ?: 1) == 0)
    }

    @Test
    fun realRun3SteadyNearPixelsSuppressOversizedCeilingLampAndFixFalseHighRisk() {
        val root = replayRoot() ?: return
        val sessionDir = File(root, "SUBJ-001_641639347")
        val cacheDir = File(root, "replay_cache/run3_steady_near")
        if (!sessionDir.isDirectory || !cacheDir.isDirectory) return

        val report = LabSessionReport.fromJson(File(sessionDir, "session_summary.json").readText())
        val nearFrames = report.frames.filter { it.stage == LabStage.NEAR_FIXATION }
        val meta = File(cacheDir, "meta.txt").readLines().associate {
            val (k, v) = it.split("=", limit = 2)
            k to v
        }
        val count = meta["count"]!!.toInt()
        val w = meta["width"]!!.toInt()
        val h = meta["height"]!!.toInt()
        val leftImgs = readGrayPack(File(cacheDir, "left.bin"), count, w, h)
        val rightImgs = readGrayPack(File(cacheDir, "right.bin"), count, w, h)

        val n = min(count, nearFrames.size)
        val startSec = nearFrames.first().timestampMs / 1000.0
        val endSec = nearFrames[n - 1].timestampMs / 1000.0
        val durSec = (endSec - startSec).coerceAtLeast(2.0)
        val events = (0..((durSec * 30).toInt() + 5)).map { i ->
            val t = i / 30.0
            LabDisplayEvent(startSec + t, startSec + t, 1.0, 1.0, (t / 0.25).toInt(), t)
        }
        val boxRadius = (w / (2.0 * 2.35)).toFloat()

        fun extractObs(imgs: List<GrayImage>, isLeft: Boolean): List<GlintObservationFrame> = (0 until n).map { i ->
            val fr = nearFrames[i]
            val img = imgs[i]
            val origIrisD = (if (isLeft) fr.leftIrisDiameterPx else fr.rightIrisDiameterPx)?.toDouble() ?: 110.0
            val origRadius = origIrisD * 0.5
            val expOrig = OpticalBudgetEstimate.expectedDiameterPx(
                distanceCm = fr.distanceCm.toDouble().coerceAtLeast(20.0),
                irisPixels = origIrisD,
                sourceMm = 22.0,
            ) ?: 3.5
            val zoom = boxRadius / origRadius
            val expBox = (expOrig * zoom).toFloat()
            val eye = boxEyeLandmarks(if (isLeft) "LEFT" else "RIGHT", w, h, boxRadius)
            val meas = EyeOpticalAlgorithm.measureScreenPoint(img, eye, 0.80f, expectedDiameterPx = expBox)
            val cands = (meas?.reflexCandidates ?: emptyList()).map { c ->
                val relX = (c.point.x - w * 0.5f) / boxRadius
                val relY = (c.point.y - h * 0.5f) / boxRadius
                val ix = (if (isLeft) fr.leftIrisX else fr.rightIrisX) ?: 1000f
                val iy = (if (isLeft) fr.leftIrisY else fr.rightIrisY) ?: 1600f
                c.copy(
                    point = Point2f((ix + relX * origRadius).toFloat(), (iy + relY * origRadius).toFloat()),
                    diameterPx = (c.diameterPx / zoom).toFloat(),
                )
            }
            GlintObservationFrame(
                frameIndex = fr.index,
                hostTimeSec = fr.timestampMs / 1000.0,
                irisCenterX = (if (isLeft) fr.leftIrisX else fr.rightIrisX)?.toDouble() ?: 1000.0,
                irisCenterY = (if (isLeft) fr.leftIrisY else fr.rightIrisY)?.toDouble() ?: 1600.0,
                irisRadiusPx = origRadius,
                distanceCm = fr.distanceCm.toDouble().coerceAtLeast(20.0),
                exposureSec = 0.015,
                visible = true,
                candidates = cands,
            )
        }

        val lNew = CornealCodeV8Analyzer.analyze(
            eye = "LEFT", frames = extractObs(leftImgs, true), displayEvents = events,
            startHostTimeSec = startSec, durationSec = durSec, chipSec = 0.25,
            scheme = LabCodingScheme.STEADY, stimulusDiameterMm = 22.0, fps = 30,
        )
        val rNew = CornealCodeV8Analyzer.analyze(
            eye = "RIGHT", frames = extractObs(rightImgs, false), displayEvents = events,
            startHostTimeSec = startSec, durationSec = durSec, chipSec = 0.25,
            scheme = LabCodingScheme.STEADY, stimulusDiameterMm = 22.0, fps = 30,
        )
        val lOld = report.nearLeftCode
        val rOld = report.nearRightCode
        // 用线性公式对比修复前后 HDE（nasalSign = -1，因 iPhone 不镜像 leftIrisX > rightIrisX）
        val k = 175.5 * 0.5
        val oldHde = report.nearMetrics?.hdePrism
        val lByIdx = lNew.frames.associateBy { it.frameIndex }
        val rByIdx = rNew.frames.associateBy { it.frameIndex }
        val newHdes = nearFrames.take(n).mapNotNull { fr ->
            val le = lByIdx[fr.index]?.takeIf { it.source.measured } ?: return@mapNotNull null
            val re = rByIdx[fr.index]?.takeIf { it.source.measured } ?: return@mapNotNull null
            val lx = le.irisRelX ?: return@mapNotNull null
            val rx = re.irisRelX ?: return@mapNotNull null
            ((-lx) - rx) * k
        }
        val newHdeMed = median(newHdes)
        println(
            "[RealRun3-Steady] OLD L ref=(${lOld?.referenceRelX},${lOld?.referenceRelY}) diam=${lOld?.opticalBudget?.observedMedianDiameterPx} " +
                "R ref=(${rOld?.referenceRelX},${rOld?.referenceRelY}) diam=${rOld?.opticalBudget?.observedMedianDiameterPx} oldHDE=$oldHde " +
                "-> NEW L ref=(${lNew.referenceRelX},${lNew.referenceRelY}) diam=${lNew.opticalBudget?.observedMedianDiameterPx} " +
                "R ref=(${rNew.referenceRelX},${rNew.referenceRelY}) diam=${rNew.opticalBudget?.observedMedianDiameterPx} newHDE=$newHdeMed (n=${newHdes.size})"
        )
        assertNotNull(newHdeMed)
        assertTrue(abs(newHdeMed) < abs(oldHde ?: 99.0), "修复后 Run #3 常亮眼位偏斜应显著低于旧版误锁天花板灯时的 $oldHde，实际=$newHdeMed")
    }
}
