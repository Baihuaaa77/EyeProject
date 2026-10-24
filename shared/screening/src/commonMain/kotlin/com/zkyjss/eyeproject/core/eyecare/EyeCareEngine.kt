package com.zkyjss.eyeproject.core.eyecare

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/*
 * 主动护眼闭环（感知 → 评估 → 归因 → 干预 → 反馈），安卓与苹果共用同一套规则：
 * 感知  多源数据：屏幕使用（安卓系统统计）、实时检测的眨眼 / 距离 / 头姿、环境光与亮度、个人档案、每日打卡；
 *       系统不提供的数据整项不出现，相关规则也不参与；
 * 评估  按当天数据给出 0–100 的用眼负荷分与等级；
 * 归因  每条超出参考线的因素记分，按分值排序即主要原因；
 * 干预  针对原因给出可马上做的事（休息、提醒、亮度、距离、眨眼……）与专业咨询建议；
 * 反馈  每日身体感受打卡回到下一次评估。
 * 参考线为应用设定的提示阈值（依据见 shared/SHARED.md「主动护眼」），不是诊断标准，上线前需眼科医生审核。
 */

/** 最近一次自测的结论（本人或未选成员的记录）。 */
@Serializable
data class TestBrief(val kind: String, val level: String, val badge: String, val day: String)

/** 评估所需的当下情况，由平台采集后传入。 */
@Serializable
data class EyeCareContext(
    val today: String,
    val hour: Int,
    /** 系统统计的逐日屏幕使用（仅安卓且已授权） */
    val systemUsage: List<UsageDay> = emptyList(),
    /** [EyeCareEngine.ACCESS_GRANTED] / [EyeCareEngine.ACCESS_DENIED] / [EyeCareEngine.ACCESS_UNSUPPORTED]（系统不提供用屏统计） */
    val usageAccess: String = EyeCareEngine.ACCESS_UNSUPPORTED,
    /** 环境光（lux），平台读不到时为 null */
    val ambientLux: Float? = null,
    /** 平台能否读取环境光 */
    val ambientSupported: Boolean = false,
    /** 屏幕亮度 0–1 */
    val screenBrightness: Float? = null,
    /** 本人资料（键同 SubjectProfiles） */
    val profile: Map<String, String> = emptyMap(),
    val age: Int? = null,
    val tests: List<TestBrief> = emptyList(),
    val reminderEnabled: Boolean = false,
    val reminderMinutes: Int = 20,
)

/** 多源感知的一项读数。[group]：BASIC 基础数据 / BEHAVIOR 视觉行为 / PROFILE 个体档案；[status]：NORMAL / ATTENTION / UNAVAILABLE。 */
@Serializable
data class EyeSignal(val key: String, val group: String, val label: String, val value: String, val status: String, val note: String = "")

/** 归因：一条超出参考线的因素与分值。 */
@Serializable
data class EyeFactor(val key: String, val title: String, val detail: String, val points: Int)

/**
 * 干预：一件可以马上做的事。[kind] 决定界面上的操作按钮：BREAK / REMINDER / BLINK / DISTANCE / BRIGHTNESS / NIGHT / OUTDOOR /
 * TEST（[target] 为测试类型）/ PROFILE / USAGE_ACCESS / LIVE / CHECK_IN；[value] 为建议的提醒间隔等数值。
 */
@Serializable
data class EyeAction(val key: String, val title: String, val detail: String, val kind: String, val target: String? = null, val value: Int? = null)

/**
 * 小瞳的气泡提醒（首页随机弹出，不朗读）：一句短话；可执行的附带动作类型与按钮文字（与 [EyeAction.kind] 同一套）。
 */
@Serializable
data class EyeTip(val text: String, val kind: String? = null, val target: String? = null, val value: Int? = null, val action: String? = null)

/** 专业咨询建议。 */
@Serializable
data class EyeConsult(val title: String, val detail: String, val department: String, val urgent: Boolean)

/** 闭环一环的完成情况。[key]：PERCEIVE / ASSESS / ATTRIBUTE / INTERVENE / FEEDBACK。 */
@Serializable
data class LoopStage(val key: String, val label: String, val done: Boolean, val summary: String)

