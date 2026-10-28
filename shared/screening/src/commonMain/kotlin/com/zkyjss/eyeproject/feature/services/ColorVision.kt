package com.zkyjss.eyeproject.feature.services

import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * 色觉自测（红绿色觉异常初筛）：假同色数字图，两端同一套图、同一套判定。
 *
 * 图的原理沿用石原氏假同色图：数字和背景由大小不一的色点组成，色点亮度随机起伏，只能靠颜色分辨数字。
 * 本应用的图由程序生成（不复制任何出版物），按视锥细胞「单独刺激」设计，判定有生理依据：
 * - 红色觉（第一型）检测图：数字与背景只差 L 视锥（长波）的兴奋量，缺 L 视锥或其功能异常者看不出或看错；
 * - 绿色觉（第二型）检测图：数字与背景只差 M 视锥（中波）的兴奋量，缺 M 视锥或其功能异常者看不出或看错；
 * - 每类 4 张，对比度由高到低，较低对比度用来发现较轻的色弱；另有 1 张人人都应读出的示例图，读错则本次无效。
 * 颜色按标准 sRGB 屏幕换算（线性 sRGB → CIE XYZ → Hunt-Pointer-Estevez LMS），手机屏幕未经校准，结果只作初筛。
 *
 * 判定参照石原氏图「错读不超过少数几张为正常」的思路：8 张检测图错 0–1 张为未见异常，2–3 张为可疑，
 * ≥4 张提示红绿色觉异常；两类图的错读数相差 ≥2 时提示倾向的类型。示例图读错时不下结论。
 */

enum class ColorPlateType { DEMO, PROTAN, DEUTAN }

/** 一个色点：中心与半径为图内坐标（图半径 1，y 向下），颜色为 0xAARRGGBB。 */
data class ColorDot(val x: Float, val y: Float, val r: Float, val argb: Int)

data class ColorPlate(
    val id: String,
    val number: String,
    val type: ColorPlateType,
    /** 数字与背景在被检测视锥上的对比度（0.3 = 30%）。 */
    val contrast: Float,
    /** 作答选项：几个数字 + 最后一个「看不出」。 */
    val options: List<String>,
    val dots: List<ColorDot>,
)

data class ColorPlateAnswer(val plateId: String, val number: String, val type: ColorPlateType, val contrast: Float,
                            val answer: String, val rtMs: Long?) {
    val correct: Boolean get() = answer == number
}

object ColorVisionProtocol {
    const val VERSION = "color-1"
    const val NOT_SEEN = "看不出"
    /** 读图时手机与眼睛的大致距离（石原氏图约 75 cm 看书本大小的图；手机图小，约 40 cm 视角相近）。 */
    const val VIEW_DISTANCE_CM = 40f
    /** 正常：8 张检测图错读不超过这个数。 */
    const val NORMAL_MAX_ERRORS = 1
    /** 可疑上限：错 2–3 张为可疑，≥4 张提示异常。 */
    const val SUSPECT_MAX_ERRORS = 3
    /** 两类图错读数相差至少这么多才提示类型。 */
    const val TYPE_MARGIN = 2

    /** 示例图（人人可读）+ 8 张检测图，固定顺序、红绿两类交替出现。 */
    val plates: List<ColorPlate> by lazy {
        listOf(
            spec("demo", "12", ColorPlateType.DEMO, 0f, listOf("12", "17", "72")),
            spec("p1", "8", ColorPlateType.PROTAN, 0.30f, listOf("8", "3", "6")),
            spec("d1", "3", ColorPlateType.DEUTAN, 0.30f, listOf("3", "8", "5")),
            spec("p2", "29", ColorPlateType.PROTAN, 0.22f, listOf("29", "70", "26")),
            spec("d2", "15", ColorPlateType.DEUTAN, 0.22f, listOf("15", "17", "13")),
            spec("p3", "5", ColorPlateType.PROTAN, 0.16f, listOf("5", "2", "6")),
            spec("d3", "6", ColorPlateType.DEUTAN, 0.16f, listOf("6", "5", "8")),
            spec("p4", "74", ColorPlateType.PROTAN, 0.11f, listOf("74", "21", "71")),
            spec("d4", "45", ColorPlateType.DEUTAN, 0.11f, listOf("45", "46", "15")),
        )
    }

    val demo: ColorPlate get() = plates.first()
    val screening: List<ColorPlate> get() = plates.drop(1)

