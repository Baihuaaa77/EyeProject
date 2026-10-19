package com.zkyjss.eyeproject.feature.services

fun stepTitle(step: ScreeningStep) = when (step) {
    ScreeningStep.PROFILE -> "基本信息"
    ScreeningStep.ENVIRONMENT -> "准备测试"
    ScreeningStep.NEAR_VISION -> "近视力"
    ScreeningStep.PUSH_UP -> "调节幅度（移近法）"
    ScreeningStep.DISTANCE_CURVE -> "多距离视力"
    ScreeningStep.FLIPPER -> "调节灵敏度（远近切换）"
    ScreeningStep.FIXATION -> "注视小瞳"
    ScreeningStep.GAZE_CALIBRATION -> "注视校准"
    ScreeningStep.COVER -> "交替遮盖"
    ScreeningStep.PURSUIT -> "跟随小瞳"
    ScreeningStep.RESULT -> "测试结果"
}

/** 老视标准模式的摘镜提醒（界面与语音共用）。 */
const val GLASSES_REMOVAL_PROMPT = "请先摘下老花镜或渐进镜；看远用的近视眼镜可以继续戴。摘好后点「已摘下，继续」。"

/** 当前引导页要说的话：摘镜提醒优先于步骤示范。 */
fun screeningGuideText(s: ScreeningState): String = if (s.glassesCheckPending) GLASSES_REMOVAL_PROMPT else guideCue(s.step)

fun guideCue(step: ScreeningStep) = when (step) {
    ScreeningStep.NEAR_VISION -> "先遮左眼，用右眼看 E，点选开口方向"
    ScreeningStep.PUSH_UP -> "从 45 cm 外看着 E，慢慢连续移近；E 一开始持续模糊就点按。共做 3 次"
    ScreeningStep.DISTANCE_CURVE -> "依次在 60、33、25 cm 停稳，看 E 选方向，记录不同距离下的视力"
    ScreeningStep.FLIPPER -> "交替移近、移远，停稳后点选 E 的开口方向"
    ScreeningStep.FIXATION -> "先依次看着九个圆点完成约 27 秒注视校准，再看中央闪烁的小瞳 12 秒，头和手机保持不动"
    ScreeningStep.COVER -> "协助者按提示遮右眼、移开、遮左眼、移开，再左右交替；不压眼球、不挡摄像头，每次动作同时点按"
    ScreeningStep.PURSUIT -> "只用眼睛跟随移动的小瞳：水平、垂直、斜向各一段，头保持不动"
    else -> "正对屏幕，保持光线均匀"
}
