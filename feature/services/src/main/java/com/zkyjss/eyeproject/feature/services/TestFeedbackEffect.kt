package com.zkyjss.eyeproject.feature.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.ConcurrentHashMap

/** 前台轮询共享事件；菜单、权限弹窗与后台期间静默并丢弃过期事件。 */
@Composable
internal fun TestFeedbackEffect(settings: TestSettings, voice: ScreeningVoice, menuOpen: Boolean,
    poll: (Boolean) -> List<ScreeningFeedbackCue>) {
    val context = LocalContext.current
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    val output = remember(context, view) { AndroidTestFeedback(context, view) }
    val currentPoll by rememberUpdatedState(poll)
    val currentMenu by rememberUpdatedState(menuOpen)
    DisposableEffect(owner, output) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { currentPoll(false); output.stop() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); currentPoll(false); output.close() }
    }
    LaunchedEffect(owner, output) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            currentPoll(false)
            while (isActive) {
                val active = !currentMenu && (settings.soundEnabled || settings.hapticsEnabled)
                currentPoll(active).forEach { cue ->
                    output.play(cue, settings.soundEnabled, settings.hapticsEnabled) {
                        voice.pauseForFeedback(cue.microphonePauseMs.toLong())
                    }
                }
                if (!active) output.stop()
                delay(40)
            }
        }
    }
}

private class AndroidTestFeedback(context: Context, private val view: View) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val loaded = ConcurrentHashMap.newKeySet<Int>()
    private val pool = runCatching { SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build() }.getOrNull()
    private var stream = 0
    private val sounds: Map<String, Int>
    init {
        pool?.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded.add(id) }
        sounds = mapOf("eye_start" to R.raw.eye_start, "eye_answer" to R.raw.eye_answer,
            "eye_phase" to R.raw.eye_phase, "eye_target" to R.raw.eye_target,
            "eye_warning" to R.raw.eye_warning, "eye_tick" to R.raw.eye_tick,
            "eye_complete" to R.raw.eye_complete).mapValues { (_, resource) -> pool?.load(context, resource, 1) ?: 0 }
    }
    fun play(cue: ScreeningFeedbackCue, sound: Boolean, haptics: Boolean, beforeSound: () -> Unit) {
        if (haptics) {
            val kind = when (cue) {
                ScreeningFeedbackCue.ANSWER -> HapticFeedbackConstants.KEYBOARD_TAP
                ScreeningFeedbackCue.WARNING, ScreeningFeedbackCue.RETRY -> HapticFeedbackConstants.CONTEXT_CLICK
                ScreeningFeedbackCue.START, ScreeningFeedbackCue.COMPLETE, ScreeningFeedbackCue.RECOVER ->
                    if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
                else -> HapticFeedbackConstants.CLOCK_TICK
            }
            view.performHapticFeedback(kind)
        }
        val id = sounds[cue.sound] ?: return
        if (sound && audio.ringerMode == AudioManager.RINGER_MODE_NORMAL && loaded.contains(id)) {
            beforeSound()
            stop()
            stream = pool?.play(id, 0.65f, 0.65f, 1, 0, 1f) ?: 0
        }
    }
    fun stop() { if (stream != 0) pool?.stop(stream); stream = 0 }
    fun close() { stop(); pool?.release(); loaded.clear() }
}
