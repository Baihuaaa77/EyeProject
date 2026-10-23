package com.zkyjss.eyeproject.feature.services

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.*
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingLarge
import com.zkyjss.eyeproject.core.ui.component.*

/** 三项测试共用语音偏好；切换测试与重启应用后沿用同一设置。 */
@Stable
internal class TestSettings(private val preferences: SharedPreferences) {
    var speechEnabled by mutableStateOf(true)
        private set
    var answersEnabled by mutableStateOf(true)
        private set
    var soundEnabled by mutableStateOf(true)
        private set
    var hapticsEnabled by mutableStateOf(true)
        private set
    /** 震动强度 0.1–1，默认 0.6 即原有分级；调高时按比例加强。 */
    var hapticStrength by mutableFloatStateOf(DEFAULT_HAPTIC_STRENGTH)
        private set
    /** 音效音量 0–1，乘在按事件分级的音量上。 */
    var soundVolume by mutableFloatStateOf(1f)
        private set
    var speechVolume by mutableFloatStateOf(1f)
        private set
    /** 播报语速倍率 0.7–1.4。 */
    var speechRate by mutableFloatStateOf(1f)
        private set
    init { refresh() }
    fun refresh() {
        speechEnabled = preferences.getBoolean("speech", true)
        answersEnabled = preferences.getBoolean("answers", true)
        soundEnabled = preferences.getBoolean("sound", true)
        hapticsEnabled = preferences.getBoolean("haptics", true)
        hapticStrength = preferences.getFloat("hapticStrength", DEFAULT_HAPTIC_STRENGTH)
        soundVolume = preferences.getFloat("soundVolume", 1f)
        speechVolume = preferences.getFloat("speechVolume", 1f)
        speechRate = preferences.getFloat("speechRate", 1f)
    }
    fun saveHapticStrength(value: Float) { hapticStrength = value; preferences.edit().putFloat("hapticStrength", value).apply() }
    fun saveSoundVolume(value: Float) { soundVolume = value; preferences.edit().putFloat("soundVolume", value).apply() }
    fun saveSpeechVolume(value: Float) { speechVolume = value; preferences.edit().putFloat("speechVolume", value).apply() }
    fun saveSpeechRate(value: Float) { speechRate = value; preferences.edit().putFloat("speechRate", value).apply() }
    companion object { const val DEFAULT_HAPTIC_STRENGTH = 0.6f }
    fun setSpeech(enabled: Boolean) { speechEnabled = enabled; preferences.edit().putBoolean("speech", enabled).apply() }
    fun setAnswers(enabled: Boolean) { answersEnabled = enabled; preferences.edit().putBoolean("answers", enabled).apply() }
    fun setSound(enabled: Boolean) { soundEnabled = enabled; preferences.edit().putBoolean("sound", enabled).apply() }
    fun setHaptics(enabled: Boolean) { hapticsEnabled = enabled; preferences.edit().putBoolean("haptics", enabled).apply() }
}

@Composable
internal fun rememberTestSettings(): TestSettings {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { context.getSharedPreferences("screening_settings", Context.MODE_PRIVATE) }
    val settings = remember(preferences) { TestSettings(preferences) }
    DisposableEffect(preferences, settings) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> settings.refresh() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return settings
}

/** 单层顶栏；进行中的任务在可用区域居中，小屏或大字号时仍可滚动操作。 */
@Composable
internal fun TestSessionLayout(
    title: String,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    centered: Boolean,
    modifier: Modifier = Modifier,
    reflexStimulus: Boolean = false,
    scroll: ScrollState = rememberScrollState(),
    progress: TestProgress? = null,
    status: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalEyeColors.current
    Column(modifier.fillMaxSize().background(colors.appBackground).navigationBarsPadding().imePadding().screenHorizontalPaddingLarge()) {
        Box {
            TopBackBar(title = title, noText = true, onBackClick = onBack)
            Box(Modifier.align(Alignment.BottomEnd).size(48.dp).semantics { contentDescription = "系统设置" }
                .clickable(role = Role.Button, onClick = onSettings), contentAlignment = Alignment.Center) {
                EyeIcon(painterResource(com.zkyjss.eyeproject.core.designsystem.R.drawable.ic_settings_gear),
                    null, Modifier.size(24.dp), tint = colors.primary)
            }
        }
        TestProgressBar(progress)
        if (reflexStimulus) {
            Canvas(Modifier.padding(bottom = 6.dp).fillMaxWidth().height(20.dp).clip(EyeShapes.small).background(colors.black)) {
                drawCircle(colors.white, radius = 4.dp.toPx(), center = center)
            }
        }
        status()
        BoxWithConstraints(Modifier.weight(1f).testTag("test-content")) {
            val density = LocalDensity.current
            val available = maxHeight - 16.dp
            val flex = remember { StageFlex() }
            Column(Modifier.fillMaxWidth().verticalScroll(scroll).heightIn(min = maxHeight).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, if (centered) Alignment.CenterVertically else Alignment.Top)) {
                // 进行中的任务：把内容区剩余高度交给舞台里可伸展的刺激区，避免卡片上下大片留白；
                // 内容放不下时不再伸展，仍可滚动。
                CompositionLocalProvider(LocalStageFlex provides flex.takeIf { centered }) {
                    Column(Modifier.fillMaxWidth().onSizeChanged { size ->
                        val natural = with(density) { size.height.toDp() }
                        val base = natural - if (flex.consumers > 0) flex.extra else 0.dp
                        val target = (available - base).coerceAtLeast(0.dp)
                        if (abs((target - flex.extra).value) > 1f) flex.extra = target
                    }, verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
                }
            }
        }
    }
}

