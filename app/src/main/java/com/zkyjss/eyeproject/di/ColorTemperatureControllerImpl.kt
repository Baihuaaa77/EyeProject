package com.zkyjss.eyeproject.di

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.zkyjss.eyeproject.core.data.ColorTemperaturePreferences
import com.zkyjss.eyeproject.core.domain.ColorTemperatureController
import com.zkyjss.eyeproject.core.model.ColorTemperatureMode
import com.zkyjss.eyeproject.display.ColorTemperatureOverlayService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ColorTemperatureControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ColorTemperatureController {

    override fun currentMode(): ColorTemperatureMode =
        ColorTemperaturePreferences.getMode(context)

    override fun setMode(mode: ColorTemperatureMode) {
        // 先同步写偏好，UI 立即反映选中状态（否则服务异步写偏好会导致选中状态慢一拍）
        ColorTemperaturePreferences.setMode(context, mode)
        if (mode == ColorTemperatureMode.OFF) {
            context.stopService(Intent(context, ColorTemperatureOverlayService::class.java))
        } else {
            ContextCompat.startForegroundService(
                context,
                ColorTemperatureOverlayService.createIntent(context, mode)
            )
        }
    }

    override fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(context)
}
