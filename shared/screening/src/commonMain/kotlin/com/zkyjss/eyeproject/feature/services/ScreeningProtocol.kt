package com.zkyjss.eyeproject.feature.services

import kotlin.math.sin

/*
 * 老视 / 斜视测试协议参数（对齐《智能眼科-整合9.18》研究内容二、三）。
 * 原文参数直接引用；原文未定或自相矛盾之处在此给出本原型的约定，并标注「候选」。
 */

object PresbyopiaProtocol {
    /** 原文：主要面向 35 岁以上人群。 */
    const val TARGET_MIN_AGE = 35

    /** 近视力固定约 40 cm。 */
    const val NEAR_DISTANCE_CM = 40f
    /** 距离提示容差：横幅变色、语音提醒与视标呈现都按它判断。 */
    const val DISTANCE_TOLERANCE_CM = 4f
    /**
     * 作答容差：在提示容差外再留 2 cm。人往往一进入「距离合适」就停下，停在容差边缘，
     * 测距的正常抖动会把作答判成越界；视标角大小按每题实测距离换算，多出的 2 cm 不影响视力读数。
     */
    const val ANSWER_TOLERANCE_CM = DISTANCE_TOLERANCE_CM + 2f

    /** 移近法：起点须在 45 cm 以外，由远向近缓慢连续移动。 */
    const val PUSH_START_MIN_CM = 45f
    /**
     * 移近速度（专家 2026-11-02）：推荐 1–2 cm/秒；超过 [PUSH_SLOW_DOWN_CM_S] 提示放慢（多留 1 cm/秒给测距抖动），
     * 确认模糊前 1 秒内的平均速度仍超过 [PUSH_MAX_SPEED_CM_S]（持续过快，易高估调节幅度）则本次无效、重新测量。
     */
    const val PUSH_SLOW_DOWN_CM_S = 3f
    const val PUSH_MAX_SPEED_CM_S = 5f
    /** 低于该距离仍未模糊记为「近点 < 12 cm」（虹膜测距在更近处不可靠）。 */
    const val PUSH_MIN_CM = 12f
    /** 每只眼的有效移近次数（专家：每眼 3 次有效测量，取三次调节幅度的算术平均）。 */
    const val PUSH_REPEATS = 3
    /** 单眼移近法依次测右眼、左眼。 */
    val PUSH_EYES = listOf(VisionEye.RIGHT, VisionEye.LEFT)
    /** 移近视标：取该眼 40 cm 近视力阈值，但不小于 0.5 档的物理尺寸（候选）；移动中物理尺寸不变。 */
    const val PUSH_MAX_LEVEL = 0.5f

    /** 距离—视觉表现曲线的附加测试距离（专家流程 F2：67 / 50 / 33 cm；40 cm 已由近视力任务覆盖）。 */
    val CURVE_DISTANCES = listOf(67f, 50f, 33f)

    /**
     * 远近动态焦点转换（专家：探索性评估，不等同于临床调节灵敏度，暂不设正常与异常阈值）：
     * 50 cm 与 25 cm 交替，60 秒内记正确完整循环（50 → 25 → 50 两处都答对）、错误次数与未完成原因。
     * 开始计时前先在两个距离各确认能看清视标（各 [FLIPPER_CHECK_TRIES] 次机会）；近端看不清记为未达到测试条件。
     */
    const val FLIPPER_FAR_CM = 50f
    const val FLIPPER_NEAR_CM = 25f
    const val FLIPPER_SECONDS = 60
    const val FLIPPER_LEVEL = 0.2f
    const val FLIPPER_CHECK_TRIES = 2

    /** 第 i 个循环为第 2i、2i+1、2i+2 题（50 → 25 → 50），三题都答对才算一个正确完整循环。 */
    fun flipperCycles(answers: List<VisionAnswer>): Int =
        (0 until (answers.size - 1) / 2).count { i -> (2 * i..2 * i + 2).all { answers[it].correct } }
    const val CURVE_TRIALS_PER_LEVEL = 3
    const val CURVE_PASS = 2

    /** 40 cm 阅读的调节需求（D）与舒适阅读所需的一半储备。 */
    const val READING_DEMAND_D = 2.5
    const val COMFORT_RESERVE_D = 5.0

