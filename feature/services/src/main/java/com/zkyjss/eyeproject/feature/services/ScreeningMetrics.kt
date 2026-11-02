package com.zkyjss.eyeproject.feature.services

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import org.json.JSONObject

/**
 * 读取本机历史记录，与最近一次同病种结果对比（原文：长期视觉功能档案）。
 * 老视只和同一检查路径、同一戴镜状态的上一次比（专家：不同戴镜状态的结果分别解释，不混合）。
 */
internal object ScreeningHistory {
    fun compare(context: Context, current: ScreeningState, assessment: ScreeningAssessment): List<String> = runCatching {
        val prefix = current.kind.name.lowercase() + "_"
        fun sameCondition(json: JSONObject) = current.kind != ScreeningKind.PRESBYOPIA ||
            (json.optString("presbyopiaPath") == current.presbyopiaPath?.code &&
                json.optJSONObject("profile")?.optString(PresbyopiaIntake.TEST_STATE).orEmpty() == current.fields[PresbyopiaIntake.TEST_STATE].orEmpty())
        val (previous, json) = context.filesDir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".json") }
            ?.sortedByDescending { it.name }?.asSequence()
            ?.map { it to JSONObject(it.readText()) }?.firstOrNull { (_, json) -> sameCondition(json) } ?: return emptyList()
        val features = json.optJSONObject("assessment")?.optJSONObject("features") ?: return emptyList()
        val time = previous.name.removePrefix(prefix).removeSuffix(".json").toLongOrNull()
        val date = time?.let { SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(it)) } ?: "上次"
        fun now(key: String) = assessment.features[key]
        fun before(key: String) = features.optDouble(key).takeIf { !it.isNaN() }
        fun line(name: String, key: String, unit: String) =
            "$name：${before(key)?.let { "%.1f".format(it) } ?: "—"} → ${now(key)?.let { "%.1f".format(it) } ?: "—"} $unit"
        when (current.kind) {
            ScreeningKind.PRESBYOPIA -> buildList {
                add("对比 $date（${current.fields[PresbyopiaIntake.TEST_STATE] ?: current.presbyopiaPath?.title ?: "同一戴镜方式"}）")
                add(line("40 cm 双眼视力", "near_both_decimal", ""))
                if (current.presbyopiaPath?.standardAmplitude == true) {
                    add(line("调节幅度（较差眼）", "amplitude_d", "D"))
                    add(line("远近切换正确循环", "flipper_cycles_per_min", "个"))
                }
            }
            ScreeningKind.STRABISMUS -> listOf(
                "对比 $date",
                line("水平偏斜", "fixation_hde_prism", "Δ"),
                line("再注视偏移", "cover_refix_median_prism", "Δ"),
                line("BSI", "bsi", ""),
            )
        }
    }.getOrDefault(emptyList())
}