@Serializable
data class TrendDay(
    val day: String,
    val label: String,
    val screenMinutes: Int? = null,
    val longestSessionMinutes: Int? = null,
    val nightMinutes: Int? = null,
    val blinksPerMinute: Float? = null,
    val score: Int? = null,
    val source: String? = null,
)

@Serializable
data class EyeTrend(
    val days: List<TrendDay>,
    val summary: List<String>,
    val averageScreenMinutes: Int? = null,
    val previousAverageScreenMinutes: Int? = null,
    val averageBlinksPerMinute: Float? = null,
    val checkInDays: Int = 0,
    val symptomDays: Int = 0,
)

@Serializable
data class EyeCareReport(
    /** LOW / MEDIUM / HIGH / UNKNOWN */
    val level: String,
    val score: Int? = null,
    val label: String,
    val headline: String,
    /** 小瞳神情：HAPPY / THINKING / SLEEPY（深夜）/ TIRED（负荷偏高，嫩芽打蔫）/ CONCERNED（危险信号） */
    val mood: String,
    val loop: List<LoopStage>,
    val signals: List<EyeSignal>,
    val factors: List<EyeFactor>,
    val actions: List<EyeAction>,
    /** 小瞳气泡依次说的话：先是针对今天的提醒，再是日常小贴士。 */
    val tips: List<EyeTip> = emptyList(),
    val consults: List<EyeConsult>,
    val trend: EyeTrend,
    val checkedInToday: Boolean,
    val todaySymptoms: List<String>,
    val suggestedReminderMinutes: Int,
) {
    fun toJson(): String = LedgerData.JSON.encodeToString(serializer(), this)
}

object EyeCareEngine {
    const val ACCESS_GRANTED = "GRANTED"
    const val ACCESS_DENIED = "DENIED"
    const val ACCESS_UNSUPPORTED = "UNSUPPORTED"

    /** 每日打卡可选的感受（两端同一份）；后两项为需要尽快就医的危险信号。 */
    val SYMPTOMS = listOf("眼睛干涩", "眼睛酸胀", "看东西模糊", "头痛", "眼睛疼痛", "突然重影或看不清")
    private val RED_FLAGS = setOf("眼睛疼痛", "突然重影或看不清")

    /** 平时眨眼约每分钟 15 次（知识库 dry-screen-blink）。 */
    private const val NORMAL_BLINKS = 15

    fun report(ledger: LedgerData, context: EyeCareContext): EyeCareReport {
        val today = context.today
        val child = context.age != null && context.age < 18
        val usage = usageByDay(context)
        val todayUsage = usage[today]
        val todaySessions = ledger.sessions.filter { it.day == today }
        val checkIn = ledger.checkIns.lastOrNull { it.day == today }
        val symptoms = checkIn?.symptoms.orEmpty()
        val redFlag = symptoms.any { it in RED_FLAGS }

        val factors = (dayFactors(todayUsage, todaySessions, symptoms, child) + contextFactors(context))
            .sortedByDescending { it.points }
        val hasData = todayUsage?.screenMinutes != null || todaySessions.isNotEmpty() || checkIn != null
        val score = if (hasData) factors.sumOf { it.points }.coerceAtMost(100) else null
        val level = when {
            score == null -> "UNKNOWN"
            score < 30 -> "LOW"
            score < 60 -> "MEDIUM"
            else -> "HIGH"
        }
        val suggested = if (child || level == "HIGH" || (todayUsage?.longestSessionMinutes ?: 0) > 60) 20 else 30
        val consults = consults(ledger, context, symptoms, redFlag, child)
        val actions = actions(ledger, context, level, factors, todayUsage, checkIn != null, suggested, child)
        val signals = signals(ledger, context, todayUsage, factors, checkIn)
        val trend = trend(ledger, context, usage, child, score)
        val sources = signals.filter { it.group != "PROFILE" && it.status != "UNAVAILABLE" }.size +
            (if (context.profile.isNotEmpty()) 1 else 0)
        val label = when (level) { "LOW" -> "轻松"; "MEDIUM" -> "留意"; "HIGH" -> "偏累"; else -> "待感知" }
        val top = factors.firstOrNull()?.title
        return EyeCareReport(
            level = level, score = score, label = label,
            headline = when {
                redFlag -> "你今天感到眼痛或突然看不清，请尽快到眼科就医。"
                level == "UNKNOWN" -> when (context.usageAccess) {
                    ACCESS_DENIED -> "开启用屏统计或做一次实时检测，小瞳就能评估你今天的用眼状态。"
                    else -> "做一次实时检测或记录今天的感受，小瞳就能评估你的用眼状态。"
                }
                level == "LOW" -> "今天用眼比较轻松，继续保持好习惯。"
                level == "MEDIUM" -> "${top ?: "用眼有些多"}，记得让眼睛歇一歇。"
                else -> "眼睛有点累了：${top ?: "用眼负荷偏高"}，现在休息一下吧。"
            },
            mood = when {
                redFlag -> "CONCERNED"
                level == "HIGH" -> "TIRED"
                context.hour >= 23 || context.hour < 5 -> "SLEEPY"
                level == "LOW" -> "HAPPY"
                else -> "THINKING"
            },
            loop = listOf(
                LoopStage("PERCEIVE", "感知", sources > 0, if (sources > 0) "$sources 类数据" else "待开启"),
                LoopStage("ASSESS", "评估", score != null, score?.let { "$label $it 分" } ?: "数据不足"),
                LoopStage("ATTRIBUTE", "归因", score != null, top ?: if (score != null) "暂无明显问题" else "—"),
                LoopStage("INTERVENE", "干预", actions.isNotEmpty(), if (actions.isEmpty()) "暂无" else "${actions.size} 条建议"),
                LoopStage("FEEDBACK", "反馈", checkIn != null, if (checkIn != null) "今日已打卡" else "待打卡"),
            ),
            signals = signals, factors = factors, actions = actions, tips = tips(actions, factors, level, redFlag), consults = consults, trend = trend,
            checkedInToday = checkIn != null, todaySymptoms = symptoms, suggestedReminderMinutes = suggested,
        )
    }

