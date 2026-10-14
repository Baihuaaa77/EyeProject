package com.zkyjss.eyeproject.feature.services

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import org.json.JSONObject

/** 读取本机历史记录，与最近一次同病种结果对比（原文：长期视觉功能档案 / 双模式比较）。 */
internal object ScreeningHistory {
    fun compare(context: Context, current: ScreeningState, assessment: ScreeningAssessment): List<String> = runCatching {
        val prefix = current.kind.name.lowercase() + "_"
        val previous = context.filesDir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".json") }
            ?.sortedByDescending { it.name }?.firstOrNull() ?: return emptyList()
        val json = JSONObject(previous.readText())
        val features = json.optJSONObject("assessment")?.optJSONObject("features") ?: return emptyList()
        val time = previous.name.removePrefix(prefix).removeSuffix(".json").toLongOrNull()
        val date = time?.let { SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(it)) } ?: "上次"
        fun now(key: String) = assessment.features[key]
        fun before(key: String) = features.optDouble(key).takeIf { !it.isNaN() }
        fun line(name: String, key: String, unit: String) =
            "$name：${before(key)?.let { "%.1f".format(it) } ?: "—"} → ${now(key)?.let { "%.1f".format(it) } ?: "—"} $unit"
        when (current.kind) {
            ScreeningKind.PRESBYOPIA -> listOf(
                "对比 $date（${json.optString("mode").ifBlank { "模式未知" }}）",
                line("调节幅度", "amplitude_d", "D"),
                line("近点", "near_point_cm", "cm"),
                line("调节灵敏度", "flipper_cycles_per_min", "次/分钟"),
            )
            ScreeningKind.STRABISMUS -> listOf(
                "对比 $date",
                line("水平偏斜", "fixation_hde_prism", "Δ"),
                line("再注视偏移", "cover_refix_median_prism", "Δ"),
                line("BSI", "bsi", ""),
            )
        }
    }.getOrDefault(emptyList())
}
