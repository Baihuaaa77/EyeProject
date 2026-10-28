package com.zkyjss.eyeproject.feature.services

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 色觉自测流程：选人 → 准备（关护眼模式、调亮屏幕、约 40 cm）→ 示例图 → 8 张检测图 → 结果。 */
enum class ColorVisionPhase { Profile, Setup, Demo, Plates, Result }

data class ColorVisionUiState(
    val phase: ColorVisionPhase = ColorVisionPhase.Profile,
    val fields: Map<String, String> = emptyMap(),
    val subject: TestSubject? = null,
    val profileSkipped: Boolean = false,
    /** 检测图序号（0 起）；示例图阶段为 -1。 */
    val plateIndex: Int = -1,
    val answers: List<ColorPlateAnswer> = emptyList(),
    val demoAnswer: ColorPlateAnswer? = null,
    val report: ColorVisionReport? = null,
    val result: ResultReport? = null,
    val savedFile: String? = null,
    val error: String? = null,
) {
    /** 当前要看的图（示例或检测图）。 */
    val plate: ColorPlate? get() = when (phase) {
        ColorVisionPhase.Demo -> ColorVisionProtocol.demo
        ColorVisionPhase.Plates -> ColorVisionProtocol.screening.getOrNull(plateIndex)
        else -> null
    }
    val plateCount: Int get() = ColorVisionProtocol.screening.size
    val progress: Float get() = if (phase == ColorVisionPhase.Plates) colorProgressFraction(answers.size) else 0f
}

/** 色觉自测共用流程（不用相机）；平台只提供时间、反馈与记录保存。 */
class ColorVisionEngine(
    private val platform: ScreeningPlatform,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _uiState = MutableStateFlow(ColorVisionUiState())
    val uiState: StateFlow<ColorVisionUiState> = _uiState.asStateFlow()
    private var shownAtMs = 0L

    /** 最近一次保存的完整记录 JSON，供结果页导出。 */
    var lastRecordJson: String? = null
        private set

    fun useSubject(profile: SubjectProfile) = _uiState.update {
        it.copy(phase = ColorVisionPhase.Setup, fields = SubjectProfiles.testFields(profile, platform.epochMs()),
            subject = TestSubject(profile.id, profile.nickname, profile.relation), profileSkipped = false, error = null)
    }

    fun skipProfile() = _uiState.update {
        it.copy(phase = ColorVisionPhase.Setup, fields = emptyMap(), subject = null, profileSkipped = true, error = null)
    }

    /** 准备好了：先看示例图。 */
    fun start() {
        if (_uiState.value.phase != ColorVisionPhase.Setup) return
        platform.feedback(ScreeningFeedbackCue.START)
        shownAtMs = platform.nowMs()
        _uiState.update { it.copy(phase = ColorVisionPhase.Demo, plateIndex = -1, answers = emptyList(), demoAnswer = null, error = null) }
    }

    /** 界面画出这张图后回报，反应时从这里算起。 */
    fun markPlateShown(timestampMs: Long) { shownAtMs = timestampMs }

    /** 选了一个选项（数字或「看不出」）。 */
    fun answer(option: String) {
        val s = _uiState.value
        val plate = s.plate ?: return
        if (option !in plate.options) return
        val rt = (platform.nowMs() - shownAtMs).takeIf { shownAtMs > 0L && it >= 0L }
        val answer = ColorPlateAnswer(plate.id, plate.number, plate.type, plate.contrast, option, rt)
        platform.feedback(ScreeningFeedbackCue.ANSWER)
        shownAtMs = platform.nowMs()
        when (s.phase) {
            ColorVisionPhase.Demo -> _uiState.update { it.copy(phase = ColorVisionPhase.Plates, plateIndex = 0, demoAnswer = answer) }
            ColorVisionPhase.Plates -> {
                val answers = s.answers + answer
                if (answers.size >= ColorVisionProtocol.screening.size) finish(s.demoAnswer, answers)
                else _uiState.update { it.copy(answers = answers, plateIndex = answers.size) }
            }
            else -> Unit
        }
    }

    /** 回到准备页重新测（选的人保留）。 */
    fun restart() = _uiState.update {
        ColorVisionUiState(phase = ColorVisionPhase.Setup, fields = it.fields, subject = it.subject, profileSkipped = it.profileSkipped)
    }

    private fun finish(demo: ColorPlateAnswer?, answers: List<ColorPlateAnswer>) {
        val report = ColorVisionAssessment.evaluate(demo, answers, completed = true)
        val s = _uiState.value
        val result = ResultReports.colorVision(report, answers, s.fields, s.subject)
        platform.feedback(ScreeningFeedbackCue.COMPLETE)
        _uiState.update { it.copy(phase = ColorVisionPhase.Result, answers = answers, report = report, result = result) }
        save(_uiState.value)
    }

    private fun save(snapshot: ColorVisionUiState) {
        val report = snapshot.report ?: return
        val result = snapshot.result ?: return
        scope.launch(io) {
            runCatching {
                val data = RecordObject().apply {
                    put("protocolVersion", 1)
                    put("kind", "COLOR_VISION")
                    put("assessmentVersion", ColorVisionAssessment.VERSION)
                    put("report", result.toRecordObject())
                    put("platform", platform.platformName)
                    put("displaySizeSource", platform.displaySizeSource)
                    put("displayXdpi", platform.display.xdpi)
                    put("displayWidthPx", platform.display.widthPixels)
                    put("displayHeightPx", platform.display.heightPixels)
                    put("profile", RecordObject(snapshot.fields))
                    snapshot.subject?.let { put("subject", it.toRecordObject()) }
                    put("profileSkipped", snapshot.profileSkipped)
                    put("completed", true)
                    put("screenBrightness", "测试期间窗口亮度设为最高")
                    put("riskLevel", report.riskLevel.name)
                    put("valid", report.valid)
                    put("validityReasons", RecordArray(report.validityReasons))
                    put("colorType", report.type ?: RecordObject.NULL)
                    put("advice", report.advice)
                    put("evidence", report.evidence.toRecordArray())
                    put("features", RecordObject().apply { report.features.forEach { (k, v) -> put(k, v ?: RecordObject.NULL) } })
                    put("plates", RecordArray().apply {
                        (listOfNotNull(snapshot.demoAnswer) + snapshot.answers).forEach { a -> put(RecordObject().apply {
                            put("id", a.plateId); put("number", a.number); put("type", a.type.name)
                            put("contrast", a.contrast.toDouble()); put("answer", a.answer); put("correct", a.correct)
                            put("rtMs", a.rtMs ?: RecordObject.NULL)
                        }) }
                    })
                }
                val name = "color_${platform.epochMs()}.json"
                val json = data.toString()
                lastRecordJson = json
                platform.saveRecord(name, json)
                name
            }.onSuccess { name -> _uiState.update { it.copy(savedFile = name) } }
        }
    }
}
