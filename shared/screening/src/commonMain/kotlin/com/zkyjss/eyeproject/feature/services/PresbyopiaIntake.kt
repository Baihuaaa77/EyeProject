package com.zkyjss.eyeproject.feature.services

/**
 * 老视测试的检查路径（专家 2026-11-02《老视-回复》第三节）。由测前问卷与本次实际戴镜状态决定，
 * 决定做哪些检查、怎样判定：
 * - A1 / A2 满足标准调节幅度条件：近视力 → 多距离视力 → 单眼移近法（调节幅度、初步 ADD）→ 可选远近切换；
 * - B 戴老花镜或多焦点眼镜：只评价现有眼镜下的近视觉表现；
 * - C 远用矫正不充分、不明确或不符合标准条件：只评价当前状态下的近视觉功能。
 * B、C 不做标准调节幅度，也不估算 ADD；不同戴镜状态的结果分别记录、分别解释。
 */
enum class PresbyopiaPath(val code: String, val title: String, val short: String, val scope: String) {
    A1("A1", "远用矫正条件下筛查", "戴远用镜", "标准远用矫正下的老视及调节功能筛查"),
    A2("A2", "裸眼条件下筛查", "裸眼", "无需远用矫正人群的老视及调节功能筛查"),
    B("B", "现有眼镜功能评价", "现有眼镜", "现有近用眼镜下的近视觉表现"),
    C("C", "限制性视觉功能筛查", "限制筛查", "当前戴镜状态下的近视觉功能");

    /** 满足标准调节幅度（AA）测量条件：最佳远用矫正（或无需远用矫正）、未加近用度数。 */
    val standardAmplitude: Boolean get() = this == A1 || this == A2

    /** 写入记录的模式文字（旧记录为「标准模式 / 日常模式」，评估仍按前缀兼容）。 */
    val modeLabel: String get() = when (this) {
        A1, A2 -> "标准模式（$code）"
        B -> "日常模式（B）"
        C -> "限制模式（C）"
    }
}

/**
 * 老视测前问卷（专家文档第一节）：屈光不正、日常戴镜方式、看远是否需要眼镜三题（同一副眼镜看远看近时追问镜片分区），
 * 再确认这次实际怎么戴镜。每次测试前都问，答案只写入本次记录；换一种戴镜方式重测时保留前几题、只重选戴镜状态。
 * 选项文字是判定依据，改动时同步 [route]。
 */
object PresbyopiaIntake {
    const val REFRACTION = "已知屈光不正"
    const val GLASSES_USE = "眼镜使用方式"
    const val LENS_ZONES = "眼镜镜片分区"
    const val DISTANCE_NEED = "看远是否需要眼镜"
    const val TEST_STATE = "本次戴镜状态"

    const val REFRACTION_YES = "有"
    const val REFRACTION_NO = "没有"
    const val REFRACTION_UNKNOWN = "不清楚"

    const val USE_NONE = "平时不戴眼镜"
    const val USE_DISTANCE = "主要戴眼镜看远，看近时摘掉"
    const val USE_READING = "主要戴老花镜看近，看远不戴"
    const val USE_SAME = "看远看近用同一副眼镜"
    const val USE_SEPARATE = "看远看近分别用不同的眼镜"
    const val USE_OTHER = "其他情况或不清楚"

    const val ZONES_NO = "不需要，正常透过镜片看远看近"
    const val ZONES_YES = "需要，看远看近用镜片的不同区域"
    const val ZONES_UNKNOWN = "不清楚"

    const val NEED_CLEAR_WITH = "需要，戴上眼镜后看远清楚"
    const val NEED_NONE = "不需要，不戴眼镜也能看清"
    const val NEED_UNCLEAR_WITH = "戴上眼镜后，看远仍然不清楚"
    const val NEED_UNSURE = "不确定"

    const val STATE_DISTANCE = "戴平时看远用的眼镜"
    const val STATE_NONE = "不戴眼镜"
    const val STATE_READING = "戴现在用的老花镜"
    const val STATE_MULTIFOCAL = "戴平时用的远近多焦点眼镜"
    const val STATE_OTHER = "其他情况"

    val questions: List<Question> = listOf(
        Question(REFRACTION, "您是否知道自己有近视、远视或散光？", listOf(REFRACTION_YES, REFRACTION_NO, REFRACTION_UNKNOWN), exclusive = emptySet()),
        Question(GLASSES_USE, "您平时主要怎么用眼镜？",
            listOf(USE_NONE, USE_DISTANCE, USE_READING, USE_SAME, USE_SEPARATE, USE_OTHER), exclusive = emptySet()),
        Question(LENS_ZONES, "这副眼镜是否需要通过镜片的不同区域看远和看近？", listOf(ZONES_NO, ZONES_YES, ZONES_UNKNOWN),
            exclusive = emptySet(), visibleWhen = { it[GLASSES_USE] == USE_SAME }),
        Question(DISTANCE_NEED, "您平时看远时，是否需要戴眼镜（或隐形眼镜）才能看清？",
            listOf(NEED_CLEAR_WITH, NEED_NONE, NEED_UNCLEAR_WITH, NEED_UNSURE), exclusive = emptySet()),
        Question(TEST_STATE, "这次测试准备怎么戴镜？",
            listOf(STATE_DISTANCE, STATE_NONE, STATE_READING, STATE_MULTIFOCAL, STATE_OTHER), exclusive = emptySet(),
            hints = mapOf(STATE_MULTIFOCAL to "渐进镜等，一副镜片看远看近都清楚")),
    )

