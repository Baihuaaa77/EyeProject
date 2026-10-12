package com.zkyjss.eyeproject.feature.services

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** 测试页使用系统中文语音合成；离开页面时释放引擎。 */
internal class ScreeningVoice(context: Context) : TextToSpeech.OnInitListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var pending: String? = null
    var available by mutableStateOf<Boolean?>(null)
        private set

    init { engine = TextToSpeech(context.applicationContext, this) }

    override fun onInit(status: Int) {
        mainHandler.post {
            val tts = engine ?: return@post
            available = status == TextToSpeech.SUCCESS &&
                tts.setLanguage(Locale.SIMPLIFIED_CHINESE) >= TextToSpeech.LANG_AVAILABLE
            if (available == true) tts.setSpeechRate(1.08f)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { lastDoneMs = SystemClock.elapsedRealtime() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { lastDoneMs = SystemClock.elapsedRealtime() }
            })
            pending?.let { phrase ->
                pending = null
                if (available == true) tts.speak(screeningSpeechText(phrase), TextToSpeech.QUEUE_FLUSH, null, "screening_instruction")
            }
        }
    }

    fun speak(phrase: String) {
        if (available == null) { pending = phrase; return }
        if (available == true) engine?.speak(screeningSpeechText(phrase), TextToSpeech.QUEUE_FLUSH, null, "screening_instruction")
    }

    @Volatile private var lastDoneMs = 0L

    /** 正在播报或刚播报完 0.6 秒内：语音作答应忽略此时的识别结果，避免把提示语当作答案。 */
    fun busy(): Boolean = engine?.isSpeaking == true || SystemClock.elapsedRealtime() - lastDoneMs < 600L

    fun stop() { engine?.stop(); pending = null }
    fun close() { stop(); engine?.shutdown(); engine = null }
}

/** 仅改变送给 TTS 的文本，屏幕上的医学与测量数据保持原样。 */
internal fun screeningSpeechText(text: String): String {
    val tolerance = Regex("""(\d+(?:\.\d+)?)\s*±\s*(\d+(?:\.\d+)?)\s*(?:cm|厘米)""")
    val range = Regex("""(\d+(?:\.\d+)?)\s*[-–—~～]\s*(\d+(?:\.\d+)?)""")
    val number = Regex("""\d+(?:\.\d+)?""")
    return text
        .replace(tolerance) { "${it.groupValues[1]}厘米，上下浮动${it.groupValues[2]}厘米" }
        .replace(range) {
            val start = if (it.groupValues[1] == "2") "两" else spokenNumber(it.groupValues[1])
            val end = if (it.groupValues[2] == "2") "两" else spokenNumber(it.groupValues[2])
            "$start 到 $end"
        }
        .replace("cm", "厘米", ignoreCase = true)
        .replace("lux", "勒克斯", ignoreCase = true)
        .replace(number) { spokenNumber(it.value) }
        .replace('：', '，')
        .replace(Regex("""\s+"""), "")
        .replace("厘米约", "厘米，约")
}

private fun spokenNumber(value: String): String {
    val parts = value.split('.')
    val integer = parts[0].toIntOrNull() ?: return value
    if (integer > 999) return value
    val digits = "零一二三四五六七八九"
    fun underHundred(number: Int): String = when {
        number < 10 -> digits[number].toString()
        number < 20 -> "十" + if (number % 10 == 0) "" else digits[number % 10]
        else -> digits[number / 10].toString() + "十" + if (number % 10 == 0) "" else digits[number % 10]
    }
    val whole = if (integer < 100) underHundred(integer) else {
        digits[integer / 100].toString() + "百" +
            if (integer % 100 == 0) "" else if (integer % 100 < 10) "零${digits[integer % 10]}" else
                if (integer % 100 < 20) "一${underHundred(integer % 100)}" else underHundred(integer % 100)
    }
    return if (parts.size == 1) whole else whole + "点" + parts[1].map { digits[it - '0'] }.joinToString("")
}
