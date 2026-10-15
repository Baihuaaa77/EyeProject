package com.zkyjss.eyeproject.feature.services

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
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
    val owner = LocalLifecycleOwner.current
    val output = rememberTestFeedbackOutput()
    val currentPoll by rememberUpdatedState(poll)
    val currentMenu by rememberUpdatedState(menuOpen)
    DisposableEffect(owner, output) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) currentPoll(false)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); currentPoll(false) }
    }
    LaunchedEffect(owner, output) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            currentPoll(false)
            var wasActive = false
            var hadSound = settings.soundEnabled
            while (isActive) {
                val active = !currentMenu && (settings.soundEnabled || settings.hapticsEnabled)
                if ((!active && wasActive) || (hadSound && !settings.soundEnabled)) output.stop()
                currentPoll(active).forEach { cue ->
                    output.play(cue, settings.soundEnabled, settings.hapticsEnabled) {
                        voice.pauseForFeedback(cue.microphonePauseMs.toLong())
                    }
                }
                wasActive = active
                hadSound = settings.soundEnabled
                delay(40)
            }
        }
    }
}

@Composable
internal fun rememberTestFeedbackOutput(): AndroidTestFeedback {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val output = remember(context, owner) { AndroidTestFeedback(context) }
    DisposableEffect(owner, output) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) output.stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); output.close() }
    }
    return output
}

/** 测试内容的声音和震动由应用开关控制，不依赖系统按键音或触摸反馈开关。 */
internal class AndroidTestFeedback(context: Context) {
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val loaded = ConcurrentHashMap.newKeySet<Int>()
    private val pool = runCatching { SoundPool.Builder().setMaxStreams(1).setAudioAttributes(audioAttributes).build() }.getOrNull()
    private var stream = 0
    private data class PendingSound(val id: Int, val expiresAt: Long, val beforeSound: () -> Unit)
    private var pending: PendingSound? = null
    private var closed = false
    private val sounds: Map<String, Int>
    init {
        pool?.setOnLoadCompleteListener { _, id, status ->
            if (!closed && status == 0) { loaded.add(id); playLoadedSound() }
        }
        sounds = mapOf("eye_start" to R.raw.eye_start, "eye_answer" to R.raw.eye_answer,
            "eye_phase" to R.raw.eye_phase, "eye_target" to R.raw.eye_target,
            "eye_warning" to R.raw.eye_warning, "eye_tick" to R.raw.eye_tick,
            "eye_complete" to R.raw.eye_complete).mapValues { (_, resource) -> pool?.load(context, resource, 1) ?: 0 }
    }
    fun play(cue: ScreeningFeedbackCue, sound: Boolean, haptics: Boolean, beforeSound: () -> Unit) {
        if (closed) return
        pending = null
        if (haptics && vibrator?.hasVibrator() == true) runCatching {
            val effect = if (Build.VERSION.SDK_INT >= 29) {
                VibrationEffect.createPredefined(when (cue) {
                    ScreeningFeedbackCue.WARNING, ScreeningFeedbackCue.RETRY, ScreeningFeedbackCue.COMPLETE -> VibrationEffect.EFFECT_DOUBLE_CLICK
                    else -> VibrationEffect.EFFECT_CLICK
                })
            } else VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE)
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, audioAttributes)
            }
        }
        val id = sounds[cue.sound] ?: return
        if (sound && id != 0) {
            // 首次点按可能早于异步载入完成；只等待很短时间，不延后重播过期事件。
            pending = PendingSound(id, SystemClock.elapsedRealtime() + 300, beforeSound)
            playLoadedSound()
        }
    }
    private fun playLoadedSound() {
        val next = pending ?: return
        if (closed || SystemClock.elapsedRealtime() > next.expiresAt) { pending = null; return }
        if (!loaded.contains(next.id)) return
        pending = null
        next.beforeSound()
        if (stream != 0) pool?.stop(stream)
        stream = pool?.play(next.id, 0.65f, 0.65f, 1, 0, 1f) ?: 0
    }
    fun stop() { pending = null; if (stream != 0) pool?.stop(stream); stream = 0; runCatching { vibrator?.cancel() } }
    fun close() { if (closed) return; closed = true; stop(); pool?.release(); loaded.clear() }
}
