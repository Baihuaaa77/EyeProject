package com.zkyjss.eyeproject.feature.services

/** 方法演示与正式测试时钟相互独立，左右均以被测者自身为准。 */
internal enum class GuideMotion { POSTURE, ACUITY, FVA, BLINK, OPEN, NEAR, MOVE, CURVE, SWITCH, FIXATE, FAR, COVER, COVER_SELF, FOLLOW, REST, LIGHTING }
internal data class GuideBeat(val start: Float, val label: String)
internal data class GuideScene(val motion: GuideMotion, val cycleMs: Int, val beats: List<GuideBeat>, val keyframe: Float)
internal enum class GuidePose { OPEN, BLINK, LEFT, RIGHT, LIGHTING_OPEN, LIGHTING_LEFT, LIGHTING_RIGHT, SIDE_FAR, SIDE_NEAR }

internal fun guideScene(motion: GuideMotion): GuideScene = when (motion) {
    GuideMotion.LIGHTING -> GuideScene(motion, 5000, listOf(GuideBeat(0f, "① 手机固定在支架上，正对双眼"), GuideBeat(0.35f, "② 协助者在手机旁打光"), GuideBeat(0.7f, "③ 本人按提示遮眼，不压眼球")), 0.5f)
    GuideMotion.REST -> GuideScene(motion, 4000, listOf(GuideBeat(0f, "① 轻轻闭眼，放松休息"), GuideBeat(0.5f, "② 倒计时结束后再睁眼")), 0.5f)
    GuideMotion.POSTURE -> GuideScene(motion, 5200, listOf(GuideBeat(0.0f, "① 正对屏幕，露出双眼"), GuideBeat(0.25f, "② 前后微调到提示的距离"), GuideBeat(0.6f, "③ 光线均匀，避免逆光")), 0.7f)
    GuideMotion.BLINK -> GuideScene(motion, 4200, listOf(GuideBeat(0.0f, "① 正对屏幕，保持放松"), GuideBeat(0.15f, "② 像平时一样自然眨眼"), GuideBeat(0.55f, "③ 应用自动记录眨眼")), 0.6f)
    GuideMotion.OPEN -> GuideScene(motion, 4800, listOf(GuideBeat(0.0f, "① 轻松睁开双眼"), GuideBeat(0.15f, "② 尽量保持睁眼，看着屏幕"), GuideBeat(0.86f, "③ 不舒服就眨眼，随时可停止")), 0.5f)
    GuideMotion.NEAR -> GuideScene(motion, 5600, listOf(GuideBeat(0.0f, "① 用手掌遮住左眼（不压眼球）"), GuideBeat(0.2f, "② 用右眼看 E 的开口方向"), GuideBeat(0.28f, "③ 点选对应方向；之后换眼再做")), 0.35f)
    GuideMotion.MOVE -> GuideScene(motion, 7600, listOf(GuideBeat(0.0f, "① 从 45 cm 外开始，看着 E"), GuideBeat(0.15f, "② 慢慢移近（每秒不超过 5 cm）"), GuideBeat(0.6f, "③ E 一开始持续模糊就点按，记录近点")), 0.66f)
    GuideMotion.SWITCH -> GuideScene(motion, 6400, listOf(GuideBeat(0.0f, "① 远处停稳，点选 E 的开口"), GuideBeat(0.15f, "② 移近停稳，再点选一次"), GuideBeat(0.55f, "③ 移远，远近交替进行")), 0.45f)
    GuideMotion.FIXATE -> GuideScene(motion, 4000, listOf(GuideBeat(0.0f, "① 看着屏幕上方闪烁的圆点"), GuideBeat(0.45f, "② 头和手机都保持不动")), 0.5f)
    GuideMotion.FAR -> GuideScene(motion, 5200, listOf(GuideBeat(0.0f, "① 手机留在眼前约 33 cm，略低于视线"), GuideBeat(0.3f, "② 眼睛越过手机，看 6 米外的目标"), GuideBeat(0.65f, "③ 看约 10 秒，头和手机都不动")), 0.5f)
    GuideMotion.COVER_SELF -> GuideScene(motion, 12000, listOf(GuideBeat(0.0f, "① 遮右眼 2 秒 → 移开，双眼注视 2 秒"), GuideBeat(0.3333333333333333f, "② 遮左眼 2 秒 → 移开，双眼注视 2 秒"), GuideBeat(0.6666666666666666f, "③ 右眼、左眼各遮 1 秒，快速交替两轮")), 0.12f)
    GuideMotion.COVER -> GuideScene(motion, 12000, listOf(GuideBeat(0.0f, "① 遮右眼 2 秒 → 移开，双眼注视 2 秒"), GuideBeat(0.3333333333333333f, "② 遮左眼 2 秒 → 移开，双眼注视 2 秒"), GuideBeat(0.6666666666666666f, "③ 右眼、左眼各遮 1 秒，快速交替两轮")), 0.12f)
    GuideMotion.FOLLOW -> GuideScene(motion, 7200, listOf(GuideBeat(0.0f, "① 水平：只用眼睛跟随圆点"), GuideBeat(0.36f, "② 垂直：头保持不动"), GuideBeat(0.72f, "③ 斜向：继续跟随")), 0.2f)
    GuideMotion.ACUITY -> GuideScene(motion, 6000, listOf(GuideBeat(0.0f, "① 看 E 的开口方向"), GuideBeat(0.1f, "② 2 秒内点选对应方向"), GuideBeat(0.4f, "③ 连对两次变小，答错或超时变大")), 0.12f)
    GuideMotion.FVA -> GuideScene(motion, 7000, listOf(GuideBeat(0.0f, "① 全程看 E，点选开口方向"), GuideBeat(0.12f, "② 前一段像平时一样自然眨眼"), GuideBeat(0.68f, "③ 不停顿，转入尽量保持睁眼")), 0.3f)
    GuideMotion.CURVE -> GuideScene(motion, 6600, listOf(GuideBeat(0.0f, "① 移到提示的距离并停稳"), GuideBeat(0.12f, "② 点选 E 的开口方向"), GuideBeat(0.36f, "③ 依次换到下一个距离")), 0.2f)
}

