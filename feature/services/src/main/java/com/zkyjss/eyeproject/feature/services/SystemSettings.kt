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
internal const val FEEDBACK_HELP = "作答、换阶段和测试完成时的提示音与震动；护眼操和休息的提示音也跟随这里的开关与音量。"

/**
 * 顶层页（首页 / 服务 / 我的）右上角齿轮打开的「系统设置」完整页面：与测试中的设置同一份本机偏好，
 * 页面底部是「关于」（应用图标、版本与免责声明）。苹果端 SystemSettingsPage 同一排版与文案。
 */
@Composable
fun SystemSettingsScreen(onBack: () -> Unit, onOpenAccount: () -> Unit = {}, onOpenPermissions: () -> Unit = {}, onOpenCloud: () -> Unit = {}) {
    val settings = rememberTestSettings()
    val voiceAnswer = rememberVoiceAnswer(active = false, voice = null, enabled = settings.answersEnabled) {}
    val context = LocalContext.current
    val version = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val build = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)
            "${info.versionName ?: "—"} ($build)"
        }.getOrNull().orEmpty()
    }
    EyeSubPage("系统设置", onBack) {
        TestSettingsContent(settings, voiceAnswer, showGestureAnswers = true)
        EyeListGroup(title = "通用") {
            EyeListRow("账户管理",onOpenAccount)
            EyeListRow("权限与通知",onOpenPermissions)
            EyeListRow("云端同步",onOpenCloud,divider = false)
        }
        AboutSection(version)
        EyeListRow("切换登录",onOpenAccount,divider = false)
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
    showGestureAnswers: Boolean = false,
    details: @Composable ColumnScope.() -> Unit = {},
) {
    EyeFullScreenPage(onDismiss) {
        EyeSubPage("系统设置", onDismiss, bottomBar = {
            onStop?.let { stop -> EyeButton("停止测试", EyeButtonRole.SECONDARY, { onDismiss(); stop() }, Modifier.fillMaxWidth()) }
            EyeButton("完成", EyeButtonRole.PRIMARY, onDismiss, Modifier.fillMaxWidth())
        }) {
            TestSettingsContent(settings, voice, speechAvailable, showGestureAnswers)
            details()
        }
    }
}

/** 反馈、语音和作答方式由应用与测试内的设置共用。 */
@Composable
internal fun TestSettingsContent(
    settings: TestSettings,
    voice: VoiceAnswerState,
    speechAvailable: Boolean? = null,
    showGestureAnswers: Boolean,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    val preview = rememberTestFeedbackOutput()
    LaunchedEffect(settings.soundEnabled, settings.hapticsEnabled) { preview.stop() }
    val context = LocalContext.current
    val speaker = remember(context) { ScreeningVoice(context) }
    DisposableEffect(speaker) { onDispose { speaker.close() } }

    var editor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    EyeListGroup(title = "反馈") {
        EyeSwitchRow("音效反馈", settings.soundEnabled, settings::setSound)
        if (settings.soundEnabled) EyeListRow("音效音量",{ editor = "音效音量" },value = "${(settings.soundVolume*100).roundToInt()}%")
        EyeSwitchRow("震动反馈", settings.hapticsEnabled, settings::setHaptics)
        if (settings.hapticsEnabled) EyeListRow("震动强度",{ editor = "震动强度" },value = "${(settings.hapticStrength*100).roundToInt()}%")
    }
    EyeListGroup(title = "语音") {
        EyeSwitchRow("语音辅助",settings.speechEnabled || settings.answersEnabled,{ settings.setSpeech(it); settings.setAnswers(it) },subtitle = "播报与作答")
        if (settings.speechEnabled || settings.answersEnabled) EyeListRow("声音与语速",{ editor = "声音与语速" },divider = false)
    }
    if (showGestureAnswers) {
        EyeListGroup(title = "作答方式") {
            EyeSwitchRow("手势作答", settings.gestureAnswersEnabled, settings::setGestureAnswers,
                subtitle = "食指或大拇指指向 E 开口，换向可直接续答")
        }
    }
    editor?.let { title ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { editor = null }) {
            com.zkyjss.eyeproject.core.ui.component.EyeCard {
                EyeText(title,style = typography.title,color = colors.textPrimary)
                when (title) {
                    "音效音量" -> LevelSlider("音效音量",settings.soundVolume,0f..1f,settings::saveSoundVolume)
                    "震动强度" -> LevelSlider("震动强度",settings.hapticStrength,0.1f..1f,settings::saveHapticStrength)
                    else -> {
                        VoicePicker()
                        LevelSlider("播报音量",settings.speechVolume,0.1f..1f,settings::saveSpeechVolume)
                        LevelSlider("播报语速",settings.speechRate,0.7f..1.4f,settings::saveSpeechRate)
                        if (speechAvailable == false) EyeText("本机暂不能播报",style = typography.body,color = colors.warning)
                        EyeListRow("检查麦克风权限",{ voice.requestPermission() })
                        if (voice.heard.isNotBlank()) EyeText("听到：${voice.heard}",style = typography.bodySecondary,color = colors.textSecondary)
                        voice.unavailableReason?.let { EyeText(it,style = typography.bodySecondary,color = colors.warning) }
                    }
                }
                EyeListRow("试听 / 试一下",{
                    if (title == "声音与语速") {
                        speaker.configure(settings.speechVolume,settings.speechRate); speaker.speak("请保持合适距离，看视标选方向。")
                    } else preview.play(ScreeningFeedbackCue.START,settings.soundEnabled,settings.hapticsEnabled,settings.soundVolume,settings.hapticStrength) {}
                })
                com.zkyjss.eyeproject.core.ui.component.EyeButton("完成",com.zkyjss.eyeproject.core.ui.component.EyeButtonRole.PRIMARY,{ editor = null },Modifier.fillMaxWidth())
            }
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
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
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
