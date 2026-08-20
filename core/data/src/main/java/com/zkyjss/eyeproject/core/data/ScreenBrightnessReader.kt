package com.zkyjss.eyeproject.core.data

import android.content.Context
import android.provider.Settings
import kotlin.math.roundToInt

data class BrightnessReading(
    val percent: Int?,
    val raw: Int?,
    val automatic: Boolean?
)

object ScreenBrightnessReader {
    fun read(context: Context): BrightnessReading {
        return try {
            val raw = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            ).coerceIn(0, 255)
            val mode = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val percent = ((raw / 255f) * 100f).roundToInt().coerceIn(0, 100)
            BrightnessReading(
                percent = percent,
                raw = raw,
                automatic = mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            )
        } catch (_: Settings.SettingNotFoundException) {
            BrightnessReading(percent = null, raw = null, automatic = null)
        } catch (_: SecurityException) {
            BrightnessReading(percent = null, raw = null, automatic = null)
        }
    }
}
