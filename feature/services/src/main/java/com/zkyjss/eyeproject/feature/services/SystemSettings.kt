package com.zkyjss.eyeproject.feature.services

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.ui.component.EyeAppIcon
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeFullScreenPage
import com.zkyjss.eyeproject.core.ui.component.EyeGlyph
import com.zkyjss.eyeproject.core.ui.component.EyeListGroup
import com.zkyjss.eyeproject.core.ui.component.EyeListRow
import com.zkyjss.eyeproject.core.ui.component.EyeSubPage
import com.zkyjss.eyeproject.core.ui.component.EyeSwitchRow
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.EyeTileTone
import com.zkyjss.eyeproject.core.ui.component.rememberEyeHaptics
import kotlin.math.roundToInt

/** 「系统设置」中语音播报的说明（问号小窗），与苹果端同一含义、各写本平台的操作路径。 */
internal const val VOICE_HELP = "测试中用语音读出提示。想要更自然的声音：在系统「设置 → 文字转语音」里下载更高音质的中文语音，再回到这里更换发声人。"
internal const val ANSWER_HELP = "测试中可以直接说「上、下、左、右」或「看不清」作答，不用点屏幕。需要麦克风权限，识别只在本机进行。"
internal const val FEEDBACK_HELP = "作答、换阶段和测试完成时的提示音与震动；小瞳的互动音效也跟随这里的开关与音量。"

/**
 * 顶层页（首页 / 服务 / 我的）右上角齿轮打开的「系统设置」完整页面：与测试中的设置同一份本机偏好，
 * 页面底部是「关于」（应用图标、版本与免责声明）。苹果端 SystemSettingsPage 同一排版与文案。
 */
@Composable
fun SystemSettingsScreen(onBack: () -> Unit) {
    val settings = rememberTestSettings()
    val voiceAnswer = rememberVoiceAnswer(active = false, voice = null, enabled = settings.answersEnabled) {}
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    EyeSubPage("系统设置", onBack) {
        TestSettingsContent(settings, voiceAnswer)
        AboutSection(version)
    }
}

/** 测试进行中打开的设置：盖在测试页上的完整页面（不离开测试），底部可停止测试。 */
@Composable
internal fun TestSettingsPage(
    settings: TestSettings,
    voice: VoiceAnswerState,
    onDismiss: () -> Unit,
    speechAvailable: Boolean? = null,
    onStop: (() -> Unit)? = null,
    details: @Composable ColumnScope.() -> Unit = {},
) {
    EyeFullScreenPage(onDismiss) {
        EyeSubPage("系统设置", onDismiss, bottomBar = {
            onStop?.let { stop -> EyeButton("停止测试", EyeButtonRole.SECONDARY, { onDismiss(); stop() }, Modifier.fillMaxWidth()) }
            EyeButton("完成", EyeButtonRole.PRIMARY, onDismiss, Modifier.fillMaxWidth())
        }) {
            TestSettingsContent(settings, voice, speechAvailable)
            details()
        }
    }
}

