package com.zkyjss.eyeproject.core.ui.component

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.zkyjss.eyeproject.core.ui.R

/** 护眼操与休息页的提示音（开始、换动作、倒数、完成），复用测试提示音素材。 */
enum class EyeCue(internal val resource: Int) {
    START(R.raw.eye_start), PHASE(R.raw.eye_phase), COUNT(R.raw.eye_count), COMPLETE(R.raw.eye_complete)
}

/**
 * 提示音：与测试音效同一套开关与音量（「系统设置」的音效反馈、音效音量、震动反馈，
 * 保存在 screening_settings 偏好里，见 feature:services 的 TestSettings）。走媒体音量。
 */
class EyeCueSounds internal constructor(context: Context) {
    private val preferences = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
    private val pool = runCatching {
        SoundPool.Builder().setMaxStreams(2).setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    }.getOrNull()
    private val ids = EyeCue.entries.associateWith { pool?.load(context, it.resource, 1) ?: 0 }

    fun play(cue: EyeCue) {
        if (!preferences.getBoolean("sound", true)) return
        val id = ids[cue] ?: return
        val volume = (preferences.getFloat("soundVolume", 1f) * 0.7f).coerceIn(0f, 1f)
        pool?.play(id, volume, volume, 1, 0, 1f)
    }

    fun hapticsEnabled(): Boolean = preferences.getBoolean("haptics", true)

    internal fun release() { pool?.release() }

    private companion object { const val SETTINGS = "screening_settings" }
}

@Composable
fun rememberEyeCueSounds(): EyeCueSounds {
    val context = LocalContext.current.applicationContext
    val sounds = remember(context) { EyeCueSounds(context) }
    DisposableEffect(sounds) { onDispose { sounds.release() } }
    return sounds
}
