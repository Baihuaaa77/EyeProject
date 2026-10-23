package com.zkyjss.eyeproject.core.eyecare

import com.zkyjss.eyeproject.core.domain.LiveEyeMonitor
import com.zkyjss.eyeproject.feature.services.LocalRecordEntry

/**
 * 苹果端入口：账本以 JSON 文本进出（Swift 只负责读写文件），报告以 JSON 返回，规则全部在 [EyeCareEngine]。
 * 安卓直接使用 [LedgerData] 与 [EyeCareEngine]。
 */
object EyeCareBridge {
    val symptoms: List<String> get() = EyeCareEngine.SYMPTOMS

    fun dayOf(epochMs: Long, utcOffsetMinutes: Int): String = EyeCareDays.dayOf(epochMs, utcOffsetMinutes)
    fun hourOf(epochMs: Long, utcOffsetMinutes: Int): Int = EyeCareDays.hourOf(epochMs, utcOffsetMinutes)

    fun addSession(ledgerJson: String, day: String, endedAtMs: Long, session: LiveEyeMonitor.Session): String =
        LedgerData.fromJson(ledgerJson).withSession(day, endedAtMs, session).pruned(day).toJson()

    fun addAppSeconds(ledgerJson: String, day: String, seconds: Long): String =
        LedgerData.fromJson(ledgerJson).withAppSeconds(day, seconds).pruned(day).toJson()

    fun checkIn(ledgerJson: String, day: String, symptoms: List<String>, atMs: Long): String =
        LedgerData.fromJson(ledgerJson).withCheckIn(day, symptoms, atMs).toJson()

    /** [contextJson] 为 [EyeCareContext] 的 JSON。 */
    fun reportJson(ledgerJson: String, contextJson: String): String {
        val context = runCatching { LedgerData.JSON.decodeFromString(EyeCareContext.serializer(), contextJson) }.getOrNull()
            ?: return "{}"
        return EyeCareEngine.report(LedgerData.fromJson(ledgerJson), context).toJson()
    }

    /** 本机记录 → 自测结论：只取本人或未选成员的记录（家人的结果不算进自己的用眼评估）。 */
    fun testBriefs(entries: List<LocalRecordEntry>, utcOffsetMinutes: Int): List<TestBrief> = EyeCareTests.from(entries, utcOffsetMinutes)

    fun testBriefsJson(entries: List<LocalRecordEntry>, utcOffsetMinutes: Int): String =
        LedgerData.JSON.encodeToString(kotlinx.serialization.builtins.ListSerializer(TestBrief.serializer()), testBriefs(entries, utcOffsetMinutes))
}

object EyeCareTests {
    fun from(entries: List<LocalRecordEntry>, utcOffsetMinutes: Int): List<TestBrief> = entries
        .filter { (it.subjectRelation == null || it.subjectRelation == "SELF") && it.testedAtMs != null }
        .map { TestBrief(it.kind, it.level, it.badge, EyeCareDays.dayOf(it.testedAtMs!!, utcOffsetMinutes)) }
}