    // region 感知：合并多源数据

    /** 逐日屏幕使用：只用系统统计（系统不提供时没有这部分数据，相关读数与图表都不出现）。 */
    private fun usageByDay(context: EyeCareContext): Map<String, UsageDay> =
        context.systemUsage.filter { it.screenMinutes != null }.associateBy { it.day }

    private fun signals(ledger: LedgerData, context: EyeCareContext, usage: UsageDay?, factors: List<EyeFactor>,
                        checkIn: CheckIn?): List<EyeSignal> = buildList {
        fun flagged(key: String) = factors.any { it.key == key }
        fun status(available: Boolean, key: String) = when { !available -> "UNAVAILABLE"; flagged(key) -> "ATTENTION"; else -> "NORMAL" }
        // 系统不提供的读数整项不出现
        if (context.usageAccess != ACCESS_UNSUPPORTED) {
            add(EyeSignal("screen", "BASIC", "今日用屏", usage?.screenMinutes?.let(::duration) ?: "—", status(usage?.screenMinutes != null, "screen")))
            add(EyeSignal("longest", "BASIC", "最长连续", usage?.longestSessionMinutes?.let(::duration) ?: "—",
                status(usage?.longestSessionMinutes != null, "longest")))
            add(EyeSignal("breaks", "BASIC", "休息次数", usage?.breaks?.let { "$it 次" } ?: "—", status(usage?.breaks != null, "breaks")))
            add(EyeSignal("night", "BASIC", "夜间使用", usage?.nightMinutes?.let(::duration) ?: "—", status(usage?.nightMinutes != null, "night")))
        }
        if (context.ambientSupported) add(EyeSignal("light", "BASIC", "环境光", context.ambientLux?.let { "${it.roundToInt()} lx" } ?: "—",
            status(context.ambientLux != null, "dark-bright")))
        add(EyeSignal("brightness", "BASIC", "屏幕亮度", context.screenBrightness?.let { "${(it * 100).roundToInt()}%" } ?: "—",
            status(context.screenBrightness != null, "night-bright")))

        // 视觉行为：今天的检测优先，否则用近 7 天最近一次并注明
        val recent = ledger.sessions.filter { it.day >= EyeCareDays.plusDays(context.today, -6) }
        val todays = recent.filter { it.day == context.today }
        val basis = todays.ifEmpty { listOfNotNull(recent.maxByOrNull { it.endedAtMs }) }
        val note = when {
            basis.isEmpty() -> "做一次实时检测"
            todays.isNotEmpty() -> "今天 ${todays.size} 次检测"
            else -> EyeCareDays.monthDay(basis.first().day)
        }
        val blink = weightedBlink(basis)
        val distance = weighted(basis) { it.meanDistanceCm }
        val head = weighted(basis) { it.meanHeadOffsetDeg }
        add(EyeSignal("blink", "BEHAVIOR", "眨眼频率", blink?.let { "${it.roundToInt()} 次/分" } ?: "—", status(blink != null, "blink"), note))
        add(EyeSignal("distance", "BEHAVIOR", "观看距离", distance?.let { "${it.roundToInt()} 厘米" } ?: "—", status(distance != null, "distance"), note))
        add(EyeSignal("posture", "BEHAVIOR", "头部姿态", head?.let { if (it < 15f) "端正" else "偏离 ${it.roundToInt()}°" } ?: "—",
            status(head != null, "posture"), note))

        val p = context.profile
        fun pick(vararg keys: String) = keys.firstNotNullOfOrNull { k -> p[k]?.takeIf { it.isNotBlank() } }
        add(EyeSignal("age", "PROFILE", "年龄", context.age?.let { "$it 岁" } ?: "未填", if (context.age != null) "NORMAL" else "UNAVAILABLE"))
        add(EyeSignal("job", "PROFILE", "职业", pick("职业") ?: "未填", if (pick("职业") != null) "NORMAL" else "UNAVAILABLE"))
        val vision = pick("基础视力")?.let { "视力$it" } ?: pick("日常矫正方式")
        add(EyeSignal("vision", "PROFILE", "视力状况", vision ?: "未填", if (vision != null) "NORMAL" else "UNAVAILABLE"))
        val history = pick("既往眼病")
        add(EyeSignal("history", "PROFILE", "历史病症", history ?: "未填",
            when { history == null -> "UNAVAILABLE"; history == "没有" -> "NORMAL"; else -> "ATTENTION" }))
        add(EyeSignal("feedback", "PROFILE", "使用反馈",
            when { checkIn == null -> "未打卡"; checkIn.symptoms.isEmpty() -> "没有不适"; else -> checkIn.symptoms.joinToString("、") },
            when { checkIn == null -> "UNAVAILABLE"; checkIn.symptoms.isEmpty() -> "NORMAL"; else -> "ATTENTION" }))
    }