    private fun spec(id: String, number: String, type: ColorPlateType, contrast: Float, choices: List<String>): ColorPlate {
        val seed = id.fold(17) { h, c -> h * 31 + c.code }
        val order = choices.shuffled(Random(seed))
        return ColorPlate(id, number, type, contrast, order + NOT_SEEN, ColorPlateArt.dots(number, type, contrast, seed))
    }
}

/** 由数字、类型与对比度生成色点（固定随机种子，两端完全一致）。 */
internal object ColorPlateArt {
    private const val SPACING = 0.056f

    fun dots(number: String, type: ColorPlateType, contrast: Float, seed: Int): List<ColorDot> {
        val random = Random(seed)
        val strokes = DigitFont.layout(number)
        val out = ArrayList<ColorDot>(1500)
        val rowHeight = SPACING * 0.866f
        var row = 0
        var y = -1f
        while (y <= 1f) {
            var x = -1f + if (row % 2 == 1) SPACING / 2 else 0f
            while (x <= 1f) {
                val r = SPACING * (0.30f + 0.20f * random.nextFloat())
                val room = SPACING / 2 - r
                val cx = x + (random.nextFloat() * 2 - 1) * room
                val cy = y + (random.nextFloat() * 2 - 1) * room
                if (sqrt(cx * cx + cy * cy) + r <= 0.985f) {
                    val inFigure = DigitFont.inside(strokes, cx, cy)
                    out += ColorDot(cx, cy, r, ColorMath.dotColor(type, inFigure, contrast, random))
                }
                x += SPACING
            }
            y += rowHeight; row++
        }
        return out
    }
}

/** 简笔数字：每个数字是 0..1 方框里的折线，粗笔画；判断点是否落在笔画里。 */
internal object DigitFont {
    private const val HEIGHT = 1.0f
    private const val WIDTH = 0.6f
    private const val GAP = 0.14f
    private const val STROKE = 0.2f

    private val glyphs: Map<Char, List<List<Pair<Float, Float>>>> = mapOf(
        '0' to listOf(listOf(0.5f to 0.04f, 0.85f to 0.2f, 0.92f to 0.5f, 0.85f to 0.8f, 0.5f to 0.96f, 0.15f to 0.8f, 0.08f to 0.5f, 0.15f to 0.2f, 0.5f to 0.04f)),
        '1' to listOf(listOf(0.25f to 0.22f, 0.55f to 0.04f, 0.55f to 0.96f)),
        '2' to listOf(listOf(0.12f to 0.26f, 0.3f to 0.07f, 0.6f to 0.04f, 0.86f to 0.2f, 0.86f to 0.42f, 0.12f to 0.96f, 0.92f to 0.96f)),
        '3' to listOf(listOf(0.12f to 0.12f, 0.5f to 0.04f, 0.82f to 0.16f, 0.82f to 0.36f, 0.48f to 0.49f, 0.84f to 0.62f, 0.86f to 0.84f, 0.5f to 0.96f, 0.12f to 0.88f)),
        '4' to listOf(listOf(0.72f to 0.96f, 0.72f to 0.04f, 0.08f to 0.7f, 0.94f to 0.7f)),
        '5' to listOf(listOf(0.86f to 0.04f, 0.2f to 0.04f, 0.14f to 0.46f, 0.5f to 0.38f, 0.84f to 0.52f, 0.86f to 0.8f, 0.5f to 0.96f, 0.12f to 0.88f)),
        '6' to listOf(listOf(0.82f to 0.1f, 0.5f to 0.04f, 0.18f to 0.26f, 0.1f to 0.62f, 0.2f to 0.88f, 0.5f to 0.96f, 0.84f to 0.82f, 0.86f to 0.6f, 0.5f to 0.46f, 0.14f to 0.62f)),
        '7' to listOf(listOf(0.08f to 0.04f, 0.92f to 0.04f, 0.4f to 0.96f)),
        '8' to listOf(
            listOf(0.5f to 0.04f, 0.8f to 0.15f, 0.8f to 0.35f, 0.5f to 0.47f, 0.2f to 0.35f, 0.2f to 0.15f, 0.5f to 0.04f),
            listOf(0.5f to 0.47f, 0.86f to 0.62f, 0.86f to 0.85f, 0.5f to 0.96f, 0.14f to 0.85f, 0.14f to 0.62f, 0.5f to 0.47f),
        ),
        '9' to listOf(listOf(0.84f to 0.4f, 0.5f to 0.54f, 0.16f to 0.4f, 0.18f to 0.14f, 0.5f to 0.04f, 0.82f to 0.16f, 0.88f to 0.46f, 0.76f to 0.8f, 0.45f to 0.96f, 0.18f to 0.9f)),
    )

