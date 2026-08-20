package com.zkyjss.eyeproject.core.data

import android.content.Context
import com.zkyjss.eyeproject.core.model.AnalysisResolution
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 主设置（40cm 标定基准、分析分辨率）的持久化封装。 */
@Singleton
class AppSettings @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var calibratedEyeDistancePx: Float?
        get() = if (prefs.contains(KEY_CALIBRATED_EYE_DISTANCE_PX)) {
            prefs.getFloat(KEY_CALIBRATED_EYE_DISTANCE_PX, 0f)
        } else {
            null
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove(KEY_CALIBRATED_EYE_DISTANCE_PX)
                } else {
                    putFloat(KEY_CALIBRATED_EYE_DISTANCE_PX, value)
                }
            }.apply()
        }

    var analysisResolution: AnalysisResolution
        get() = AnalysisResolution.fromKey(
            prefs.getString(KEY_ANALYSIS_RESOLUTION, AnalysisResolution.BALANCED.key)
        )
        set(value) {
            prefs.edit().putString(KEY_ANALYSIS_RESOLUTION, value.key).apply()
        }

    companion object {
        private const val PREFS_NAME = "eyeprotect_settings"
        private const val KEY_CALIBRATED_EYE_DISTANCE_PX = "calibrated_eye_distance_px"
        private const val KEY_ANALYSIS_RESOLUTION = "analysis_resolution"
    }
}
