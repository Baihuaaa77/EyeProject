package com.zkyjss.eyeproject.feature.services

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.theme.rememberReducedMotion
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeSwitch
import com.zkyjss.eyeproject.core.ui.component.EyeText

/*
 * 语音作答：测试中手持手机点按方向键会带动手机，破坏距离与头姿，
 * 改为说出「上 / 下 / 左 / 右」作答；按钮保留为后备。
 */

/**
 * 连续识别循环：利用部分结果尽早识别指令（缩短反应时），识别到指令后立即重启下一轮。
 * 优先使用设备端识别；无设备端引擎时直接用内置离线模型，系统识别失败时也有离线后备。
 * Android 识别服务的调用均在主线程，离线模型加载在工作线程。
 */
internal class VoiceAnswerRecognizer(
    private val context: Context,
    private val callbacks: Callbacks,
) : RecognitionListener {
    interface Callbacks {
        fun onListening(listening: Boolean)
        fun onHeard(text: String)
        fun onCommand(command: VoiceCommand)
        fun onUnavailable(reason: String)
        fun onPreparing(preparing: Boolean) = Unit
    }

    val available: Boolean = true // APK 内置离线模型，不再由系统识别服务是否存在决定功能可用性。
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
    private var active = false
    private var firedThisUtterance = false
    private var failures = 0
    private var recognitionGeneration = 0
    // 没有设备端引擎时直接用内置模型，避免厂商系统服务看似可用却无法识别。
    private var useOffline = !onDevice
    private val offline by lazy { OfflineVoiceAnswer(context, callbacks) }
    private var offlineCreated = false

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
    }

    fun start() {
        if (active) return
        active = true
        failures = 0
        if (useOffline) { offlineCreated = true; offline.start(); return }
        listen()
    }

    fun stop() {
        active = false
        handler.removeCallbacksAndMessages(null)
        recognitionGeneration++
        runCatching { recognizer?.destroy() }
        recognizer = null
        if (offlineCreated) offline.stop()
        callbacks.onListening(false)
    }

    fun destroy() {
        stop()
        runCatching { recognizer?.destroy() }
        recognizer = null
        if (offlineCreated) offline.destroy()
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        val token = ++recognitionGeneration
        fun current() = active && !useOffline && token == recognitionGeneration
        recognizer = (if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context))
            .also { it.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if (current()) this@VoiceAnswerRecognizer.onReadyForSpeech(params) }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) { if (current()) this@VoiceAnswerRecognizer.onPartialResults(partialResults) }
                override fun onResults(results: Bundle?) { if (current()) this@VoiceAnswerRecognizer.onResults(results) }
                override fun onError(error: Int) { if (current()) this@VoiceAnswerRecognizer.onError(error) }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            }) }
    }

    private fun listen() {
        if (!active) return
        firedThisUtterance = false
        runCatching {
            ensureRecognizer()
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
            recognizer?.startListening(intent)
        }.onFailure { startOffline() }
    }

    private fun startOffline() {
        if (!active) return
        handler.removeCallbacksAndMessages(null)
        recognitionGeneration++
        runCatching { recognizer?.destroy() }; recognizer = null
        useOffline = true; offlineCreated = true
        offline.start()
    }

    private fun restart(delayMs: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ listen() }, delayMs)
    }

    private fun handle(results: Bundle?, final: Boolean) {
        if (!active || useOffline) return
        failures = 0
        val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val first = texts.firstOrNull()?.takeIf { it.isNotBlank() }
        if (first != null) callbacks.onHeard(first)
        if (!firedThisUtterance) {
            val candidates = if (final) texts else listOfNotNull(first)
            val command = candidates.firstNotNullOfOrNull(VoiceCommandParser::parse)
            if (command != null) {
                firedThisUtterance = true
                callbacks.onCommand(command)
                if (!final) {
                    // 部分结果已拿到指令：立即结束本轮，准备听下一题
                    runCatching { recognizer?.cancel() }
                    restart(120)
                    return
                }
            }
        }
        if (final) restart(100)
    }

    override fun onReadyForSpeech(params: Bundle?) { if (active && !useOffline) callbacks.onListening(true) }
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onPartialResults(partialResults: Bundle?) = handle(partialResults, final = false)
    override fun onResults(results: Bundle?) = handle(results, final = true)
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onError(error: Int) {
        callbacks.onListening(false)
        if (!active || useOffline) return
        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                active = false
                callbacks.onUnavailable("未授予麦克风权限")
            }
            // 设备端识别不支持中文：换系统识别服务重来
            12 /* ERROR_LANGUAGE_NOT_SUPPORTED */, 13 /* ERROR_LANGUAGE_UNAVAILABLE */ -> if (onDevice) {
                onDevice = false
                runCatching { recognizer?.destroy() }
                recognizer = null
                restart(200)
            } else {
                startOffline()
            }
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(150)
            else -> if (++failures >= 3) startOffline() else restart(400)
        }
    }
}

@Stable
internal class VoiceAnswerState {
    var enabled by mutableStateOf(true)
    var available by mutableStateOf<Boolean?>(null)
    var permissionGranted by mutableStateOf(false)
    var listening by mutableStateOf(false)
    var preparing by mutableStateOf(false)
    var pausedForSpeech by mutableStateOf(false)
    var heard by mutableStateOf("")
    var unavailableReason by mutableStateOf<String?>(null)
    var requestPermission: () -> Unit = {}
    /** 界面开关回调（由 [rememberVoiceAnswer] 注入，写入可保存状态）。 */
    var onToggle: (Boolean) -> Unit = {}
}

