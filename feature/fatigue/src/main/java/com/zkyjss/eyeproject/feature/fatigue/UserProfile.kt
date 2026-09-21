package com.zkyjss.eyeproject.feature.fatigue

/**
 * 静态用户画像 + 交互反馈（本阶段暂用默认值）。
 * 与 JNI 的 profile double[7] 顺序严格一致。
 * 注：已移除低头角度（hasHeadTilt / headTiltDeg）。
 */
data class UserProfile(
    val age: Double = 30.0,
    val hasMyopia: Double = 0.0,
    val hasDryEye: Double = 0.0,
    val parentalMyopia: Double = 0.0,
    val readingPosture: Double = 0.0,
    val discomfortScore: Double = 0.0,
    val contentContrast: Double = 0.8
) {
    fun toDoubleArray(): DoubleArray = doubleArrayOf(
        age, hasMyopia, hasDryEye, parentalMyopia, readingPosture,
        discomfortScore, contentContrast
    )
}