/** 设置分组：反馈、语音播报、语音作答。 */
@Composable
internal fun TestSettingsContent(settings: TestSettings, voice: VoiceAnswerState, speechAvailable: Boolean? = null) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    val preview = rememberTestFeedbackOutput()
    LaunchedEffect(settings.soundEnabled, settings.hapticsEnabled) { preview.stop() }
    val context = LocalContext.current
    val speaker = remember(context) { ScreeningVoice(context) }
    DisposableEffect(speaker) { onDispose { speaker.close() } }

    EyeListGroup(title = "反馈") {
        EyeSwitchRow("音效反馈", settings.soundEnabled, settings::setSound, glyph = EyeGlyph.BELL, help = "音效与震动" to FEEDBACK_HELP)
        if (settings.soundEnabled) LevelSlider("音效音量", settings.soundVolume, 0f..1f, settings::saveSoundVolume)
        EyeSwitchRow("震动反馈", settings.hapticsEnabled, settings::setHaptics, glyph = EyeGlyph.SYNC, tone = EyeTileTone.ACCENT)
        if (settings.hapticsEnabled) LevelSlider("震动强度", settings.hapticStrength, 0.1f..1f, settings::saveHapticStrength)
        EyeListRow("试一下反馈", onClick = {
            preview.play(ScreeningFeedbackCue.START, settings.soundEnabled, settings.hapticsEnabled, settings.soundVolume, settings.hapticStrength) {}
        }, titleColor = colors.primary, divider = false, enabled = settings.soundEnabled || settings.hapticsEnabled,
            modifier = Modifier.padding(start = 50.dp))
    }
    EyeListGroup(title = "语音") {
        EyeSwitchRow("语音播报", settings.speechEnabled, settings::setSpeech, glyph = EyeGlyph.CHAT, help = "语音播报" to VOICE_HELP,
            subtitle = if (settings.speechEnabled && speechAvailable == false) "本机暂不能播报" else null)
        if (settings.speechEnabled) {
            VoicePicker()
            LevelSlider("播报音量", settings.speechVolume, 0.1f..1f, settings::saveSpeechVolume)
            LevelSlider("播报语速", settings.speechRate, 0.7f..1.4f, settings::saveSpeechRate)
            EyeListRow("试听", onClick = {
                speaker.configure(settings.speechVolume, settings.speechRate)
                speaker.speak("请保持四十厘米，看视标选方向。")
            }, titleColor = colors.primary, modifier = Modifier.padding(start = 50.dp))
        }
        EyeSwitchRow("语音作答", settings.answersEnabled, settings::setAnswers, glyph = EyeGlyph.MIC, tone = EyeTileTone.SUCCESS,
            help = "语音作答" to ANSWER_HELP, divider = settings.answersEnabled)
        if (settings.answersEnabled) {
            val status = when {
                !voice.permissionGranted -> null
                voice.preparing -> "正在准备离线语音"
                voice.unavailableReason != null -> voice.unavailableReason
                voice.available == false -> "本机不支持语音识别"
                voice.heard.isNotBlank() -> "听到：${voice.heard}"
                else -> "已就绪"
            }
            if (status == null) EyeListRow("允许麦克风", onClick = { haptics.tap(); voice.requestPermission() }, titleColor = colors.primary, divider = false,
                modifier = Modifier.padding(start = 50.dp))
            else EyeListRow("识别状态", onClick = null, value = status, divider = false, modifier = Modifier.padding(start = 50.dp),
                valueColor = if (voice.unavailableReason != null || voice.available == false) colors.warning else colors.textSecondary)
        }
    }
}

/** 关于：应用图标（与桌面图标一致）、名称、版本与免责声明。 */
@Composable
internal fun AboutSection(version: String) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    EyeListGroup(title = "关于") {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            EyeAppIcon(size = 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                EyeText("眼健康助手", style = typography.subtitle, color = colors.textPrimary)
                EyeText("你的主动眼健康助手", style = typography.bodySecondary, color = colors.textSecondary)
            }
        }
        EyeListRow("版本", onClick = null, value = version.ifEmpty { "—" })
        EyeText(ResultReports.FOOTNOTE, Modifier.padding(horizontal = 16.dp, vertical = 14.dp), style = typography.bodySecondary, color = colors.textSecondary)
    }
}

/** 设置中的滑块：标题 + 当前百分比 + 主色滑轨；松手时保存。 */
@Composable
private fun LevelSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    var current by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(start = 66.dp, end = 16.dp, top = 6.dp, bottom = 2.dp)) {
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

/** 当前发声人与更换入口：三项测试共用同一发声人，在任一处更换都对全部生效。 */
@Composable
private fun VoicePicker() {
    val colors = LocalEyeColors.current
    val voices = SharedSpeech.voices
    val current = SharedSpeech.currentVoice
    var open by rememberSaveable { mutableStateOf(false) }
    EyeListRow("发声人", onClick = if (voices.size > 1) { { open = !open } } else null,
        value = current?.let(::voiceLabel) ?: "系统默认", modifier = Modifier.padding(start = 50.dp))
    if (open) voices.forEach { voice ->
        val selected = voice.name == current
        EyeListRow(voiceLabel(voice.name) + qualityLabel(voice), onClick = { SharedSpeech.selectVoice(voice.name); open = false },
            titleColor = if (selected) colors.primary else colors.textPrimary, value = if (selected) "当前" else null,
            valueColor = colors.primary, modifier = Modifier.padding(start = 50.dp))
    }
    Spacer(Modifier.height(0.dp))
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
