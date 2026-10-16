package com.zkyjss.eyeproject.feature.services

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * 全应用唯一的中文语音合成引擎：三项测试共用同一个 TextToSpeech 实例、同一发声人、语速与音调。
 * 以前每个测试页各建一个引擎、各自挑选发声人，某个实例设置发声人失败时会静默退回系统默认声音，
 * 于是不同测试音色不一致。最后一个使用者释放时才关闭引擎；换一个应用上下文（如单测）时重建。
 */
internal object SharedSpeech : TextToSpeech.OnInitListener {
    private const val PREFS = "screening_voice"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var appContext: Context? = null
    private var users = 0
    private val owners = mutableMapOf<String, ScreeningVoice>()

    var available by mutableStateOf<Boolean?>(null)
        private set
    /** 可选的已安装中文发声人（按离线优先、音质从高到低）。 */
    var voices by mutableStateOf<List<Voice>>(emptyList())
        private set
    /** 当前实际使用的发声人名称；设置页显示，便于确认三项测试一致。 */
    var currentVoice by mutableStateOf<String?>(null)
        private set
    private var chosen: Voice? = null
    var rate = 1f
        private set

    fun acquire(context: Context) {
        val app = context.applicationContext
        if (appContext !== app) { engine?.shutdown(); engine = null; users = 0; owners.clear() }
        appContext = app
        users++
        if (engine == null) {
            available = null; chosen = null; currentVoice = null
            engine = TextToSpeech(app, this)
        }
    }

    fun release(owner: ScreeningVoice) {
        owners.values.removeAll { it === owner }
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) { engine?.shutdown(); engine = null; available = null; chosen = null; currentVoice = null }
    }

    override fun onInit(status: Int) {
        mainHandler.post {
            val tts = engine ?: return@post
            available = status == TextToSpeech.SUCCESS && tts.setLanguage(Locale.SIMPLIFIED_CHINESE) >= TextToSpeech.LANG_AVAILABLE
            if (available == true) {
                // 测试语音按「语音」内容播放，所有测试走同一条音频通路与音效处理。
                tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                chooseVoice(tts, null)
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = finish(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finish(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
            })
            owners.values.toSet().forEach { it.engineReady() }
        }
    }

    /**
     * 选音质最高、已安装、离线可用的中文发声人；用户在设置里选过的优先。键名带版本，
     * 旧版本保存的选择会重新挑选一次。网络声音只在没有可用离线声音时使用。
     */
    private fun chooseVoice(tts: TextToSpeech, requested: String?) {
        runCatching {
            val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
            val key = "voice_v2_${tts.defaultEngine}"
            val list = tts.voices.orEmpty().filter { it.locale.language in setOf("zh", "cmn") &&
                it.locale.country in setOf("CN", "") && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
                .sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenByDescending { it.quality }
                    .thenBy { it.latency }.thenBy { it.name })
            voices = list
            val wanted = requested ?: prefs.getString(key, null)
            val pick = list.firstOrNull { it.name == wanted } ?: list.firstOrNull()
            if (pick != null && tts.setVoice(pick) == TextToSpeech.SUCCESS) {
                chosen = pick
                prefs.edit().putString(key, pick.name).apply()
            }
        }
        currentVoice = tts.voice?.name
        tts.setSpeechRate(rate)
        tts.setPitch(1f)
    }

    /** 设置页选择发声人；空串表示恢复自动选择。 */
    fun selectVoice(name: String) {
        val tts = engine ?: return
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.remove("voice_v2_${tts.defaultEngine}")?.apply()
        chooseVoice(tts, name.ifEmpty { null })
    }

    fun setRate(value: Float) {
        rate = value.coerceIn(0.5f, 2f)
        engine?.setSpeechRate(rate)
    }

    /** 每句开口前重新确认发声人、语速与音调：个别引擎在打断或切换语言后会悄悄换回默认声音。 */
    fun speak(owner: ScreeningVoice, text: String, id: String, volume: Float): Boolean {
        val tts = engine ?: return false
        chosen?.let { voice -> if (tts.voice?.name != voice.name) tts.setVoice(voice) }
        currentVoice = tts.voice?.name
        tts.setSpeechRate(rate)
        tts.setPitch(1f)
        owners[id] = owner
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
        return tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) == TextToSpeech.SUCCESS
    }

    fun talking(): Boolean = engine?.isSpeaking == true
    fun stop() { engine?.stop() }
    fun watch(owner: ScreeningVoice) { owners["init:${System.identityHashCode(owner)}"] = owner }

    private fun finish(id: String?) {
        mainHandler.post { id?.let { owners.remove(it) }?.finishSpeaking(id) }
    }
}

