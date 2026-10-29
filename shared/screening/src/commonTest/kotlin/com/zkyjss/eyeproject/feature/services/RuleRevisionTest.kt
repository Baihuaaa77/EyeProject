package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.camera.EyeOpticalAlgorithm
import com.zkyjss.eyeproject.core.camera.GrayImage
import com.zkyjss.eyeproject.core.domain.BlinkDetector
import com.zkyjss.eyeproject.core.model.EyeLandmarks
import com.zkyjss.eyeproject.core.model.Point2f
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.*
import kotlinx.serialization.json.*

/** rules-2：测量噪声不能直接算成异常。模拟回归 + 真机问题复现。 */
class RuleRevisionTest {

    // region 干眼：心理测量模型的模拟标定

    /** 按代码中的阶梯规则模拟一次 12 秒练习 + 60 + 20 秒正式测试；[threshold] 为随时间变化的真实阈值（logMAR）。 */
    private fun simulate(random: Random, threshold: (Double) -> Double): List<DryEyeTrial> {
        fun answer(level: Float, t: Double): Boolean {
            val l = -log10(level.toDouble())
            val p = 0.25 + (0.75 - 0.03) / (1 + exp(-(l - threshold(t)) / 0.05))
            return random.nextDouble() < p
        }
        val stair = DryEyeStaircase()
        var t = 0.0
        while (t < 12) { stair.record(answer(stair.level, 0.0)); t += 1.0 }
        val trials = mutableListOf<DryEyeTrial>()
        t = 0.0
        while (t < 80) {
            val ok = answer(stair.level, t)
            trials += DryEyeTrial(if (t < 60) DryEyeStage.NATURAL else DryEyeStage.OPEN, t.toFloat(), stair.level, 0, if (ok) 0 else 1, 700L, 40f, 30f)
            stair.record(ok)
            t += if (ok) 0.9 else 1.6
        }
        return trials
    }

    private fun fit(trials: List<DryEyeTrial>) = assertNotNull(VisualStability.fit(
        trials.map { VisualStability.Response(it.onsetSec.toDouble(), it.level.toDouble(), it.correct) }, 60.0))

    @Test fun stableVisionIsAlmostNeverCalledAbnormal() {
        val random = Random(20261016)
        val runs = 40
        val abnormal = (0 until runs).count {
            val f = fit(simulate(random) { 0.3 })
            f.naturalStatus == EvidenceStatus.ABNORMAL || f.openStatus == EvidenceStatus.ABNORMAL
        }
        // 原规则（VMR < 0.85 或下降 ≥ 2 档）在同样条件下约一半触发
        assertTrue(abnormal <= 2, "视力恒定却判异常 $abnormal/$runs")
    }

    @Test fun realDeclineDuringOpenPhaseIsDetected() {
        val random = Random(7)
        val runs = 30
        val flagged = (0 until runs).count {
            val f = fit(simulate(random) { t -> 0.3 + if (t > 60) 0.3 * (t - 60) / 20 else 0.0 })
            f.openStatus != EvidenceStatus.NORMAL
        }
        assertTrue(flagged >= runs * 0.8, "真实下降 0.3 logMAR 只有 $flagged/$runs 被标出")
    }

    // endregion

    // region 干眼：真机问题复现

    private fun steadyTrials(timeoutsFrom: Float? = null, timeoutsTo: Float? = null) =
        generateSequence(0f) { it + 1.2f }.takeWhile { it < 80f }.map { t ->
            val timeout = timeoutsFrom != null && t >= timeoutsFrom && t < timeoutsTo!!
            DryEyeTrial(if (t < 60f) DryEyeStage.NATURAL else DryEyeStage.OPEN, t, 0.5f, 0,
                if (timeout) null else 0, if (timeout) null else 700L, 40f, 30f)
        }.toList()

