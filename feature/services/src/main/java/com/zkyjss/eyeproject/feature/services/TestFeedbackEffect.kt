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
    private data class PendingSound(val id: Int, val volume: Float, val expiresAt: Long, val beforeSound: () -> Unit)
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
            val effect = hapticEffect(cue)
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
            pending = PendingSound(id, cue.volume, SystemClock.elapsedRealtime() + 300, beforeSound)
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
        stream = pool?.play(next.id, next.volume, next.volume, 1, 0, 1f) ?: 0
    }
    /**
     * 分级触觉：高频的作答、节拍最轻，阶段切换次之，提醒为两下，完成为渐强三下。
     * 支持原语的设备用组合效果，其余回落到系统预定义效果或短脉冲。
     */
    private fun hapticEffect(cue: ScreeningFeedbackCue): VibrationEffect {
        val v = vibrator
        if (Build.VERSION.SDK_INT >= 30 && v != null && v.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK)) {
            val click = VibrationEffect.Composition.PRIMITIVE_CLICK
            val tick = VibrationEffect.Composition.PRIMITIVE_TICK
            return VibrationEffect.startComposition().apply {
                when (cue) {
                    ScreeningFeedbackCue.TICK -> addPrimitive(tick, 0.6f)
                    ScreeningFeedbackCue.ANSWER -> addPrimitive(click, 0.45f)
                    ScreeningFeedbackCue.TARGET -> addPrimitive(tick, 0.75f)
                    ScreeningFeedbackCue.PHASE -> { addPrimitive(click, 0.6f); addPrimitive(tick, 0.6f, 60) }
                    ScreeningFeedbackCue.RECOVER -> { addPrimitive(tick, 0.5f); addPrimitive(click, 0.7f, 50) }
                    ScreeningFeedbackCue.START -> addPrimitive(click, 0.9f)
                    ScreeningFeedbackCue.WARNING, ScreeningFeedbackCue.RETRY -> { addPrimitive(click, 0.8f); addPrimitive(click, 0.8f, 100) }
                    ScreeningFeedbackCue.COMPLETE -> { addPrimitive(tick, 0.5f); addPrimitive(tick, 0.75f, 60); addPrimitive(click, 1f, 60) }
                }
            }.compose()
        }
        if (Build.VERSION.SDK_INT >= 29) return VibrationEffect.createPredefined(when (cue) {
            ScreeningFeedbackCue.TICK, ScreeningFeedbackCue.ANSWER, ScreeningFeedbackCue.TARGET -> VibrationEffect.EFFECT_TICK
            ScreeningFeedbackCue.WARNING, ScreeningFeedbackCue.RETRY -> VibrationEffect.EFFECT_DOUBLE_CLICK
            ScreeningFeedbackCue.COMPLETE -> VibrationEffect.EFFECT_HEAVY_CLICK
            else -> VibrationEffect.EFFECT_CLICK
        })
        return when (cue) {
            ScreeningFeedbackCue.WARNING, ScreeningFeedbackCue.RETRY, ScreeningFeedbackCue.COMPLETE ->
                VibrationEffect.createWaveform(longArrayOf(0, 25, 90, 25), -1)
            else -> VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }
    fun stop() { pending = null; if (stream != 0) pool?.stop(stream); stream = 0; runCatching { vibrator?.cancel() } }
    fun close() { if (closed) return; closed = true; stop(); pool?.release(); loaded.clear() }
}

/** 高频的作答与节拍放轻，提醒和完成稍响，整体仍低于媒体满音量。 */
private val ScreeningFeedbackCue.volume: Float get() = when (this) {
    ScreeningFeedbackCue.ANSWER, ScreeningFeedbackCue.TICK -> 0.5f
    ScreeningFeedbackCue.TARGET -> 0.55f
    ScreeningFeedbackCue.PHASE, ScreeningFeedbackCue.RECOVER -> 0.6f
    ScreeningFeedbackCue.START -> 0.65f
    ScreeningFeedbackCue.WARNING, ScreeningFeedbackCue.RETRY, ScreeningFeedbackCue.COMPLETE -> 0.7f
}
