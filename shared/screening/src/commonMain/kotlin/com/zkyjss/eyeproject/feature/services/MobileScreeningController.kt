package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.camera.GrayImage
import com.zkyjss.eyeproject.core.camera.LandmarkGeometry
import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
import com.zkyjss.eyeproject.core.model.FaceMetrics
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/**
 * 苹果相机每帧给出的内参焦距随对焦、防抖有千分之几的抖动；只有超过 2% 的变化（换镜头、测距校准）
 * 才视为新的相机标定。否则每帧都会清空测距窗口，近视力题永远凑不够稳定样本而拒收作答。
 */
internal fun focalChanged(current: Float, next: Float): Boolean =
    next.isFinite() && next > 0f && (current <= 0f || kotlin.math.abs(next - current) / current > 0.02f)

/** 苹果界面通过轻量快照与事件连接共用引擎；不在界面中复制流程与分级规则。 */
class MobileScreeningController(
    private val systemEvent: (String) -> Unit,
    private val recordSaved: (String, String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var display = DisplayGeometry(0,0,0f,0f)
    private var focalPx = 0f
    private var cameraVersion = 0L
    private var cameraFrame: ((FaceLandmarkFrame) -> Unit)? = null
    private var cameraError: ((Throwable) -> Unit)? = null
    private var kind = "DRY_EYE"
    private var closed = false
    private var captureStartedMs = 0L
    private val feedbackSession = ScreeningFeedbackSession()
    private val platform = object : ScreeningPlatform {
        override val display get() = this@MobileScreeningController.display
        override val platformName = "iOS"
        override val displaySizeSource = "USER_RULER_SCREEN_CALIBRATION"
        override fun nowMs() = SharedClock.nowMs()
        override fun epochMs() = SharedClock.epochMs()
        override fun cameraGeometry(width: Int,height: Int) = CameraGeometry(width,height,focalPx,cameraVersion)
        override fun startCamera(onFrame: (FaceLandmarkFrame) -> Unit,onError: (Throwable) -> Unit) {
            captureStartedMs = nowMs()
            cameraFrame = onFrame; cameraError = onError; systemEvent("camera:start")
        }
        override fun stopCamera() { cameraFrame = null; cameraError = null; systemEvent("camera:stop") }
        override fun feedback(cue: ScreeningFeedbackCue) = feedbackSession.offer(cue)
        override fun startLight(onLux: (Float) -> Unit) = false
        override suspend fun saveRecord(name: String,json: String) { recordSaved(name,json) }
        override suspend fun compareHistory(state: ScreeningState,assessment: ScreeningAssessment) = emptyList<String>()
    }
    private var screening = ScreeningEngine(platform,scope)
    private var dry = DryEyeEngine(platform,scope)

    fun configureDisplay(widthPixels: Int,heightPixels: Int,pixelsPerMm: Float) {
        require(widthPixels > 0 && heightPixels > 0 && pixelsPerMm.isFinite() && pixelsPerMm > 0)
        display = DisplayGeometry(widthPixels,heightPixels,pixelsPerMm * 25.4f,pixelsPerMm * 25.4f)
    }
    fun selectTest(name: String) {
        require(name in listOf("DRY_EYE","PRESBYOPIA","STRABISMUS"))
        dry.close(); screening.close()
        dry = DryEyeEngine(platform,scope); screening = ScreeningEngine(platform,scope)
        kind = name
        if (kind != "DRY_EYE") screening.configure(ScreeningKind.valueOf(kind))
    }
    fun setField(key: String,value: String) { if (kind == "DRY_EYE") dry.setField(key,value) else screening.setField(key,value) }
    fun setMode(mode: String) = screening.setMode(mode)
    fun profileComplete(): Boolean {
        val fields = if (kind == "DRY_EYE") dry.uiState.value.fields else screening.state.value.fields
        val valid = questions().filter { it.visibleWhen(fields) }.all { q ->
            q.optional || (!fields[q.key].isNullOrBlank() && q.validate(fields[q.key]!!))
        }
        if (!valid || display.xdpi <= 0) return false
        if (kind == "DRY_EYE") { dry.finishProfile(); dry.startMonitoring() }
        else {
            screening.nextFromProfile()
            if (screening.state.value.step != ScreeningStep.ENVIRONMENT) return false
            screening.startCamera()
        }
        return true
    }
    fun action(name: String) {
        when(name) {
            "begin" -> if (kind == "DRY_EYE") dry.startTest() else screening.begin()
            "practice" -> dry.confirmPracticeGuide()
            "formal" -> dry.confirmFormalGuide()
            "guide" -> screening.confirmGuide()
            "eyeReady" -> screening.readyForNearEye()
            "pushStart" -> screening.startPushTrial()
            "blur" -> screening.markPushBlur()
            "cover" -> screening.markCover()
            "calibrate" -> screening.retryGazeCalibration()
            "extended" -> dry.startExtended()
            "rest" -> dry.startRestRetest()
            "restart" -> if (kind == "DRY_EYE") dry.restartAll() else screening.restart()
            "skipProfile" -> if (display.xdpi > 0) {
                if (kind == "DRY_EYE") { dry.skipProfile(); dry.startMonitoring() } else { screening.skipProfile(); screening.startCamera() }
            }
            "retryCamera" -> if (kind == "DRY_EYE") { dry.stopMonitoring(); dry.startMonitoring() } else screening.retryCamera()
        }
    }
    fun answer(direction: Int,byVoice: Boolean) {
        if (kind == "DRY_EYE") dry.answer(direction,byVoice) else screening.answer(direction,byVoice)
    }
    fun voiceAnswer(text: String): Boolean {
        when(val command = VoiceCommandParser.parse(text)) {
            is VoiceCommand.Direction -> answer(command.direction,true)
            VoiceCommand.Unsure -> answer(-1,true)
            VoiceCommand.Blur -> action("blur")
            VoiceCommand.Ready -> {
                val s = screening.state.value
                if (s.step == ScreeningStep.PUSH_UP) action("pushStart")
                else if (s.nearAwaitingEyeReady) action("eyeReady")
            }
            null -> return false
        }
        return true
    }
    fun markPresented(trialId: Int) { if (kind != "DRY_EYE") screening.markTrialPresented(trialId,platform.nowMs()) }
    fun feedbackJson(enabled: Boolean): String {
        val frame = if (kind == "DRY_EYE") screeningFeedbackFrame(dry.uiState.value) else screeningFeedbackFrame(screening.state.value, screening.coverRemainingMs())
        val events = feedbackSession.poll(frame, platform.nowMs(), enabled && !closed)
        return RecordArray(events.map { cue -> RecordObject().apply {
            put("name", cue.name); put("sound", cue.sound); put("microphonePauseMs", cue.microphonePauseMs)
        } }).toString()
    }
    /** 送给 TTS 的口语化文本与距离提醒短句，与安卓共用同一套读法。 */
    fun speechText(text: String) = screeningSpeechText(text)
    fun distancePrompt(zone: Int, count: Int): String? = SpokenPrompts.distance(zone, count)
    val distanceOkPrompt: String get() = SpokenPrompts.DISTANCE_OK
    fun reportTarget(xPx: Float,yPx: Float) = screening.reportGazeTarget(xPx,yPx)
    fun reportPursuitCapacity(xDeg: Float,yDeg: Float) = screening.reportPursuitCapacity(xDeg,yDeg)
    /** 与安卓一致：问卷和结果页切后台不丢内容，只有进行中的任务回到准备页。 */
    fun interrupt() {
        if (kind == "DRY_EYE") {
            val phase = dry.uiState.value.phase
            if (phase != DryEyePhase.Profile && phase != DryEyePhase.Result) dry.backToSetup("测试已中断，请重新开始")
            dry.stopMonitoring()
        } else screening.interruptTest()
    }
    /** 回到前台后重新挂接相机；问卷阶段尚未需要相机。 */
    fun resume() {
        if (closed) return
        if (kind == "DRY_EYE") { if (dry.uiState.value.phase != DryEyePhase.Profile) dry.startMonitoring() }
        else if (screening.state.value.step == ScreeningStep.ENVIRONMENT) screening.startCamera()
    }
    fun close() { if (!closed) { closed = true; dry.close(); screening.close(); scope.cancel() } }
    fun cameraFailed(message: String) { cameraError?.invoke(IllegalStateException(message)) }
    fun cameraSample(points: FloatArray,matrix: FloatArray,image: GrayImage,focalLengthPx: Float,timeMs: Long,brightness: Float) =
        cameraFace(LandmarkGeometry.face(points,matrix,image),image.width,image.height,focalLengthPx,timeMs,brightness)

    /**
     * 已在相机线程算好关键点几何与光学测量的帧。苹果端在相机队列调用 [LandmarkGeometry.face]，
     * 主线程只做状态更新，避免逐帧图像计算拖慢界面与分析帧率。
     */
    fun cameraFace(face: FaceMetrics?,imageWidth: Int,imageHeight: Int,focalLengthPx: Float,timeMs: Long,brightness: Float) {
        if (closed || cameraFrame == null || timeMs < captureStartedMs) return
        if (focalChanged(focalPx, focalLengthPx)) { focalPx = focalLengthPx; cameraVersion++ }
        cameraFrame?.invoke(FaceLandmarkFrame(listOfNotNull(face),imageWidth,imageHeight,timeMs,0,30f,brightness,timeMs))
    }
    private fun questions(): List<Question> = when(kind) {
        "DRY_EYE" -> dryEyeQuestions
        "PRESBYOPIA" -> presbyopiaQuestions
        else -> strabismusQuestions
    }
    fun questionnaireJson(): String {
        val fields = if (kind == "DRY_EYE") dry.uiState.value.fields else screening.state.value.fields
        return RecordObject().apply {
            put("fields",RecordObject(fields))
            put("questions",RecordArray(questions().filter { it.visibleWhen(fields) }.map { q -> RecordObject().apply {
                put("key",q.key); put("title",q.title); put("options",q.options?.let(::RecordArray))
                put("multiple",q.multiple); put("numeric",q.numeric); put("optional",q.optional)
                put("hints",RecordObject(q.hints)); put("exclusive",RecordArray(q.exclusive.toList()))
                put("valid",q.optional || (!fields[q.key].isNullOrBlank() && q.validate(fields[q.key]!!)))
            }}))
        }.toString()
    }
    fun snapshotJson(): String {
        val result = RecordObject().apply { put("kind",kind) }
        if (kind == "DRY_EYE") {
            val s = dry.uiState.value
            result.apply {
                put("phase",s.phase.name); put("title","干眼自测"); put("instruction",s.instruction)
                put("distanceCm",s.distanceCm); put("gateReady",s.gateReady); put("warning",s.error ?: s.qualityWarning)
                put("faceDetected",s.faceDetected)
                val distanceSpeech = s.phase in setOf(DryEyePhase.Setup, DryEyePhase.FormalGuide, DryEyePhase.Countdown,
                    DryEyePhase.Practice, DryEyePhase.Natural, DryEyePhase.Open)
                put("distanceZone",spokenDistanceZone(s.distanceCm,s.faceDetected,
                    if (distanceSpeech) DryEyeProtocol.TARGET_DISTANCE_CM to DryEyeProtocol.DISTANCE_TOLERANCE_CM else null))
                val distanceVisible = s.phase in setOf(DryEyePhase.Setup, DryEyePhase.FormalGuide, DryEyePhase.Countdown,
                    DryEyePhase.Practice, DryEyePhase.Natural, DryEyePhase.Open)
                put("targetDistanceCm",if (distanceVisible) DryEyeProtocol.TARGET_DISTANCE_CM else null)
                put("distanceToleranceCm",if (distanceVisible) DryEyeProtocol.DISTANCE_TOLERANCE_CM else null)
                put("secondsLeft",s.remainingSeconds); put("trialVisible",s.trialVisible); put("trialId",s.trialId)
                put("direction",s.direction); put("optotypePx",s.optotypePx); put("savedFile",s.savedFile)
                put("risk",s.report?.riskLevel?.name); put("findings",RecordArray(s.report?.indicators.orEmpty() + s.report?.abnormal.orEmpty()))
                put("advice",s.report?.advice); put("voiceActive",s.trialVisible)
                put("naturalSeconds",s.naturalSeconds); put("afterRest",s.afterRest); put("canExtend",s.canExtend); put("canRest",s.canRestRetest); put("progress",s.progressFraction)
                dryEyeProgress(s).let { p -> put("overallProgress",p?.fraction); put("remainingText",p?.remainingText) }
                ResultReports.dryEye(s)?.let { put("report",it.toRecordObject()) }
            }
        } else {
            val s = screening.state.value
            result.apply {
                put("phase",s.step.name); put("title",if (s.kind == ScreeningKind.PRESBYOPIA) "老视自测" else "眼位自测")
                put("instruction",s.instruction); put("stepTitle",stepTitle(s.step)); put("guidePending",s.guidePending)
                put("guideText",guideCue(s.step))
                put("distanceCm",s.distanceCm); put("faceDetected",s.faceDetected); put("warning",s.gazeWarning ?: s.warning ?: screeningGuidance(s)?.takeUnless { it == "保持稳定" })
                put("secondsLeft",s.secondsLeft); put("trialVisible",s.trialVisible); put("trialId",s.trialId)
                put("targetDistanceCm",screeningDistanceTarget(s)?.first)
                put("distanceZone",if (s.guidePending || s.gazeWarning != null || s.warning != null) null else spokenDistanceZone(s.distanceCm,
                    s.faceDetected || (s.step == ScreeningStep.NEAR_VISION && s.distanceCm > 0f),screeningDistanceTarget(s)))
                put("distanceToleranceCm",screeningDistanceTarget(s)?.second)
                put("direction",s.direction); put("optotypePx",s.trialOptotypePx); put("eye",s.visionEye.name)
                put("nearAwaitingEyeReady",s.nearAwaitingEyeReady); put("pushStage",s.pushStage.name)
                put("targetX",s.targetX); put("targetY",s.targetY); put("savedFile",s.savedFile)
                put("gazeCalibrationIndex",s.gazeCalibrationIndex); put("gazeCalibrationRound",s.gazeCalibrationRound)
                put("gazeCalibrationFailed",s.gazeCalibrationFailed)
                put("targetDiameterMm",StrabismusProtocol.targetDiameterMm(s.fields["年龄"]?.toIntOrNull()))
                put("coverDone",s.coverMarks.size); put("coverNext",StrabismusProtocol.COVER_SEQUENCE.getOrNull(s.coverMarks.size)?.label)
                put("coverRemainingMs",screening.coverRemainingMs())
                StrabismusProtocol.COVER_SEQUENCE.getOrNull(s.coverMarks.size - 1)?.let { put("coverCurrent",it.label); put("coverHoldMs",it.holdMs) }
                put("coverPrompt",coverSpokenPrompt(s))
                put("risk",s.riskLevel.name); put("category",s.category); put("findings",RecordArray(s.findings + s.limitations))
                put("voiceActive",s.trialVisible || s.nearAwaitingEyeReady || s.step == ScreeningStep.PUSH_UP)
                screeningProgress(s).let { p -> put("overallProgress",p?.fraction); put("remainingText",p?.remainingText) }
                StrabismusGaze.calibrationTargets.getOrNull(s.gazeCalibrationIndex)?.let { put("calibrationX",it.x); put("calibrationY",it.y) }
                put("calibrationCount",StrabismusGaze.calibrationTargets.size)
                ResultReports.screening(s)?.let { put("report",it.toRecordObject()) }
            }
        }
        return result.toString()
    }
}
