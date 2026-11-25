package com.zkyjss.strabismuslab

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import java.util.Locale

/** StrabismusLab Android 端轻量中文语音提示、提示音与触觉反馈引擎。 */
class LabAndroidFeedback(context: Context) {
    enum class Cue { START, PHASE, CONFIRM, TICK, COUNT, WARNING, COMPLETE }

    var voiceEnabled: Boolean = true
    var soundHapticsEnabled: Boolean = true

    private var ttsReady = false
    private var pendingSpeech: String? = null
    private val tts = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            ttsEngine?.language = Locale.SIMPLIFIED_CHINESE
            ttsEngine?.setSpeechRate(1.05f)
            pendingSpeech?.let {
                pendingSpeech = null
                speak(it, interrupt = true)
            }
        }
    }
    private val ttsEngine: TextToSpeech? get() = tts

    private val tone: ToneGenerator? = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, 72)
    }.getOrNull()

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    fun speak(text: String, interrupt: Boolean = true) {
        if (!voiceEnabled || text.isBlank()) return
        if (!ttsReady) {
            pendingSpeech = text
            return
        }
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, mode, null, "lab_${System.nanoTime()}")
    }

    fun stopSpeech() {
        pendingSpeech = null
        runCatching { tts.stop() }
    }

    fun play(cue: Cue) {
        if (!soundHapticsEnabled) return
        val (toneType, durationMs, vibMs) = when (cue) {
            Cue.START -> Triple(ToneGenerator.TONE_PROP_ACK, 120, 35L)
            Cue.PHASE -> Triple(ToneGenerator.TONE_PROP_BEEP2, 130, 30L)
            Cue.CONFIRM -> Triple(ToneGenerator.TONE_PROP_ACK, 90, 28L)
            Cue.TICK -> Triple(ToneGenerator.TONE_PROP_BEEP, 60, 18L)
            Cue.COUNT -> Triple(ToneGenerator.TONE_PROP_BEEP, 75, 22L)
            Cue.WARNING -> Triple(ToneGenerator.TONE_PROP_NACK, 160, 50L)
            Cue.COMPLETE -> Triple(ToneGenerator.TONE_PROP_PROMPT, 220, 60L)
        }
        runCatching { tone?.startTone(toneType, durationMs) }
        vibrate(vibMs)
    }

    private fun vibrate(ms: Long) {
        val v = vibrator ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        }
    }

    fun release() {
        runCatching { tts.stop(); tts.shutdown() }
        runCatching { tone?.release() }
    }
}