    /**
     * 初步 ADD（D）：40 cm 阅读需求减去一半调节幅度（保留一半储备），按 0.25 D 取整，不小于 0。
     * 只用符合标准测试条件（A1 / A2）的有效调节幅度计算；仅供参考，配镜以验光为准。
     */
    fun tentativeAdd(amplitudeD: Double): Double =
        (kotlin.math.round((READING_DEMAND_D - amplitudeD / 2).coerceAtLeast(0.0) / 0.25) * 0.25)

    /** Hofstetter 最小调节幅度：15 − 0.25 × 年龄（原文引用）。 */
    fun minimumAmplitude(age: Double) = 15.0 - 0.25 * age
    /** Hofstetter 平均调节幅度：18.5 − 0.3 × 年龄（用于「调节年龄」参考反推）。 */
    fun averageAmplitude(age: Double) = 18.5 - 0.3 * age
    fun amplitudeAge(amplitudeD: Double) = ((18.5 - amplitudeD) / 0.3).coerceIn(10.0, 80.0)

    /**
     * 虹膜测距的相对误差（候选）：虹膜直径个体差异约 ±4%，加上关键点抖动，未做实物标定时取 6%。
     * A = 1/d 对距离误差的敏感度为 δA ≈ δd/d²，近点越近误差越大。
     */
    const val DISTANCE_REL_ERROR = 0.06
    /** 调节幅度区间上限（近点 < 12 cm 时上界不可测）。 */
    const val MAX_AMPLITUDE_D = 100.0 / PUSH_MIN_CM

    /** 由各次近点（cm）给出调节幅度的约 95% 范围：取最远/最近一次并叠加测距误差；有删失（< 12 cm）时上界不可测。 */
    fun amplitudeRange(nearPointsCm: List<Double>, censored: Boolean): Pair<Double, Double> {
        val far = (nearPointsCm.maxOrNull() ?: PUSH_MIN_CM.toDouble()) * (1 + DISTANCE_REL_ERROR)
        val near = (nearPointsCm.minOrNull() ?: PUSH_MIN_CM.toDouble()) * (1 - DISTANCE_REL_ERROR)
        val high = if (censored || nearPointsCm.isEmpty()) MAX_AMPLITUDE_D else minOf(100.0 / near, MAX_AMPLITUDE_D)
        return 100.0 / far to high
    }
}

object StrabismusProtocol {
    const val DISTANCE_CM = 33f
    /** 原文：33 cm，超出 ±5 cm 时提醒。 */
    const val DISTANCE_TOLERANCE_CM = 5f
    /**
     * 采样容差：提醒仍在 ±5 cm，采样放宽到 ±7 cm。人一进入「距离合适」就停下，常停在 ±5 cm 边缘，
     * 测距抖动会让大段画面作废、校准采不到点；未经实物校准的前置测距本身也有约 6% 误差。
     */
    const val DISTANCE_GATE_CM = DISTANCE_TOLERANCE_CM + 2f
    const val FIXATION_SECONDS = 12
    const val PURSUIT_SECONDS = 22

    /**
     * 看远眼位（专家 2026-10-29 建议）：缩小视标模拟看远时眼睛仍在看 33 cm 的手机，调节和集合没有放松，
     * 看远才出现的间歇性外斜测不出来。改为手机留在眼前约 33 cm、略低于视线，受试者越过手机注视 6 米外的目标 5–10 秒，
     * 前置相机同时记录双眼映光。前 [FAR_SETTLE_MS] 是视线转向远处、调节放松的时间，不计入。
     */
    const val FAR_FIXATION_SECONDS = 10
    const val FAR_SETTLE_MS = 1_500L
    const val FAR_TARGET_M = 6
    /** 手机放低到视线下方，相机看到的头部俯仰会比正对时大：看远时放宽到 20°（其余任务 10°）。 */
    const val FAR_PITCH_LIMIT_DEG = 20f

    /** 平均角膜横径（mm），用于把图像像素换算为毫米（以每帧虹膜直径作尺度）。 */
    const val CORNEA_DIAMETER_MM = 11.7
    /** Hirschberg 经验值：1 mm 映光偏移 ≈ 15Δ（原文；候选参数，未针对本机标定）。 */
    const val PRISM_PER_MM = 15.0
    /** 虹膜直径单位 → 棱镜度。 */
    const val PRISM_PER_IRIS_UNIT = CORNEA_DIAMETER_MM * PRISM_PER_MM
    /** 1Δ ≈ 0.573°。 */
    const val DEG_PER_PRISM = 0.5729