/** 2 秒遮右 → 2 秒双眼 → 2 秒遮左 → 2 秒双眼 → 每眼 1 秒交替两轮。 */
internal fun guidePoseAt(motion: GuideMotion, phase: Float): GuidePose {
    val p = phase.coerceIn(0f, 0.999999f)
    if (motion == GuideMotion.REST) return GuidePose.BLINK
    if (motion == GuideMotion.LIGHTING) return GuidePose.LIGHTING_OPEN
    if (motion == GuideMotion.COVER || motion == GuideMotion.COVER_SELF) {
        val second = (p * 12).toInt()
        val pose = when (second) {
            0, 1, 8, 10 -> GuidePose.LIGHTING_RIGHT
            4, 5, 9, 11 -> GuidePose.LIGHTING_LEFT
            else -> GuidePose.LIGHTING_OPEN
        }
        return pose
    }
    if (motion == GuideMotion.NEAR) return when {
        p < 0.06f || p in 0.62f..0.68f || p >= 0.94f -> GuidePose.OPEN
        p < 0.62f -> GuidePose.LEFT
        else -> GuidePose.RIGHT
    }
    if (motion == GuideMotion.MOVE) return if (p in 0.45f..0.8f) GuidePose.SIDE_NEAR else GuidePose.SIDE_FAR
    val blinkTimes = when (motion) {
        GuideMotion.BLINK -> listOf(0.2f, 0.5f, 0.8f)
        GuideMotion.FVA -> if (p < 0.68f) listOf(0.2f, 0.45f) else emptyList()
        GuideMotion.OPEN -> listOf(0.92f)
        else -> emptyList()
    }
    // 闭眼图片只短暂出现，保留自然眨眼节奏；减弱动态效果时停在睁眼关键帧。
    return if (blinkTimes.any { kotlin.math.abs(p - it) < 0.025f }) GuidePose.BLINK else GuidePose.OPEN
}

internal fun guidePoseLabel(pose: GuidePose) = when (pose) {
    GuidePose.LEFT -> "遮左眼"
    GuidePose.RIGHT -> "遮右眼"
    GuidePose.LIGHTING_LEFT -> "本人遮左眼 · 协助者打光"
    GuidePose.LIGHTING_RIGHT -> "本人遮右眼 · 协助者打光"
    GuidePose.LIGHTING_OPEN -> "双眼注视 · 协助者打光"
    GuidePose.SIDE_FAR -> "自己遮左眼 · 从远处开始"
    GuidePose.SIDE_NEAR -> "保持遮眼 · 慢慢移近手机"
    GuidePose.BLINK -> "自然眨眼"
    GuidePose.OPEN -> "双眼注视"
}

internal fun guideCycleMs(motion: GuideMotion): Int = guideScene(motion).cycleMs

internal fun guideMotion(step: ScreeningStep, fields: Map<String, String> = emptyMap()) = when (step) {
    ScreeningStep.NEAR_VISION -> GuideMotion.NEAR
    ScreeningStep.PUSH_UP -> GuideMotion.MOVE
    ScreeningStep.DISTANCE_CURVE -> GuideMotion.CURVE
    ScreeningStep.FLIPPER -> GuideMotion.SWITCH
    ScreeningStep.FIXATION, ScreeningStep.GAZE_CALIBRATION -> GuideMotion.FIXATE
    ScreeningStep.FAR_FIXATION -> GuideMotion.FAR
    ScreeningStep.COVER -> GuideMotion.COVER_SELF
    ScreeningStep.PURSUIT -> GuideMotion.FOLLOW
    else -> GuideMotion.POSTURE
}
