package com.zkyjss.eyeproject.feature.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.Vibrator
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSoundPool
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidTestFeedbackTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun soundPool(output: AndroidTestFeedback): ShadowSoundPool =
        Shadow.extract(ReflectionHelpers.getField<SoundPool>(output, "pool"))

    @Test fun feedbackReachesMediaOutputWithSilentRingerAndSystemTouchFeedbackOff() {
        context.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_SILENT
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0)
        val vibration = shadowOf(context.getSystemService(Vibrator::class.java)).apply { setHasVibrator(true) }
        val output = AndroidTestFeedback(context)
        try {
            val sounds = soundPool(output)
            sounds.notifyResourceLoaded(R.raw.eye_answer, true)
            var guardedMicrophone = false
            output.play(ScreeningFeedbackCue.ANSWER, true, true) { guardedMicrophone = true }
            assertTrue("静音铃声不应屏蔽已开启的测试音效", sounds.wasResourcePlayed(R.raw.eye_answer))
            assertTrue(guardedMicrophone)
            val pool = ReflectionHelpers.getField<SoundPool>(output, "pool")
            assertEquals(AudioAttributes.USAGE_MEDIA, ReflectionHelpers.getField<AudioAttributes>(pool, "mAttributes").usage)
            assertTrue("测试震动不应依赖 View 的系统触摸反馈调用", vibration.isVibrating)
            assertEquals(VibrationAttributes.USAGE_MEDIA,
                (vibration.vibrationAttributesFromLastVibration as VibrationAttributes).usage)
            output.stop()
            assertTrue(vibration.isCancelled)
        } finally { output.close() }
    }

    @Test fun firstSoundWaitsForLoadButIsDiscardedAfterPauseOrExpiry() {
        for (action in listOf("load", "pause", "expire", "close")) {
            val output = AndroidTestFeedback(context)
            val sounds = soundPool(output)
            var guardedMicrophone = false
            try {
                output.play(ScreeningFeedbackCue.START, true, false) { guardedMicrophone = true }
                assertFalse(sounds.wasResourcePlayed(R.raw.eye_start))
                when (action) {
                    "pause" -> output.stop()
                    "expire" -> SystemClock.sleep(301)
                    "close" -> output.close()
                }
                sounds.notifyResourceLoaded(R.raw.eye_start, true)
                assertEquals(action, action == "load", sounds.wasResourcePlayed(R.raw.eye_start))
                assertEquals(action, action == "load", guardedMicrophone)
            } finally { output.close() }
        }
    }

    @Test fun disabledOutputsAndDevicesWithoutVibratorStayQuiet() {
        val vibration = shadowOf(context.getSystemService(Vibrator::class.java)).apply { setHasVibrator(false) }
        val output = AndroidTestFeedback(context)
        try {
            val sounds = soundPool(output)
            sounds.notifyResourceLoaded(R.raw.eye_start, true)
            output.play(ScreeningFeedbackCue.START, false, false) { fail("关闭音效不应暂停麦克风") }
            output.play(ScreeningFeedbackCue.START, false, true) {}
            assertFalse(sounds.wasResourcePlayed(R.raw.eye_start))
            assertFalse(vibration.isVibrating)
        } finally { output.close() }
    }

    @Test @Config(sdk = [28]) fun androidNineUsesShortPulseAndMediaAudioAttributes() {
        val vibration = shadowOf(context.getSystemService(Vibrator::class.java)).apply { setHasVibrator(true) }
        val output = AndroidTestFeedback(context)
        try {
            output.play(ScreeningFeedbackCue.TARGET, false, true) {}
            assertTrue(vibration.isVibrating)
            assertEquals(25L, vibration.milliseconds)
            assertEquals(AudioAttributes.USAGE_MEDIA, vibration.audioAttributesFromLastVibration?.usage)
        } finally { output.close() }
    }
}