    // endregion

    // region 评估与归因

    /** 一天里用屏与视觉行为的因素（趋势逐日评估也用它）。 */
    private fun dayFactors(usage: UsageDay?, sessions: List<LiveSessionRecord>, symptoms: List<String>, child: Boolean): List<EyeFactor> = buildList {
        usage?.screenMinutes?.let { m ->
            val (high, mid, low) = if (child) Triple(240, 180, 120) else Triple(480, 360, 240)
            val points = when { m > high -> 25; m > mid -> 18; m > low -> 10; else -> 0 }
            if (points > 0) add(EyeFactor("screen", "用屏时间偏长", "今天累计用屏 ${duration(m)}，超过 ${duration(if (m > high) high else if (m > mid) mid else low)}", points))
        }
        usage?.longestSessionMinutes?.let { l ->
            val (high, mid, low) = if (child) Triple(60, 40, 20) else Triple(120, 60, 40)
            val points = when { l > high -> 20; l > mid -> 15; l > low -> 8; else -> 0 }
            if (points > 0) add(EyeFactor("longest", "连续用眼太久", "最长一次连续看了 ${duration(l)}，中间没有停下来", points))
        }
        val minutes = usage?.screenMinutes
        val breaks = usage?.breaks
        if (minutes != null && breaks != null && minutes >= 120 && breaks * 60f / minutes < 0.5f)
            add(EyeFactor("breaks", "休息太少", "用屏 ${duration(minutes)} 只休息了 $breaks 次", 8))
        usage?.nightMinutes?.let { n ->
            if (n >= 60) add(EyeFactor("night", "夜间用屏较多", "22 点以后用了 ${duration(n)}", 10))
            else if (n >= 20) add(EyeFactor("night", "夜间用屏", "22 点以后用了 ${duration(n)}", 5))
        }
        weightedBlink(sessions)?.let { b ->
            if (b < 8f) add(EyeFactor("blink", "眨眼偏少", "检测到每分钟眨眼约 ${b.roundToInt()} 次，平时约 $NORMAL_BLINKS 次", 15))
            else if (b < 12f) add(EyeFactor("blink", "眨眼略少", "检测到每分钟眨眼约 ${b.roundToInt()} 次，平时约 $NORMAL_BLINKS 次", 8))
        }
        weighted(sessions) { it.meanDistanceCm }?.let { d ->
            if (d < 25f) add(EyeFactor("distance", "看得太近", "屏幕离眼睛平均只有 ${d.roundToInt()} 厘米", 12))
            else if (d < 30f) add(EyeFactor("distance", "看得偏近", "屏幕离眼睛平均 ${d.roundToInt()} 厘米", 8))
        }
        weighted(sessions) { it.meanHeadOffsetDeg }?.let { h ->
            if (h > 25f) add(EyeFactor("posture", "姿势不正", "头部平均偏离正对屏幕 ${h.roundToInt()}°", 4))
        }
        val felt = symptoms.filter { it in SYMPTOMS }
        if (felt.isNotEmpty()) add(EyeFactor("symptoms", "自觉不适", "今天感到${felt.joinToString("、")}", (felt.size * 6).coerceAtMost(18)))
    }

