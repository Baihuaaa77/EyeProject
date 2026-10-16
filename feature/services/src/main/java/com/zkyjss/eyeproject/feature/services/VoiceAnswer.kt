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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/*
 * 语音作答：测试中手持手机点按方向键会带动手机，破坏距离与头姿，
 * 改为说出「上 / 下 / 左 / 右」作答；按钮保留为后备。
 */

/**
 * 连续识别：优先使用内置离线模型（Vosk，短指令词表）——它连续收听、不需每句重启，
 * 系统识别服务每句话后都要重启一轮，重启间隙说的话会丢失，实测「不够灵敏」。
 * 内置模型无法启动时退回系统识别服务（利用部分结果尽早识别指令，识别到指令后立即重启下一轮）。
 * Android 识别服务的调用均在主线程，离线模型加载在工作线程。
 */
internal class VoiceAnswerRecognizer(
    private val context: Context,
    private val callbacks: Callbacks,
    preferOffline: Boolean = true,
) : RecognitionListener {
    interface Callbacks {
        fun onListening(listening: Boolean)
        fun onHeard(text: String)
        fun onCommand(command: VoiceCommand)
        fun onUnavailable(reason: String)
        fun onPreparing(preparing: Boolean) = Unit
        /** 是否是播报的回声；回声会吞掉整句话（同一句后续的部分与最终结果也不再作答）。 */
        fun isEcho(command: VoiceCommand): Boolean = false
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
    private var useOffline = preferOffline
    /** 系统识别服务已失败过：内置模型再失败就只能报告不可用。 */
    private var systemFailed = false
    // 内置模型无法启动时先退回系统识别服务，而不是直接报告不可用
    private val offlineCallbacks = object : Callbacks by callbacks {
        override fun onUnavailable(reason: String) {
            if (systemFailed) callbacks.onUnavailable(reason) else handler.post { useSystem() }
        }
    }
    private val offline by lazy { OfflineVoiceAnswer(context, offlineCallbacks) }
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

    private fun useSystem() {
        if (!active) return
        if (offlineCreated) offline.stop()
        useOffline = false; systemFailed = false
        listen()
    }

    private fun startOffline() {
        systemFailed = true
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
                if (!callbacks.isEcho(command)) callbacks.onCommand(command)
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
    var heard by mutableStateOf("")
    var unavailableReason by mutableStateOf<String?>(null)
    var requestPermission: () -> Unit = {}
}

/**
 * 在 [active] 为 true 时持续收听语音指令，播报期间也不中断；[voice] 正在播报时只丢弃提示语里也出现的指令
 * （防止把「左眼」「右眼」当成作答）。首次需要时请求麦克风权限；不可用时界面回退到按钮。
 */
@Composable
internal fun rememberVoiceAnswer(active: Boolean, voice: ScreeningVoice?, enabled: Boolean = true, onCommand: (VoiceCommand) -> Unit): VoiceAnswerState {
    val context = LocalContext.current
    val state = remember { VoiceAnswerState() }
    state.enabled = enabled
    var askedPermission by rememberSaveable { mutableStateOf(false) }
    val latestOnCommand by rememberUpdatedState(onCommand)
    val latestVoice by rememberUpdatedState(voice)
    val recognizer = remember(context) {
        VoiceAnswerRecognizer(context.applicationContext, object : VoiceAnswerRecognizer.Callbacks {
            override fun onListening(listening: Boolean) { state.listening = listening }
            override fun onPreparing(preparing: Boolean) { state.preparing = preparing }
            override fun onHeard(text: String) { state.heard = text }
            override fun onCommand(command: VoiceCommand) = latestOnCommand(command)
            override fun isEcho(command: VoiceCommand) = latestVoice?.echoes(command) == true
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
    // 播报与音效期间不再停止收听：每次停止再启动都会丢掉这段时间里说的话，回声由 [ScreeningVoice.echoes] 过滤
    val run = active && state.enabled && recognizer.available
    LaunchedEffect(run, state.permissionGranted) {
        if (run && !state.permissionGranted && !askedPermission) state.requestPermission()
        if (run && state.permissionGranted) {
            state.unavailableReason = null
            recognizer.start()
        } else recognizer.stop()
    }
    DisposableEffect(recognizer) { onDispose { recognizer.destroy() } }
    return state
}