    /** 问卷键：换一种戴镜方式重测时保留的是除 [TEST_STATE] 以外的这些。 */
    val keys: Set<String> = questions.map { it.key }.toSet()

    /**
     * 按文档 3.1 对应表与补充规则分流；没回答（未选成员直接测试）时按 C。
     * - 本次戴老花镜 / 多焦点 → B；戴镜后看远仍不清楚时不进入标准 AA；
     * - 戴看远的眼镜且自述戴上清楚 → A1；同一副眼镜要用不同区域看远看近 → B，说不清 → C；
     * - 不戴眼镜：只有无已知屈光不正且自述裸眼看远清楚才是 A2；自述有屈光不正（未经专业确认）或「不清楚」→ C。
     */
    fun route(fields: Map<String, String>): PresbyopiaPath {
        val state = fields[TEST_STATE] ?: return PresbyopiaPath.C
        val refraction = fields[REFRACTION]
        val use = fields[GLASSES_USE]
        val need = fields[DISTANCE_NEED]
        return when (state) {
            STATE_READING, STATE_MULTIFOCAL -> PresbyopiaPath.B
            STATE_DISTANCE -> when {
                use == USE_SAME && fields[LENS_ZONES] == ZONES_YES -> PresbyopiaPath.B
                use == USE_SAME && fields[LENS_ZONES] != ZONES_NO -> PresbyopiaPath.C
                use == USE_OTHER -> PresbyopiaPath.C
                need == NEED_CLEAR_WITH -> PresbyopiaPath.A1
                else -> PresbyopiaPath.C
            }
            STATE_NONE -> if (refraction == REFRACTION_NO && need == NEED_NONE) PresbyopiaPath.A2 else PresbyopiaPath.C
            else -> PresbyopiaPath.C
        }
    }

    /** B 路径下摘掉近用眼镜就符合 A2 条件的人：结果页建议摘镜再测一次标准调节检查。 */
    fun a2WithoutGlasses(fields: Map<String, String>): Boolean =
        fields[REFRACTION] == REFRACTION_NO && fields[DISTANCE_NEED] == NEED_NONE

    /** C 路径的原因（结果页一句话）。 */
    fun limitedReason(fields: Map<String, String>): String {
        val state = fields[TEST_STATE]
        return when {
            state == null -> "没有回答测试前的戴镜问题，无法确认矫正条件"
            fields[DISTANCE_NEED] == NEED_UNCLEAR_WITH -> "戴上眼镜看远仍不清楚，远用矫正可能不充分"
            state == STATE_NONE && fields[REFRACTION] == REFRACTION_YES -> "有已知的近视、远视或散光，不戴眼镜测试时无法确认是否需要矫正"
            state == STATE_NONE && fields[REFRACTION] == REFRACTION_UNKNOWN -> "不清楚自己有没有屈光不正，不能确认为标准测试条件"
            state == STATE_NONE -> "平时看远需要眼镜或不确定，不戴眼镜测试时远用矫正条件不明确"
            state == STATE_OTHER -> "本次戴镜方式不属于标准测试条件"
            fields[GLASSES_USE] == USE_SAME -> "说不清这副眼镜是否含看近的度数"
            else -> "远用矫正条件不明确"
        }
    }

    /** 开始前的戴镜确认页：标题、要说的话、按钮。整组测试要保持同一戴镜状态（文档 1.2）。 */
    data class GlassesCheck(val title: String, val prompt: String, val button: String)

    fun glassesCheck(fields: Map<String, String>): GlassesCheck = when (fields[TEST_STATE]) {
        STATE_DISTANCE -> GlassesCheck("戴好看远的眼镜", "这次戴平时看远用的眼镜测试。请现在戴好，整组测试中不要换眼镜。", "已戴好，继续")
        STATE_NONE -> GlassesCheck("摘下眼镜", "这次不戴眼镜测试。请现在摘下眼镜（含隐形眼镜），整组测试中不要再戴上。", "已摘下，继续")
        STATE_READING -> GlassesCheck("戴好老花镜", "这次戴现在用的老花镜测试。请现在戴好，整组测试中不要换眼镜。", "已戴好，继续")
        STATE_MULTIFOCAL -> GlassesCheck("戴好多焦点眼镜", "这次戴平时用的远近多焦点眼镜测试。请现在戴好，整组测试中不要换眼镜。", "已戴好，继续")
        else -> GlassesCheck("保持现在的戴镜方式", "整组测试请保持现在的戴镜方式，中途不要换眼镜。", "好的，继续")
    }
}