    /** 只属于「今天」的因素：环境光与亮度、近期自测结论、个人档案。 */
    private fun contextFactors(c: EyeCareContext): List<EyeFactor> = buildList {
        val brightness = c.screenBrightness
        val night = c.hour >= 21 || c.hour < 6
        if (c.ambientLux != null && brightness != null && c.ambientLux < 50f && brightness >= 0.6f)
            add(EyeFactor("dark-bright", "暗处屏幕过亮", "周围较暗，屏幕亮度却有 ${(brightness * 100).roundToInt()}%", 6))
        else if (night && brightness != null && brightness >= 0.7f)
            add(EyeFactor("night-bright", "夜间屏幕偏亮", "晚上屏幕亮度 ${(brightness * 100).roundToInt()}%", 4))
        val since = EyeCareDays.plusDays(c.today, -90)
        c.tests.filter { it.kind == "DRY_EYE" && it.day >= since }.maxByOrNull { it.day }?.let { t ->
            when (t.level) {
                "HIGH" -> add(EyeFactor("dry-test", "干眼自测为高风险", "最近一次干眼自测：${t.badge}", 10))
                "MEDIUM" -> add(EyeFactor("dry-test", "干眼自测提示风险", "最近一次干眼自测：${t.badge}", 6))
            }
        }
        if (c.profile["佩戴隐形眼镜史"] == "现在在戴") add(EyeFactor("contacts", "佩戴隐形眼镜", "戴隐形眼镜时眼睛更容易干", 4))
        if (c.profile["既往眼病"].orEmpty().split("、").contains("干眼")) add(EyeFactor("dry-history", "有干眼病史", "医生诊断过干眼", 5))
        if (c.profile["睡眠时长"] == "不到 6 小时") add(EyeFactor("sleep", "睡眠偏少", "每天睡不到 6 小时", 4))
    }

    // endregion

    // region 干预与专业咨询