    /** 数字在图中的笔画线段（图坐标），整体居中。 */
    fun layout(number: String): List<FloatArray> {
        val total = number.length * WIDTH + (number.length - 1) * GAP
        val left0 = -total / 2
        return number.flatMapIndexed { i, c ->
            val left = left0 + i * (WIDTH + GAP)
            glyphs[c].orEmpty().flatMap { line ->
                line.zipWithNext { a, b ->
                    floatArrayOf(left + a.first * WIDTH, -HEIGHT / 2 + a.second * HEIGHT, left + b.first * WIDTH, -HEIGHT / 2 + b.second * HEIGHT)
                }
            }
        }
    }

    fun inside(segments: List<FloatArray>, x: Float, y: Float): Boolean =
        segments.any { s -> distance(x, y, s[0], s[1], s[2], s[3]) <= STROKE / 2 }

    private fun distance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len = dx * dx + dy * dy
        val t = if (len == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len).coerceIn(0f, 1f)
        val qx = ax + t * dx - px
        val qy = ay + t * dy - py
        return sqrt(qx * qx + qy * qy)
    }
}

/** sRGB ↔ LMS 换算与色点取色。 */
internal object ColorMath {
    /** 线性 sRGB → XYZ（D65）→ Hunt-Pointer-Estevez LMS。 */
    private val toLms: Array<DoubleArray> = multiply(
        arrayOf(doubleArrayOf(0.4002, 0.7076, -0.0808), doubleArrayOf(-0.2263, 1.1653, 0.0457), doubleArrayOf(0.0, 0.0, 0.9182)),
        arrayOf(doubleArrayOf(0.4124, 0.3576, 0.1805), doubleArrayOf(0.2126, 0.7152, 0.0722), doubleArrayOf(0.0193, 0.1192, 0.9505)),
    )
    private val fromLms: Array<DoubleArray> = invert(toLms)

    /** 红色觉检测图的背景：几种黄绿（数字在其上只多一些 L 视锥兴奋）。 */
    private val greens = listOf(intArrayOf(122, 160, 86), intArrayOf(140, 166, 80), intArrayOf(108, 150, 96), intArrayOf(150, 170, 100))
    /** 绿色觉检测图的背景：几种橙黄（数字在其上只多一些 M 视锥兴奋）。 */
    private val oranges = listOf(intArrayOf(206, 150, 96), intArrayOf(196, 136, 84), intArrayOf(214, 160, 104), intArrayOf(188, 146, 92))
    /** 示例图的数字：明显更暗的橙红，人人都能看出。 */
    private val demoFigure = listOf(intArrayOf(214, 96, 52), intArrayOf(200, 84, 48), intArrayOf(222, 108, 60))

    /** 色点颜色：从底色里随机挑一种，数字区在被检测视锥上加 [contrast]，再整体乘以随机亮度（遮掉亮度线索）。 */
    fun dotColor(type: ColorPlateType, inFigure: Boolean, contrast: Float, random: Random): Int {
        val palette = when {
            type == ColorPlateType.DEMO && inFigure -> demoFigure
            type == ColorPlateType.DEUTAN -> oranges
            else -> greens
        }
        val lms = lms(palette[random.nextInt(palette.size)])
        if (inFigure) when (type) {
            ColorPlateType.PROTAN -> lms[0] *= 1.0 + contrast
            ColorPlateType.DEUTAN -> lms[1] *= 1.0 + contrast
            ColorPlateType.DEMO -> Unit
        }
        val k = 0.86 + 0.28 * random.nextDouble()
        val rgb = DoubleArray(3) { i -> (0 until 3).sumOf { j -> fromLms[i][j] * lms[j] * k } }
        val bytes = rgb.map { (encode(it.coerceIn(0.0, 1.0)) * 255.0 + 0.5).toInt().coerceIn(0, 255) }
        return (0xFF shl 24) or (bytes[0] shl 16) or (bytes[1] shl 8) or bytes[2]
    }

    private fun lms(rgb: IntArray): DoubleArray {
        val linear = DoubleArray(3) { decode(rgb[it] / 255.0) }
        return DoubleArray(3) { i -> (0 until 3).sumOf { j -> toLms[i][j] * linear[j] } }
    }

