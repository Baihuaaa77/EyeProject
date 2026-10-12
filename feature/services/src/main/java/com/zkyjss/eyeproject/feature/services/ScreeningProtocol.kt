package com.zkyjss.eyeproject.feature.services

import kotlin.math.sin

/*
 * 老视 / 斜视测试协议参数（对齐《智能眼科-整合9.18》研究内容二、三）。
 * 原文参数直接引用；原文未定或自相矛盾之处在此给出本原型的约定，并标注「候选」。
 */

internal object PresbyopiaProtocol {
    /** 原文：主要面向 35 岁以上人群。 */
    const val TARGET_MIN_AGE = 35

    /** 近视力固定约 40 cm。 */
    const val NEAR_DISTANCE_CM = 40f

    /** 移近法：起点须在 45 cm 以外，由远向近缓慢连续移动。 */
    const val PUSH_START_MIN_CM = 45f
    /** 候选：移动速度上限（cm/秒），超过则本次不计、请放慢重做。 */
    const val PUSH_MAX_SPEED_CM_S = 5f
    /** 低于该距离仍未模糊记为「近点 < 12 cm」（虹膜测距在更近处不可靠）。 */
    const val PUSH_MIN_CM = 12f
    const val PUSH_REPEATS = 3
    /** 移近视标：取双眼 40 cm 近视力阈值，但不小于 0.5 档的物理尺寸（候选）。 */
    const val PUSH_MAX_LEVEL = 0.5f

    /** 距离—视觉表现曲线的附加测试距离（40 cm 已由近视力任务覆盖）。 */
    val CURVE_DISTANCES = listOf(60f, 33f, 25f)
    const val CURVE_TRIALS_PER_LEVEL = 3
    const val CURVE_PASS = 2

    /** 40 cm 阅读的调节需求（D）与舒适阅读所需的一半储备。 */
    const val READING_DEMAND_D = 2.5
    const val COMFORT_RESERVE_D = 5.0

    /** Hofstetter 最小调节幅度：15 − 0.25 × 年龄（原文引用）。 */
    fun minimumAmplitude(age: Double) = 15.0 - 0.25 * age
    /** Hofstetter 平均调节幅度：18.5 − 0.3 × 年龄（用于「调节年龄」参考反推）。 */
    fun averageAmplitude(age: Double) = 18.5 - 0.3 * age
    fun amplitudeAge(amplitudeD: Double) = ((18.5 - amplitudeD) / 0.3).coerceIn(10.0, 80.0)
}

internal object StrabismusProtocol {
    const val DISTANCE_CM = 33f
    const val DISTANCE_TOLERANCE_CM = 5f
    const val FIXATION_SECONDS = 12
    const val PURSUIT_SECONDS = 22

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
    /** 原文 BSI 初版界值。 */
    const val BSI_SUSPECT = 0.7
    const val BSI_ABNORMAL = 0.5

    /** 原文：注视点闪烁 1–2 Hz；视标直径 3–5 岁约 2.0 cm（≈3.5°），6–12 岁约 1.5 cm（≈2.6°）。 */
    const val FLASH_HZ = 1.5f
    fun targetDiameterMm(age: Int?) = if ((age ?: 6) <= 5) 20f else 15f

    /** 追踪：幅度 ±10°，水平/垂直正弦周期 4 秒各 2 次，45° 斜向直线往复周期 3 秒 2 次。 */
    const val PURSUIT_AMPLITUDE_DEG = 10f