    private fun actions(ledger: LedgerData, c: EyeCareContext, level: String, factors: List<EyeFactor>, usage: UsageDay?,
                        checkedIn: Boolean, suggested: Int, child: Boolean): List<EyeAction> = buildList {
        val keys = factors.map { it.key }.toSet()
        val busy = level == "MEDIUM" || level == "HIGH" || (usage?.longestSessionMinutes ?: 0) > 40
        if (busy) add(EyeAction("break", "现在休息 20 秒", "看 6 米外的远处 20 秒，让眼睛放松一下", "BREAK"))
        if (busy && !c.reminderEnabled) add(EyeAction("reminder", "开启休息提醒", "每 $suggested 分钟提醒你远眺放松", "REMINDER", value = suggested))
        else if (busy && c.reminderMinutes > suggested) add(EyeAction("reminder", "缩短提醒间隔", "改为每 $suggested 分钟提醒一次", "REMINDER", value = suggested))
        if ("blink" in keys) add(EyeAction("blink", "有意识地多眨眼", "看屏幕时隔一会儿完整地眨几下眼", "BLINK"))
        if ("distance" in keys) add(EyeAction("distance", "把屏幕拿远一点", "保持 30 厘米以上，最好约 40 厘米", "DISTANCE"))
        if ("dark-bright" in keys || "night-bright" in keys || "night" in keys)
            add(EyeAction("brightness", "调低亮度、减少蓝光", "打开夜间模式或护眼模式，亮度和周围环境接近", "BRIGHTNESS"))
        if (c.hour >= 23 || c.hour < 5) add(EyeAction("night", "夜深了，早点休息", "睡前少看屏幕，眼睛和睡眠都会更好", "NIGHT"))
        if (child) add(EyeAction("outdoor", "多到户外活动", "白天户外活动每天累计 2 小时", "OUTDOOR"))
        val symptoms = ledger.checkIns.lastOrNull { it.day == c.today }?.symptoms.orEmpty()
        val recentDry = c.tests.any { it.kind == "DRY_EYE" && it.day >= EyeCareDays.plusDays(c.today, -30) }
        if (!recentDry && ("眼睛干涩" in symptoms || "dry-history" in keys || "contacts" in keys))
            add(EyeAction("test-dry", "做一次干眼自测", "约 2 分钟，看看眼睛干不干", "TEST", target = "DRY_EYE"))
        if ((c.age ?: 0) >= 40 && c.tests.none { it.kind == "PRESBYOPIA" })
            add(EyeAction("test-presbyopia", "做一次老视自测", "40 岁后看近能力会慢慢下降", "TEST", target = "PRESBYOPIA"))
        if (c.usageAccess == ACCESS_DENIED) add(EyeAction("usage", "开启用屏统计", "自动统计每天用屏多久、连续用了多久", "USAGE_ACCESS"))
        if (ledger.sessions.none { it.day >= EyeCareDays.plusDays(c.today, -6) })
            add(EyeAction("live", "做一次实时检测", "看看眨眼次数和观看距离", "LIVE"))
        val missing = listOf(c.age == null, c.profile["职业"].isNullOrBlank(),
            c.profile["基础视力"].isNullOrBlank() && c.profile["日常矫正方式"].isNullOrBlank(), c.profile["既往眼病"].isNullOrBlank()).count { it }
        if (missing > 0) add(EyeAction("profile", "完善个人档案", "还差 $missing 项，建议会更贴合你", "PROFILE"))
        if (!checkedIn) add(EyeAction("check-in", "记录今天的感受", "眼睛舒服吗？点一下就好", "CHECK_IN"))
    }

    /** 气泡提醒：危险信号最先；再把今天的建议换成小瞳的口吻（可执行的带按钮），最后补几句日常小贴士。 */
    private fun tips(actions: List<EyeAction>, factors: List<EyeFactor>, level: String, redFlag: Boolean): List<EyeTip> = buildList {
        if (redFlag) add(EyeTip("眼睛疼或突然看不清，要尽快去医院哦"))
        if (level == "HIGH") add(EyeTip("眼睛累坏啦，闭上眼歇一会儿吧", "BREAK", action = "去休息"))
        actions.forEach { a ->
            when (a.key) {
                "break" -> add(EyeTip("看了好久啦，看看远处 20 秒吧", "BREAK", action = "去休息"))
                "reminder" -> add(EyeTip(if (a.title.startsWith("缩短")) "提醒改成每 ${a.value} 分钟一次吧" else "开个休息提醒，我来叫你歇一歇",
                    "REMINDER", value = a.value, action = "开启"))
                "blink" -> add(EyeTip("多眨眨眼，眼睛会舒服些"))
                "distance" -> add(EyeTip("把屏幕拿远一点"))
                "brightness" -> add(EyeTip("屏幕有点亮，调暗一点吧", "BRIGHTNESS", action = "去调节"))
                "night" -> add(EyeTip("夜深了，早点休息吧"))
                "outdoor" -> add(EyeTip("白天多去户外玩一玩"))
                "test-dry" -> add(EyeTip("眼睛发干？来做个干眼自测吧", "TEST", target = "DRY_EYE", action = "去自测"))
                "test-presbyopia" -> add(EyeTip("看近吃力吗？做个老视自测吧", "TEST", target = "PRESBYOPIA", action = "去自测"))
                "usage" -> add(EyeTip("开启用屏统计，我就知道你看了多久", "USAGE_ACCESS", action = "去开启"))
                "live" -> add(EyeTip("打开实时检测，看看你眨眼多不多", "LIVE", action = "去检测"))
                "profile" -> add(EyeTip("把档案填完整，建议会更贴合你", "PROFILE", action = "去完善"))
            }
        }
        val keys = factors.map { it.key }.toSet()
        if (level == "LOW") add(EyeTip("今天用眼很棒，继续保持！"))
        if ("distance" !in keys) add(EyeTip("屏幕别贴太近，保持一臂远"))
        if ("posture" !in keys) add(EyeTip("坐直一点，别低头太久"))
        add(EyeTip("每 20 分钟，看看 6 米外 20 秒"))
        if (level == "MEDIUM" || level == "HIGH") add(EyeTip("喝口水，伸个懒腰吧"))
        add(EyeTip("看看窗外的绿色吧"))
    }.distinctBy { it.text }

