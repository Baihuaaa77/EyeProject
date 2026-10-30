package com.zkyjss.eyeproject.feature.services

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zkyjss.eyeproject.core.camera.CameraController
import com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider
import com.zkyjss.eyeproject.core.data.record.ScreeningRecordRepository
import com.zkyjss.eyeproject.core.data.subject.SubjectRepository
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeSectionCard
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.MascotMood
import com.zkyjss.eyeproject.core.ui.component.XiaoTong
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** 色觉自测的安卓桥：流程、出图与判定全部在共用 [ColorVisionEngine]（与苹果端同一套）。 */
@HiltViewModel
class ColorVisionViewModel @Inject constructor(
    @param:ApplicationContext context: Context,
    camera: CameraController,
    intrinsics: CameraIntrinsicsProvider,
    records: ScreeningRecordRepository,
    subjectRepository: SubjectRepository,
) : ViewModel() {
    private val platform = AndroidScreeningPlatform(context, camera, intrinsics, records)
    val engine = ColorVisionEngine(platform, viewModelScope)
    val uiState get() = engine.uiState
    val subjects: StateFlow<List<SubjectProfile>> =
        subjectRepository.subjects.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun feedbackCues(enabled: Boolean) = uiState.value.let { s ->
        platform.feedbackSession.poll(ScreeningFeedbackFrame("${s.phase}:${s.plateIndex}",
            taskActive = s.phase == ColorVisionPhase.Demo || s.phase == ColorVisionPhase.Plates,
            complete = s.phase == ColorVisionPhase.Result), platform.nowMs(), enabled)
    }
    fun useSubject(profile: SubjectProfile) = engine.useSubject(profile)
    fun skipProfile() = engine.skipProfile()
    fun start() = engine.start()
    fun answer(option: String) = engine.answer(option)
    fun markPlateShown(timestampMs: Long) = engine.markPlateShown(timestampMs)
    fun restart() = engine.restart()
}

/**
 * 色觉自测页：选人 → 准备（关护眼模式、屏幕调亮、约 40 cm）→ 示例图 → 8 张检测图（选看到的数字或「看不出」）→ 结果。
 * 与其他三项测试同一套顶栏、进度条、系统设置、结果页与导出；不用相机。与苹果端 ColorVisionView 同一排版。
 */
@Composable
fun ColorVisionScreen(
    onBack: () -> Unit,
    onEditSubject: (String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ColorVisionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val voice = remember(context) { ScreeningVoice(context) }
    val settings = rememberTestSettings()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    TestFeedbackEffect(settings, voice, settingsOpen, viewModel::feedbackCues)
    DisposableEffect(voice) { onDispose { voice.close() } }
    // 测试期间窗口亮度固定为最高，颜色对比一致
    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        val old = window?.attributes?.screenBrightness
        window?.let { it.attributes = it.attributes.apply { screenBrightness = 1f } }
        onDispose { if (window != null && old != null) window.attributes = window.attributes.apply { screenBrightness = old } }
    }
    val running = state.phase == ColorVisionPhase.Demo || state.phase == ColorVisionPhase.Plates
    Box(modifier) {
        TestSessionLayout("色觉自测", onBack, { settingsOpen = true }, running, confirmExit = running,
            progress = if (state.phase == ColorVisionPhase.Plates)
                TestProgress(state.progress, (state.plateCount - state.answers.size) * 5) else null) {
            when (state.phase) {
                ColorVisionPhase.Profile -> {
                    val subjects by viewModel.subjects.collectAsStateWithLifecycle()
                    SubjectPicker("COLOR_VISION", subjects, state.subject?.id, viewModel::useSubject, onEditSubject, viewModel::skipProfile)
                }
                ColorVisionPhase.Setup -> ColorSetup(viewModel::start)
                ColorVisionPhase.Demo, ColorVisionPhase.Plates -> state.plate?.let { plate ->
                    PlateQuestion(plate, state.phase == ColorVisionPhase.Demo, state.answers.size, state.plateCount,
                        viewModel::markPlateShown, viewModel::answer)
                }
                ColorVisionPhase.Result -> state.result?.let { report ->
                    ResultReportView(report, rememberRecordExport(state.savedFile) { viewModel.engine.lastRecordJson }, actions = {
                        EyeButton("重新检测", EyeButtonRole.PRIMARY, viewModel::restart, Modifier.fillMaxWidth())
                    })
                }
            }
        }
    }
    // 色觉图用手点选作答，不收听语音；设置页仍可调音效、震动与播报
    val voiceAnswer = remember { VoiceAnswerState() }
    if (settingsOpen) TestSettingsPage(settings, voiceAnswer, { settingsOpen = false },
        speechAvailable = voice.available, onStop = if (running) { { viewModel.restart() } } else null) {}
}