    private fun decode(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun encode(c: Double) = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055

    private fun multiply(a: Array<DoubleArray>, b: Array<DoubleArray>) =
        Array(3) { i -> DoubleArray(3) { j -> (0 until 3).sumOf { k -> a[i][k] * b[k][j] } } }

    private fun invert(m: Array<DoubleArray>): Array<DoubleArray> {
        val (a, b, c) = m[0].let { Triple(it[0], it[1], it[2]) }
        val (d, e, f) = m[1].let { Triple(it[0], it[1], it[2]) }
        val (g, h, i) = m[2].let { Triple(it[0], it[1], it[2]) }
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        return arrayOf(
            doubleArrayOf((e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det),
            doubleArrayOf((f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det),
            doubleArrayOf((d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det),
        )
    }

    /** 测试用：颜色换回 LMS（检验检测图是否只改变了一种视锥）。 */
    fun lmsOf(argb: Int): DoubleArray = lms(intArrayOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF))
}

data class ColorVisionReport(
    val valid: Boolean,
    val riskLevel: ScreeningRiskLevel,
    val protanErrors: Int,
    val deutanErrors: Int,
    /** 倾向的类型：PROTAN / DEUTAN / UNCLEAR；未见异常时为 null。 */
    val type: String?,
    val advice: String,
    val evidence: List<Evidence>,
    val validityReasons: List<String>,
    val features: Map<String, Double?>,
) {
    val totalErrors: Int get() = protanErrors + deutanErrors
}

object ColorVisionAssessment {
    const val VERSION = ColorVisionProtocol.VERSION

    fun evaluate(demo: ColorPlateAnswer?, answers: List<ColorPlateAnswer>, completed: Boolean): ColorVisionReport {
        val protan = answers.count { it.type == ColorPlateType.PROTAN && !it.correct }
        val deutan = answers.count { it.type == ColorPlateType.DEUTAN && !it.correct }
        val total = protan + deutan
        val rts = answers.mapNotNull { it.rtMs }
        val features = mapOf(
            "protan_errors" to protan.toDouble(), "deutan_errors" to deutan.toDouble(), "total_errors" to total.toDouble(),
            "plates_answered" to answers.size.toDouble(), "demo_correct" to if (demo?.correct == true) 1.0 else 0.0,
            "mean_rt_ms" to rts.takeIf { it.isNotEmpty() }?.average(),
        )
        val reasons = buildList {
            if (!completed || answers.size < ColorVisionProtocol.screening.size) add("测试没有做完")
            if (demo != null && !demo.correct) add("示例图读错了：可能是屏幕太暗、开了护眼或夜间模式，或没看清，请调亮屏幕后重测")
        }
        if (reasons.isNotEmpty()) return ColorVisionReport(false, ScreeningRiskLevel.UNKNOWN, protan, deutan, null,
            "请关闭护眼 / 夜间模式、把屏幕调亮，在明亮的地方重新测试。", emptyList(), reasons, features)
        val level = when {
            total <= ColorVisionProtocol.NORMAL_MAX_ERRORS -> ScreeningRiskLevel.LOW
            total <= ColorVisionProtocol.SUSPECT_MAX_ERRORS -> ScreeningRiskLevel.MEDIUM
            else -> ScreeningRiskLevel.HIGH
        }
        val type = when {
            level == ScreeningRiskLevel.LOW -> null
            protan - deutan >= ColorVisionProtocol.TYPE_MARGIN -> "PROTAN"
            deutan - protan >= ColorVisionProtocol.TYPE_MARGIN -> "DEUTAN"
            else -> "UNCLEAR"
        }
        fun group(t: ColorPlateType, label: String, errors: Int) = Evidence(
            key = t.name.lowercase(), title = label,
            status = when { errors == 0 -> EvidenceStatus.NORMAL; errors == 1 -> EvidenceStatus.UNCERTAIN; else -> EvidenceStatus.ABNORMAL },
            summary = "4 张里读错 $errors 张",
            details = answers.filter { it.type == t }.map { a ->
                "图中数字 ${a.number}（对比度 ${(a.contrast * 100).toInt()}%）：${if (a.correct) "读对" else "答「${a.answer}」"}"
            },
        )
        val evidence = listOf(group(ColorPlateType.PROTAN, "红色觉检测图", protan), group(ColorPlateType.DEUTAN, "绿色觉检测图", deutan))
        val advice = when (level) {
            ScreeningRiskLevel.LOW -> "本次未见红绿色觉异常。色觉一般不会随年龄改变，有需要（如体检、报考专业）时以医院检查为准。"
            ScreeningRiskLevel.MEDIUM -> "读错了几张，可能有轻度色弱，也可能与屏幕或光线有关。建议换个明亮的地方复测；仍读错请到医院做色觉检查。"
            else -> "多张检测图读错，提示可能有红绿色觉异常（色弱或色盲）。建议到医院眼科做色觉检查确认类型和程度。"
        }
        return ColorVisionReport(true, level, protan, deutan, type, advice, evidence, emptyList(), features)
    }

    fun typeLabel(type: String?): String = when (type) {
        "PROTAN" -> "倾向红色觉（第一型）异常"
        "DEUTAN" -> "倾向绿色觉（第二型）异常"
        "UNCLEAR" -> "红绿色觉异常，类型未能区分"
        else -> "未见异常"
    }
}

/** 测试中显示的进度与时长（用于顶部进度条）。 */
internal fun colorProgressFraction(answered: Int): Float =
    (answered.toFloat() / ColorVisionProtocol.screening.size).coerceIn(0f, 1f)

/** 结果页（两端同一份）。 */
internal fun ResultReports.colorVision(report: ColorVisionReport, answers: List<ColorPlateAnswer>, fields: Map<String, String>,
                                       subject: TestSubject?): ResultReport {
    val level = report.riskLevel
    val title = when (level) {
        ScreeningRiskLevel.LOW -> "未见红绿色觉异常"
        ScreeningRiskLevel.MEDIUM -> "色觉结果可疑"
        ScreeningRiskLevel.HIGH -> "提示可能有红绿色觉异常"
        else -> "这次数据无效"
    }
    val metrics = listOf(
        ResultMetric("检测图读对", "${answers.count { it.correct }}/${answers.size}", if (report.totalErrors <= 1) EvidenceStatus.NORMAL else EvidenceStatus.ABNORMAL),
        ResultMetric("红色觉图读错", "${report.protanErrors} 张", report.evidence.firstOrNull()?.status ?: EvidenceStatus.UNAVAILABLE),
        ResultMetric("绿色觉图读错", "${report.deutanErrors} 张", report.evidence.getOrNull(1)?.status ?: EvidenceStatus.UNAVAILABLE),
        ResultMetric("倾向类型", if (report.type == null) "—" else ColorVisionAssessment.typeLabel(report.type).removePrefix("倾向"), EvidenceStatus.UNAVAILABLE),
    )
    val sections = buildList {
        add(ResultSection("逐张作答", answers.map { a ->
            ResultRow("${if (a.type == ColorPlateType.PROTAN) "红" else "绿"}色觉图 ${a.number}",
                (if (a.correct) "读对" else "答「${a.answer}」") + (a.rtMs?.let { " · ${decimalString(it / 1000.0, 1)} 秒" } ?: ""))
        }))
        add(ResultSection("判定标准", listOf(
            ResultRow("", "8 张检测图读错 0–1 张：未见异常；2–3 张：可疑；4 张及以上：提示红绿色觉异常"),
            ResultRow("", "红色觉图与绿色觉图的错读数相差 2 张及以上时，提示倾向的类型"),
            ResultRow("", "检测图按视锥细胞单独刺激设计；手机屏幕未经颜色校准，结果只作初筛"),
        )))
        val profileRows = listOfNotNull(subject?.let { ResultRow("被测者", "${it.name}（${SubjectProfiles.relationLabel(it.relation)}）") },
            fields["年龄"]?.let { ResultRow("年龄", "$it 岁") })
        if (profileRows.isNotEmpty()) add(ResultSection("被测者资料", profileRows))
    }
    return ResultReport(
        kind = "COLOR_VISION", level = level,
        badge = when (level) {
            ScreeningRiskLevel.LOW -> "色觉未见异常"
            ScreeningRiskLevel.MEDIUM -> "色觉可疑"
            ScreeningRiskLevel.HIGH -> "色觉异常可能"
            else -> "需要重测"
        },
        title = title, advice = report.advice,
        mood = when (level) {
            ScreeningRiskLevel.LOW -> ResultMood.HAPPY
            ScreeningRiskLevel.MEDIUM -> ResultMood.THINKING
            ScreeningRiskLevel.HIGH -> ResultMood.CONCERNED
            else -> ResultMood.SLEEPY
        },
        metrics = if (report.valid) metrics else emptyList(),
        evidence = report.evidence,
        problems = if (report.valid) emptyList() else report.validityReasons,
        sections = sections, version = ColorVisionAssessment.VERSION,
        qualityNotes = listOf("手机屏幕颜色未经校准，护眼 / 夜间模式会改变颜色；测试期间屏幕亮度已调到最高。"),
    )
}
