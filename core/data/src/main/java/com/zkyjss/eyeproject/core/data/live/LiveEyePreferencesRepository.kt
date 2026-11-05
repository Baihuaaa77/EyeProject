package com.zkyjss.eyeproject.core.data.live

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 首页实时检测的显示偏好（只影响画面，不影响检测本身）。与苹果端 `EyeLiveModel.showLandmarks` 同一默认值。 */
interface LiveEyePreferencesRepository {
    /** 预览上是否叠加眼部关键点（眼睑轮廓 + 虹膜圈），默认关闭。 */
    val showLandmarks: Flow<Boolean>

    suspend fun setShowLandmarks(show: Boolean)
}

/** SharedPreferences 落盘，内存里用 StateFlow 推送变化。 */
@Singleton
internal class SharedPrefsLiveEyePreferencesRepository @Inject constructor(
    @ApplicationContext context: Context,
) : LiveEyePreferencesRepository {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _showLandmarks = MutableStateFlow(prefs.getBoolean(KEY_SHOW_LANDMARKS, false))
    override val showLandmarks: Flow<Boolean> = _showLandmarks.asStateFlow()

    override suspend fun setShowLandmarks(show: Boolean) {
        _showLandmarks.value = show
        withContext(Dispatchers.IO) { prefs.edit().putBoolean(KEY_SHOW_LANDMARKS, show).commit() }
    }

    private companion object {
        const val PREFS = "eye_live_detection"
        const val KEY_SHOW_LANDMARKS = "show_landmarks"
    }
}