    private fun session(trials: List<DryEyeTrial>, blinks: List<Float>, fps: Double?) = DryEyeSession(60, 0.5f, trials, blinks,
        0.95, 0.9, 0.05, false, true, analysisFps = fps)

    @Test fun unansweredRunIsNotReadAsVisualDecline() {
        // 复现 iPhone 记录：12–35 秒连续不作答。旧规则把阶梯下滑算成「看久了容易模糊」
        val trials = steadyTrials(12f, 20f)
        assertTrue(DryEyeAssessment.unansweredTrials(trials).size >= 3)
        val blinks = (1..12).map { it * 5f - 2f } + listOf(75f)
        val report = DryEyeAssessment.evaluate(session(trials, blinks, 30.0), emptyMap())
        val visual = report.evidence.first { it.key == "visual" }
        assertNotEquals(EvidenceStatus.ABNORMAL, visual.status, visual.summary)
    }

    @Test fun lowFrameRateMakesBlinkIndicatorsUnavailableInsteadOfAbnormal() {
        // 5–10 fps 时只记到 3 次眨眼：旧规则判「眨眼过少」
        val report = DryEyeAssessment.evaluate(session(steadyTrials(), listOf(9f, 16f, 37f, 68f), 8.0), emptyMap())
        val blink = report.evidence.first { it.key == "blink" }
        assertEquals(EvidenceStatus.UNAVAILABLE, blink.status)
        assertNotEquals(ScreeningRiskLevel.MEDIUM, report.riskLevel)
        assertTrue(report.abnormal.none { it.contains("眨眼") })
    }

    @Test fun borderlineBlinkCountIsUncertainNotAbnormal() {
        // 60 秒 7 次（7 次/分）：误差范围覆盖 10 次/分 → 不确定，建议延长
        val blinks = (1..7).map { it * 8f } + listOf(75f)
        val report = DryEyeAssessment.evaluate(session(steadyTrials(), blinks, 30.0), emptyMap())
        assertEquals(EvidenceStatus.UNCERTAIN, report.evidence.first { it.key == "blink" }.status)
        assertEquals(ScreeningRiskLevel.UNCERTAIN, report.riskLevel)
        assertTrue(DryEyeTestUiState(phase = DryEyePhase.Result, report = report).canExtend)
    }

    @Test fun poissonIntervalCoversTheCount() {
        val (low, high) = poissonInterval(12)
        assertTrue(low < 12 && high > 12)
        assertEquals(0.0, poissonInterval(0).first)
        assertEquals(EvidenceStatus.NORMAL, guardBand(12.0, 6.0, 20.0, 10.0, 16.0))
        assertEquals(EvidenceStatus.UNCERTAIN, guardBand(7.0, 3.0, 13.0, 10.0, 16.0))
        assertEquals(EvidenceStatus.ABNORMAL, guardBand(3.0, 1.0, 8.0, 10.0, 16.0))
    }

    @Test fun timedBlinkDetectionDoesNotDependOnFrameRate() {
        // 10 fps：一次 150 ms 的闭眼只落在 1–2 帧上，旧的「连续 3 帧」会漏掉
        val slow = BlinkDetector(minClosedMs = 60)
        listOf(0.3f to 0L, 0.1f to 100L, 0.3f to 200L).forEach { (ear, t) -> slow.update(ear, t) }
        assertEquals(1, slow.blinkCount)
        // 30 fps 单帧抖动（33 ms）不算眨眼
        val fast = BlinkDetector(minClosedMs = 60)
        listOf(0.3f to 0L, 0.1f to 33L, 0.3f to 66L).forEach { (ear, t) -> fast.update(ear, t) }
        assertEquals(0, fast.blinkCount)
    }

    @Test fun adaptiveThresholdFollowsNarrowEyes() {
        // 眼裂小的人睁眼 EAR 只有 0.2，固定 0.23 阈值会一直判为闭眼、永远数不到眨眼
        val detector = BlinkDetector(minClosedMs = 60, adaptive = true)
        var t = 0L
        repeat(30) { detector.update(0.2f, t); t += 33 }
        repeat(4) { detector.update(0.08f, t); t += 33 }
        detector.update(0.2f, t)
        assertEquals(1, detector.blinkCount)
        assertTrue(detector.threshold < 0.2f)
    }

