package com.zkyjss.eyeproject.feature.services

fun stepTitle(step: ScreeningStep) = when (step) {
    ScreeningStep.PROFILE -> "基本信息"
    ScreeningStep.ENVIRONMENT -> "准备测试"
    ScreeningStep.NEAR_VISION -> "近视力"
    ScreeningStep.PUSH_UP -> "单眼调节幅度（移近法）"
    ScreeningStep.DISTANCE_CURVE -> "多距离视力"
    ScreeningStep.FLIPPER -> "远近切换（可选）"
    ScreeningStep.FIXATION -> "注视圆点"
    ScreeningStep.FAR_FIXATION -> "看远处"
    ScreeningStep.GAZE_CALIBRATION -> "注视校准"
    ScreeningStep.COVER -> "交替遮盖"
    ScreeningStep.PURSUIT -> "跟随圆点"
    ScreeningStep.RESULT -> "测试结果"
}

/** 看远：等受试者看向 6 米外的目标（界面与语音共用）。 */
const val FAR_WAIT_PROMPT = "请看着 6 米外的目标。看好以后，协助者点「已在看远处，开始」。"

/** 当前引导页要说的话：戴镜确认优先于步骤示范。 */
fun screeningGuideText(s: ScreeningState): String =
    if (s.glassesCheckPending) PresbyopiaIntake.glassesCheck(s.fields).prompt else guideCue(s.step)

fun guideCue(step: ScreeningStep) = when (step) {
    ScreeningStep.NEAR_VISION -> "先遮左眼，用右眼看 E，点选开口方向"
    ScreeningStep.PUSH_UP -> "遮住一只眼，从 45 cm 外看着 E，每秒移近一两厘米；E 模糊后停一下，仍看不清就点按。每只眼做 3 次"
    ScreeningStep.DISTANCE_CURVE -> "依次在 67、50、33 cm 停稳，看 E 选方向，记录不同距离下的视力"
    ScreeningStep.FLIPPER -> "可选，约 1 分钟：先在 50 cm 和 25 cm 各看清一次 E，再来回移动手机，停稳后点选开口方向"
    ScreeningStep.FIXATION -> "先依次看着九个圆点完成约 18 秒注视校准，再看屏幕上方闪烁的圆点 12 秒，头和手机保持不动"
    ScreeningStep.FAR_FIXATION -> "在 6 米外找一个目标（门、窗或墙上的画）。手机留在眼前约 33 cm、略低于视线，" +
        "眼睛越过手机看着目标约 10 秒，头和手机不动；协助者确认一直看着远处"
    ScreeningStep.COVER -> "协助者按提示遮右眼、移开、遮左眼、移开，再左右交替；不压眼球、不挡摄像头，每次动作同时点按"
    ScreeningStep.PURSUIT -> "只用眼睛跟随移动的圆点：水平、垂直、斜向各一段，头保持不动"
    else -> "正对屏幕，保持光线均匀"
}
