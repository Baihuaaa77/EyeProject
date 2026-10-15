package com.zkyjss.eyeproject.feature.services

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.*
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingLarge
import com.zkyjss.eyeproject.core.ui.component.*
import kotlin.math.cos
import kotlin.math.sin

/** 三项测试共用语音偏好；切换测试与重启应用后沿用同一设置。 */
@Stable
internal class TestSettings(private val preferences: SharedPreferences) {
    var speechEnabled by mutableStateOf(true)
        private set
    var answersEnabled by mutableStateOf(true)
        private set
    init { refresh() }
    fun refresh() {
        speechEnabled = preferences.getBoolean("speech", true)
        answersEnabled = preferences.getBoolean("answers", true)
    }
    fun setSpeech(enabled: Boolean) { speechEnabled = enabled; preferences.edit().putBoolean("speech", enabled).apply() }
    fun setAnswers(enabled: Boolean) { answersEnabled = enabled; preferences.edit().putBoolean("answers", enabled).apply() }
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
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalEyeColors.current
    Column(modifier.fillMaxSize().background(colors.appBackground).navigationBarsPadding().imePadding().screenHorizontalPaddingLarge()) {
        Box {
            TopBackBar(title = title, noText = true, onBackClick = onBack)
            Box(Modifier.align(Alignment.BottomEnd).size(48.dp).semantics { contentDescription = "系统设置" }
                .clickable(role = Role.Button, onClick = onSettings), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(22.dp)) {
                    val radius = size.minDimension / 2
                    repeat(8) { index ->
                        val angle = index * kotlin.math.PI / 4
                        val axis = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                        drawLine(colors.textSecondary, center + axis * radius * 0.7f, center + axis * radius,
                            radius * 0.25f, StrokeCap.Round)
                    }
                    drawCircle(colors.textSecondary, radius * 0.7f, style = Stroke(radius * 0.18f))
                    drawCircle(colors.textSecondary, radius * 0.25f, style = Stroke(radius * 0.18f))
                }
            }
        }
        if (reflexStimulus) {
            Canvas(Modifier.fillMaxWidth().height(20.dp).clip(EyeShapes.small).background(colors.black)) {
                drawCircle(colors.white, radius = 4.dp.toPx(), center = center)
            }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth().verticalScroll(scroll).heightIn(min = maxHeight).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, if (centered) Alignment.CenterVertically else Alignment.Top),
                content = content)
        }
    }
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
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.panelBackground) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            EyeText("系统设置", style = typography.title, color = colors.textPrimary)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(settings.speechEnabled, role = Role.Switch, onValueChange = settings::setSpeech), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                EyeText("语音播报", style = typography.body, color = colors.textPrimary)
                EyeSwitch(settings.speechEnabled, null)
            }
            if (settings.speechEnabled && speechAvailable == false) EyeText("语音播报不可用", style = typography.bodySecondary, color = colors.warning)
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
