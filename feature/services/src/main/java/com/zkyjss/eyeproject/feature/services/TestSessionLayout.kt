package com.zkyjss.eyeproject.feature.services

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.saveable.rememberSaveable
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

/**
 * 单层顶栏；进行中的任务在可用区域居中，小屏或大字号时仍可滚动操作。
 * [statusAtBottom] 时距离条放在屏幕底部（斜视：注视目标要尽量靠近屏幕顶部）。
 * [confirmExit] 时点返回（含系统返回）先弹「确定要退出测试吗？」，确认后才离开。
 */
@Composable
internal fun TestSessionLayout(
    title: String,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    centered: Boolean,
    modifier: Modifier = Modifier,
    confirmExit: Boolean = false,
    statusAtBottom: Boolean = false,
    scroll: ScrollState = rememberScrollState(),
    progress: TestProgress? = null,
    status: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalEyeColors.current
    var asking by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = confirmExit) { asking = true }
    if (asking) TestExitDialog(onStay = { asking = false }, onExit = { asking = false; onBack() })
    Column(modifier.fillMaxSize().background(colors.appBackground).navigationBarsPadding().imePadding().screenHorizontalPaddingLarge()) {
        Box {
            TopBackBar(title = title, noText = true, onBackClick = { if (confirmExit) asking = true else onBack() })
            Box(Modifier.align(Alignment.BottomEnd).size(48.dp).semantics { contentDescription = "系统设置" }
                .clickable(role = Role.Button, onClick = onSettings), contentAlignment = Alignment.Center) {
                EyeIcon(painterResource(com.zkyjss.eyeproject.core.designsystem.R.drawable.ic_settings_gear),
                    null, Modifier.size(24.dp), tint = colors.primary)
            }
        }
        TestProgressBar(progress)
        if (!statusAtBottom) status()
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
        if (statusAtBottom) Box(Modifier.padding(bottom = 8.dp)) { status() }
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
