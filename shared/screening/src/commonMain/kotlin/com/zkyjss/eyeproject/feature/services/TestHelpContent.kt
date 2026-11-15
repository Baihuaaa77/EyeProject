package com.zkyjss.eyeproject.feature.services

/** 两端共用当前环节的演示类型与说明，不把下一环节的做法提前展示。 */
data class TestHelpContent(val motion: String, val title: String, val cue: String)

fun screeningHelp(s: ScreeningState): TestHelpContent = TestHelpContent(
    motion = when (s.step) {
        ScreeningStep.ENVIRONMENT -> if (s.kind == ScreeningKind.STRABISMUS) "LIGHTING" else "POSTURE"
        ScreeningStep.NEAR_VISION -> "NEAR"
        ScreeningStep.PUSH_UP -> "MOVE"
        ScreeningStep.DISTANCE_CURVE -> "CURVE"
        ScreeningStep.FLIPPER -> "SWITCH"
        ScreeningStep.FIXATION, ScreeningStep.GAZE_CALIBRATION -> "FIXATE"
        ScreeningStep.FAR_FIXATION -> "FAR"
        ScreeningStep.COVER -> "COVER_SELF"
        ScreeningStep.PURSUIT -> "FOLLOW"
        else -> "POSTURE"
    },
    title = stepTitle(s.step),
    cue = if (s.step == ScreeningStep.COVER) coverGuideCue(s.fields)
        else if (s.step == ScreeningStep.ENVIRONMENT && s.kind == ScreeningKind.STRABISMUS)
            "把手机固定在支架上，正对双眼约 33 厘米。本人按提示遮眼，协助者在手机旁打光，保持双眼光照均匀。"
        else if (s.step == ScreeningStep.GAZE_CALIBRATION)
            "只用眼睛依次看着圆点，头和手机保持不动；每个圆点停稳后自动进入下一点。"
        else guideCue(s.step),
)

fun dryEyeHelp(s: DryEyeTestUiState): TestHelpContent = when (s.phase) {
    DryEyePhase.Practice, DryEyePhase.PracticeGuide -> TestHelpContent("ACUITY", "视标练习", "看清 E 的开口方向，在 2 秒内选择；看不清就选跳过，练习不计入结果。")
    DryEyePhase.Open -> TestHelpContent("OPEN", "持续睁眼", "继续看 E 选方向，尽量保持睁眼；不舒服就眨眼，随时可以停止。")
    DryEyePhase.Natural -> TestHelpContent("BLINK", "自然眨眼", "像平时一样自然眨眼，同时看 E 选方向；不要刻意多眨或少眨。")
    DryEyePhase.Rest -> TestHelpContent("REST", "闭眼休息", "轻轻闭眼休息，倒计时结束后再睁眼，按提示进行复测。")
    DryEyePhase.Countdown, DryEyePhase.FormalGuide -> TestHelpContent("FVA", "正式测试", "全程看 E 选方向。先自然眨眼，随后直接转入持续睁眼；手机约 40 厘米，保持头部稳定。")
    else -> TestHelpContent("POSTURE", "准备干眼自测", "建议戴好最佳矫正视力眼镜，正对屏幕约 40 厘米，露出双眼，保持均匀光照。")
}

fun colorVisionHelp() = TestHelpContent("POSTURE", "色觉自测", "关闭护眼和夜间模式，在明亮室内测试，手机离眼睛约 40 厘米。选出色点图中看到的数字；看不出就选「看不出」，不要猜。")