    // endregion

    // region 老视

    private fun presbyopia(age: Int, nearCm: Float, mode: String = PRESBYOPIA_MODE_STANDARD, level: Float = 0.8f,
                           fields: Map<String, String> = emptyMap()): ScreeningAssessment {
        val answers = VisionEye.entries.flatMap { eye -> List(4) { VisionAnswer(ScreeningStep.NEAR_VISION, 40f, level, 0, 0, true, 500, 20f, eye) } } +
            List(8) { VisionAnswer(ScreeningStep.FLIPPER, if (it % 2 == 0) 50f else 25f, 0.2f, 1, if (it < 2) 1 else 0, it < 2, 500, 20f) }
        return ScreeningAssessmentModel.presbyopia(ScreeningState(fields = mapOf("年龄" to "$age") + fields, mode = mode, answers = answers,
            nearPoints = listOf(NearPoint(nearCm), NearPoint(nearCm + 0.5f)), curvePoints = listOf(CurvePoint(60f, 0.8f), CurvePoint(33f, 0.8f))))
    }

    @Test fun ageCutoffNoLongerFlipsAt45() {
        // 44 岁、约 4.6 D：高于同龄最低 4 D，旧规则因「< 45 岁且 < 5 D」判高风险
        val result = presbyopia(44, 21.5f)
        assertEquals(ScreeningRiskLevel.LOW, result.riskLevel)
    }

    @Test fun slowFlipperAloneIsNotGraded() {
        // 远近切换只完成 1 次（含挥动手机的时间），旧规则据此判中风险
        assertEquals(ScreeningRiskLevel.LOW, presbyopia(50, 25f).riskLevel)
    }

    @Test fun amplitudeJustBelowReferenceIsUncertain() {
        // 50 岁最低参考 2.5 D；近点 41 cm ≈ 2.4 D，在测距误差范围内
        val result = presbyopia(50, 41f)
        assertEquals(ScreeningRiskLevel.UNCERTAIN, result.riskLevel)
        assertEquals(EvidenceStatus.UNCERTAIN, result.evidence.first { it.key == "amplitude" }.status)
    }

    @Test fun dailyModeIsNotComparedWithAgeNorms() {
        val result = presbyopia(50, 60f, mode = PRESBYOPIA_MODE_DAILY)
        assertEquals(EvidenceStatus.UNAVAILABLE, result.evidence.first { it.key == "amplitude" }.status)
        assertEquals(ScreeningRiskLevel.LOW, result.riskLevel)
        assertEquals(ScreeningRiskLevel.MEDIUM, presbyopia(50, 60f, mode = PRESBYOPIA_MODE_DAILY, level = 0.3f).riskLevel)
    }

    @Test fun uncorrectedRefractionMakesAmplitudeUncertain() {
        val result = presbyopia(45, 60f, fields = mapOf("屈光情况" to "远视", "日常矫正方式" to "不戴眼镜"))
        assertEquals(EvidenceStatus.UNCERTAIN, result.evidence.first { it.key == "amplitude" }.status)
    }

    // endregion

    // region 斜视