/** 内容区留给舞台伸展的高度；伸展元素登记后才计入，避免没有伸展元素时反复累加。 */
@Stable
internal class StageFlex {
    var extra by mutableStateOf(0.dp)
    var consumers = 0
}

internal val LocalStageFlex = staticCompositionLocalOf<StageFlex?> { null }

/** 舞台中可伸展的元素（刺激区、注视画布）调用：返回应额外增加的高度。每屏只应有一个伸展元素。 */
@Composable
internal fun rememberStageExtra(): Dp {
    val flex = LocalStageFlex.current ?: return 0.dp
    DisposableEffect(flex) { flex.consumers++; onDispose { flex.consumers-- } }
    return flex.extra
}

/** 测试开始后在顶栏下显示整体进度与剩余时间：细条加一行小字，不抢视标的注意力。 */
@Composable
private fun TestProgressBar(progress: TestProgress?) {
    val colors = LocalEyeColors.current
    AnimatedVisibility(progress != null, enter = fadeIn(tween(EyeMotion.MEDIUM)) + expandVertically(),
        exit = fadeOut(tween(EyeMotion.SHORT)) + shrinkVertically()) {
        // 收起动画期间保留最后一次读数。
        var last by remember { mutableStateOf(progress ?: TestProgress(0f, 0)) }
        if (progress != null) last = progress
        val fraction by animateFloatAsState(last.fraction, tween(EyeMotion.LONG, easing = EyeMotion.Standard), label = "测试进度")
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("test-progress")
            .semantics(mergeDescendants = true) { progressBarRangeInfo = ProgressBarRangeInfo(last.fraction, 0f..1f) },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Canvas(Modifier.weight(1f).height(4.dp)) {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(colors.neutralSoft, cornerRadius = radius)
                drawRoundRect(colors.primary, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), cornerRadius = radius)
            }
            EyeText(last.remainingText, style = LocalEyeTypography.current.bodySecondary, color = colors.textSecondary)
        }
    }
}

/**
 * 顶层页（首页 / 服务 / 我的）右上角齿轮打开的「系统设置」：与测试页同一面板、同一份本机偏好；
 * 不在测试中，所以不收听语音、没有「停止测试」。显示状态由 app 持有。
 */
