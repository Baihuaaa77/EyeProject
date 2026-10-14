package com.zkyjss.eyeproject.core.model

enum class ColorTemperatureMode(
    val key: String,
    val label: String,
    val overlayColor: Int,
    val windowOpacity: Float
) {
    OFF("off", "关闭", 0x00000000, 0f),
    COMFORT("comfort", "舒适", 0xFFFF9800.toInt(), 0.13f),
    WARM("warm", "暖色", 0xFFFF8A00.toInt(), 0.24f);

    companion object {
        fun fromKey(key: String?): ColorTemperatureMode =
            entries.firstOrNull { it.key == key } ?: OFF
    }
}
