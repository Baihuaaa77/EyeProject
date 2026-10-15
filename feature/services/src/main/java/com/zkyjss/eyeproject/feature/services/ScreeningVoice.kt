package com.zkyjss.eyeproject.feature.services

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.util.Locale

/** 测试页使用系统中文语音合成；离开页面时释放引擎。 */
internal class ScreeningVoice(context: Context) : TextToSpeech.OnInitListener {
    private val preferences = context.getSharedPreferences("screening_voice", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val feedbackHandler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var pending: String? = null
    private var utteranceNumber = 0L
    private var currentUtterance: String? = null
    var speaking by mutableStateOf(false)
        private set
    var feedbackPlaying by mutableStateOf(false)
        private set
    private var feedbackGeneration = 0
    fun pauseForFeedback(durationMs: Long) {
        val ticket = ++feedbackGeneration
        feedbackPlaying = true
        feedbackHandler.postDelayed({ if (ticket == feedbackGeneration) feedbackPlaying = false }, durationMs)
    }
    var available by mutableStateOf<Boolean?>(null)
        private set

    init { engine = TextToSpeech(context.applicationContext, this) }

    override fun onInit(status: Int) {
        mainHandler.post {
            val tts = engine ?: return@post
            available = status == TextToSpeech.SUCCESS &&
                tts.setLanguage(Locale.SIMPLIFIED_CHINESE) >= TextToSpeech.LANG_AVAILABLE
            if (available == true) {
                // 三个测试固定使用同一引擎的同一中文发声人：选音质最高、已安装、离线可用的声音，
                // 网络声音只在没有可用离线声音时使用。键名带版本，旧版本保存的低音质选择会重新挑选一次。
                runCatching {
                    val key = "voice_v2_${tts.defaultEngine}"
                    val voices = tts.voices.orEmpty().filter { it.locale.language in setOf("zh", "cmn") &&
                        it.locale.country in setOf("CN", "") &&
                        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
                    val chosen = voices.firstOrNull { it.name == preferences.getString(key, null) }
                        ?: voices.sortedWith(compareBy<android.speech.tts.Voice> { it.isNetworkConnectionRequired }
                            .thenByDescending { it.quality }.thenBy { it.latency }
                            .thenByDescending { it.name == tts.voice?.name }.thenBy { it.name }).firstOrNull()
                    if (chosen != null && tts.setVoice(chosen) == TextToSpeech.SUCCESS)
                        preferences.edit().putString(key, chosen.name).apply()
                }
                // 常速、原音高最接近真人讲解；略快的语速会让中文听起来急促机械。
                tts.setSpeechRate(1f)
                tts.setPitch(1f)
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = finishSpeaking(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finishSpeaking(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finishSpeaking(utteranceId)
            })
            pending?.let { phrase ->
                pending = null
                speak(phrase)
            }
        }
    }

    fun speak(phrase: String) {
        if (available == null) { pending = phrase; return }
        if (available == true) {
            val id = "screening_instruction_${++utteranceNumber}"
            currentUtterance = id
            speaking = true
            if (engine?.speak(screeningSpeechText(phrase), TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
                currentUtterance = null
                speaking = false
            }
        }
    }

    private fun finishSpeaking(id: String?) {
        mainHandler.post {
            if (id != currentUtterance) return@post
            mainHandler.postDelayed({ if (id == currentUtterance) { currentUtterance = null; speaking = false } }, 600L)
        }
    }

    /** 正在播报或刚播报完 0.6 秒内：语音作答应忽略此时的识别结果，避免把提示语当作答案。 */
    fun busy(): Boolean = speaking || feedbackPlaying || engine?.isSpeaking == true

    fun stop() { currentUtterance = null; speaking = false; engine?.stop(); pending = null; mainHandler.removeCallbacksAndMessages(null) }
    fun close() { stop(); feedbackGeneration++; feedbackPlaying = false; feedbackHandler.removeCallbacksAndMessages(null); engine?.shutdown(); engine = null }
}

private class SpokenMemory { var lastWasDistance = false; var lastBase = "" }

/**
 * 播报当前提示。距离偏离时优先提醒，持续偏离每 6 秒换一种说法再提醒；回到合适距离先说一句肯定，
 * 期间若主提示有变化再接着播报。状态稳定 650 ms 后才开口，避免测距波动不断打断；切到后台不播报。
 */
@Composable
internal fun SpeakTestPrompts(voice: ScreeningVoice, enabled: Boolean, base: String, offDistance: DistanceStatus?) {
    val owner = LocalLifecycleOwner.current
    val memory = remember { SpokenMemory() }
    val prompt = offDistance?.spoken ?: base
    LaunchedEffect(prompt, enabled) {
        if (!enabled || prompt.isBlank()) { voice.stop(); return@LaunchedEffect }
        delay(650)
        val foreground = { owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        val text = when {
            offDistance != null -> prompt
            memory.lastWasDistance -> SpokenPrompts.DISTANCE_OK + if (base != memory.lastBase) base else ""
            else -> base
        }
        if (foreground()) {
            voice.speak(text)
            memory.lastWasDistance = offDistance != null
            if (offDistance == null) memory.lastBase = base
        }
        var repeat = 1
        while (offDistance != null) {
            delay(6_000)
            if (foreground()) voice.speak(offDistance.spoken(repeat++) ?: prompt)
        }
    }
}
