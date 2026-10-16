package com.zkyjss.eyeproject.feature.services

/** 仅提供行动提示；开始、呈现和作答仍由原测量门控判断。 */
fun screeningGuidance(s: ScreeningState): String? {
    if (s.step == ScreeningStep.PROFILE || s.step == ScreeningStep.RESULT || s.guidePending) return null
    // 遮盖阶段单眼不可见是预期情况，不提示用户移开遮盖。
    if (s.step == ScreeningStep.COVER) return null
    val monocular = s.step == ScreeningStep.NEAR_VISION && s.visionEye != VisionEye.BOTH
    if ((!s.faceDetected && !monocular) || !s.distanceCm.isFinite() || s.distanceCm <= 0f) return "请正对屏幕"
    if (s.lux != null && s.lux < 50f) return "请增加光照"
    s.headPose?.let { pose -> if (kotlin.math.abs(pose.yawDeg) > 15f || kotlin.math.abs(pose.pitchDeg) > 15f) return "请把头摆正" }
    // 移近法：只提示速度，不提示距离
    if (s.step == ScreeningStep.PUSH_UP) {
        if (s.pushStage != PushStage.APPROACH) {
            return if (s.distanceCm < PresbyopiaProtocol.PUSH_START_MIN_CM) "先移到 45 cm 以外" else "保持稳定"
        }
        val speed = s.pushSpeedCmPerSec ?: return "慢慢移近"
        return if (speed > PresbyopiaProtocol.PUSH_MAX_SPEED_CM_S) "慢一点" else "慢慢移近"
    }
    s.distanceMoveToCm?.let { return moveToPrompt(it) }
    val (target, tolerance) = screeningDistanceTarget(s) ?: return "保持稳定"
    return when {
        s.distanceCm < target - tolerance -> "太近了，请把手机往远处移"
        s.distanceCm > target + tolerance -> "太远了，请把手机靠近一点"
        else -> "保持稳定"
    }
}

/** 当前步骤要求的（目标距离, 容差）；移近法、遮盖、问卷与结果页不做距离约束时返回 null。 */
fun screeningDistanceTarget(s: ScreeningState): Pair<Float, Float>? = when (s.step) {
    ScreeningStep.PROFILE, ScreeningStep.RESULT, ScreeningStep.PUSH_UP, ScreeningStep.COVER -> null
    ScreeningStep.FLIPPER -> (if (s.trialIndex % 2 == 0) 50f else 25f) to PresbyopiaProtocol.DISTANCE_TOLERANCE_CM
    ScreeningStep.DISTANCE_CURVE -> PresbyopiaProtocol.CURVE_DISTANCES.getOrElse(s.curveIndex) { 40f } to PresbyopiaProtocol.DISTANCE_TOLERANCE_CM
    else -> if (s.kind == ScreeningKind.STRABISMUS) StrabismusProtocol.DISTANCE_CM to StrabismusProtocol.DISTANCE_TOLERANCE_CM
        else PresbyopiaProtocol.NEAR_DISTANCE_CM to PresbyopiaProtocol.DISTANCE_TOLERANCE_CM
}

/** 换目标距离时的提示（屏幕与语音共用）。 */
fun moveToPrompt(targetCm: Float) = "请移动到 ${targetCm.toInt()} 厘米处"
