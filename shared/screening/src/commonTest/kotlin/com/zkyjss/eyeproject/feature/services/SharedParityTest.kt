package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.camera.*
import com.zkyjss.eyeproject.core.model.*
import kotlin.test.*
import kotlin.math.sin
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

/** 同一组验收输入在 JVM 和 iOS Simulator 运行；验证的是记录到结论的共同规则。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedParityTest {
    @Test fun dryEyeGoldenSessionProducesHighRisk() {
        val trials = List(10) { DryEyeTrial(DryEyeStage.NATURAL,it * 6f,0.4f,0,0,600,40f,20f) } +
            List(5) { DryEyeTrial(DryEyeStage.OPEN,60f + it * 4,if (it == 0) 0.4f else 0.25f,1,1,600,40f,20f) }
        val session = DryEyeSession(60,0.5f,trials,List(20) { it * 3f } + listOf(61f,66f,71f,76f),
            0.95,0.95,0.05,false,true)
        val report = DryEyeAssessment.evaluate(session,mapOf("干眼症状" to "眼睛发干、发涩","症状频率" to "经常"))
        assertTrue(report.valid)
        assertEquals(ScreeningRiskLevel.HIGH,report.riskLevel)
        assertEquals(20.0,report.blinkRatePerMin)
        assertEquals(5.0,report.longestOpenSec)
        assertTrue(report.vmr!! < 0.85)
    }
    @Test fun presbyopiaGoldenSessionProducesLowRisk() {
        val answers = VisionEye.entries.flatMap { eye -> List(4) { VisionAnswer(ScreeningStep.NEAR_VISION,40f,0.8f,0,0,true,500,20f,eye) } } +
            List(8) { VisionAnswer(ScreeningStep.FLIPPER,if (it % 2 == 0) 50f else 25f,0.2f,1,1,true,500,20f) }
        val state = ScreeningState(fields = mapOf("年龄" to "50"),mode = PRESBYOPIA_MODE_STANDARD,answers = answers,
            nearPoints = listOf(NearPoint(20f),NearPoint(20f)),curvePoints = listOf(CurvePoint(60f,0.8f),CurvePoint(33f,0.8f)))
        val result = ScreeningAssessmentModel.presbyopia(state)
        assertEquals(ScreeningRiskLevel.LOW,result.riskLevel)
        assertEquals(5.0,result.features["amplitude_d"])
        assertEquals(4.0,result.features["flipper_cycles_per_min"])
    }
    @Test fun missingMeasurementsNeverBecomeNormal() {
        assertEquals(ScreeningRiskLevel.UNKNOWN,ScreeningAssessmentModel.presbyopia(ScreeningState()).riskLevel)
        assertEquals(ScreeningRiskLevel.UNKNOWN,ScreeningAssessmentModel.strabismus(ScreeningState(),emptyList(),0,0).riskLevel)
    }
    @Test fun strabismusGoldenSessionProducesHighRisk() {
        val fixation = List(40) { i -> EyeMotionSample(i * 100L,ScreeningStep.FIXATION,0f,0f,0f,0f,33f,
            leftNasal = 0.18f,rightNasal = 0.1f,leftVertical = 0f,rightVertical = 0f,gazeOnTarget = true) }
        val marks = listOf(CoverMark("移开遮盖，双眼注视",5_000,uncovered = VisionEye.RIGHT),
            CoverMark("移开遮盖，双眼注视",6_000,uncovered = VisionEye.LEFT))
        val cover = marks.flatMap { mark -> listOf(100L,400L).map { offset ->
            EyeMotionSample(mark.timeMs + offset,ScreeningStep.COVER,0f,0f,0f,0f,33f,
                leftNasal = 0.1f,rightNasal = 0.1f,leftVertical = 0f,rightVertical = 0f,gazeOnTarget = true)
        }}
        val pursuit = List(72) { i ->
            val segment = i / 24; val a = sin(i * 0.3f) * 0.2f
            val x = if (segment == 1) 0f else a; val y = if (segment == 0) 0f else a
            EyeMotionSample(7_000 + i * 100L,ScreeningStep.PURSUIT,x,y,x,y,33f,pursuitSegment = segment,
                targetScreenX = 0.5f + x,targetScreenY = 0.5f + y,
                leftGazeX = 0.5f + x,leftGazeY = 0.5f + y,rightGazeX = 0.5f + x,rightGazeY = 0.5f + y,gazeOnTarget = true)
        }
        val result = ScreeningAssessmentModel.strabismus(ScreeningState(kind = ScreeningKind.STRABISMUS,
            coverMarks = marks,gazeCalibrated = true),fixation + cover + pursuit,100,90)
        assertEquals(ScreeningRiskLevel.HIGH,result.riskLevel)
        assertEquals("高度疑似斜视",result.category)
        assertEquals(14.04,result.features["fixation_hde_prism"]!!,0.05)
        assertEquals(1.0,result.features["bsi"]!!,0.01)
    }
    @Test fun oneReliableEyeCanCalibrateWithoutHidingTheOtherEye() {
        val samples = StrabismusGaze.calibrationTargets.flatMapIndexed { index,p ->
            List(6) { GazeCalibrationSample(index,p,GazePoint((p.x - 0.5f) / 5,(p.y - 0.5f) / 3),null) }
        }
        val mapping = assertNotNull(StrabismusGaze.calibrate(samples))
        val estimate = mapping.estimate(GazePoint(0f,0f),null)
        assertTrue(StrabismusGaze.onTarget(estimate.first,GazePoint(0.5f,0.5f)))
        assertNull(estimate.second)
        assertEquals(3_000L,StrabismusGaze.CALIBRATION_POINT_MS)
        assertEquals(1_200L,StrabismusGaze.SETTLE_MS)
    }
    @Test fun opticalPixelsAreProcessedByTheSameAlgorithm() {
        val image = object : GrayImage {
            override val width = 40; override val height = 40
            override fun grayRegion(left: Int,top: Int,width: Int,height: Int) = IntArray(width * height) { i ->
                val x = left + i % width; val y = top + i / width
                when { x == 22 && y == 18 -> 255; (x - 20) * (x - 20) + (y - 20) * (y - 20) <= 16 -> 10; else -> 100 }
            }
        }
        val eye = EyeLandmarks("LEFT",emptyList(),listOf(Point2f(20f,20f)),20f)
        val measurement = assertNotNull(EyeOpticalAlgorithm.measure(image,eye))
        assertTrue(measurement.usable)
        assertEquals(Point2f(22f,18f),measurement.cornealReflex)
    }
    @Test fun optotypeGeometryUsesPhysicalDistanceAndDensity() {
        val display = DisplayGeometry(1170,2532,460f,460f)
        val far = OptotypeGeometry.calculate(display,0.5f,40f)
        val near = OptotypeGeometry.calculate(display,0.5f,20f)
        assertTrue(far.displayable)
        assertEquals(far.sizePx / 2,near.sizePx,0.001f)
        assertFalse(OptotypeGeometry.calculate(DisplayGeometry(0,0,0f,0f),0.5f,40f).displayable)
    }
    @Test fun nonFiniteRecordCoordinatesAreNull() {
        val record = RecordObject().apply { put("leftX",Float.NaN); put("rightX",0.5f); put("missing",RecordObject.NULL) }
        val json = Json.parseToJsonElement(record.toString()).jsonObject
        assertEquals(JsonNull,json["leftX"])
        assertEquals(JsonNull,json["missing"])
        assertEquals(0.5f,json["rightX"]!!.jsonPrimitive.float)
    }
    @Test fun mobileBridgeStartsEveryTestAtTheSharedQuestionnaire() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = MobileScreeningController({}, { _, _ -> })
        try {
            controller.configureDisplay(1170,2532,18f)
            for (kind in listOf("DRY_EYE","PRESBYOPIA","STRABISMUS")) {
                controller.selectTest(kind)
                val state = Json.parseToJsonElement(controller.snapshotJson()).jsonObject
                assertEquals(kind,state["kind"]!!.jsonPrimitive.content)
                assertTrue(state["phase"]!!.jsonPrimitive.content in setOf("Profile","PROFILE"))
                assertFalse(controller.proceedFromProfile(), "未选被测者不能进入测量")
                assertEquals(0, controller.voiceAnswer("无关内容", null))
            }
        } finally { controller.close(); Dispatchers.resetMain() }
    }
    @Test fun calibrationFailureAndInterruptionDoNotAdvanceOldTimers() = runTest {
        val platform = TestPlatform { 1_000 + testScheduler.currentTime }
        val engine = ScreeningEngine(platform,backgroundScope)
        engine.configure(ScreeningKind.STRABISMUS); engine.setField("年龄","6"); engine.nextFromProfile(); engine.startCamera()
        engine.begin()
        assertTrue(platform.feedbackEvents.isEmpty(), "没有满足门控时不确认开始")
        runCurrent()
        repeat(8) { index ->
            val now = platform.nowMs()
            val eye = EyeLandmarks("LEFT",listOf(Point2f(0f,0f),Point2f(20f,-10f),Point2f(60f,-10f),Point2f(80f,0f),Point2f(60f,10f),Point2f(20f,10f)),
                listOf(Point2f(40f,0f)),11.7f * 400 / 330)
            platform.onFrame!!(FaceLandmarkFrame(listOf(FaceMetrics(BoundingBox(0f,0f,300f,300f),eye,eye,
                headPose = HeadPoseEstimate(0f,0f,0f))),400,400,now,0,15f,150f,now))
            advanceTimeBy(100); runCurrent()
        }
        engine.begin(); engine.confirmGuide(); runCurrent()
        assertEquals(listOf(ScreeningFeedbackCue.START), platform.feedbackEvents)
        engine.answer(0); engine.markCover()
        assertEquals(listOf(ScreeningFeedbackCue.START), platform.feedbackEvents, "非作答与遮盖阶段不确认输入")
        assertEquals(ScreeningStep.GAZE_CALIBRATION,engine.state.value.step)
        // 没有有效帧时校准暂停、每点顺延：按原 3 秒/点不会结束，到每点最长等待后才判失败
        advanceTimeBy(StrabismusGaze.calibrationTargets.size * StrabismusGaze.CALIBRATION_POINT_MS); runCurrent()
        assertFalse(engine.state.value.gazeCalibrationFailed)
        advanceTimeBy(StrabismusGaze.calibrationTargets.size * StrabismusGaze.CALIBRATION_POINT_MAX_MS); runCurrent()
        assertTrue(engine.state.value.gazeCalibrationFailed)
        engine.retryGazeCalibration(); runCurrent(); engine.interruptTest()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(ScreeningStep.ENVIRONMENT,engine.state.value.step)
        assertFalse(engine.state.value.gazeCalibrated)
        engine.close()
    }
    @Test fun calibrationPausesWhileEyesAreNotTrackedAndResumes() = runTest {
        val platform = TestPlatform { 1_000 + testScheduler.currentTime }
        val engine = ScreeningEngine(platform,backgroundScope)
        engine.configure(ScreeningKind.STRABISMUS); engine.setField("年龄","8"); engine.nextFromProfile(); engine.startCamera(); runCurrent()
        val eye = EyeLandmarks("LEFT",listOf(Point2f(0f,0f),Point2f(20f,-10f),Point2f(60f,-10f),Point2f(80f,0f),Point2f(60f,10f),Point2f(20f,10f)),
            listOf(Point2f(40f,0f)),11.7f * 400 / 330)
        fun frame(withFace: Boolean) {
            val now = platform.nowMs()
            platform.onFrame!!(FaceLandmarkFrame(if (withFace) listOf(FaceMetrics(BoundingBox(0f,0f,300f,300f),eye,eye,
                headPose = HeadPoseEstimate(0f,0f,0f))) else emptyList(),400,400,now,0,30f,150f,now))
        }
        repeat(8) { frame(true); advanceTimeBy(100); runCurrent() }
        engine.begin(); engine.confirmGuide(); runCurrent()
        assertEquals(ScreeningStep.GAZE_CALIBRATION,engine.state.value.step)
        repeat(40) { frame(false); advanceTimeBy(100); runCurrent() } // 4 秒：旧逻辑 3 秒就换点
        assertTrue(engine.state.value.gazeCalibrationPaused, "看不到双眼时校准应暂停")
        assertTrue(engine.state.value.gazeWarning!!.contains("暂停"))
        assertEquals(0,engine.state.value.gazeCalibrationIndex, "暂停期间不换点")
        repeat(3) { frame(true); advanceTimeBy(100); runCurrent() }
        assertFalse(engine.state.value.gazeCalibrationPaused)
        engine.close()
    }
    @Test fun standardModeWithReadingGlassesAsksToRemoveThemFirst() = runTest {
        val platform = TestPlatform { 1_000 + testScheduler.currentTime }
        val engine = ScreeningEngine(platform,backgroundScope)
        engine.configure(ScreeningKind.PRESBYOPIA); engine.setField("年龄","55"); engine.setField("日常矫正方式","老花镜")
        engine.setMode(PRESBYOPIA_MODE_STANDARD); engine.nextFromProfile(); engine.startCamera(); runCurrent()
        repeat(20) {
            val now = platform.nowMs()
            val eye = EyeLandmarks("LEFT",listOf(Point2f(0f,0f),Point2f(20f,-10f),Point2f(60f,-10f),Point2f(80f,0f),Point2f(60f,10f),Point2f(20f,10f)),
                listOf(Point2f(40f,0f)),11.7f * 400 / 400)
            platform.onFrame!!(FaceLandmarkFrame(listOf(FaceMetrics(BoundingBox(0f,0f,300f,300f),eye,eye,
                headPose = HeadPoseEstimate(0f,0f,0f))),400,400,now,0,30f,150f,now))
            advanceTimeBy(100); runCurrent()
        }
        engine.begin(); runCurrent()
        assertEquals(ScreeningStep.NEAR_VISION,engine.state.value.step)
        assertTrue(engine.state.value.glassesCheckPending)
        assertEquals(GLASSES_REMOVAL_PROMPT,screeningGuideText(engine.state.value))
        engine.confirmGuide()
        assertTrue(engine.state.value.guidePending, "确认摘镜前不能跳过")
        engine.confirmGlassesRemoved()
        assertFalse(engine.state.value.glassesCheckPending); assertTrue(engine.state.value.glassesRemovedConfirmed)
        engine.confirmGuide()
        assertFalse(engine.state.value.guidePending)
        engine.close()
    }
    @Test fun backgroundingDuringEnvironmentCheckOnlyReleasesTheCamera() = runTest {
        val platform = TestPlatform { 1_000 + testScheduler.currentTime }
        val engine = ScreeningEngine(platform,backgroundScope)
        engine.configure(ScreeningKind.STRABISMUS); engine.setField("年龄","6"); engine.nextFromProfile(); engine.startCamera()
        engine.interruptTest(); runCurrent()
        assertEquals(ScreeningStep.ENVIRONMENT,engine.state.value.step)
        assertNull(engine.state.value.warning, "尚未开始的环境检查不应提示测试已中断")
        assertNull(platform.onFrame)
        engine.close()
    }
    @Test fun dryEyeFollowUpsOnlyStartFromEligibleResults() = runTest {
        val report = DryEyeReport(true,emptyList(),ScreeningRiskLevel.MEDIUM,emptyList(),emptyList(),"",false,
            null,null,null,null,null,null,null,emptyMap())
        val result = DryEyeTestUiState(phase = DryEyePhase.Result,report = report)
        assertTrue(result.canExtend); assertTrue(result.canRestRetest)
        assertFalse(result.copy(naturalSeconds = DryEyeProtocol.EXTENDED_NATURAL_SECONDS).canExtend, "已延长过不再延长")
        assertFalse(result.copy(afterRest = true).canRestRetest, "休息复测只做一次")
        assertFalse(result.copy(report = report.copy(valid = false)).canExtend)
        assertFalse(result.copy(phase = DryEyePhase.Setup).canRestRetest)
        val engine = DryEyeEngine(TestPlatform { testScheduler.currentTime },backgroundScope)
        engine.startExtended(); engine.startRestRetest(); runCurrent()
        assertEquals(DryEyePhase.Profile,engine.uiState.value.phase, "没有结果时不能进入延长或复测")
        engine.close()
    }
    @Test fun mobileBridgeKeepsQuestionnaireWhenBackgrounded() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val events = mutableListOf<String>()
        val controller = MobileScreeningController({ events += it }, { _, _ -> })
        try {
            controller.configureDisplay(1170,2532,18f)
            controller.selectTest("DRY_EYE")
            controller.interrupt(); controller.resume()
            val state = Json.parseToJsonElement(controller.snapshotJson()).jsonObject
            assertEquals("Profile",state["phase"]!!.jsonPrimitive.content, "问卷阶段切后台不能跳到准备页")
            assertFalse("camera:start" in events, "问卷阶段回到前台不启动相机")
        } finally { controller.close(); Dispatchers.resetMain() }
    }
    private class TestPlatform(private val time: () -> Long) : ScreeningPlatform {
        val feedbackEvents = mutableListOf<ScreeningFeedbackCue>()
        override fun feedback(cue: ScreeningFeedbackCue) { feedbackEvents += cue }
        override val display = DisplayGeometry(400,800,160f,160f)
        var onFrame: ((FaceLandmarkFrame) -> Unit)? = null
        override fun nowMs() = time()
        override fun epochMs() = time()
        override fun cameraGeometry(width: Int,height: Int) = CameraGeometry(width,height,400f)
        override fun startCamera(onFrame: (FaceLandmarkFrame) -> Unit,onError: (Throwable) -> Unit) { this.onFrame = onFrame }
        override fun stopCamera() { onFrame = null }
        override fun startLight(onLux: (Float) -> Unit) = false
        override suspend fun saveRecord(name: String,json: String) = Unit
        override suspend fun compareHistory(state: ScreeningState,assessment: ScreeningAssessment) = emptyList<String>()
    }
}