    /** 返回 (x°, y°, 段号 0 水平 / 1 垂直 / 2 斜向)。 */
    fun pursuitTarget(seconds: Float): Triple<Float, Float, Int> = when {
        seconds < 8f -> Triple(PURSUIT_AMPLITUDE_DEG * sin(2.0 * Math.PI * seconds / 4.0).toFloat(), 0f, 0)
        seconds < 16f -> Triple(0f, PURSUIT_AMPLITUDE_DEG * sin(2.0 * Math.PI * (seconds - 8f) / 4.0).toFloat(), 1)
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
internal data class CoverAction(val label: String, val covered: VisionEye?, val holdMs: Long, val uncovered: VisionEye?)

/** 老视测试的两种戴镜方式（存储值以「标准」「日常」开头，评估按前缀判断）。 */
internal const val PRESBYOPIA_MODE_STANDARD = "标准模式"
internal const val PRESBYOPIA_MODE_DAILY = "日常模式"

/** 平时是否戴看近用的眼镜（老花镜 / 渐进多焦点）：即原「近用 Add」，由日常戴镜情况推断，不再单独询问。 */
internal fun nearCorrectionInUse(fields: Map<String, String>): Boolean =
    fields.hasAny("日常矫正方式", "老花镜", "渐进多焦点眼镜")

internal val presbyopiaQuestions = listOf(
    ageQuestion(18..100),
    Question("性别", "你的性别", listOf("男", "女", "不愿透露")),
    Question("日常矫正方式", "平时戴什么眼镜？",
        listOf("不戴眼镜", "近视或远视眼镜", "老花镜", "渐进多焦点眼镜", "隐形眼镜"),
        multiple = true, hints = mapOf("渐进多焦点眼镜" to "一副镜片看远看近都清楚")),
    Question("屈光情况", "知道自己有以下情况吗？", listOf("近视", "远视", "散光", "老花", "都没有或不清楚"), multiple = true),
    Question("每日近距离用眼", "每天看手机、电脑或书本大约多久？", listOf("不到 2 小时", "2–4 小时", "4–8 小时", "8 小时以上")),
    Question("职业", "平时主要做什么工作？", listOf("办公室工作", "户外工作", "精细手工", "老师或学生", "已退休", "其他")),
    Question("既往眼病", "得过以下眼病吗？", listOf("干眼", "白内障", "青光眼", "眼底病", "其他眼病", "没有"),
        multiple = true, hints = mapOf("眼底病" to "如黄斑病变、视网膜病变")),
    Question("眼部手术", "做过眼部手术吗？",
        listOf("近视激光手术", "白内障手术", "ICL 晶体植入", "其他眼部手术", "没有", "不清楚"),
        multiple = true, hints = mapOf("近视激光手术" to "全飞秒、半飞秒、表层手术等", "ICL 晶体植入" to "在眼内放一片矫正近视的镜片")),
    Question("激光手术类型", "做的是哪种近视激光手术？", listOf("全飞秒（SMILE）", "半飞秒（FS-LASIK）", "表层手术（PRK）", "不清楚"),
        visibleWhen = { f -> f.hasAny("眼部手术", "近视激光手术") }),
    Question("人工晶体类型", "白内障手术换的是哪种人工晶体？", listOf("普通单焦点", "多焦点或景深延长（EDOF）", "不清楚"),
        visibleWhen = { f -> f.hasAny("眼部手术", "白内障手术") }),
    Question("手术年份", "手术大约是哪一年做的？", numeric = true, optional = true,
        visibleWhen = { f -> f.hasAny("眼部手术", "近视激光手术", "白内障手术", "ICL 晶体植入", "其他眼部手术") },
        validate = { it.toIntOrNull()?.let { y -> y in 1980..2100 } == true }),
)

/** 斜视测试多为家长替孩子填写：问法以「孩子」为主语。 */
internal val strabismusQuestions = listOf(
    ageQuestion(3..100, title = "孩子的年龄"),
    Question("性别", "孩子的性别", listOf("男", "女", "不愿透露")),
    Question("基础视力", "孩子平时视力怎么样？", listOf("正常", "偏差", "没测过", "不清楚")),
    Question("屈光状态", "孩子有以下情况吗？", listOf("近视", "远视", "散光", "已经配了眼镜", "都没有或不清楚"), multiple = true),
    Question("既往眼病或手术", "孩子以前有过这些情况吗？", listOf("斜视", "弱视", "做过眼部手术", "其他眼病", "都没有"), multiple = true),
    Question("协助测试者", "谁来帮忙完成测试？", listOf("家长", "医生或护士", "其他")),
)
