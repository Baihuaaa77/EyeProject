package com.zkyjss.eyeproject.core.data

import android.content.Context
import com.zkyjss.eyeproject.core.model.ColorTemperatureMode

object ColorTemperaturePreferences {
    private const val PREFS_NAME = "color_temperature_settings"
    private const val KEY_MODE = "mode"

    fun getMode(context: Context): ColorTemperatureMode {
        val key = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODE, ColorTemperatureMode.OFF.key)
        return ColorTemperatureMode.fromKey(key)
    }

    fun setMode(context: Context, mode: ColorTemperatureMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.key)
            .apply()
    }
}
