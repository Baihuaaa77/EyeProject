package com.zkyjss.eyeproject.core.model

/** 同一相机帧、同一像素坐标系内的眼部光学候选测量。 */
data class OpticalEyeMeasurement(
    val pupilCenter: Point2f?,
    val cornealReflex: Point2f?,
    val irisDiameterPx: Float,
    val pupilContrast: Float,
    val reflexContrast: Float,
    val rejectionReason: String?,
) {
    val usable: Boolean get() = pupilCenter != null && cornealReflex != null && rejectionReason == null
    val horizontalIrisUnits: Float?
        get() = if (usable && irisDiameterPx > 0f) (pupilCenter!!.x - cornealReflex!!.x) / irisDiameterPx else null
    val verticalIrisUnits: Float?
        get() = if (usable && irisDiameterPx > 0f) (pupilCenter!!.y - cornealReflex!!.y) / irisDiameterPx else null
}

/** MediaPipe 面部变换矩阵估计的相对头姿；角度只用于采集质量检查。 */
data class HeadPoseEstimate(val yawDeg: Float, val pitchDeg: Float, val rollDeg: Float)