    /** 原文判定：水平 ≥ 10Δ 或垂直 ≥ 5Δ 且注视稳定 → 高度疑似。 */
    const val HIGH_H_PRISM = 10.0
    const val HIGH_V_PRISM = 5.0
    /** 候选：注视稳定的标准差上限（原文未给出具体数值）。 */
    const val STABLE_SD_H_PRISM = 5.0
    const val STABLE_SD_V_PRISM = 2.5
    /** 原文：遮盖解除后再注视偏移 ≥ 6Δ → 可疑阳性。 */
    const val REFIX_PRISM = 6.0
    const val REFIX_WINDOW_MS = 500L
    /** 相机检测的解除遮盖首帧为起点；多帧确认延迟不平移事件时间。 */
    const val REFIX_SEARCH_MS = REFIX_WINDOW_MS
    /** 原文 BSI 初版界值。 */
    const val BSI_SUSPECT = 0.7
    const val BSI_ABNORMAL = 0.5
    /** 候选：追踪幅度（水平与垂直的较小者）低于此值时双眼速度相关被噪声主导，不评估 BSI。 */
    const val BSI_MIN_AMPLITUDE_DEG = 5f

    /** 原文：注视点闪烁 1–2 Hz；视标直径 3–5 岁约 2.0 cm（≈3.5°），6–12 岁约 1.5 cm（≈2.6°）。 */
    const val FLASH_HZ = 1.5f
    fun targetDiameterMm(age: Int?) = if ((age ?: 6) <= 5) 20f else 15f

    /** 追踪：幅度 ±10°，水平/垂直正弦周期 4 秒各 2 次，45° 斜向直线往复周期 3 秒 2 次。 */
    const val PURSUIT_AMPLITUDE_DEG = 10f

    /** 返回 (x°, y°, 段号 0 水平 / 1 垂直 / 2 斜向)。 */
    fun pursuitTarget(seconds: Float): Triple<Float, Float, Int> = when {
        seconds < 8f -> Triple(PURSUIT_AMPLITUDE_DEG * sin(2.0 * kotlin.math.PI * seconds / 4.0).toFloat(), 0f, 0)
        seconds < 16f -> Triple(0f, PURSUIT_AMPLITUDE_DEG * sin(2.0 * kotlin.math.PI * (seconds - 8f) / 4.0).toFloat(), 1)
        else -> {
            val t = ((seconds - 16f) / 3f) % 1f
            val wave = if (t < 0.25f) t * 4f else if (t < 0.75f) 2f - t * 4f else t * 4f - 4f
            // 沿 45° 方向 ±10°：两个分量各 ±10/√2
            val c = PURSUIT_AMPLITUDE_DEG * wave * 0.7071f
            Triple(c, -c, 2)
        }
    }

    /** 遮盖序列（原文）：遮右 2 秒 → 去遮盖 2 秒；遮左 2 秒 → 去遮盖 2 秒；右 1 秒、左 1 秒交替两轮。 */
    val COVER_SEQUENCE = listOf(
        CoverAction("遮右眼", covered = VisionEye.RIGHT, holdMs = 2_000L, uncovered = null),
        CoverAction("移开遮盖，双眼注视", covered = null, holdMs = 2_000L, uncovered = VisionEye.RIGHT),
        CoverAction("遮左眼", covered = VisionEye.LEFT, holdMs = 2_000L, uncovered = null),
        CoverAction("移开遮盖，双眼注视", covered = null, holdMs = 2_000L, uncovered = VisionEye.LEFT),
        CoverAction("遮右眼", covered = VisionEye.RIGHT, holdMs = 1_000L, uncovered = null),
        CoverAction("立即换遮左眼", covered = VisionEye.LEFT, holdMs = 1_000L, uncovered = VisionEye.RIGHT),
        CoverAction("立即换遮右眼", covered = VisionEye.RIGHT, holdMs = 1_000L, uncovered = VisionEye.LEFT),
        CoverAction("立即换遮左眼", covered = VisionEye.LEFT, holdMs = 1_000L, uncovered = VisionEye.RIGHT),
        CoverAction("完全移开遮盖", covered = null, holdMs = 0L, uncovered = VisionEye.LEFT),
    )
}

/** 一个遮盖动作：执行时遮住 [covered]，此刻被解除遮盖的是 [uncovered]，需保持 [holdMs]。 */
data class CoverAction(val label: String, val covered: VisionEye?, val holdMs: Long, val uncovered: VisionEye?)

