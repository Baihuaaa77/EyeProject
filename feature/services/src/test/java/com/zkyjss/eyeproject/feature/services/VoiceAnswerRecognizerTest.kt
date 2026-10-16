package com.zkyjss.eyeproject.feature.services

import android.content.Context
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSpeechRecognizer
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceAnswerRecognizerTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private class Callbacks : VoiceAnswerRecognizer.Callbacks {
        val commands = mutableListOf<VoiceCommand>()
        var listening = false
        override fun onListening(listening: Boolean) { this.listening = listening }
        override fun onHeard(text: String) = Unit
        override fun onCommand(command: VoiceCommand) { commands.add(command) }
        override fun onUnavailable(reason: String) = Unit
    }
    private fun result(text: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) }

    @Test fun unavailableSystemServiceDoesNotDisableBundledVoiceAnswers() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        val recognizer = VoiceAnswerRecognizer(context, Callbacks())
        assertTrue(recognizer.available)
        recognizer.destroy()
    }

    @Test fun chineseFallbackAllowsSystemNetworkRecognitionAndDirectionsReachCallback() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val callbacks = Callbacks()
        val recognizer = VoiceAnswerRecognizer(context, callbacks, preferOffline = false)
        recognizer.start(); ShadowLooper.idleMainLooper()
        val device = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        assertTrue(device.lastRecognizerIntent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        device.triggerOnError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        val system = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        assertFalse(system.lastRecognizerIntent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
        system.triggerOnReadyForSpeech(Bundle())
        system.triggerOnPartialResults(result("左"))
        system.triggerOnResults(result("左"))
        assertEquals(listOf(VoiceCommand.Direction(2)), callbacks.commands)
        recognizer.destroy()
    }

    @Test fun cancelledSessionsCannotAnswerAfterListeningRestarts() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val callbacks = Callbacks()
        val recognizer = VoiceAnswerRecognizer(context, callbacks, preferOffline = false)
        recognizer.start(); ShadowLooper.idleMainLooper()
        val old = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        recognizer.stop(); recognizer.start(); ShadowLooper.idleMainLooper()
        old.triggerOnResults(result("右"))
        assertTrue(callbacks.commands.isEmpty())
        val fresh = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        fresh.triggerOnResults(result("上"))
        assertEquals(listOf(VoiceCommand.Direction(3)), callbacks.commands)
        recognizer.destroy()
    }

    @Test fun offlinePartialAndFinalResultsDoNotAnswerTwiceButNextUtteranceStillWorks() {
        val commands = mutableListOf<VoiceCommand>()
        val results = OfflineVoiceResults({}, commands::add)
        results.accept("""{"partial":"向 左"}""", false)
        results.accept("""{"text":"向 左"}""", true)
        results.accept("""{"text":"左"}""", true)
        results.accept("""{"text":"[unk]"}""", true)
        results.accept("bad json", true)
        results.accept("""{"text":"看 不 清"}""", true)
        // 噪声识别成的 [unk] 夹在指令前不再让整句作废
        results.accept("""{"partial":"[unk] 右"}""", false)
        assertEquals(listOf(VoiceCommand.Direction(2), VoiceCommand.Direction(2), VoiceCommand.Unsure, VoiceCommand.Direction(0)), commands)
    }

    @Test fun echoSwallowsTheWholeUtteranceEvenIfItsFinalResultArrivesAfterSpeech() {
        val commands = mutableListOf<VoiceCommand>()
        var speaking = true
        val results = OfflineVoiceResults({}, commands::add) { speaking && it == VoiceCommand.Direction(0) }
        results.accept("""{"partial":"右"}""", false)
        speaking = false
        results.accept("""{"text":"右"}""", true)
        assertTrue(commands.isEmpty())
        results.accept("""{"text":"右"}""", true)
        assertEquals(listOf(VoiceCommand.Direction(0)), commands)
    }

    private fun initializedVoice(): ScreeningVoice {
        val voice = ScreeningVoice(context)
        shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).onInitListener.onInit(TextToSpeech.SUCCESS)
        ShadowLooper.idleMainLooper()
        return voice
    }

    @Test fun allScreenInstancesReuseTheSameChineseVoice() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.SIMPLIFIED_CHINESE)
        val first = Voice("chinese-default", Locale.SIMPLIFIED_CHINESE, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet())
        ShadowTextToSpeech.addVoice(first)
        val a = initializedVoice()
        val selected = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).currentVoice.name
        a.close()
        ShadowTextToSpeech.addVoice(Voice("another-chinese", Locale.SIMPLIFIED_CHINESE, Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL, false, emptySet()))
        val b = initializedVoice()
        assertEquals(selected, shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).currentVoice.name)
        b.close()
    }

    @Test fun concurrentScreensShareOneEngineAndVoice() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.SIMPLIFIED_CHINESE)
        ShadowTextToSpeech.addVoice(Voice("chinese-default", Locale.SIMPLIFIED_CHINESE, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet()))
        val first = initializedVoice()
        val engine = ShadowTextToSpeech.getLastTextToSpeechInstance()
        // 导航过渡时新页先建、旧页后释放：不能再建第二个引擎，也不能各自挑选发声人
        val second = ScreeningVoice(context)
        assertTrue(engine === ShadowTextToSpeech.getLastTextToSpeechInstance())
        first.close()
        second.speak("请保持三十三厘米")
        assertEquals("chinese-default", shadowOf(engine).currentVoice.name)
        assertTrue(second.speaking)
        second.close()
    }

    @Test fun duringSpeechOnlyCommandsMentionedByThePromptAreTreatedAsEcho() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.SIMPLIFIED_CHINESE)
        val voice = initializedVoice()
        voice.speak("右眼测试")
        assertTrue(voice.echoes(VoiceCommand.Direction(0)))
        assertFalse(voice.echoes(VoiceCommand.Direction(2)))
        voice.stop()
        assertFalse(voice.echoes(VoiceCommand.Direction(0)))
        voice.close()
    }

    @Test fun playbackGuardTracksNewUtteranceAndEndsAfterCooldown() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.SIMPLIFIED_CHINESE)
        val voice = initializedVoice()
        val shadow = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        fun id() = ScreeningVoice::class.java.getDeclaredField("currentUtterance").apply { isAccessible = true }.get(voice) as String
        voice.speak("左眼测试")
        val old = id()
        voice.speak("请准备好")
        val latest = id()
        shadow.utteranceProgressListener.onDone(old)
        ShadowLooper.idleMainLooper()
        assertTrue(voice.speaking)
        shadow.utteranceProgressListener.onDone(latest)
        ShadowLooper.idleMainLooper(599, TimeUnit.MILLISECONDS)
        assertTrue(voice.speaking)
        ShadowLooper.idleMainLooper(1, TimeUnit.MILLISECONDS)
        assertFalse(voice.speaking)
        voice.close()
    }
}