    private fun consults(ledger: LedgerData, c: EyeCareContext, symptoms: List<String>, redFlag: Boolean, child: Boolean): List<EyeConsult> = buildList {
        if (redFlag) add(EyeConsult("尽快就医", "眼睛疼痛、突然重影或看不清，不要等待自测，请尽快到医院检查。", "眼科急诊或眼科门诊", true))
        val since = EyeCareDays.plusDays(c.today, -180)
        val latest = c.tests.filter { it.day >= since }.groupBy { it.kind }.mapValues { (_, list) -> list.maxBy { it.day } }
        latest["DRY_EYE"]?.let { t ->
            when (t.level) {
                "HIGH" -> add(EyeConsult("干眼自测提示高风险", "建议 1–2 周内到眼科就诊，做泪膜等检查。", "眼科，可选角膜病或眼表门诊", false))
                "MEDIUM" -> add(EyeConsult("干眼自测提示中风险", "先调整用眼习惯，2 周后复测；不适持续请到眼科检查。", "眼科", false))
                "UNCERTAIN" -> add(EyeConsult("干眼自测结果不确定", "换到光线均匀的地方，放松后再测一次。", "暂不需要就医", false))
            }
        }
        latest["PRESBYOPIA"]?.let { t ->
            if (t.level == "HIGH" || t.level == "MEDIUM")
                add(EyeConsult("看近能力下降", "建议到医院验光，评估是否需要看近用的眼镜。", "眼科或视光门诊", false))
        }
        latest["STRABISMUS"]?.let { t ->
            when (t.level) {
                "HIGH" -> add(EyeConsult("眼位自测高度疑似", "建议尽快做遮盖试验等专业检查。", "小儿眼科或斜视专科", child))
                "MEDIUM" -> add(EyeConsult("眼位自测可疑", "建议近期到眼科复查眼位。", "小儿眼科或斜视专科", false))
            }
        }
        val week = EyeCareDays.plusDays(c.today, -6)
        val uneasyDays = ledger.checkIns.filter { it.day >= week && it.symptoms.isNotEmpty() }.map { it.day }.toSet().size
        if (!redFlag && uneasyDays >= 4) add(EyeConsult("近一周多次感到不适", "休息后仍反复不舒服，建议到眼科查一查原因。", "眼科", false))
        val age = c.age
        if (isEmpty() && age != null && age >= 40)
            add(EyeConsult("定期做眼科检查", "40 岁左右建议做一次全面的眼科检查，之后按医生建议复查。", "眼科", false))
        if (isEmpty() && age != null && age <= 6)
            add(EyeConsult("按时做儿童眼保健", "0～6 岁儿童有 13 次眼保健和视力检查，记得按时去。", "儿童保健科或小儿眼科", false))
        if (isEmpty()) add(EyeConsult("目前没有需要就医的提示", if (symptoms.isEmpty()) "有不适时仍应到眼科检查。" else "注意休息，不适持续请到眼科检查。", "", false))
    }

    // endregion

    // region 趋势