/**
 * 在 [active] 为 true 时持续收听语音指令。[voice] 正在播报时忽略识别结果（防止把提示语里的
 * 「左眼」「右眼」当成作答）。首次需要时请求麦克风权限；不可用时界面回退到按钮。
 */
@Composable
internal fun rememberVoiceAnswer(active: Boolean, voice: ScreeningVoice?, onCommand: (VoiceCommand) -> Unit): VoiceAnswerState {
    val context = LocalContext.current
    val state = remember { VoiceAnswerState() }
    var enabledSaved by rememberSaveable { mutableStateOf(true) }
    state.enabled = enabledSaved
    var askedPermission by rememberSaveable { mutableStateOf(false) }
    val latestOnCommand by rememberUpdatedState(onCommand)
    val latestVoice by rememberUpdatedState(voice)
    val recognizer = remember(context) {
        VoiceAnswerRecognizer(context.applicationContext, object : VoiceAnswerRecognizer.Callbacks {
            override fun onListening(listening: Boolean) { state.listening = listening }
            override fun onPreparing(preparing: Boolean) { state.preparing = preparing }
            override fun onHeard(text: String) { if (latestVoice?.busy() != true) state.heard = text }
            override fun onCommand(command: VoiceCommand) { if (latestVoice?.busy() != true) latestOnCommand(command) }
            override fun onUnavailable(reason: String) { state.unavailableReason = reason }
        })
    }
    state.available = recognizer.available
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        state.permissionGranted = granted
        if (!granted) state.unavailableReason = "未授予麦克风权限"
    }
    state.permissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    state.requestPermission = { askedPermission = true; launcher.launch(Manifest.permission.RECORD_AUDIO) }
    state.pausedForSpeech = voice?.speaking == true
    val run = active && state.enabled && recognizer.available && !state.pausedForSpeech
    LaunchedEffect(run, state.permissionGranted) {
        if (run && !state.permissionGranted && !askedPermission) state.requestPermission()
        if (run && state.permissionGranted) {
            state.unavailableReason = null
            recognizer.start()
        } else recognizer.stop()
    }
    DisposableEffect(recognizer) { onDispose { recognizer.destroy() } }
    state.onToggle = { enabledSaved = it }
    return state
}

/** 语音作答状态条：麦克风动效 + 提示词 + 刚听到的内容；不可用时说明原因并提示用按钮。 */
@Composable
internal fun VoiceAnswerBar(state: VoiceAnswerState, hint: String = "说「上、下、左、右」作答，看不清就说「看不清」") {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Row(
        Modifier.fillMaxWidth().clip(EyeShapes.medium).background(colors.primarySoft).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MicGlyph(active = state.enabled && state.listening, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f)) {
            when {
                !state.enabled -> EyeText("语音作答已关闭，请点按钮", style = typography.body, color = colors.textSecondary)
                state.preparing -> EyeText("正在准备离线语音，请稍候", style = typography.body, color = colors.textSecondary)
                state.available == false || state.unavailableReason != null ->
                    EyeText("${state.unavailableReason ?: "本机不支持语音识别"}，请点按钮作答", style = typography.body, color = colors.textSecondary)
                !state.permissionGranted -> EyeText("需要麦克风权限才能语音作答", style = typography.body, color = colors.textPrimary)
                else -> {
                    EyeText(when {
                        state.listening -> "正在听：$hint"
                        state.pausedForSpeech -> "播报结束后可语音作答"
                        else -> "正在启动语音作答，请稍候"
                    }, style = typography.body, color = colors.primary)
                    if (state.heard.isNotBlank()) EyeText("听到：${state.heard}", style = typography.bodySecondary, color = colors.textSecondary)
                }
            }
        }
        if (state.enabled && state.available == true && !state.permissionGranted) {
            EyeButton("允许", EyeButtonRole.PRIMARY, state.requestPermission)
        } else {
            EyeSwitch(checked = state.enabled, onCheckedChange = { state.onToggle(it) })
        }
    }
}

/** 麦克风图标；收听中带呼吸光圈。 */
@Composable
private fun MicGlyph(active: Boolean, modifier: Modifier) {
    val colors = LocalEyeColors.current
    val reduced = rememberReducedMotion()
    val pulse = if (active && !reduced) rememberInfiniteTransition(label = "收听").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "收听光圈"
    ) else null
    val tint = if (active) colors.primary else colors.textSecondary
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        pulse?.let { drawCircle(tint, w * (0.3f + 0.25f * it.value), center, alpha = 0.3f * (1f - it.value)) }
        drawRoundRect(tint, Offset(w * 0.36f, h * 0.1f), Size(w * 0.28f, h * 0.48f), CornerRadius(w * 0.14f))
        drawArc(tint, 0f, 180f, false, Offset(w * 0.24f, h * 0.28f), Size(w * 0.52f, h * 0.44f), style = Stroke(w * 0.07f, cap = StrokeCap.Round))
        drawLine(tint, Offset(w * 0.5f, h * 0.72f), Offset(w * 0.5f, h * 0.88f), w * 0.07f, StrokeCap.Round)
    }
}
