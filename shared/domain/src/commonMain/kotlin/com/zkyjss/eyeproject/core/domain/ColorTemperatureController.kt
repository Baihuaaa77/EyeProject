package com.zkyjss.eyeproject.core.domain

import com.zkyjss.eyeproject.core.model.ColorTemperatureMode

/** 全局护眼色温控制入口，由 app 层实现。 */
interface ColorTemperatureController {
    fun currentMode(): ColorTemperatureMode
    fun setMode(mode: ColorTemperatureMode)
    fun hasOverlayPermission(): Boolean
}
