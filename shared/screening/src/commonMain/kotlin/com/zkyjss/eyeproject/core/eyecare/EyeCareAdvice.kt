package com.zkyjss.eyeproject.core.eyecare

import com.zkyjss.eyeproject.core.sync.AdviceRequest
import com.zkyjss.eyeproject.core.sync.AdviceTest
import com.zkyjss.eyeproject.core.sync.Advices.bounded
import com.zkyjss.eyeproject.core.sync.Explanations
import com.zkyjss.eyeproject.core.sync.SyncContract
import kotlin.math.roundToInt

/**
 * AI 综合建议的输入：由同一份账本、当下情况与规则报告抽取用户已确认的去标识概况（见 core.sync.Advice）。
 * 系统不提供的数据对应字段为空，不发送。两端共用，保证同样的概况得到同样的缓存键。
 */
object EyeCareAdvice {
    fun request(ledger: LedgerData, context: EyeCareContext, report: EyeCareReport): AdviceRequest {
        val week = EyeCareDays.plusDays(context.today, -6)
        val usage = context.systemUsage.filter { it.day >= week && it.screenMinutes != null }
            .takeIf { context.usageAccess != EyeCareEngine.ACCESS_UNSUPPORTED }.orEmpty()
        val sessions = ledger.sessions.filter { it.day >= week }
        val tracked = sessions.sumOf { it.trackedSec }
        fun weighted(value: (LiveSessionRecord) -> Float?): Int? {
            val usable = sessions.mapNotNull { s -> value(s)?.let { it to s.trackedSec } }
            val total = usable.sumOf { it.second }
            return if (total > 0) (usable.sumOf { it.first.toDouble() * it.second } / total).roundToInt() else null
        }
        val since = EyeCareDays.plusDays(context.today, -180)
        val tests = context.tests.filter { it.day >= since }.groupBy { it.kind }
            .filterKeys { it in ADVICE_TEST_KINDS }
            .map { (kind, list) -> AdviceTest(kind, list.maxBy { it.day }.level) }.sortedBy { it.kind }
        val profile = context.profile
        return AdviceRequest(
            fatigueLevel = report.level,
            fatigueScore = report.score,
            factors = report.factors.map { it.title },
            ageGroup = Explanations.ageGroup(context.age),
            occupation = profile["职业"]?.takeIf { it.isNotBlank() },
            visionCorrection = profile["日常矫正方式"]?.takeIf { it.isNotBlank() },
            eyeHistory = profile["既往眼病"]?.takeIf { it.isNotBlank() },
            averageScreenMinutes = usage.mapNotNull { it.screenMinutes }.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            averageLongestSessionMinutes = usage.mapNotNull { it.longestSessionMinutes }.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            nightUseDays = usage.takeIf { it.isNotEmpty() }?.count { (it.nightMinutes ?: 0) >= 60 },
            averageBreaks = usage.mapNotNull { it.breaks }.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            blinksPerMinute = if (tracked > 0) (sessions.sumOf { it.blinksPerMinute.toDouble() * it.trackedSec } / tracked).roundToInt() else null,
            viewingDistanceCm = weighted { it.meanDistanceCm },
            headOffsetDeg = weighted { it.meanHeadOffsetDeg },
            ambientLight = context.ambientLux?.takeIf { context.ambientSupported }?.let { if (it < 50f) "偏暗" else if (it < 1000f) "适中" else "偏亮" },
            screenBrightness = context.screenBrightness?.let { if (it < 0.3f) "偏暗" else if (it < 0.75f) "适中" else "偏亮" },
            tests = tests,
            symptoms = report.todaySymptoms,
        ).bounded()
    }

    /** 苹果端：与 [EyeCareBridge.reportJson] 同一份输入，返回请求 JSON（无法解析时为 null）。 */
    fun requestJson(ledgerJson: String, contextJson: String): String? {
        val context = runCatching { LedgerData.JSON.decodeFromString(EyeCareContext.serializer(), contextJson) }.getOrNull() ?: return null
        val ledger = LedgerData.fromJson(ledgerJson)
        val request = request(ledger, context, EyeCareEngine.report(ledger, context))
        return SyncContract.json.encodeToString(AdviceRequest.serializer(), request)
    }
}

/** 发给 AI 综合建议的自测种类：用户确认过的三项（干眼、老视、眼位）；色觉自测不发送。 */
private val ADVICE_TEST_KINDS = setOf("DRY_EYE", "PRESBYOPIA", "STRABISMUS")