    private fun trend(ledger: LedgerData, c: EyeCareContext, usage: Map<String, UsageDay>, child: Boolean, todayScore: Int?): EyeTrend {
        val days = (6 downTo 0).map { EyeCareDays.plusDays(c.today, -it) }
        val rows = days.map { day ->
            val u = usage[day]
            val sessions = ledger.sessions.filter { it.day == day }
            val symptoms = ledger.checkIns.lastOrNull { it.day == day }
            val hasData = u?.screenMinutes != null || sessions.isNotEmpty() || symptoms != null
            val score = if (day == c.today) todayScore
                else if (hasData) dayFactors(u, sessions, symptoms?.symptoms.orEmpty(), child).sumOf { it.points }.coerceAtMost(100) else null
            TrendDay(day, EyeCareDays.label(day, c.today), u?.screenMinutes, u?.longestSessionMinutes, u?.nightMinutes,
                weightedBlink(sessions)?.let { (it * 10).roundToInt() / 10f }, score, u?.source)
        }
        val screen = rows.mapNotNull { it.screenMinutes }
        val average = screen.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        val previous = (13 downTo 7).mapNotNull { usage[EyeCareDays.plusDays(c.today, -it)]?.screenMinutes }
            .takeIf { it.size >= 3 }?.average()?.roundToInt()
        val weekSessions = ledger.sessions.filter { it.day >= days.first() }
        val blink = weightedBlink(weekSessions)
        val checkIns = ledger.checkIns.filter { it.day >= days.first() }
        val symptomDays = checkIns.count { it.symptoms.isNotEmpty() }
        val heavyLine = if (child) 180 else 360
        val summary = buildList {
            if (average == null) { if (c.usageAccess != ACCESS_UNSUPPORTED) add("近 7 天还没有屏幕使用数据") }
            else {
                val change = previous?.takeIf { it > 0 }?.let { ((average - it) * 100f / it).roundToInt() }
                add("日均用屏 ${duration(average)}" + when {
                    change == null -> ""
                    change > 0 -> "，比前一周多 $change%"
                    change < 0 -> "，比前一周少 ${-change}%"
                    else -> "，与前一周持平"
                })
                val heavy = screen.count { it > heavyLine }
                if (heavy > 0) add("有 $heavy 天用屏超过 ${duration(heavyLine)}")
                val nights = rows.count { (it.nightMinutes ?: 0) >= 60 }
                if (nights > 0) add("有 $nights 天夜间用屏超过 1 小时")
                rows.filter { it.screenMinutes != null }.minByOrNull { it.screenMinutes!! }?.takeIf { screen.size >= 3 }?.let {
                    add("${it.label}用得最少，${duration(it.screenMinutes!!)}")
                }
            }
            if (blink != null) add("检测到的眨眼平均每分钟 ${blink.roundToInt()} 次" + if (blink < 12f) "，比平时约 $NORMAL_BLINKS 次少" else "")
            else add("近 7 天没有做实时检测")
            if (checkIns.isNotEmpty()) add("打卡 ${checkIns.size} 天" + if (symptomDays > 0) "，其中 $symptomDays 天有不适" else "，都没有不适")
        }
        return EyeTrend(rows, summary, average, previous, blink?.let { (it * 10).roundToInt() / 10f }, checkIns.size, symptomDays)
    }

    // endregion

    private fun weightedBlink(sessions: List<LiveSessionRecord>): Float? {
        val total = sessions.sumOf { it.trackedSec }
        if (total <= 0) return null
        return (sessions.sumOf { it.blinksPerMinute.toDouble() * it.trackedSec } / total).toFloat()
    }

    private fun weighted(sessions: List<LiveSessionRecord>, value: (LiveSessionRecord) -> Float?): Float? {
        val usable = sessions.mapNotNull { s -> value(s)?.let { it to s.trackedSec } }.filter { it.second > 0 }
        val total = usable.sumOf { it.second }
        if (total <= 0) return null
        return (usable.sumOf { it.first.toDouble() * it.second } / total).toFloat()
    }

    /** 「3小时20分」「45分钟」。 */
    fun duration(minutes: Int): String = when {
        minutes < 60 -> "${minutes}分钟"
        minutes % 60 == 0 -> "${minutes / 60}小时"
        else -> "${minutes / 60}小时${minutes % 60}分"
    }
}