/** 测试页的播报会话：共用 [SharedSpeech] 引擎，自己只管本页的待播、正在播与作答保护状态。 */
internal class ScreeningVoice(context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val feedbackHandler = Handler(Looper.getMainLooper())
    private var pending: String? = null
    private var utteranceNumber = 0L
    private var currentUtterance: String? = null
    /** 最近一句播报的文本（送给 TTS 的读法），用于回声过滤。 */
    private var lastSpoken = ""
    private var closed = false
    var speaking by mutableStateOf(false)
        private set
    var feedbackPlaying by mutableStateOf(false)
        private set
    private var feedbackGeneration = 0
    private var volume = 1f
    fun pauseForFeedback(durationMs: Long) {
        val ticket = ++feedbackGeneration
        feedbackPlaying = true
        feedbackHandler.postDelayed({ if (ticket == feedbackGeneration) feedbackPlaying = false }, durationMs)
    }
    val available: Boolean? get() = SharedSpeech.available

    init { SharedSpeech.acquire(context); SharedSpeech.watch(this) }

    /** 引擎初始化完成：补播初始化前到来的最后一句。 */
    internal fun engineReady() {
        if (closed) return
        pending?.let { phrase -> pending = null; speak(phrase) }
    }

    /** 设置中的播报音量（0–1）与语速倍率；语速对所有测试生效。 */
    fun configure(volume: Float, rate: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        SharedSpeech.setRate(rate)
    }

    /** 引擎此刻是否正在出声（不含结束后的 600 ms 收听保护）。 */
    fun talking(): Boolean = SharedSpeech.talking()

    fun speak(phrase: String) {
        if (closed) return
        when (SharedSpeech.available) {
            null -> pending = phrase
            true -> {
                val id = "screening_instruction_${System.identityHashCode(this)}_${++utteranceNumber}"
                currentUtterance = id
                speaking = true
                lastSpoken = screeningSpeechText(phrase)
                if (!SharedSpeech.speak(this, lastSpoken, id, volume)) {
                    currentUtterance = null
                    speaking = false
                }
            }
            false -> Unit
        }
    }

    internal fun finishSpeaking(id: String?) {
        if (id != currentUtterance) return
        mainHandler.postDelayed({ if (id == currentUtterance) { currentUtterance = null; speaking = false } }, 600L)
    }

    /**
     * 正在播报或刚播报完 0.6 秒内，且提示语里也出现了 [command]：可能是麦克风收到的提示语回声，不作答。
     * 其余指令照常作答——收听不再因播报或音效暂停，用户不必等播报说完。
     */
    fun echoes(command: VoiceCommand): Boolean =
        (speaking || SharedSpeech.talking()) && VoiceCommandParser.mentions(lastSpoken, command)

    fun stop() {
        val wasMine = currentUtterance != null
        currentUtterance = null; speaking = false; pending = null
        mainHandler.removeCallbacksAndMessages(null)
        if (wasMine) SharedSpeech.stop()
    }

    fun close() {
        if (closed) return
        stop(); closed = true
        feedbackGeneration++; feedbackPlaying = false; feedbackHandler.removeCallbacksAndMessages(null)
        SharedSpeech.release(this)
    }
}

private class SpokenMemory { var lastWasDistance = false; var lastWasMove = false; var lastBase = "" }

/**
 * 播报当前提示。距离偏离时优先提醒，持续偏离每 6 秒换一种说法再提醒；回到合适距离先说一句肯定，
 * 期间若主提示有变化再接着播报。状态稳定 650 ms 后才开口，避免测距波动不断打断；切到后台不播报。
 * 「请移动到 xx 厘米处」到位后不说肯定（由提示音代替），主提示没变也不重读——远近切换每题都换距离，
 * 否则会反复听到「好，就保持这个距离」。
 */
@Composable
internal fun SpeakTestPrompts(voice: ScreeningVoice, enabled: Boolean, base: String, offDistance: DistanceStatus?) {
    val owner = LocalLifecycleOwner.current
    val memory = remember { SpokenMemory() }
    val prompt = offDistance?.spoken ?: base
    LaunchedEffect(prompt, enabled) {
        if (!enabled || prompt.isBlank()) { voice.stop(); return@LaunchedEffect }
        delay(650)
        // 让正在说的一句说完再开口：提醒频繁变化时逐句打断，听起来断断续续、很不自然。
        // 距离提醒除外，需要及时。
        if (offDistance == null) withTimeoutOrNull(6_000) { while (voice.talking()) delay(100) }
        val foreground = { owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        val moving = offDistance?.zone == DistanceZone.MOVE_TO
        val text = when {
            offDistance != null -> prompt
            memory.lastWasDistance -> SpokenPrompts.DISTANCE_OK + if (base != memory.lastBase) base else ""
            memory.lastWasMove && base == memory.lastBase -> ""
            else -> base
        }
        if (foreground() && text.isNotEmpty()) voice.speak(text)
        memory.lastWasDistance = offDistance != null && !moving
        memory.lastWasMove = moving
        if (offDistance == null) memory.lastBase = base
        var repeat = 1
        while (offDistance != null) {
            delay(6_000)
            if (foreground()) voice.speak(offDistance.spoken(repeat++) ?: prompt)
        }
    }
}