@Composable
fun SystemSettingsSheet(onDismiss: () -> Unit) {
    val settings = rememberTestSettings()
    val voiceAnswer = rememberVoiceAnswer(active = false, voice = null, enabled = settings.answersEnabled) {}
    TestSettingsSheet(settings, voiceAnswer, onDismiss)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TestSettingsSheet(
    settings: TestSettings,
    voice: VoiceAnswerState,
    onDismiss: () -> Unit,
    speechAvailable: Boolean? = null,
    onStop: (() -> Unit)? = null,
    details: @Composable () -> Unit = {},
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val preview = rememberTestFeedbackOutput()
    LaunchedEffect(settings.soundEnabled, settings.hapticsEnabled) { preview.stop() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.panelBackground) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            EyeText("系统设置", style = typography.title, color = colors.textPrimary)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(settings.soundEnabled, role = Role.Switch, onValueChange = settings::setSound), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                EyeText("音效反馈", style = typography.body, color = colors.textPrimary)
                EyeSwitch(settings.soundEnabled, null)
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(settings.hapticsEnabled, role = Role.Switch, onValueChange = settings::setHaptics), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                EyeText("震动反馈", style = typography.body, color = colors.textPrimary)
                EyeSwitch(settings.hapticsEnabled, null)
            }
            if (settings.soundEnabled) LevelSlider("音效音量", settings.soundVolume, 0f..1f, settings::saveSoundVolume)
            if (settings.hapticsEnabled) LevelSlider("震动强度", settings.hapticStrength, 0.1f..1f, settings::saveHapticStrength)
            EyeButton("试一下反馈", EyeButtonRole.SECONDARY, {
                preview.play(ScreeningFeedbackCue.START, settings.soundEnabled, settings.hapticsEnabled,
                    settings.soundVolume, settings.hapticStrength) {}
            }, Modifier.fillMaxWidth(), enabled = settings.soundEnabled || settings.hapticsEnabled)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(settings.speechEnabled, role = Role.Switch, onValueChange = settings::setSpeech), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                EyeText("语音播报", style = typography.body, color = colors.textPrimary)
                EyeSwitch(settings.speechEnabled, null)
            }
            if (settings.speechEnabled && speechAvailable == false) EyeText("语音播报不可用", style = typography.bodySecondary, color = colors.warning)
            if (settings.speechEnabled) {
                VoicePicker()
                LevelSlider("播报音量", settings.speechVolume, 0.1f..1f, settings::saveSpeechVolume)
                LevelSlider("播报语速", settings.speechRate, 0.7f..1.4f, settings::saveSpeechRate)
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(settings.answersEnabled, role = Role.Switch, onValueChange = settings::setAnswers), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                EyeText("语音作答", style = typography.body, color = colors.textPrimary)
                EyeSwitch(settings.answersEnabled, null)
            }
            if (settings.answersEnabled) {
                when {
                    !voice.permissionGranted -> EyeButton("允许麦克风", EyeButtonRole.SECONDARY, voice.requestPermission, Modifier.fillMaxWidth())
                    voice.preparing -> EyeText("正在准备离线语音", style = typography.bodySecondary, color = colors.textSecondary)
                    voice.unavailableReason != null -> EyeText(voice.unavailableReason!!, style = typography.bodySecondary, color = colors.warning)
                    voice.available == false -> EyeText("本机不支持语音识别", style = typography.bodySecondary, color = colors.warning)
                    voice.heard.isNotBlank() -> EyeText("听到：${voice.heard}", style = typography.bodySecondary, color = colors.textSecondary)
                }
            }
            details()
            onStop?.let { stop -> EyeButton("停止测试", EyeButtonRole.SECONDARY, { onDismiss(); stop() }, Modifier.fillMaxWidth()) }
            EyeButton("完成", EyeButtonRole.PRIMARY, onDismiss, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 设置中的滑块：标题 + 当前百分比 + 主色滑轨；松手时保存。 */
@Composable
private fun LevelSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    var current by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            EyeText(title, style = typography.body, color = colors.textPrimary)
            EyeText("${(current * 100).roundToInt()}%", style = typography.bodySecondary, color = colors.textSecondary)
        }
        androidx.compose.material3.Slider(
            value = current, onValueChange = { current = it }, valueRange = range,
            onValueChangeFinished = { onChange(current) },
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = colors.primary,
                activeTrackColor = colors.primary, inactiveTrackColor = colors.neutralSoft),
        )
    }
}

/** 当前发声人与更换入口：三项测试共用同一发声人，在任一测试里更换都对全部生效。 */
@Composable
private fun VoicePicker() {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val voices = SharedSpeech.voices
    val current = SharedSpeech.currentVoice
    EyeText("发声人：${current?.let(::voiceLabel) ?: "系统默认"}", style = typography.body, color = colors.textPrimary)
    if (voices.size > 1) EyeDisclosure("更换发声人") {
        voices.forEach { voice ->
            val selected = voice.name == current
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.RadioButton) { SharedSpeech.selectVoice(voice.name) },
                verticalAlignment = Alignment.CenterVertically) {
                EyeText(voiceLabel(voice.name) + qualityLabel(voice), style = typography.body,
                    color = if (selected) colors.primary else colors.textPrimary, modifier = Modifier.weight(1f))
                if (selected) EyeText("当前", style = typography.bodySecondary, color = colors.primary)
            }
        }
    }
}

/** 引擎内部名（如 cmn-cn-x-ccc-local）转成易读的编号与离线标记。 */
private fun voiceLabel(name: String): String {
    val code = Regex("""x-([a-z]{3})""").find(name)?.groupValues?.get(1)?.uppercase()
    val where = if (name.endsWith("network")) "在线" else "离线"
    return if (code != null) "中文 $code（$where）" else name
}

private fun qualityLabel(voice: android.speech.tts.Voice): String = when {
    voice.quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH -> " · 最高音质"
    voice.quality >= android.speech.tts.Voice.QUALITY_HIGH -> " · 高音质"
    else -> ""
}