/** 准备：小瞳 + 三条要求（关护眼 / 夜间模式、明亮处、约 40 cm）。 */
@Composable
private fun ColorSetup(onStart: () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    EyeSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EyeText("开始前", Modifier.weight(1f), style = typography.title, color = colors.textPrimary)
                XiaoTong(MascotMood.HAPPY, Modifier.widthIn(max = 96.dp).aspectRatio(1f), headScale = 0.7f)
            }
            listOf("关掉手机的护眼模式、夜间模式（会改变屏幕颜色）", "在明亮的室内，屏幕亮度已自动调到最高", "手机离眼睛约 40 cm，平时戴眼镜就戴着")
                .forEachIndexed { i, text -> EyeText("${i + 1}. $text", style = typography.body, color = colors.textPrimary) }
            EyeText("先看一张示例图，再看 8 张检测图，选出看到的数字；看不出就选「看不出」，不要猜。",
                style = typography.body, color = colors.textSecondary)
            EyeButton("准备好了", EyeButtonRole.PRIMARY, onStart, Modifier.fillMaxWidth())
        }
    }
}

/** 一张图 + 作答按钮（三个数字与「看不出」）。图画出后回报，反应时从那时算起。 */
@Composable
private fun PlateQuestion(plate: ColorPlate, demo: Boolean, answered: Int, total: Int,
                          onShown: (Long) -> Unit, onAnswer: (String) -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    LaunchedEffect(plate.id) {
        withFrameNanos { }; withFrameNanos { }
        onShown(android.os.SystemClock.elapsedRealtime())
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EyeText(if (demo) "示例图" else "第 ${answered + 1} / $total 张", style = typography.subtitle, color = colors.primary)
        ColorPlateView(plate, Modifier.fillMaxWidth().widthIn(max = 360.dp).aspectRatio(1f))
        EyeText("图里是什么数字？", style = typography.title, color = colors.textPrimary, textAlign = TextAlign.Center)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            plate.options.forEach { option ->
                EyeButton(option, if (option == ColorVisionProtocol.NOT_SEEN) EyeButtonRole.SECONDARY else EyeButtonRole.PRIMARY,
                    { onAnswer(option) }, Modifier.weight(1f).heightIn(min = 56.dp))
            }
        }
    }
}

/** 画一张假同色图：深色圆底上逐个画色点（颜色由共用代码给出，不做主题换色）。 */
@Composable
internal fun ColorPlateView(plate: ColorPlate, modifier: Modifier = Modifier) {
    val colors = LocalEyeColors.current
    val dots = remember(plate.id) { plate.dots.map { Triple(Offset(it.x, it.y), it.r, Color(it.argb)) } }
    Canvas(modifier.clip(EyeShapes.panel).background(colors.panelBackground).padding(8.dp)
        .semantics { contentDescription = "色觉检测图" }) {
        val radius = size.minDimension / 2
        val center = Offset(size.width / 2, size.height / 2)
        dots.forEach { (p, r, color) -> drawCircle(color, r * radius, Offset(center.x + p.x * radius, center.y + p.y * radius)) }
    }
}
