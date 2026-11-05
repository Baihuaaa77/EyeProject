package com.zkyjss.eyeproject.core.data.theme

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 界面明暗：跟随系统（默认）/ 白天 / 夜晚。用户点右上角太阳 / 月亮后固定为白天或夜晚。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 界面明暗偏好。读取为 [StateFlow]（冷启动第一帧就有值，不会先闪一下系统主题），写入为挂起函数。
 * 与苹果端 UserDefaults「eye.themeMode」同一取值（system / light / dark）。
 */
interface ThemeRepository {
    val themeMode: StateFlow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)
}

@Singleton
internal class DefaultThemeRepository @Inject constructor(@ApplicationContext context: Context) : ThemeRepository {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(parse(prefs.getString(KEY_MODE, null)))
    override val themeMode: StateFlow<ThemeMode> = state.asStateFlow()

    override suspend fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_MODE, mode.name.lowercase()).apply()
        state.value = mode
    }

    private fun parse(value: String?) = ThemeMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ThemeMode.SYSTEM

    private companion object {
        const val PREFS_NAME = "eye_theme"
        const val KEY_MODE = "mode"
    }
}