    private fun strabismus(nasal: (Int) -> Float, pursuitDeg: Pair<Float, Float>? = null, coordinated: Boolean = true,
                           far: ((Int) -> Float)? = null, farFrames: Int = 100): ScreeningAssessment {
        val fixation = List(120) { i -> EyeMotionSample(i * 100L, ScreeningStep.FIXATION, 0f, 0f, 0f, 0f, 33f,
            leftNasal = 0.1f + nasal(i), rightNasal = 0.1f, leftVertical = 0f, rightVertical = 0f, gazeOnTarget = true) }
        val marks = listOf(CoverMark("移开遮盖，双眼注视", 15_000, uncovered = VisionEye.RIGHT),
            CoverMark("移开遮盖，双眼注视", 16_000, uncovered = VisionEye.LEFT))
        val cover = marks.flatMap { mark -> listOf(100L, 400L).map { offset ->
            EyeMotionSample(mark.timeMs + offset, ScreeningStep.COVER, 0f, 0f, 0f, 0f, 33f,
                leftNasal = 0.1f, rightNasal = 0.1f, leftVertical = 0f, rightVertical = 0f, gazeOnTarget = true)
        } }
        val pursuit = List(72) { i ->
            val segment = i / 24; val a = sin(i * 0.3f) * 0.2f
            val b = if (coordinated) a else kotlin.math.cos(i * 0.3f) * 0.2f
            val (lx, ly) = when (segment) { 0 -> a to 0f; 1 -> 0f to a; else -> a to a }
            val (rx, ry) = when (segment) { 0 -> b to 0f; 1 -> 0f to b; else -> b to b }
            EyeMotionSample(17_000 + i * 100L, ScreeningStep.PURSUIT, lx, ly, rx, ry, 33f, pursuitSegment = segment,
                targetScreenX = 0.5f + lx, targetScreenY = 0.5f + ly,
                leftGazeX = 0.5f + lx, leftGazeY = 0.5f + ly, rightGazeX = 0.5f + lx, rightGazeY = 0.5f + ly, gazeOnTarget = true)
        }
        return ScreeningAssessmentModel.strabismus(ScreeningState(kind = ScreeningKind.STRABISMUS, coverMarks = marks,
            gazeCalibrated = true, pursuitMaxDeg = pursuitDeg), fixation + cover + pursuit + farSamples(far, farFrames), 100, 90)
    }

    /** 看远 10 秒（每 100 ms 一帧）；看远不监督屏幕注视，gazeOnTarget 为空。 */
    private fun farSamples(far: ((Int) -> Float)?, frames: Int) = if (far == null) emptyList() else List(frames) { i ->
        EyeMotionSample(30_000 + i * 100L, ScreeningStep.FAR_FIXATION, 0f, 0f, 0f, 0f, 33f,
            leftNasal = 0.1f + far(i), rightNasal = 0.1f, leftVertical = 0f, rightVertical = 0f)
    }

    @Test fun deviationOnlyAtDistanceIsFlagged() {
        // 看近正常、看远约 17Δ：提示只在看远时出现的间歇性斜视
        val result = strabismus({ 0f }, far = { 0.1f })
        assertEquals(EvidenceStatus.NORMAL, result.evidence.first { it.key == "alignment" }.status)
        assertEquals(EvidenceStatus.ABNORMAL, result.evidence.first { it.key == "far" }.status)
        assertEquals(ScreeningRiskLevel.HIGH, result.riskLevel)
        assertTrue(result.findings.any { "间歇性" in it })
        // 前 1.5 秒视线转向远处、调节放松，不计入
        assertEquals(85.0, result.features["far_frames"])
    }

    @Test fun missingDistanceDataOnlySkipsTheDistanceEvidence() {
        // 看远拍不清（如手机挡住、头低得太多）：只是不评估看远，整次测试仍有效
        for (result in listOf(strabismus({ 0f }), strabismus({ 0f }, far = { 0.1f }, farFrames = 30))) {
            assertEquals(EvidenceStatus.UNAVAILABLE, result.evidence.first { it.key == "far" }.status)
            assertEquals(ScreeningRiskLevel.LOW, result.riskLevel)
        }
        assertEquals(EvidenceStatus.NORMAL, strabismus({ 0f }, far = { 0f }).evidence.first { it.key == "far" }.status)
    }

    @Test fun measurementJitterCannotHideALargeDeviation() {
        // 约 20Δ 的偏斜叠加 ±0.06 虹膜单位（约 ±10Δ）的逐帧抖动：旧规则判「注视不稳定」后不能报高度疑似
        val result = strabismus({ i -> 0.114f + if (i % 2 == 0) 0.06f else -0.06f })
        assertTrue(result.features["fixation_hde_sd_prism"]!! > StrabismusProtocol.STABLE_SD_H_PRISM)
        assertEquals(ScreeningRiskLevel.HIGH, result.riskLevel)
    }

    @Test fun borderlineNoisyDeviationIsUncertain() {
        // 约 11Δ、抖动大：误差范围覆盖 10Δ → 不确定（旧规则判未见异常）
        val result = strabismus({ i -> 0.063f + if (i % 2 == 0) 0.2f else -0.2f })
        assertEquals(EvidenceStatus.UNCERTAIN, result.evidence.first { it.key == "alignment" }.status)
        assertEquals(ScreeningRiskLevel.UNCERTAIN, result.riskLevel)
    }

    @Test fun smallScreenPursuitDoesNotDriveCoordination() {
        // 竖屏 iPhone 只能显示 ±4.8°：双眼速度相关被噪声主导，不再单独判「可疑」，也不再因此整份拒判
        val result = strabismus({ 0f }, pursuitDeg = 4.8f to 8.4f, coordinated = false)
        assertEquals(EvidenceStatus.UNAVAILABLE, result.evidence.first { it.key == "coordination" }.status)
        assertEquals(ScreeningRiskLevel.LOW, result.riskLevel)
        assertEquals(ScreeningRiskLevel.MEDIUM, strabismus({ 0f }, pursuitDeg = 9f to 9f, coordinated = false).riskLevel)
    }

    @Test fun lowContrastPupilFallsBackToIrisCenter() {
        // 深色虹膜：瞳孔与虹膜几乎同灰度，只有角膜反光点清晰
        val image = object : GrayImage {
            override val width = 40; override val height = 40
            override fun grayRegion(left: Int, top: Int, width: Int, height: Int) = IntArray(width * height) { i ->
                val x = left + i % width; val y = top + i / width
                if (x == 22 && y == 18) 255 else 60
            }
        }
        val measurement = assertNotNull(EyeOpticalAlgorithm.measure(image, EyeLandmarks("LEFT", emptyList(), listOf(Point2f(20f, 20f)), 20f)))
        assertTrue(measurement.usable)
        assertTrue(measurement.referenceFromIris)
        assertEquals(Point2f(20f, 20f), measurement.pupilCenter)
    }

    // endregion

    // region 结果页

    @Test fun resultReportsCarryEvidenceAndSerialize() {
        val dry = DryEyeAssessment.evaluate(session(steadyTrials(), (1..12).map { it * 5f - 2f } + listOf(75f), 30.0), emptyMap())
        val dryView = assertNotNull(ResultReports.dryEye(DryEyeTestUiState(phase = DryEyePhase.Result, report = dry)))
        assertEquals(3, dryView.evidence.size)
        assertEquals(4, dryView.metrics.size)
        assertEquals(ResultMood.HAPPY, dryView.mood)

        val assessment = strabismus({ 0.08f })
        val state = ScreeningState(kind = ScreeningKind.STRABISMUS, step = ScreeningStep.RESULT, riskLevel = assessment.riskLevel,
            category = assessment.category, findings = assessment.findings, features = assessment.features, evidence = assessment.evidence)
        val view = assertNotNull(ResultReports.screening(state))
        assertEquals("高度疑似", view.badge)
        val json = Json.parseToJsonElement(view.toRecordObject().toString()).jsonObject
        assertEquals("HIGH", json["level"]!!.jsonPrimitive.content)
        // 看近、看远、遮盖、协调四条依据
        assertEquals(4, json["evidence"]!!.jsonArray.size)
        assertTrue(json["sections"]!!.jsonArray.isNotEmpty())
    }

    // endregion
}
